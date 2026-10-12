#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <spawn.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>
#include "unicode.h"

static void failure(JNIEnv* env, const char* operation, int error) {
    auto type = env->FindClass("java/io/IOException");
    std::string message = std::string(operation) + ": " + strerror(error);
    env->ThrowNew(type, message.c_str());
}
static std::vector<std::string> strings(JNIEnv* env, jobjectArray input) {
    std::vector<std::string> out;
    for (jsize i = 0; i < env->GetArrayLength(input); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(input, i));
        out.push_back(utf8(env, value)); env->DeleteLocalRef(value);
    }
    return out;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_spawn(JNIEnv* env, jobject, jstring executable,
        jobjectArray arguments, jobjectArray environment, jstring cwd, jintArray sources, jintArray targets) {
    auto exe = utf8(env, executable); auto dir = utf8(env, cwd);
    auto args = strings(env, arguments); auto vars = strings(env, environment);
    vars.emplace_back("MC_CWD=" + dir);
    args.insert(args.begin(), exe);
    std::vector<char*> argv, envp;
    for (auto& value : args) argv.push_back(value.data()); argv.push_back(nullptr);
    for (auto& value : vars) envp.push_back(value.data()); envp.push_back(nullptr);
    int in[2], out[2], err[2];
    if (pipe2(in, O_CLOEXEC)) { failure(env, "stdin pipe", errno); return nullptr; }
    if (pipe2(out, O_CLOEXEC)) { int e = errno; close(in[0]); close(in[1]); failure(env, "stdout pipe", e); return nullptr; }
    if (pipe2(err, O_CLOEXEC)) { int e = errno; for (int fd : {in[0], in[1], out[0], out[1]}) close(fd); failure(env, "stderr pipe", e); return nullptr; }
    posix_spawn_file_actions_t actions; posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions, in[0], STDIN_FILENO);
    posix_spawn_file_actions_adddup2(&actions, out[1], STDOUT_FILENO);
    posix_spawn_file_actions_adddup2(&actions, err[1], STDERR_FILENO);
    auto src = env->GetIntArrayElements(sources, nullptr);
    auto dst = env->GetIntArrayElements(targets, nullptr);
    for (jsize i = 0; i < env->GetArrayLength(sources); ++i) posix_spawn_file_actions_adddup2(&actions, src[i], dst[i]);
    env->ReleaseIntArrayElements(sources, src, JNI_ABORT); env->ReleaseIntArrayElements(targets, dst, JNI_ABORT);
    for (int fd : {in[0], in[1], out[0], out[1], err[0], err[1]}) posix_spawn_file_actions_addclose(&actions, fd);
    posix_spawnattr_t attr; posix_spawnattr_init(&attr);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP);
    posix_spawnattr_setpgroup(&attr, 0);
    pid_t pid;
    int result = posix_spawn(&pid, exe.c_str(), &actions, &attr, argv.data(), envp.data());
    posix_spawnattr_destroy(&attr); posix_spawn_file_actions_destroy(&actions);
    close(in[0]); close(out[1]); close(err[1]);
    if (result) { close(in[1]); close(out[0]); close(err[0]); failure(env, "spawn", result); return nullptr; }
    jint data[] = {pid, in[1], out[0], err[0]};
    auto array = env->NewIntArray(4); env->SetIntArrayRegion(array, 0, 4, data); return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_waitFor(JNIEnv*, jobject, jint pid) {
    int status = 0; pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_signal(JNIEnv*, jobject, jint pid, jint signal, jboolean group) {
    if (pid > 1 && pid != getpid()) kill(group ? -pid : pid, signal);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_socketPair(JNIEnv* env, jobject) {
    int fds[2]; if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fds)) { failure(env, "socketpair", errno); return nullptr; }
    jint values[] = {fds[0], fds[1]}; auto out = env->NewIntArray(2); env->SetIntArrayRegion(out, 0, 2, values); return out;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_pipe(JNIEnv* env, jobject) {
    int fds[2]; if (pipe2(fds, O_CLOEXEC)) { failure(env, "pipe", errno); return nullptr; }
    jint values[] = {fds[0], fds[1]}; auto out = env->NewIntArray(2); env->SetIntArrayRegion(out, 0, 2, values); return out;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_fifoEndpoints(JNIEnv* env, jobject, jobjectArray input) {
    auto paths=strings(env,input);
    if(paths.size()!=3) { failure(env,"FIFO paths",EINVAL); return nullptr; }
    int fds[6]={-1,-1,-1,-1,-1,-1};
    int saved=0;
    for(int i=0;i<3 && !saved;i++) {
        if(mkfifo(paths[i].c_str(),0600) && errno!=EEXIST) { saved=errno; break; }
        struct stat attributes{};
        if(lstat(paths[i].c_str(),&attributes) || !S_ISFIFO(attributes.st_mode) || attributes.st_uid!=getuid()) { saved=EINVAL; break; }
        fds[i+3]=open(paths[i].c_str(),O_RDWR|O_NONBLOCK|O_CLOEXEC);
        if(fds[i+3]<0) { saved=errno; break; }
        fds[i]=open(paths[i].c_str(),(i==0?O_WRONLY:O_RDONLY)|O_CLOEXEC);
        if(fds[i]<0) { saved=errno; break; }
    }
    if(saved) { for(int fd:fds) if(fd>=0) close(fd); failure(env,"console FIFO",saved); return nullptr; }
    jint result[6]; for(int i=0;i<6;i++) result[i]=fds[i];
    auto values=env->NewIntArray(6); env->SetIntArrayRegion(values,0,6,result); return values;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_mobilecode_ide_runtime_NativeProcess_closeFd(JNIEnv*, jobject, jint fd) { if (fd >= 0) close(fd); }
