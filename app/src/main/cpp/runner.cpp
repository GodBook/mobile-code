#include <jni.h>
#include <dlfcn.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <fcntl.h>
#include <unistd.h>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <clocale>
#include <iostream>
#include <string>
#include <vector>
#include "unicode.h"

#ifdef __ANDROID__
// Android's packaged native directory is flat. The Android OpenJDK port derives
// java.home from dladdr(libjvm), without consulting JAVA_HOME. Give that lookup
// the logical resource-tree alias; all mapped ELF bytes still come from the APK.
static std::string logical_jvm_path;
extern "C" __attribute__((visibility("default"))) int dladdr(const void* address, Dl_info* info) {
    using Lookup = int(*)(const void*, Dl_info*);
    static auto lookup = reinterpret_cast<Lookup>(dlsym(RTLD_NEXT, "dladdr"));
    if (!lookup) return 0;
    int result = lookup(address, info);
    if (result && info->dli_fname && !logical_jvm_path.empty()) {
        const char* name = strrchr(info->dli_fname, '/');
        if (!strcmp(name ? name + 1 : info->dli_fname, "libjvm.so")) info->dli_fname = logical_jvm_path.c_str();
    }
    return result;
}
#endif

static int error(const std::string& message) { fprintf(stderr, "Mobile Code: %s\n", message.c_str()); return 125; }
static bool connect_debug_console(char** paths,const char* ready) {
    for(int i=0;i<3;i++) {
        int fd=open(paths[i],(i==0?O_RDONLY:O_WRONLY)|O_CLOEXEC);
        if(fd<0) return false;
        int result=dup2(fd,i); if(fd!=i) close(fd);
        if(result<0) return false;
    }
    int fd=open(ready,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    if(fd<0) return false;
    const char value='1'; bool result=write(fd,&value,1)==1; close(fd);
    return result;
}
template<class T> T symbol(void* library, const char* name) {
    auto address = dlsym(library, name);
    if (!address) { fprintf(stderr, "Missing runtime API %s: %s\n", name, dlerror()); exit(125); }
    return reinterpret_cast<T>(address);
}
static std::wstring wide(const std::string& value) {
    std::wstring out; for (auto cp : codepoints(value)) out.push_back(static_cast<wchar_t>(cp)); return out;
}

static int python(int argc, char** argv) {
    if (argc < 5) return error("python requires library, home, script and arguments");
    setenv("PYTHONHOME", argv[2], 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    setenv("PYTHONUNBUFFERED", "1", 1);
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return error(dlerror());
    auto home = wide(argv[2]); auto program = wide(argv[0]);
    symbol<void(*)(const wchar_t*)>(lib, "Py_SetPythonHome")(home.c_str());
    symbol<void(*)(const wchar_t*)>(lib, "Py_SetProgramName")(program.c_str());
    symbol<void(*)()>(lib, "Py_Initialize")();
    std::vector<std::wstring> args;
    for (int i = 3; i < argc; ++i) args.push_back(wide(argv[i]));
    std::vector<wchar_t*> pointers; for (auto& arg : args) pointers.push_back(arg.data());
    symbol<void(*)(int,wchar_t**,int)>(lib, "PySys_SetArgvEx")(static_cast<int>(pointers.size()), pointers.data(), 0);
    FILE* input = fopen(argv[3], "rb"); if (!input) return error("Python bootstrap could not be opened");
    int status = symbol<int(*)(FILE*,const char*,int,void*)>(lib, "PyRun_SimpleFileExFlags")(input, argv[3], 1, nullptr);
    int finalized = symbol<int(*)()>(lib, "Py_FinalizeEx")();
    return status == 0 && finalized == 0 ? 0 : 1;
}

static int jvm(int argc, char** argv) {
    if (argc < 6) return error("jvm requires library, home, native directory, classpath, main class");
    setenv("JAVA_HOME", argv[2], 1);
#ifdef __ANDROID__
    logical_jvm_path = std::string(argv[2]) + "/lib/server/libjvm.so";
#endif
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) return error(dlerror());
    std::vector<std::string> options = {
        "-Djava.home=" + std::string(argv[2]), "-Dsun.boot.library.path=" + std::string(argv[3]),
        "-Djava.library.path=" + std::string(argv[3]), "-Djava.class.path=" + std::string(argv[4]),
        "-Dsun.java.launcher.is_altjvm=true",
        "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + std::string(getenv("TMPDIR") ? getenv("TMPDIR") : "."),
        "--add-modules=ALL-SYSTEM", "-Xmx256m", "-XX:ReservedCodeCacheSize=64m", "-XX:+UseSerialGC"
    };
    int first = 6;
    while (first < argc && strncmp(argv[first], "--vm=", 5) == 0) options.emplace_back(argv[first++] + 5);
    std::vector<JavaVMOption> joptions;
    for (auto& option : options) joptions.push_back({option.data(), nullptr});
    // The Android JNI header ends at 1.6; this process hosts OpenJDK, which accepts 1.8.
    JavaVMInitArgs config{}; config.version = 0x00010008; config.nOptions = static_cast<jint>(joptions.size());
    config.options = joptions.data(); config.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr; JNIEnv* env = nullptr;
    auto create = symbol<jint(*)(JavaVM**,void**,void*)>(lib, "JNI_CreateJavaVM");
    if (create(&vm, reinterpret_cast<void**>(&env), &config) != JNI_OK) return error("JVM initialization failed");
    std::string main = argv[5]; for (auto& c : main) if (c == '.') c = '/';
    auto type = env->FindClass(main.c_str());
    auto method = type ? env->GetStaticMethodID(type, "main", "([Ljava/lang/String;)V") : nullptr;
    int status = 0;
    if (!method) { env->ExceptionDescribe(); status = 1; }
    else {
        auto strings = env->FindClass("java/lang/String");
        auto args = env->NewObjectArray(argc - first, strings, nullptr);
        for (int i = first; i < argc; ++i) {
            auto arg = java_string(env, argv[i]); env->SetObjectArrayElement(args, i - first, arg); env->DeleteLocalRef(arg);
        }
        env->CallStaticVoidMethod(type, method, args);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); status = 1; }
    }
    vm->DestroyJavaVM(); return status;
}

