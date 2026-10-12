"""Host integration tests: executed C/C++, Python DAP and private-FD Java JDWP.

These prove portable runtime/protocol behavior, not Android SELinux/device support.
Run in WSL: python3 verification/test_runtime.py
"""
from __future__ import annotations
import importlib.util
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = ROOT / "app/src/main/assets/runtime/mobilecode_python.py"
BUILD = ROOT / ".toolchains/host-checks"

class Client:
    def __init__(self, reader, writer):
        spec=importlib.util.spec_from_file_location("mc_python",BOOTSTRAP)
        module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        self.channel=module.Framed(reader,writer)
        self.messages=queue.Queue(); self.saved=[]; self.seq=0
        def read():
            try:
                while True:
                    value=self.channel.read()
                    if value is None: return
                    self.messages.put(value)
            except (OSError,EOFError): pass
        threading.Thread(target=read,daemon=True).start()
    def wait(self,predicate,timeout=15):
        end=time.monotonic()+timeout
        while True:
            for i,value in enumerate(self.saved):
                if predicate(value): return self.saved.pop(i)
            value=self.messages.get(timeout=max(.01,end-time.monotonic()))
            if predicate(value): return value
            self.saved.append(value)
    def request(self,command,arguments=None):
        self.seq+=1; seq=self.seq
        self.channel.send({"seq":seq,"type":"request","command":command,"arguments":arguments or {}})
        value=self.wait(lambda v:v.get("request_seq")==seq)
        if not value.get("success"): raise AssertionError(value)
        return value.get("body",{})
    def event(self,name): return self.wait(lambda v:v.get("event")==name).get("body",{})

class RuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        BUILD.mkdir(parents=True,exist_ok=True)
        cls.java_home=Path("/usr/lib/jvm/java-21-openjdk-amd64")
        subprocess.run(["cmake","-S",str(ROOT/"tools/native-host"),"-B",str(BUILD/"native"),"-G","Ninja",f"-DJAVA_HOME={cls.java_home}"],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(["cmake","--build",str(BUILD/"native")],check=True,stdout=subprocess.DEVNULL)
        gson=ROOT/".downloads/gson-2.13.2.jar"
        if not gson.exists():
            subprocess.run(["curl","-fsSL","--retry","4","--retry-all-errors","https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar","-o",str(gson)],check=True)
        cls.classpath=str(BUILD/"classes")+":"+str(gson)
        sources=list((ROOT/"runtime-java/src/main/java").rglob("*.java"))
        subprocess.run(["javac","--release","17","-cp",str(gson),"-d",str(BUILD/"classes")]+[str(p) for p in sources],check=True)
        cls.runner=BUILD/"native/mc_runner"

    def test_native_c_and_cpp_are_executed(self):
        for language,source,compiler in [("c","#include <stdio.h>\nint main(int argc,char**argv){int n;scanf(\"%d\",&n);printf(\"answer %d %s\\n\",n+5,argv[1]);return 0;}","clang"),
            ("cpp","#include <iostream>\n#include <vector>\nint main(){std::vector<int> v{3,5}; std::cout << v[0]+v[1] << std::endl;return 0;}","clang++")]:
            with self.subTest(language=language),tempfile.TemporaryDirectory() as td:
                path=Path(td); file=path/f"main.{language}"; file.write_text(source); library=path/"program.so"
                subprocess.run([compiler,"-shared","-fPIC","-g","-O0",str(file),"-o",str(library)],check=True)
                result=subprocess.run([str(self.runner),"native",str(library),"中文😀"],input="3\n",text=True,capture_output=True,check=True)
                self.assertIn("8",result.stdout)
                if language=="c": self.assertIn("中文😀",result.stdout)
                paths=[path/name for name in ("stdin","stdout","stderr")]
                for fifo in paths: os.mkfifo(fifo,0o600)
                keepers=[os.open(fifo,os.O_RDWR|os.O_NONBLOCK) for fifo in paths]
                endpoints=[os.open(fifo,os.O_WRONLY if i==0 else os.O_RDONLY) for i,fifo in enumerate(paths)]
                ready=path/"ready"
                process=subprocess.Popen([str(self.runner),"native-debug",str(library)]+[str(p) for p in paths]+[str(ready),"中文😀"],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                try:
                    until=time.monotonic()+5
                    while not ready.exists() and process.poll() is None and time.monotonic()<until: time.sleep(.01)
                    self.assertTrue(ready.exists())
                    for fd in keepers: os.close(fd)
                    keepers=[]
                    if language=="c": os.write(endpoints[0],b"3\n")
                    os.close(endpoints[0]); endpoints[0]=-1
                    process.communicate(timeout=5)
                    output=os.read(endpoints[1],4096).decode()
                    self.assertEqual(process.returncode,0); self.assertIn("8",output)
                    if language=="c": self.assertIn("中文😀",output)
                finally:
                    if process.poll() is None: process.kill(); process.wait()
                    for fd in keepers+endpoints:
                        if fd>=0: os.close(fd)

    def test_python_run_input_and_exception_exit(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); source=root/"main.py"; source.write_text("print('你好', input())\n")
            config=root/"config.json"; config.write_text(json.dumps({"source":td,"work":td,"entry":"main.py","sourceRoots":["."],"arguments":[]}))
            result=subprocess.run([sys.executable,str(BOOTSTRAP),"run",str(config)],input="😀\n",text=True,capture_output=True,check=True)
            self.assertEqual(result.stdout.strip(),"你好 😀")
            source.write_text("raise ValueError('failure')\n")
            result=subprocess.run([sys.executable,str(BOOTSTRAP),"run",str(config)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0); self.assertIn("ValueError",result.stderr)

    def test_python_real_break_step_stack_and_watch(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); source=root/"main.py"
            source.write_text("def add(a, b):\n    value = a + b\n    return value\n\nresult = add(3, 5)\nprint('answer', result)\n")
            parent,child=socket.socketpair()
            config=root/"config.json"; config.write_text(json.dumps({"source":td,"work":td,"entry":"main.py","sourceRoots":["."],"arguments":[],"debugFd":child.fileno()}))
            process=subprocess.Popen([sys.executable,str(BOOTSTRAP),"debug",str(config)],pass_fds=(child.fileno(),),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            child.close(); stream=parent.makefile("rwb"); client=Client(stream,stream)
            try:
                client.request("initialize"); client.request("launch"); client.event("initialized")
                bound=client.request("setBreakpoints",{"source":{"path":str(source)},"breakpoints":[{"line":2}]})
                self.assertTrue(bound["breakpoints"][0]["verified"])
                client.request("configurationDone"); client.event("stopped"); client.request("continue"); client.event("stopped")
                frames=client.request("stackTrace",{"threadId":1})["stackFrames"]; self.assertEqual(frames[0]["name"],"add")
                scopes=client.request("scopes",{"frameId":frames[0]["id"]})["scopes"]
                variables=client.request("variables",{"variablesReference":scopes[0]["variablesReference"]})["variables"]
                self.assertEqual({v["name"]:v["value"] for v in variables}, {"a":"3","b":"5"})
                self.assertEqual(client.request("evaluate",{"frameId":frames[0]["id"],"expression":"a"})["result"],"3")
                client.request("next"); client.event("stopped")
                frame=client.request("stackTrace",{"threadId":1})["stackFrames"][0]
                self.assertEqual(client.request("evaluate",{"frameId":frame["id"],"expression":"value"})["result"],"8")
                client.request("stepOut"); client.event("stopped")
                caller=client.request("stackTrace",{"threadId":1})["stackFrames"][0]
                self.assertNotEqual(caller["name"],"add")
                client.request("continue"); client.event("terminated")
                out,err=process.communicate(timeout=10); self.assertEqual(process.returncode,0,err); self.assertIn(b"answer 8",out)
            finally:
                if process.poll() is None: process.kill(); process.wait()
                stream.close()
                parent.close()
                for handle in (process.stdout, process.stderr):
                    if handle: handle.close()

    def test_java_private_fd_breakpoint_and_variables(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); source=root/"Main.java"
            source.write_text("public class Main {\n  static int add(int a, int b) {\n    int value = a + b;\n    return value;\n  }\n  public static void main(String[] args) {\n    int result = add(3, 5);\n    System.out.println(\"answer \" + result);\n  }\n}\n")
            subprocess.run(["javac","--release","17","-g",str(source)],check=True)
            parent,child=socket.socketpair(); native=BUILD/"native"
            env=dict(os.environ,LD_LIBRARY_PATH=str(native))
            target=subprocess.Popen(["java",f"-agentlib:jdwp=transport=dt_mobile,server=n,suspend=y,address={child.fileno()}","-cp",td,"Main"],pass_fds=(child.fileno(),),env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            config=root/"adapter.json"; config.write_text(json.dumps({"source":td,"jdwpFd":parent.fileno(),"transportLibrary":str(native/"libdt_mobile.so")}))
            adapter=subprocess.Popen(["java","-cp",self.classpath,"dev.mobilecode.runtime.Main","debug-adapter",str(config)],pass_fds=(parent.fileno(),),stdout=subprocess.PIPE,stderr=subprocess.PIPE,stdin=subprocess.PIPE)
            parent.close(); child.close(); client=Client(adapter.stdout,adapter.stdin)
            try:
                client.request("initialize"); client.request("launch"); client.event("initialized")
                client.request("setBreakpoints",{"source":{"path":str(source)},"breakpoints":[{"line":4}]}); client.request("configurationDone")
                stopped=client.event("stopped")
                frame=client.request("stackTrace",{"threadId":stopped["threadId"]})["stackFrames"][0]
                self.assertTrue(frame["name"].endswith("add")); self.assertEqual(frame["line"],4)
                self.assertEqual(client.request("evaluate",{"frameId":frame["id"],"expression":"value"})["result"],"8")
                scopes=client.request("scopes",{"frameId":frame["id"]})["scopes"]
                variables=client.request("variables",{"variablesReference":scopes[0]["variablesReference"]})["variables"]
                self.assertEqual({v["name"]:v["value"] for v in variables}["a"],"3")
                client.request("stepOut"); client.event("stopped"); client.request("continue"); client.event("terminated")
                out,err=target.communicate(timeout=10); self.assertEqual(target.returncode,0,err); self.assertIn(b"answer 8",out)
            finally:
                for process in (target,adapter):
                    if process.poll() is None: process.kill(); process.wait()
                    for handle in (process.stdin,process.stdout,process.stderr):
                        if handle: handle.close()

    def test_jni_jvm_launcher_runs_java_compiler(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); (root/"Main.java").write_text("public class Main { public static void main(String[] a) { System.out.println(\"ok\"); } }")
            config=root/"compile.json"; config.write_text(json.dumps({"source":td,"build":str(root/"classes")}))
            result=subprocess.run([str(self.runner),"jvm",str(self.java_home/"lib/server/libjvm.so"),str(self.java_home),str(self.java_home/"lib"),self.classpath,"dev.mobilecode.runtime.Main","compile",str(config)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr); self.assertIn('"Main"',result.stdout)

if __name__=="__main__":
    suite=unittest.defaultTestLoader.loadTestsFromNames(sys.argv[1:],sys.modules[__name__]) if len(sys.argv)>1 else unittest.defaultTestLoader.loadTestsFromTestCase(RuntimeTests)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    report=ROOT/"verification/local/host-runtime.json"
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps({"tests":result.testsRun,"success":result.wasSuccessful(),"failures":[str(t) for t,_ in result.failures],"errors":[str(t) for t,_ in result.errors],"python":sys.version,"platform":sys.platform},indent=2))
    sys.exit(0 if result.wasSuccessful() else 1)
