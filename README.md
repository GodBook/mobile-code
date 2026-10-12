<img src="docs/icon.svg" width="72" alt="Mobile Code" />

# Mobile Code

在 Android 手机上编辑、运行和调试 C、C++、Python、Java 的本地 IDE。编译器、标准库、调试器和语义补全资源随 APK 交付，编程功能断网可用；软件更新从 GitHub Releases 获取。

当前为首版开发原型。实际验收状态见 [验证记录](docs/VERIFICATION.md)；不能仅凭编译成功认定四语言和全部 Android 版本已兼容。

[下载 APK](https://github.com/GodBook/mobile-code/releases/latest) · [在线更新与发布](docs/UPDATES.md) · [第三方源码与许可材料](docs/SOURCE_MATERIALS.md)

## 使用

Android 10 以上，ARM64 手机；建议 6GB 内存和至少 3GB 可用存储。安装后首次打开会解压并检查本地资源，可同时创建项目、编辑代码。

1. 在首页选择“新建项目”，选择语言和单文件或多文件模板。
2. 从文件抽屉切换文件，在编辑器中输入代码；修改自动保存。
3. 点击运行。需要标准输入时在底部控制台发送；预置输入、参数和入口在“运行配置”中设置。
4. 点击行号区域设置断点，再点击调试；底部提供单步、调用栈、变量和观察项。
5. 通过系统文件选择器导入、导出项目 ZIP，或在更多菜单中导出当前源码文件。
6. 在设置 → 软件更新中检查、下载和安装新版本。检查不会自动下载，安装仍需安卓系统确认。

| 语言 | 执行 | 调试 | 语义补全 |
|---|---|---|---|
| C17 / C++17 | Clang、LLD、Bionic、libc++ | LLDB DAP | clangd |
| Python 3.13 | 独立进程嵌入 CPython | bdb DAP，主线程 | Jedi / Parso |
| Java 17 | OpenJDK 21，`--release 17` | JDI / 私有 FD JDWP | Eclipse JDT LS |

这是轻量控制台项目工具，暂不支持 Android/GUI 工程、完整依赖管理或第三方原生 Python 扩展。调试时源码只读；普通运行时可以继续编辑，下次运行使用新内容。导入项目不会自动执行。

## 构建

Windows 负责 Android 构建，Linux/WSL 负责工具链准备。需要 JDK 17、Android SDK 37.0、NDK 28.2.13676358、CMake 3.22.1；Gradle Wrapper 使用 9.4.1。

Linux 工具：Python 3.12+、curl、dpkg-deb、patchelf、readelf、make、CMake、Ninja、完整 C/C++ 构建环境，以及 Linux NDK r28c。初次构建需要网络，APK 使用时不需要网络。

```bash
# 在 Linux / WSL 中，先准备 Android 依赖，再交叉编译指定版本 Python。
python3 tools/prepare_toolchains.py --abi arm64-v8a --download-only
bash tools/build_python.sh arm64-v8a /opt/android-ndk-r28c
python3 tools/prepare_toolchains.py --abi arm64-v8a \
  --python-prefix "$HOME/.cache/mobilecode/build/python-build-arm64-v8a/stage/mobilecode/cpython313"
```

```powershell
# 在 Windows 工程目录中。SDK 可通过 ANDROID_HOME 指定。
$env:JAVA_HOME = 'D:\dev\jdk-17'
$env:ANDROID_HOME = 'D:\dev\android-sdk'
.\gradlew.bat :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。准备工具链期间不要同时构建同一 ABI 的 APK。详细环境、组件来源和常见问题见 [构建说明](docs/BUILD.md)。

当前 release 使用本机 Android debug keystore 签名，应用级 `debuggable=false`。这是可安装的本地测试产物；公开发布时应配置持久化的发布密钥，不能将密钥提交到源码目录。

## 验证

```bash
# Linux/WSL，需 OpenJDK 21、clang 和 Python 3.13。
PYTHONDONTWRITEBYTECODE=1 python3.13 verification/test_runtime.py
```

```powershell
# ARM64 真机的非 debuggable 配置。指定 serial，避免操作其他设备。
.\gradlew.bat :app:assembleP0 :app:assembleP0AndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/p0/app-p0.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/p0/app-p0-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r `
  dev.mobilecode.ide.test/androidx.test.runner.AndroidJUnitRunner
```

模拟器单独使用 `-PmcAbi=x86_64` 构建；生产 APK 默认仅含 ARM64。源码中的测试执行实际编译器、语言服务器和调试器，不模拟成功状态。

## 工程入口

- `app/`：Compose/Sora 工作台、项目保存、语言服务、会话管理及 Android 原生桥接。
- `runtime-java/`：Java 编译服务、JDI 调试适配器。
- `tools/`：离线工具链准备、CPython 交叉编译、主机原生验证。
- `verification/`：主机集成测试和验证证据。
- [实现设计](docs/DESIGN.md)、[当前检查点](docs/project/STATE.md)、[任务状态](docs/project/TASKS.md)。

用户程序与编辑器使用不同进程，但仍属于同一应用 UID；请仅运行自己信任的代码。它提供崩溃隔离和资源限制，不是恶意代码沙箱。
