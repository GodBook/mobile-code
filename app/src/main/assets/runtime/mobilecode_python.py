"""Offline CPython runner, Jedi LSP bridge and main-thread bdb DAP adapter."""
from __future__ import annotations
import ast
import bdb
import dis
import inspect
import io
import json
import os
from pathlib import Path
import runpy
import socket
import sys
import threading
import traceback
import types

MAX_MESSAGE = 16 * 1024 * 1024

# CPython's Android initialization wraps stdout/stderr in logcat streams.
# The IDE owns real console pipes; restore them before starting LSP or user code.
if sys.platform == "android":
    sys.stdin = sys.__stdin__ = io.TextIOWrapper(io.FileIO(0, "r", closefd=False), encoding="utf-8", errors="replace")
    sys.stdout = sys.__stdout__ = io.TextIOWrapper(io.FileIO(1, "w", closefd=False), encoding="utf-8", write_through=True)
    sys.stderr = sys.__stderr__ = io.TextIOWrapper(io.FileIO(2, "w", closefd=False), encoding="utf-8", errors="backslashreplace", write_through=True)

class Framed:
    def __init__(self, reader, writer):
        self.reader, self.writer = reader, writer
        self.lock = threading.Lock()

    def read(self):
        headers = {}
        while True:
            line = self.reader.readline(8192)
            if not line:
                return None
            if line in (b"\r\n", b"\n"):
                break
            key, value = line.decode("ascii").split(":", 1)
            headers[key.lower()] = value.strip()
        size = int(headers.get("content-length", "-1"))
        if size < 0 or size > MAX_MESSAGE:
            raise ValueError("Invalid Content-Length")
        chunks, remaining = [], size
        while remaining:
            chunk = self.reader.read(remaining)
            if not chunk:
                raise EOFError("Incomplete protocol message")
            chunks.append(chunk)
            remaining -= len(chunk)
        return json.loads(b"".join(chunks))

    def send(self, message):
        payload = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode()
        data = f"Content-Length: {len(payload)}\r\n\r\n".encode() + payload
        with self.lock:
            view = memoryview(data)
            while view:
                count = self.writer.write(view)
                if count is None:
                    break
                if count == 0:
                    raise EOFError("Protocol stream closed")
                view = view[count:]
            self.writer.flush()

def column_to_python(line, utf16_column):
    units = 0
    for index, char in enumerate(line):
        if units >= utf16_column:
            return index
        units += 2 if ord(char) > 0xffff else 1
    return len(line)

def column_to_utf16(line, column):
    return len(line[:column].encode("utf-16-le")) // 2

def project_path(config, relative):
    root = Path(config["source"]).resolve()
    target = (root / relative).resolve()
    if not target.is_relative_to(root):
        raise ValueError("Path outside source snapshot")
    return target

def configure(config):
    os.chdir(config["work"])
    for directory in reversed(config.get("sourceRoots", ["src"])):
        sys.path.insert(0, str(Path(config["source"]) / directory))
    sys.argv = [str(project_path(config, config["entry"]))] + config.get("arguments", [])
    sys.dont_write_bytecode = True

def compile_project(config):
    failed = False
    for source in Path(config["source"]).rglob("*.py"):
        try:
            compile(source.read_bytes(), str(source), "exec")
        except (SyntaxError, UnicodeError) as error:
            failed = True
            line = max(0, (getattr(error, "lineno", None) or 1) - 1)
            text = source.read_text(errors="replace").splitlines()
            content = text[line] if line < len(text) else ""
            column = column_to_utf16(content, max(0, (getattr(error, "offset", None) or 1) - 1))
            diagnostic = {"path": str(source), "range": {"start": {"line": line, "character": column}, "end": {"line": line, "character": column + 1}}, "message": str(error), "severity": 1, "source": "Python"}
            print("MC_DIAGNOSTIC:" + json.dumps(diagnostic, ensure_ascii=False), file=sys.stderr)
    return 1 if failed else 0

