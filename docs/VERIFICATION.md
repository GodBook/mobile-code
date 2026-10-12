# 验证记录

更新日期：2026-10-12。所有“待验证”均表示未取得通过证据。

## 0.2.2 在线更新与最终真机验收

公开仓库：[GodBook/mobile-code](https://github.com/GodBook/mobile-code)。已发布：[v0.2.2](https://github.com/GodBook/mobile-code/releases/tag/v0.2.2)。五个发布资产的大小和GitHub SHA256摘要均与本地文件核对后才发布，匿名更新入口已确认指向versionCode5。应用保留原签名身份，覆盖安装兼容。

最终APK：0.2.2/versionCode5，429,738,676字节。SHA256：`61acbfc1d73789876d1e218733244f45a1921eb45e047656abb92e8d8a8613d3`。发布包、手机在线下载的包、安装后的base.apk哈希一致。应用级debuggable=false，ARM64-only，minSdk29/targetSdk37。

| 检查 | 结果 | 范围 |
|---|---|---|
| 主机单元测试 | 6/6通过 | 版本比较、固定仓库/HTTPS、包名/SDK/ABI、大小/哈希、损坏及溢出元数据 |
| 最终APK原生审计 | 484/484文件通过 | 1460项检查，ABI、16KiB ELF对齐、文件哈希、离线资源与唯一安装名称 |
| 实际GitHub下载 | 1/1通过，69.747秒 | 本地桥接包versionCode4获取公开versionCode5，DownloadManager下载完整APK并通过验证 |
| 安卓覆盖安装 | 通过 | 应用“安装更新”→仅允许本应用安装来源→系统“更新”确认；未通过adb替代应用升级 |
| 升级数据保留 | 前/后各1/1通过 | 原项目ID、中文/emoji源码哈希、断点、主题、字号、换行和运行限制；升级后0.476秒 |
| 最终APK真机回归 | 22/22通过，119.706秒 | 四语言真实运行/调试/跨文件语义补全、编辑器、快照、导入导出、输入、故障与资源限制、更新安全 |
| 升级后更新检查 | 1/1通过，3.29秒 | installed=5/offered=5，显示CURRENT，不重复下载 |

可公开核验的[验收JSON及原始测试日志](../verification/releases/v0.2.2/acceptance.json)保存在仓库。真实下载由版本覆盖的本地桥接包发起，最终22项回归在公开APK上进行，两者在记录中明确区分。损坏/降级APK拒绝和FileProvider不能暴露项目文件这两项安全检查包含在最终22项中。

手机曾对本应用设置REJECT_ALL系统网络策略，按在线更新要求仅解除该应用限制。历史锁屏/Doze运行分别为17/22（0.2.0）和19/22（桥接包），不计作通过；失败日志仍保留。用户解锁后桥接包三项重跑通过，随后最终APK在可交互前台状态独立完成22/22。临时保活助手已停止，测试后未残留运行/调试进程，项目及测试数据保留。

0.2.0起新增INTERNET和REQUEST_INSTALL_PACKAGES用于用户授权的软件更新，禁止明文流量，不申请全部文件访问权限。编程功能仍由本地资源完成。下方“无INTERNET”仅描述0.1.0历史基线。无线ADB测试保留Wi-Fi，尚未在这台手机切换飞行模式重跑。

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

核心功能已在上述 ARM64 手机验证。正式 P0 的16KB实际执行、Android10～17/厂商全矩阵，以及性能 P95、低内存、后台回收和长时间运行仍需合适环境。当前APK保留原开发证书以保持升级链；公开发布的对应源码材料已整理，见 [SOURCE_MATERIALS.md](SOURCE_MATERIALS.md)。
