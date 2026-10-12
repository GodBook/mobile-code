# 第三方源码与许可材料

用户已要求创建公开GitHub仓库并支持在线更新。应用自身源码、构建工程、运行时适配器和验证脚本公开于GodBook/mobile-code；版本APK与源码材料在对应Release提供。当前仍为开发原型，兼容性范围以验证记录为准。

## 已保存材料

- APK 中的 `assets/THIRD_PARTY_NOTICES.md` 和 `assets/licenses/`：编辑器、Tree-sitter 四语法、用户 CPython 的完整许可文本。
- APK 离线资源 `root.zip`：保留上游 `usr/share/doc/` 与 OpenJDK `legal/` 的完整许可、版权及例外说明。
- 每个 ABI 的 `manifest.json`：所有打包 Android 依赖的名称、版本、SHA256、源项目主页、原生文件校验和、16KB 审计结果和源码替换记录。
- `.downloads/`：本次构建使用的原始包、CPython / Tree-sitter / JDT LS 归档、Jedi / Parso wheel 及索引缓存。
- `.toolchains/grammars/`：固定标签的 Tree-sitter 和四门语言语法源码。
- `tools/`、`app/src/main/cpp/`、`runtime-java/`：所有应用侧适配、JDWP transport、入口桥接、16KB 重编和打包逻辑。

## 对应源码入口

| 组件 | 对应源码 |
|---|---|
| Sora 0.24.6 | https://github.com/Rosemoe/sora-editor/tree/0.24.6 |
| CPython 3.13.12 | https://www.python.org/ftp/python/3.13.12/Python-3.13.12.tar.xz |
| OpenJDK 21.0.12 | https://github.com/openjdk/jdk21u/tree/jdk-21.0.12-ga |
| LLVM 21.1.8 | https://github.com/llvm/llvm-project/tree/llvmorg-21.1.8 |
| JDT LS 1.61.0 | https://github.com/eclipse-jdtls/eclipse.jdt.ls/tree/v1.61.0 |
| Android 构建配方与补丁 | https://github.com/termux/termux-packages/tree/aed8f2405a392e49b487eb7ac0ed2eded4f45015/packages |

其他底层库依据 manifest 中的准确版本、主页和对应 Termux 包目录追溯。原始 CPython 3.13 和语法源码已在本机解包；Android spawn 使用固定 Termux commit 的源码重编，原始文件保存在 `.downloads/android-spawn-<commit>/`。

## Release 对应源码材料

`tools/archive_corresponding_sources.py`对ARM64锁文件逐项检查构建配方许可，收集其中16个包含GPL/LGPL组件的源码配方及Sora源码。0.2.0归档包含23份上游源码/补丁输入，约150MiB；所有输入与源码归档均使用固定SHA256。GNU Readline的6个上游补丁也包含在内，不只提供基础版本tar包。

Release中的 `MobileCode-<版本>-corresponding-sources.zip` 包含 `sources.json`，逐项映射原生包版本、二进制SHA256、源码SHA256和配方。完整固定commit的Termux构建框架、配置、源码和补丁一并归档。匹配的应用源码ZIP提供本项目全部入口桥接、JDWP transport、Python适配、原生重打包和重链接构建脚本；重编译步骤见BUILD.md。替换Sora依赖后可以重编译和重链接应用。

其他宽松许可组件的完整声明保留在APK中；对应源码版本和标准获取入口在上表、工具链清单和构建脚本中记录。Eclipse JDT LS与其EPL组件保留上游版权、许可和版本信息，可按JDT LS 1.61.0源码及其目标平台定义取得对应源码。不要将本归档描述为所有第三方项目的完整源码镜像。

应用本身尚未选用通用开源许可证，公开查看源码不意味着第三方许可自动适用于自有代码。当前APK沿用首版签名身份以支持覆盖安装，应用级 `debuggable=false`；私钥和密码不发布。更换签名将破坏现有版本的升级链，不能直接用另一台机器生成的debug keystore发布新版。