def language_server(config):
    import jedi
    from jedi.api.environment import InterpreterEnvironment
    connection = Framed(sys.stdin.buffer, sys.stdout.buffer)
    root = Path(config["source"]).resolve()
    project = jedi.Project(str(root), added_sys_path=[str(root / p) for p in config.get("sourceRoots", ["src"])])
    # Pinned Jedi 0.19.2: embedded Android has no standalone Python executable.
    project._environment = InterpreterEnvironment()
    documents = {}
    shutdown = False

    def path_of(uri):
        from urllib.parse import unquote, urlparse
        return unquote(urlparse(uri).path)

    def publish(uri):
        text, version = documents[uri]
        diagnostics = []
        try:
            ast.parse(text, filename=path_of(uri))
        except SyntaxError as error:
            line = max(0, (error.lineno or 1) - 1)
            lines = text.splitlines()
            column = column_to_utf16(lines[line] if line < len(lines) else "", max(0, (error.offset or 1) - 1))
            diagnostics.append({"range": {"start": {"line": line, "character": column}, "end": {"line": line, "character": column + 1}}, "severity": 1, "message": error.msg, "source": "Python"})
        connection.send({"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics", "params": {"uri": uri, "version": version, "diagnostics": diagnostics}})

    while True:
        message = connection.read()
        if message is None:
            return
        method, params = message.get("method"), message.get("params", {})
        request_id = message.get("id")
        result = None
        try:
            if method == "initialize":
                result = {"capabilities": {"positionEncoding": "utf-16", "textDocumentSync": 1, "completionProvider": {"triggerCharacters": ["."]}, "signatureHelpProvider": {"triggerCharacters": ["(", ","]}}, "serverInfo": {"name": "Mobile Code / Jedi", "version": "0.19.2"}}
            elif method == "shutdown":
                shutdown = True
            elif method == "exit":
                return
            elif method == "textDocument/didOpen":
                item = params["textDocument"]
                documents[item["uri"]] = item["text"], item["version"]
                publish(item["uri"])
            elif method == "textDocument/didChange":
                item = params["textDocument"]
                documents[item["uri"]] = params["contentChanges"][-1]["text"], item["version"]
                publish(item["uri"])
            elif method == "textDocument/didClose":
                documents.pop(params["textDocument"]["uri"], None)
            elif method in ("textDocument/completion", "textDocument/signatureHelp") and not shutdown:
                uri = params["textDocument"]["uri"]
                text, _ = documents.get(uri, (Path(path_of(uri)).read_text(), 0))
                position = params["position"]
                lines = text.split("\n")
                line = position["line"]
                column = column_to_python(lines[line], position["character"])
                script = jedi.Script(text, path=path_of(uri), project=project)
                if method.endswith("completion"):
                    items = []
                    for completion in script.complete(line + 1, column)[:100]:
                        prefix_length = len(completion.name) - len(completion.complete)
                        start = column_to_utf16(lines[line], max(0, column - prefix_length))
                        items.append({"label": completion.name, "detail": completion.description, "insertText": completion.name,
                            "textEdit": {"range": {"start": {"line": line, "character": start}, "end": position}, "newText": completion.name}})
                    result = {"isIncomplete": False, "items": items}
                else:
                    signatures = script.get_signatures(line + 1, column)
                    result = {"signatures": [{"label": s.to_string(), "parameters": [{"label": p.to_string()} for p in s.params]} for s in signatures], "activeSignature": 0, "activeParameter": (signatures[0].index or 0) if signatures else 0}
            if request_id is not None:
                connection.send({"jsonrpc": "2.0", "id": request_id, "result": result})
        except Exception as error:
            if request_id is not None:
                connection.send({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32603, "message": str(error)}})
            else:
                print(f"Python language service: {error}", file=sys.stderr)

def safe_text(value):
    kind = type(value)
    if kind in (type(None), bool, int, float, complex, str, bytes):
        return repr(value)[:1024]
    if kind in (list, tuple, dict, set, frozenset):
        return f"{kind.__name__} ({len(value)} items)"
    return f"<{type.__getattribute__(kind, '__name__')}>"

def safe_evaluate(expression, frame):
    def access(node):
        if isinstance(node, ast.Name):
            if node.id in frame.f_locals:
                return frame.f_locals[node.id]
            if node.id in frame.f_globals:
                return frame.f_globals[node.id]
            raise NameError(node.id)
        if isinstance(node, ast.Attribute):
            return inspect.getattr_static(access(node.value), node.attr)
        if isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Constant):
            base, index = access(node.value), node.slice.value
            if type(base) not in (list, tuple, dict, str, bytes) or type(index) not in (int, str):
                raise ValueError("仅支持内置容器的整数或字符串下标")
            return base[index]
        raise ValueError("观察项只支持变量、字段和固定下标")
    return access(ast.parse(expression, mode="eval").body)

