# 播放接口适配

本地分支 `codex/playback-interface-fix` 基于 N3urda/hongguoTV `eea73e6`。

## 依据

- [Issue #19](https://github.com/N3urda/hongguoTV/issues/19)：0.8.0 无法播放、持续重试。
- [Issue #17](https://github.com/N3urda/hongguoTV/issues/17)：旧批量接口返回 110001；早期 preload 方案及后续失效反馈。
- [PR #23](https://github.com/N3urda/hongguoTV/pull/23)：注册设备与完整签名方案，尚未合并；本修复未移植该 PR。
- [tsukiyo-zero/hongguoTV 88c6238](https://github.com/tsukiyo-zero/hongguoTV/blob/88c6238d4ee987f45eb134215e221df0b8634f55/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt)：独立播放设备标识、相应请求头、直接读取内联视频列表。本修复适配了这一实现，保留 GPL 署名。

2026-10-25 同一公开剧集请求对照：旧实现 HTTP 200 / code 110001；社区 preload 补丁 HTTP 200 / 空响应；维护分支请求 HTTP 200 / code 0 / 5 档视频资源。此结果仅说明测试时点的可用性。

## 实现

- 每个 ContentRepository 实例使用独立播放标识，实例内复用；不使用实际电视标识，也不注册、登录或激活付费权益。
- 播放请求采用已在线验证的请求头；搜索及目录原签名逻辑保留。
- 优先读取当前模型中的 video_list，过滤 ByteVC2 并遵守清晰度上限；旧格式继续使用原有 fallback 解析。
- 只接受请求的分集，避免把其他集当成结果；空响应和接口错误码提供明确提示。
- 不向日志输出请求签名、媒体地址或内容密钥。

## 验证

- JDK 17 核心测试：90 项通过，包含 7 项新增接口契约与错误路径测试。
- 真实 Kotlin 播放链路：公开剧集返回 1080P，RemoteVideo 成功解析索引、解密开头 1 MiB 与末尾 64 KiB，得到合法 MP4 ftyp 头。
- Android Debug 构建成功；lint 为 0 错误、30 项警告。
- API 31 / ARM32 实体电视：同一漫剧前两集均成功渲染画面，首帧分别约 2.6 秒和 1.5 秒；第一集播放到 14 秒后暂停，手动选第二集起播成功。证据保存在本地 outputs/sony-playback。
- 未验收：实际扬声器听感、实体遥控器、片尾自动连播、真人短剧与长时间播放。ADB 方向键操作不等于实体遥控器验收。

可跳过搜索/目录、单独检查播放链路：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test
./kotlin-tv/gradlew -p kotlin-tv :core:run --args='--episode <公开分集数字ID>'
```

开发机需要 JDK 17；Android 构建还需要 README 所列 SDK。这里只修改播放链路，尚未承诺修复第三方搜索或短剧目录接口。

Debug 构建使用独立包名 `com.hongguotv.nativeapp.playbackfix`。构建号 10 起名称为「红果 TV」，采用用户选定的红果图标及深色电视横幅；构建号 9 的名称为「红果 TV 修复测试」。同包名、同开发签名可保留本机数据覆盖升级；作者发布签名的原版继续独立保留。它使用开发签名，不是上游正式版。执行 `:app:assembleDebug` 即可生成，不需要发布私钥。

图标与横幅由 imagegen 内置工具按选定预览分别生成，源图仅存本地 outputs/branding。生产资源缩至 256×256 / 640×360，以 WebP quality=88、method=6 压缩；构建无需生成工具或大尺寸源图。

构建号 11 去掉横幅图片自带的内层卡片边框和外侧留白，让单层背景铺满桌面应用卡片。图片不再绘制容器边框，遥控器选中描边由桌面提供。

构建号 12 进一步缩小、减淡红色光晕，只在图标近边缘轻微提亮，文字后方恢复深色背景。
