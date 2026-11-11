# hongguo 版本、APK 发布与应用内更新

当前开发基线为 **1.0.0**，正式安装包命名为 **`hongguo-1.0.0.apk`**。生产 Android 源码位于 `kotlin-tv/`，个人渠道为 `BeatsAD/hongguoTV`。最新已公开的版本以 [Releases](https://github.com/BeatsAD/hongguoTV/releases/latest) 为准，修改版本配置不表示已经发布。

## 名称与版本

| 项目 | 规则 |
| --- | --- |
| 应用名称 | `hongguo` |
| 用户看到的版本 | `1.0.0`，不附加流水线编号或 `playbackfix` |
| Release 标题 | `hongguo 1.0.0` |
| Git 标签 | `v1.0.0` |
| 唯一上传附件 | `hongguo-1.0.0.apk` |
| 内部版本号 | 自动分配，保持大于已有分发构建，不进入安装包文件名 |

修复问题递增修订号，例如 `1.0.1`；增加功能递增次版本，例如 `1.1.0`；重大不兼容调整才提升主版本。一个正式版本只对应一个 APK，发布后不替换内容。重做已发布版本必须提升版本名称。

`distribution/channel.properties` 记录版本、包名、签名公钥指纹和内部编号基线。自动编号取以下较大值：

- `max(versionCodeBase, versionCodeFloor) + GITHUB_RUN_NUMBER`；
- 所有可识别历史 Release（包括草稿）内部编号的最大值加一。

当前最低保留编号为 10003，不因显示版本改为 1.0.0 而归零。工作流串行执行；重新运行已存在且源码相同的草稿会复用其编号，公开版本禁止重新分配或覆盖。未知 latest 格式、签名冲突、编号溢出、不同源码的同名草稿都会停止发布。

本地 `version` 命令还使用被忽略的 `outputs/version-code-reservation.txt` 记录本机编号。独立机器上未公开、未上报的私有构建无法由远端得知；不要把不同机器的私有签名包作为正式升级渠道混发。正式分发统一由本仓库串行工作流完成。

## 升级与数据连续性

`distribution` 保持 `com.hongguotv.nativeapp.playbackfix` 包名和现有个人签名，关闭调试、启用 R8 与在线更新。应用名称和显示版本变化不迁移或清除本机记录。安装前仍需包名、签名匹配，内部版本号更高。

**从 0.10.x 首次迁移到 1.0.0，需要手动下载并覆盖安装一次。** 旧客户端固定读取 `latest/download/update.json`，无法识别只有 APK 的新 Release。不要卸载、清除数据或降级来迁移。1.0.0 及以后使用新的 Release API 更新协议，后续可继续在应用内更新。

新客户端遇到历史 `kotlin-v…-build.…` Release 时，只读取该明确标签下的历史 `update.json`；现代 Release 的错误、缺失字段、限流和访问拒绝不触发此兼容回退。历史附件不自动删除或改写。

## APK-only 更新协议

客户端通过固定的 `https://api.github.com/repos/BeatsAD/hongguoTV/releases/latest` 检查更新，不需要 GitHub 登录或凭据。正式 Release 正文保留两个面向用户的栏目：

```markdown
## 新增功能
- 本版新增功能；无新增时明确写“本版无新增功能”。

## 问题修复
- 相对上一版实际完成的问题修复；无修复时明确说明。
```

内容维护在 `kotlin-tv/distribution/notes/版本号.md`。正式版本没有对应说明或缺少任一栏目时，验证会失败。不能重复将旧版已完成的修复写为本版新增。

发布脚本在说明末尾追加不在渲染页面显示的 `<!-- hongguo-update:… -->` 元数据，记录协议 schema 2、包名、显示版本、内部编号、最低 API 和源码提交。这里没有密钥或私人数据，也不是额外上传文件。

客户端要求正式、非草稿版本，标签与显示版本匹配，存在唯一且命名正确的 APK，并从 GitHub 附件信息取得长度和 SHA-256。下载地址由固定仓库、标签和文件名构建，不信任正文或附件中任意 URL。下载后核对大小和 SHA-256；安装前再次核对实际包名、版本、最低 API 与当前安装签名。

API 响应受大小和超时限制。应用前台浏览时每 6 小时检查，首次延迟 8 秒；播放或进入后台会取消请求。设置中可以手动检查、关闭自动检查和下载。下载后仍需用户确认系统安装，首次须允许本应用安装未知来源应用。

## 构建与发布

工作流为 `.github/workflows/tv-release.yml`。PR 运行验证；只有本仓库 `main` 的验证及保留定制检查成功、对应 PR 当前提交经所有者审核，并存在应用代码变化，才可使用签名 Secrets 发布。原生路径推送或手动触发仍受工作流条件控制，普通本地构建命令不上传。发布前脚本会再次核对人工审核及发布记录的 PR 来源；手动触发不能绕过门禁，详情见 [上游修复流水线](UPSTREAM_SYNC.md)。本地候选包允许没有 PR 审核记录，但这种记录不能用于公开发布。

1. 运行 Python 发布约束测试及 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
2. 查询历史 Release，自动分配内部编号；使用 JDK 17、SDK 36 / Build Tools 36.0.0 构建并运行 `lintDistribution`。
3. 检查 APK 实际名称、包名、版本、最低 API、非调试标记、安装权限和既有签名。
4. 输出被忽略的 `outputs/releases/v1.0.0/hongguo-1.0.0.apk`、`release-record.json` 和 `release-notes.md`。后两者用于内部复核，**不上传到 Release 附件**。
5. 创建草稿，仅上传 APK；读回附件大小、摘要和完成状态，全部匹配后公开并按内部编号与显示版本判断能否设为 latest。失败保留旧 latest，不使用 `--clobber`，不删除已有附件。

不再单独上传源码 ZIP、`SHA256SUMS` 或 `update.json`。源码通过对应公开 Git 标签及其构建说明提供，GitHub 自动生成的源码 ZIP / tar.gz 下载链接仍会显示。

本地使用现有签名配置后，在干净、已提交的仓库执行：

```sh
./scripts/build-kotlin.sh
# 自动分配编号并构建，输出 outputs/releases/v1.0.0/hongguo-1.0.0.apk
# 不会发布；已存在的同版本输出不覆盖。
```

`version` 命令需要已配置的 GitHub CLI 读取公开历史；可用 `python3 scripts/tv-update.py version` 单独预留编号。精确重建历史包时应检出对应源码标签，使用内部发布记录里的编号和版本参数；不要用当前源码重建旧版本号。

签名变量为 `HONGGUOTV_KEYSTORE`、`HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`，也可以使用被忽略的 `kotlin-tv/keystore.properties`。私钥不随源码提供。自动化构建不等于旧版覆盖升级、系统安装器或实体电视验收。
