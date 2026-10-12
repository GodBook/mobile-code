# 在线更新与 GitHub 发布

仓库：https://github.com/GodBook/mobile-code 。下载版本位于 Releases，APK 不放入 Git 历史。应用编译和运行仍不需要网络；0.2.0 新增联网与安装权限用于用户要求的软件更新。

## 用户操作

打开设置 → 软件更新，可以检查版本、阅读说明并选择下载。默认启动时最多每天检查一次，可关闭；不会自动下载或安装。下载交给 Android DownloadManager，切后台或应用进程结束后仍能继续，重新打开应用恢复进度。网络暂时不可用时显示等待或错误，可以取消和重试。

下载后校验大小、SHA256、包名、版本号、SDK、原生架构和签名，再保存到应用私有目录。只有更新 APK 可以通过 FileProvider 分享给安卓安装器。安装前再次校验并保存编辑内容，运行/调试中禁止安装。系统可能要求允许此应用安装未知来源应用，然后仍需确认安装。项目数据不会被卸载或清空。

0.1.0 没有更新入口，需要先手动覆盖安装一次0.2.0。以后发布更高 versionCode 的版本即可从应用更新。

## 更新协议

入口：`https://github.com/GodBook/mobile-code/releases/latest/download/update.json`。只使用 HTTPS，不在 APK 中保存 GitHub 令牌。元数据最大64KiB，重定向只允许 GitHub 官方下载域名，最多6次。网络请求有超时，失败不会显示为“已是最新版本”。

清单字段：schemaVersion=1、packageName、versionCode、versionName、minSdk、abi、apkUrl、size、sha256、releaseNotes。下载地址必须精确属于本仓库的 `v<versionName>/MobileCode-<versionName>-arm64-v8a.apk`。比较 Android versionCode，拒绝同版本、降级、错误架构及签名变化。

## 发布新版

1. 更新 app/build.gradle.kts 的版本名称和递增版本号；填写 docs/releases/vX.Y.Z.md。
2. 使用原有签名身份构建 release，执行单元测试、真机回归和 APK 审计。签名指纹固定在 tools/release-config.json；不要提交私钥，也不要重新生成签名后直接发布到现有更新通道。
3. 在WSL运行 `python3 tools/archive_corresponding_sources.py --version X.Y.Z`，按锁文件收集GPL/LGPL源码、补丁和构建配方。修改运行时依赖时必须同步更新源码材料。
4. 审查并提交源码，再运行以下命令，由实际APK生成清单和校验和。脚本会拒绝错误签名、可调试配置、SDK或架构不符的APK。

```bash
python3 tools/prepare_release.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --sdk /mnt/d/dev/android-sdk \
  --notes docs/releases/vX.Y.Z.md
```

5. 将源码推送到main，为该提交创建 `vX.Y.Z` 的GitHub Release草稿。上传APK、update.json、SHA256SUMS.txt、应用源码ZIP和对应源码ZIP。确认所有资产完整后发布草稿并设为latest。
6. 用已安装的较低版本实际检查、下载和覆盖安装，记录版本、签名、项目保留及失败处理结果。

版本号只在本地升级测试时可以通过 `-PmcVersionCode`、`-PmcVersionName` 覆盖，测试构建不能发布到stable。回滚程序代码时仍须使用更高versionCode；不要覆盖已有版本的APK或复用旧标签。
