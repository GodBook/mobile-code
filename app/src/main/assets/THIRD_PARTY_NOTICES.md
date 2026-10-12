# Mobile Code — 第三方组件

本应用包含开源编辑器、编译器、运行时、语言服务器及其依赖。各组件保留自己的版权和许可，不因打包而改变。

主要组件：

| 组件 | 版本 | 许可 / 来源 |
|---|---|---|
| Sora Editor | 0.24.6 | LGPL-2.1，https://github.com/Rosemoe/sora-editor |
| Tree-sitter | 0.25.10 | MIT，https://github.com/tree-sitter/tree-sitter |
| C / C++ / Python / Java grammars | 0.24.1 / 0.23.4 / 0.23.6 / 0.23.5 | MIT，各 tree-sitter 语法仓库 |
| LLVM / Clang / clangd / LLD / LLDB | 21.1.8 | Apache-2.0 WITH LLVM-exception，https://github.com/llvm/llvm-project |
| libc++ | 随工具链版本清单 | Apache-2.0 WITH LLVM-exception |
| CPython（用户运行时） | 3.13.12 | PSF-2.0 等，https://www.python.org |
| CPython（LLDB 的依赖） | 3.14.6 | PSF-2.0 等，与用户运行时独立 |
| Jedi / Parso | 0.19.2 / 0.8.5 | MIT，https://github.com/davidhalter/jedi ，https://github.com/davidhalter/parso |
| OpenJDK | 21.0.12 | GPL-2.0 WITH Classpath-exception-2.0；部分模块有各自第三方声明 |
| Eclipse JDT LS | 1.61.0 | EPL-2.0 及其依赖许可，https://github.com/eclipse-jdtls/eclipse.jdt.ls |
| AndroidX / Compose / Room / DataStore | 见构建文件 | Apache-2.0 |
| Kotlin / Coroutines / Gson | 见构建文件 | Apache-2.0 |
| Android spawn 兼容库 | 0.3，16KB 重编 | BSD-2-Clause，上游 Termux / Android Open Source Project |

完整工具链依赖的名称、版本、包文件 SHA256 在 APK 的 toolchains/manifest.json 中。各原生组件的完整许可文本位于内置资源归档的 usr/share/doc/<组件>/；OpenJDK 的 legal/ 目录保留各模块的许可和例外文本。编辑器、语法和用户 CPython 许可另附在 APK 的 licenses/ 资源目录。

Android 原生包来源：Termux packages。它只作为构建组件来源，用户无需安装其他应用。
构建脚本和补丁：https://github.com/termux/termux-packages/tree/aed8f2405a392e49b487eb7ac0ed2eded4f45015

Mobile Code 自身的编辑器桥接、运行器、Python/Java 适配器及工具链重打包脚本随工程源码提供。Sora 通过公开依赖链接；完整应用源码和 Gradle 工程可用于修改、替换或重新链接编辑器。

公开分发前，应按各组件许可准备与二进制版本一致的对应源码、构建补丁及重链接材料。源码材料索引和当前交付边界见工程 docs/SOURCE_MATERIALS.md。
