<img src="docs/icon.svg" width="72" alt="Mobile Code" />

# Mobile Code

在 Android 手机上编辑、运行和调试 C、C++、Python、Java 的本地 IDE。编译器、标准库、调试器和语义补全资源随 APK 提供，编程功能断网可用；软件更新从 GitHub Releases 获取。

[下载 APK](https://github.com/GodBook/mobile-code/releases/latest) · [构建说明](docs/BUILD.md) · [第三方源码与许可](docs/SOURCE_MATERIALS.md)

当前开发版为 **0.3.1（versionCode 8）**。支持范围和设备测试结果见[验证记录](docs/VERIFICATION.md)。

## 使用

需要 Android 10 或更新版本的 ARM64 手机，建议 6 GB 内存和至少 3 GB 可用存储。首次打开会解压本地资源。

1. 新建项目，选择语言及单文件或多文件模板。
2. 从文件抽屉切换文件，修改后自动保存。保存失败时可重试或导出未保存内容。
3. 点击运行，在底部控制台发送标准输入；参数、预置输入和入口可在“运行配置”中设置。
4. 点击行号设置断点，再点击调试，查看单步、调用栈、变量和观察项。
5. 通过系统文件选择器导入、导出项目 ZIP，或导出当前源码文件。

支持项目搜索与置顶、目录树、多标签、文件和项目文本搜索、定义与引用跳转、大纲、回收站及运行历史。草稿恢复前需核对内容；磁盘已满时，草稿也可能无法写入。“存储与历史”可查看日志、生成文件并清理历史产物。

主题可选跟随系统、浅色或深色。横屏或键盘占用较多空间时，工作台收起辅助栏，控制台可展开为独立面板。

## 语言与限制

| 语言 | 执行 | 调试 | 语义补全 |
| --- | --- | --- | --- |
| C17 / C++17 | Clang、LLD、Bionic、libc++ | LLDB DAP | clangd |
| Python 3.13 | 独立进程嵌入 CPython | bdb DAP，主线程 | Jedi / Parso |
| Java 17 | OpenJDK 21，`--release 17` | JDI / 私有 FD JDWP | Eclipse JDT LS |

这是控制台项目工具，暂不支持 Android/GUI 工程、完整依赖管理或第三方原生 Python 扩展。调试时源码只读；普通运行时可继续编辑，下次运行使用新内容。

导入项目不会自动执行。用户程序与编辑器位于不同进程，但属于同一应用 UID；这不是恶意代码沙箱，请只运行可信代码。

Android 10–17 的完整设备兼容矩阵、16 KiB 页大小的实际执行，以及物理输入法延迟等项目尚未完成全面验证，具体范围见验证记录。

## 更新

在“设置 → 软件更新”中检查、下载和安装新版。检查不会自动下载，安装需 Android 系统确认。

覆盖升级需保留相同包名和签名。发布现有更新通道的新版必须沿用原签名；另一台电脑生成的开发密钥不能替代它。应用级 `debuggable=false`，签名材料不进入仓库。

[0.3.1 更新说明](docs/releases/v0.3.1.md)记录了运行历史关联、诊断来源、构建超时和离线组件修复的变更；此前的工作台功能见 [0.3.0 更新说明](docs/releases/v0.3.0.md)。

## 构建

Windows 负责 Android 构建，Linux/WSL 准备工具链。需要 JDK 17、Android SDK 37、NDK 28.2.13676358 和 CMake 3.22.1，Gradle Wrapper 为 9.4.1。

Linux 还需 Python 3.12+、curl、dpkg-deb、patchelf、readelf、make、CMake、Ninja、C/C++ 构建环境和 Linux NDK r28c。初次准备需要联网。

```bash
# Linux / WSL：准备依赖并交叉编译 Python。
python3 tools/prepare_toolchains.py --abi arm64-v8a --download-only
bash tools/build_python.sh arm64-v8a /opt/android-ndk-r28c
python3 tools/prepare_toolchains.py --abi arm64-v8a \
  --python-prefix "$HOME/.cache/mobilecode/build/python-build-arm64-v8a/stage/mobilecode/cpython313"
```

在 Windows 上配置本机的 `JAVA_HOME` 和 `ANDROID_HOME` 后运行：

```powershell
.\gradlew.bat :app:assembleRelease
```

APK 输出到 `app/build/outputs/apk/release/app-release.apk`。生产包默认仅含 ARM64；模拟器开发可使用 `-PmcAbi=x86_64`。工具链准备期间不要同时构建同一 ABI 的 APK，完整步骤见[构建说明](docs/BUILD.md)。

## 测试

主机运行时验证需要 Linux/WSL、OpenJDK 21、clang 和 Python 3.13：

```bash
PYTHONDONTWRITEBYTECODE=1 python3.13 verification/test_runtime.py
```

Android 主机单元测试：

```powershell
.\gradlew.bat :app:testP0UnitTest --offline '-Pkotlin.compiler.execution.strategy=in-process'
```

发布配置的测试与构建：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest -PmcTestBuildType=release
```

设备测试会执行编译器、语言服务和调试器；部分用例会暂时修改测试项目、离线资源或进程状态。设备准备、恢复流程、性能测量口径和各版结果见[验证记录](docs/VERIFICATION.md)，联网升级及数据保留测试见[在线更新与发布](docs/UPDATES.md)。执行前请指定测试设备并阅读对应流程。

## 代码与文档

- `app/`：编辑器、项目保存、语言服务、会话管理和原生桥接。
- `runtime-java/`：Java 编译服务和 JDI 调试适配器。
- `tools/`：工具链准备、交叉编译和主机验证。
- `verification/`：集成测试与历史验证材料。
- [实现设计](docs/DESIGN.md)与[工作台升级规划](docs/UX_UPGRADE_PLAN.md)。
- 开发任务与状态见 [docs/project/](docs/project/)。
