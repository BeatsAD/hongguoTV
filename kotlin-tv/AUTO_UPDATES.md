# 个人分发流水线与应用内更新

更新渠道为 `BeatsAD/hongguoTV` 的 GitHub Releases。生产 Android 源码仍在 `kotlin-tv/`，上游仓库仅供参考。本流程不自动同步或合并上游，也不运行旧的整仓镜像导出脚本。

## 包名、签名和升级连续性

`distribution` 构建沿用当前已安装应用的 `com.hongguotv.nativeapp.playbackfix` 包名及个人开发签名，以便覆盖升级保留片单、进度、播放身份和设置；分发构建关闭调试、启用 R8 和在线更新。它不是上游正式签名发行版。签名公钥指纹、仓库、版本基线和最低 API 统一记录于 `distribution/channel.properties`。

`debug` 构建保留本地调试及更新隔离；旧 `release` 构建不加入本个人渠道。首次从不带在线更新的版本升级，须核对设备、用户、原包签名及版本后手动覆盖安装一次；随后通过应用内更新。更换包名或签名需要另行设计迁移，不能卸载或降级绕过。

## GitHub Actions

工作流：`.github/workflows/tv-release.yml`。

1. 原生代码 PR、main 推送和手动触发运行 Python 发布约束测试，以及 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
2. 只有本 fork 的 main、且验证成功，才进入持有签名 Secrets 的发布任务；PR 不签名、不发布。
3. 使用 JDK17、SDK36/build-tools36，执行 `:app:lintDistribution :app:assembleDistribution`。
4. 发布脚本核对 APK 实际包名、版本、最低 API、非调试标记、安装权限和签名；生成 APK、对应原生源码 ZIP、update.json 与 SHA256SUMS。
5. 先上传到草稿，逐个核对 GitHub 附件长度、摘要和上传状态，全部成功再公开并设置 latest。失败保留旧 latest，不重写已发布附件；旧构建重跑不取代较新版本。

仅 `main` 的相关路径推送会自动发布；`workflow_dispatch` 可从 main 对同一源码分配一个新构建号，用于升级验收或重新发布可信源码。回退修复应使用更高构建号，不降低设备版本。

版本：`versionCodeBase + GITHUB_RUN_NUMBER`，当前基线为 10000；versionName 为 `版本基线+运行编号-playbackfix`。例如第一轮为 `0.10.5+1-playbackfix / 10001`，标签 `kotlin-v0.10.5-build.10001`。更改工作流或版本基线时仍须维持构建号单调递增。

## 首次配置

仓库启用 Actions；在 Settings → Secrets and variables → Actions 配置：

- HONGGUOTV_KEYSTORE_B64：现有签名 keystore 的 Base64 内容。
- HONGGUOTV_STORE_PASSWORD、HONGGUOTV_KEY_ALIAS、HONGGUOTV_KEY_PASSWORD：对应配置。

密钥仅进入发布任务临时单文件和 GitHub Secrets；不写入源码、日志、APK、源码 ZIP 或测试附件。当前渠道签名已用于现有设备，不能以新生成密钥替换。仓库 GITHUB_TOKEN 只在发布任务申请 contents:write，用于本仓库 Release，无需把 GitHub 凭据放进电视。

## 电视更新行为

应用前台浏览时每 6 小时检查一次，首次延迟 8 秒；播放及进入后台取消更新请求。设置 → 版本与更新可手动检查或关闭自动检查和下载。

元数据固定从本渠道 latest/download/update.json 获取；文件名和标签严格校验，清单内任意仓库或 URL 不影响下载目标。下载允许 GitHub HTTPS 及其附件 CDN 跳转，核对长度和 SHA-256；安装前再次核对实际包名、版本、最低 API 和当前安装签名。

下载后须在应用选择安装，并由系统确认；首次需要允许安装未知来源应用。普通电视没有静默安装权限。下载失败不影响当前安装，不允许通过清除数据或卸载解决升级冲突。

## 本地复现

安装 JDK17、Android SDK36/build-tools36；设置签名环境变量和 Release 清单中的精确版本：

```sh
HONGGUOTV_VERSION_CODE=10001 HONGGUOTV_VERSION_NAME=0.10.5+1 ./kotlin-tv/gradlew -p kotlin-tv :app:lintDistribution :app:assembleDistribution
python3 scripts/test-tv-update.py
```

签名路径及密码使用环境变量或被忽略的 keystore.properties 配置，勿提交私钥。原生源码压缩包包含 Gradle 包装器、许可证、第三方声明、构建说明及签名参考源码；不导出整个本地 Git 历史、设备数据或 outputs。

自动构建、公开附件、模拟器在线覆盖升级和实体电视验收是不同证据层。流水线成功不等于所有影片可播，当前接口兼容回退仍可能受上游变化影响。实体电视安装须另获授权。
