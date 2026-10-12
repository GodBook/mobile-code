# 构建与工具链说明

## 固定环境

| 项目 | 版本 |
|---|---|
| Gradle / AGP | 9.4.1 / 9.2.1 |
| 构建 JDK | 17 |
| Android SDK | 37.0，minSdk 29、targetSdk 37 |
| NDK / CMake | 28.2.13676358 / 3.22.1 |
| Compose 编译器 | 2.2.10 |
| Sora | 0.24.6 |
| 用户 Python | CPython 3.13.12 |
| Jedi / Parso | 0.19.2 / 0.8.5 |
| Tree-sitter | 0.25.10 |
| C / C++ / Python / Java grammar | 0.24.1 / 0.23.4 / 0.23.6 / 0.23.5 |

LLVM、OpenJDK、LLDB 和底层库的准确版本、仓库文件名与 SHA256 冻结在 `tools/locks/<ABI>.packages.json`，也随每个 ABI 的 `manifest.json` 交付。脚本优先读取锁文件，避免跟随滚动仓库升级。请备份原始 deb 缓存，以便在镜像下架旧包后继续重建。JDT LS 1.61.0、CPython 3.13.12、Gradle 9.4.1 的归档固定 SHA256；Maven 依赖由 app 和 runtime-java 的 gradle.lockfile 固定。

## 准备流程

`prepare_toolchains.py` 使用 Android/Bionic 构建的 Termux 包作为上游组件来源。用户无需安装 Termux。每个 deb 对照索引校验 SHA256，再解包到 Linux ext4 缓存；不会在电脑上运行下载的 Android ELF。

`build_python.sh` 将 CPython 3.13.12 交叉编译为 API29、16KB 对齐的运行时。LLDB 自己依赖的 Python 3.14 独立保留，不能替换用户 Python 3.13。标准库、Jedi 和 Parso 安装在用户 Python 的独立 home 下。

工具链脚本将实际执行的 ELF 放入 APK 的原生库目录；资源 ZIP 中不放可以直接执行的私有目录程序。脚本调整 SONAME、NEEDED 和 RUNPATH，在资源目录建立指向系统安装目录的别名。sysroot 的 libc 等链接桩只作为编译资源，不能作为应用实际运行时库。

重名原生文件采用独立名称并按源目录解析依赖，尤其是用户 Python3.13 和 LLDB Python3.14 各自的 libpython3.so。运行时逐个校验原生 SHA256。只调整原生组件时，可使用 `--native-only` 保留已经校验的资源 ZIP，重建原生文件、别名和清单。

上游 `libandroid-spawn` 原版仅 4KB 对齐，脚本从固定 commit `aed8f2405a392e49b487eb7ac0ed2eded4f45015` 重编，记录在 `sourceOverrides`。其他组件逐一检查 ELF LOAD 段的对齐与偏移一致性。

生成目录：

```text
app/toolchains/arm64-v8a/
  assets/toolchains/manifest.json
  assets/toolchains/root.zip
  jniLibs/arm64-v8a/*.so
```

Windows 源码目录可放 NTFS，但大规模 Linux 构建应在 ext4 缓存进行，避免 WSL 文件往返显著拖慢构建。默认缓存为 `$HOME/.cache/mobilecode`，可分别用 `MC_TOOLCHAIN_CACHE`、`MC_BUILD_ROOT` 指定持久化路径。不要使用会在 WSL 重启后消失的 `/tmp` 作为唯一构建产物位置。

## APK 与测试变体

默认 `mcAbi=arm64-v8a`。`p0` 与 `release` 都将应用级调试标志关闭。`p0AndroidTest` 签名与目标 APK 相同，测试实际发布配置下的进程和调试能力。`-PmcAbi=x86_64` 仅用于开发模拟器。

`app/src/main/cpp` 构建 JNI 进程桥接、Tree-sitter、私有 JDWP transport 和原生运行器。运行器是 ELF 可执行程序，以 `libmc_runner.so` 名称由 Android 安装到只读、可执行的 native library 路径。`useLegacyPackaging=true` 要求系统解压这些组件。

打包 Java runtime JAR 的任务依赖 `runtime-java:jar`，无需用户设备安装单独 Java 应用。

## 排障

- Android SDK 安装目录移动后，应停止 Gradle daemon 再构建；本工程曾因缓存的 `android-37.0-2` 路径出现 Android 类全部无法解析。
- 如果在打包工具链过程中执行了 Gradle，完成打包后运行一次 `:app:clean` 清除中间 asset merger 状态，再构建。不要删除源码或用户数据。
- 首次应用启动需解压标准库和语言服务资源。应保证额外可用空间充足；失败时应用保留编辑内容，设置中可以重新检查。
- Eclipse、PyPI、GitHub 下载失败时，脚本保留缓存；修复网络后重新运行。归档使用 `.part` 临时文件，校验完成后才生效。
- ELF 对齐检查通过不能代替 16KB 设备运行；x86_64 测试不能代替 ARM64 真机验证。