class DebugAdapter(bdb.Bdb):
    def __init__(self, config, connection):
        super().__init__()
        self.config, self.connection = config, connection
        self.source_root = Path(config["source"]).resolve()
        self.condition = threading.Condition()
        self.ready = self.terminated = self.paused = self.pause_requested = False
        self.sequence = 0
        self.sequence_lock = threading.Lock()
        self.frame = None
        self.frames, self.references = {}, {}
        self.reference_id = 1000
        self.stop_reason = "entry"

    def send(self, message):
        with self.sequence_lock:
            self.sequence += 1
            message["seq"] = self.sequence
            self.connection.send(message)

    def event(self, name, body=None):
        self.send({"type": "event", "event": name, "body": body or {}})

    def response(self, request, body=None, error=None):
        message = {"type": "response", "request_seq": request["seq"], "command": request["command"], "success": error is None, "body": body or {}}
        if error:
            message["message"] = str(error)
        self.send(message)

    def own_frame(self, frame):
        try:
            return Path(frame.f_code.co_filename).resolve().is_relative_to(self.source_root)
        except (ValueError, OSError):
            return False

    def dispatch_call(self, frame, arg):
        if not self.own_frame(frame):
            return None
        return super().dispatch_call(frame, arg)

    def dispatch_line(self, frame):
        if self.quitting:
            raise bdb.BdbQuit
        if self.own_frame(frame) and (self.pause_requested or self.stop_here(frame) or self.break_here(frame)):
            self.user_line(frame)
        return self.trace_dispatch

    def user_line(self, frame):
        if not self.own_frame(frame):
            return
        self.frame = frame
        self.frames.clear()
        self.references.clear()
        self.pause_requested = False
        self.paused = True
        reason = "breakpoint" if self.break_here(frame) else self.stop_reason
        self.event("stopped", {"reason": reason, "threadId": 1, "allThreadsStopped": True})
        with self.condition:
            while self.paused and not self.terminated:
                self.condition.wait()
        if self.terminated:
            self.set_quit()
            raise bdb.BdbQuit

    def user_return(self, frame, value):
        if self.own_frame(frame) and frame.f_back and self.own_frame(frame.f_back):
            self.set_next(frame.f_back)
            self.stop_reason = "step"

    def set_continue(self):
        # Keep tracing installed so a later pause request can be honored even
        # when there are no breakpoints (Bdb's default removes tracing).
        self._set_stopinfo(self.botframe, None, -1)

    def reference(self, value):
        if type(value) not in (dict, list, tuple) and inspect.getattr_static(type(value), "__dict__", None) is None:
            return 0
        if type(value) in (type(None), bool, int, float, complex, str, bytes):
            return 0
        self.reference_id += 1
        self.references[self.reference_id] = value
        return self.reference_id

    def variable(self, name, value):
        return {"name": str(name), "value": safe_text(value), "type": type.__getattribute__(type(value), "__name__"), "variablesReference": self.reference(value)}

    def stack(self):
        frames = []
        current = self.frame
        while current:
            if self.own_frame(current):
                identity = len(frames) + 1
                self.frames[identity] = current
                frames.append({"id": identity, "name": current.f_code.co_name, "source": {"path": current.f_code.co_filename, "name": Path(current.f_code.co_filename).name}, "line": current.f_lineno, "column": 1})
            current = current.f_back
        return frames

    def commands(self):
        try:
            while not self.terminated:
                request = self.connection.read()
                if request is None:
                    break
                command, args = request["command"], request.get("arguments", {})
                try:
                    body = {}
                    if command == "initialize":
                        body = {"supportsConfigurationDoneRequest": True, "supportsEvaluateForHovers": True, "supportsTerminateRequest": True, "supportsSetVariable": False, "supportsConditionalBreakpoints": False}
                    elif command == "launch":
                        self.response(request)
                        self.event("initialized")
                        continue
                    elif command == "setBreakpoints":
                        filename = str(Path(args["source"]["path"]).resolve())
                        if not Path(filename).is_relative_to(self.source_root):
                            raise ValueError("断点不在项目源码中")
                        self.clear_all_file_breaks(filename)
                        code = compile(Path(filename).read_bytes(), filename, "exec")
                        executable = set()
                        def lines(item):
                            executable.update(line for _, _, line in item.co_lines() if line)
                            for value in item.co_consts:
                                if isinstance(value, types.CodeType):
                                    lines(value)
                        lines(code)
                        points = []
                        for point in args.get("breakpoints", []):
                            actual = next((line for line in sorted(executable) if line >= point["line"]), None)
                            error = self.set_break(filename, actual) if actual is not None else "没有可执行语句"
                            points.append({"verified": error is None, "line": actual or point["line"], "message": error or ""})
                        body = {"breakpoints": points}
                    elif command == "configurationDone":
                        with self.condition:
                            self.ready = True
                            self.condition.notify_all()
                    elif command == "threads":
                        body = {"threads": [{"id": 1, "name": "Python 主线程"}]}
                    elif command == "stackTrace":
                        frames = self.stack() if self.paused else []
                        body = {"stackFrames": frames, "totalFrames": len(frames)}
                    elif command == "scopes":
                        frame = self.frames[args["frameId"]]
                        body = {"scopes": [{"name": "局部变量", "variablesReference": self.reference(dict(frame.f_locals)), "expensive": False}, {"name": "全局变量", "variablesReference": self.reference(frame.f_globals), "expensive": False}]}
                    elif command == "variables":
                        value = self.references[args["variablesReference"]]
                        if type(value) is dict:
                            entries = list(value.items())[:100]
                        elif type(value) in (list, tuple):
                            entries = list(enumerate(value[:100]))
                        else:
                            raw = inspect.getattr_static(value, "__dict__", {})
                            entries = list(raw.items())[:100] if type(raw) is dict else []
                        body = {"variables": [self.variable(name if type(name) in (str, int) else safe_text(name), item) for name, item in entries]}
                    elif command == "evaluate":
                        frame = self.frames.get(args.get("frameId", 1), self.frame)
                        if frame is None:
                            raise ValueError("程序尚未暂停")
                        value = safe_evaluate(args["expression"], frame)
                        body = {"result": safe_text(value), "type": type.__getattribute__(type(value), "__name__"), "variablesReference": self.reference(value)}
                    elif command == "pause":
                        self.stop_reason = "pause"
                        self.pause_requested = True
                        self.set_step()
                    elif command in ("continue", "next", "stepIn", "stepOut"):
                        if not self.paused:
                            raise ValueError("程序未暂停")
                        self.stop_reason = "step"
                        if command == "continue":
                            self.set_continue()
                        elif command == "next":
                            self.set_next(self.frame)
                        elif command == "stepIn":
                            self.set_step()
                        else:
                            self.set_return(self.frame)
                        self.response(request, {"allThreadsContinued": True} if command == "continue" else {})
                        self.event("continued", {"threadId": 1, "allThreadsContinued": True})
                        with self.condition:
                            self.paused = False
                            self.condition.notify_all()
                        continue
                    elif command in ("disconnect", "terminate"):
                        self.terminated = True
                        self.set_quit()
                        with self.condition:
                            self.paused = False
                            self.condition.notify_all()
                    elif command == "setExceptionBreakpoints":
                        body = {"breakpoints": []}
                    else:
                        raise ValueError(f"不支持的调试请求: {command}")
                    self.response(request, body)
                except Exception as error:
                    self.response(request, error=error)
        except (EOFError, OSError, ValueError):
            pass
        finally:
            self.terminated = True
            with self.condition:
                self.paused = False
                self.condition.notify_all()

    def execute(self):
        threading.Thread(target=self.commands, name="DAP commands", daemon=True).start()
        with self.condition:
            while not self.ready and not self.terminated:
                self.condition.wait()
        if self.terminated:
            return 0
        configure(self.config)
        entry = project_path(self.config, self.config["entry"])
        scope = {"__name__": "__main__", "__file__": str(entry), "__package__": None, "__builtins__": __builtins__}
        status = 0
        try:
            self.runctx(compile(entry.read_bytes(), str(entry), "exec"), scope, scope)
        except SystemExit as error:
            status = error.code if isinstance(error.code, int) else (0 if error.code is None else 1)
        except bdb.BdbQuit:
            pass
        except BaseException:
            traceback.print_exc()
            status = 1
        self.event("exited", {"exitCode": status})
        self.event("terminated")
        return status

def main():
    mode, config_file = sys.argv[1:3]
    config = json.loads(Path(config_file).read_text())
    if mode == "compile":
        return compile_project(config)
    if mode == "lsp":
        language_server(config)
        return 0
    if mode == "debug":
        channel = socket.socket(fileno=int(config.get("debugFd", 3)))
        stream = channel.makefile("rwb")
        try:
            return DebugAdapter(config, Framed(stream, stream)).execute()
        finally:
            # Wake the command reader before closing its buffered stream lock.
            try:
                channel.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()
            channel.close()
    if mode == "run":
        configure(config)
        runpy.run_path(str(project_path(config, config["entry"])), run_name="__main__")
        return 0
    raise ValueError(f"Unknown Python mode: {mode}")

if __name__ == "__main__":
    sys.exit(main())
