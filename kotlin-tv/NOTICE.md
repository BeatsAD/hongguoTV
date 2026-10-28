# 原生版第三方声明

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

本地修复分支的应用图标与 TV banner 采用用户选定的 AI 生成红果设计，分别以 256×256 与 640×360 的 WebP 资源随包提供。这里的应用不是内容平台官方客户端。

播放请求身份、请求头及内联视频列表解析参考 tsukiyo-zero/hongguoTV（GPL-3.0），提交
`88c6238d4ee987f45eb134215e221df0b8634f55`：
https://github.com/tsukiyo-zero/hongguoTV/blob/88c6238d4ee987f45eb134215e221df0b8634f55/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt

匿名设备注册协议参考 tsukiyo-zero/hongguoTV 的 `DeviceRegister.kt`（GPL-3.0-only），
原实现声明派生于 charlie901208/HGTV 的 feat/phone-login 分支。本版保留协议字段，
使用 SecureRandom、受限响应读取、独立匿名 Cookie、持久身份和失败冷却；不引入账号登录。
https://github.com/tsukiyo-zero/hongguoTV/blob/main/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DeviceRegister.kt