int main(int argc, char** argv) {
    pid_t parent = getppid(); prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (parent == 1 || getppid() != parent) return 125;
    if (const char* directory = getenv("MC_CWD")) {
        if (chdir(directory)) return error(std::strerror(errno));
        unsetenv("MC_CWD");
    }
    signal(SIGPIPE, SIG_DFL);
    rlimit core{0,0}; setrlimit(RLIMIT_CORE, &core);
    setlocale(LC_ALL, "C.UTF-8");
    setvbuf(stdout, nullptr, _IONBF, 0); setvbuf(stderr, nullptr, _IONBF, 0);
    std::cout << std::unitbuf;
    if (argc < 2) return error("missing execution mode");
    if (!strcmp(argv[1], "exec")) {
        if (argc < 3) return error("missing executable");
        execv(argv[2], argv + 2); return error(std::strerror(errno));
    }
    if (!strcmp(argv[1], "python")) return python(argc - 1, argv + 1);
    if (!strcmp(argv[1], "jvm")) return jvm(argc - 1, argv + 1);
    if (!strcmp(argv[1], "native") || !strcmp(argv[1], "native-debug")) {
        if (argc < 3) return error("missing user module");
        bool debugging=!strcmp(argv[1],"native-debug");
        if (debugging || getenv("MC_DEBUG")) prctl(PR_SET_DUMPABLE, 1);
        if (debugging && (argc<7 || !connect_debug_console(argv+3,argv[6]))) return error("Cannot connect the debug console pipes");
        void* module = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
        if (!module) return error(dlerror());
        auto entry = symbol<int(*)(int,char**)>(module, "main");
        if(debugging) {
            std::vector<char*> arguments{argv[2]};
            for(int i=7;i<argc;i++) arguments.push_back(argv[i]);
            int count=static_cast<int>(arguments.size()); arguments.push_back(nullptr);
            return entry(count,arguments.data());
        }
        return entry(argc - 2, argv + 2);
    }
    return error("unknown execution mode");
}
