# 验证记录

更新日期：2026-10-12。所有“待验证”均表示未取得通过证据。

## 0.2.0 在线更新增量

6/6主机单元测试通过：版本号比较、固定仓库/HTTPS地址、架构/SDK/包名、损坏或超大元数据、无效SHA256/大小，以及浮点、字符串和整数溢出版本号。手机上的2/2安全测试通过：损坏/降级APK在安装前被拒绝，FileProvider无法分享项目文件。测试基于保留原签名的0.1.1升级桥接构建。

0.2.0新增INTERNET和REQUEST_INSTALL_PACKAGES，APK禁止明文流量，不申请全部文件访问权限。下方“无INTERNET”是0.1.0历史基线。真实GitHub下载、覆盖安装与新版本完整回归仍在进行；手机PIN锁屏阻挡系统安装确认。

## 0.1.0 已验收历史基线

| 层级 | 状态 | 证据 / 范围 |
|---|---|---|
| 主机集成 | 5/5 通过 | `verification/local/host-runtime.json`；包括原生调试控制台 FIFO、Unicode argv、Python DAP、JNI JVM 和私有 FD Java 调试 |
| ARM64 release / Android test 构建 | 通过 | `.downloads/build-arm64.log`；发布配置、应用级 debuggable=false |
| 完整 APK 原生审计 | 484/484 通过 | `verification/local/apk-audit.json`；含应用、AndroidX 和全部内置工具链 ELF，对齐、ABI、文件唯一性、SHA256 全部通过 |
| APK 签名与权限 | 通过 | `apk-signing.txt`、`apk-manifest*.txt`；开发证书签名，minSdk29/target37，无 INTERNET/全部文件访问权限 |
| 四语言 ARM64 真机与界面 | 20/20 通过，117.815秒 | `verification/local/phone-runtime.json`、`phone-acceptance.log` |
| 安装包版本一致性 | 通过 | `installed-apk-sha256.txt` 与本地 APK、两个审计报告 SHA256 一致 |
| 16KB 页实际执行 | 待验证 | 当前手机为 4KB 页 |
| Android 10～17 / 厂商系统矩阵 | 待验证 | 当前可测试 Android 15 真机、API36 x86_64 模拟器 |

已连接测试手机：小米 9 SE，LineageOS，Android 15/API35，`arm64-v8a`，`getconf PAGESIZE=4096`，约 6GB RAM。测试设备序列号在本地执行日志中记录，文档不依赖固定 IP。

Android 集成测试涵盖：应用 `debuggable=false`、无 INTERNET 权限、原子保存/快照/源码导出/ZIP 往返、路径越界拒绝、中文/emoji 位置、四语言真实编译运行、调试期间 stdin、断点、进入/跳过/跳出、栈、局部变量、观察、跨文件语义补全、暂停时间不计执行预算、交互 stdin/EOF、幂等启动、2秒内停止、原生 SIGSEGV 隔离、12MB 输出/10MB 日志限制、阻塞输入超时，以及异常后的再次运行。

界面测试实际通过 Compose/Sora 创建项目、执行30次快速文本重置、输入中文组合文本和 emoji、撤销重做、折叠/展开、等待自动保存并点击运行。四个 Tree-sitter 语法的 Unicode、未闭合语法和增量修改也通过。

交付 APK：429,662,908 字节，约409.8MiB。SHA256：`89253f5cf325560c336ef9a5b25a04e35a53dd3cc8d6bdc57d6910bab576259e`。原始失败日志仍保留在本机 .downloads 下，最终报告只绑定这一已通过版本。

测试保持无线 ADB 所需的 Wi-Fi 连接，未在手机上实际切换飞行模式；应用 UID 没有联网权限，所有必需资源均由 APK 提供并完成校验。SAF 选择器由标准 Android 合约实现，源码/ZIP 的流接口和往返已测试，外部文件提供方的完整 UI 矩阵尚未覆盖。

核心功能已在上述 ARM64 手机验证。正式 P0 的16KB实际执行、Android10～17/厂商全矩阵，以及性能 P95、低内存、后台回收和长时间运行仍需合适环境。当前 APK 使用开发证书；完整公开发布源码材料尚未整理，见 SOURCE_MATERIALS.md。
