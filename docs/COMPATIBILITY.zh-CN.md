# 兼容性说明

本说明针对本仓库 Kotlin 原生个人分发渠道。配置支持、构建通过、模拟器结果和实体电视验收分别列出；具体版本以 [Releases](https://github.com/BeatsAD/hongguoTV/releases/latest) 为准。

## 系统与硬件

| 项目 | 范围与限制 |
| --- | --- |
| 最低系统 | Android 8.0 / API 26；Android 4.x、5.x、6.x、7.x 不在当前支持范围 |
| 使用设备 | Android 电视或电视盒子，面向横屏和遥控器；手机、平板并非主要验收对象 |
| CPU 架构 | APK 不包含 JNI 原生库；Java / Kotlin 层不绑定单一 CPU ABI，实际播放仍依赖设备系统与解码器 |
| 操作方式 | 方向键、确认、返回及常见媒体按键；不同厂商遥控器需分别验证 |
| 视频能力 | 清晰度设置为资源选择上限；可播放格式、分辨率与音频输出由设备和片源共同决定 |
| 网络 | 内容接口和媒体需要网络；应用内更新还需要电视可访问 GitHub API、GitHub 与附件下载域名 |
| 手机辅助 | 手机浏览器与电视须处于同一局域网，不支持跨网扫码直接连接 |
| 其他平台 | 当前没有交付 iOS、Apple TV 或 Web 客户端；根目录历史工程不代表对应平台已可用 |

当前应用不是 Android 4.x 的轻量兼容包，不提供仅修改最低 API 的旧系统安装包。提高或降低系统门槛、支持新解码器都需要独立验证。

## 安装包与数据

| 构建或来源 | 包名与更新方式 |
| --- | --- |
| 本仓库个人 `distribution` | `com.hongguotv.nativeapp.playbackfix`，既有个人签名，非调试，启用本仓库在线更新 |
| 本地 `debug` | 同为 `com.hongguotv.nativeapp.playbackfix`，不接入在线更新；本机调试签名可能不同 |
| 历史 `release` | `com.hongguotv.nativeapp`，不加入当前个人渠道 |
| 更早 React Native 版 | `com.hongguotv`，属于历史独立实现 |

覆盖升级需要实际包名与签名相同，并使用更高构建号。包名相同不代表签名相同；界面名称相同也不代表数据互通。不要卸载、清数据或降级绕过冲突。收藏与观看位置保存在本机，导出备份后可在兼容的原生版之间由用户确认恢复。

1.0.0 起显示版本采用简洁格式，内部编号继续递增。0.10.x 迁移到新协议需先手动覆盖安装一次；应用数据仍按同包名、同签名的正常覆盖升级保留。相关代码和测试不替代具体设备的系统安装器验收。

## 已有验证与未验证范围

截至 2026-11-11：

- 当前源码已通过 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。个人分发流水线还执行签名构建与 APK 身份检查。
- `0.10.5+3-playbackfix / 10003` 在 Android TV API 31 / ARM64 模拟器上完成系统覆盖升级、启动、版本检查及可导出业务数据对比。该次 GitHub 下载使用了测试环境的网络代理。
- README 中的首页和更新展示图以该模拟器上的实际截图为参考，经 AI 编辑统一使用 `hongguo` 名称，细节以实际应用为准；不能用于像素对照。该轮升级验证没有新增视频首帧、换集、长时播放或实体电视验收。
- 既往开发版本有模拟器播放和部分电视测试记录，不能推导为当前分发包在所有设备通过。Android 8.0 真机、不同芯片解码与音频输出、特殊遥控器和长时稳定性仍需按设备验证。

模拟器升级记录对应的公开版本为[构建 10003](https://github.com/BeatsAD/hongguoTV/releases/tag/kotlin-v0.10.5-build.10003)，自动化发布记录见[该次工作流](https://github.com/BeatsAD/hongguoTV/actions/runs/42954622658)。这些证据不能保证未来第三方接口、网络或内容持续可用。

## 反馈设备兼容性问题

请使用 [Bug 模板](https://github.com/BeatsAD/hongguoTV/issues/new?template=bug_report.yml)，提供设备型号、Android 版本、应用版本和构建号、问题步骤、画质、错误原文及脱敏诊断。请明确是真机还是模拟器，不要上传私人备份、Cookie 或完整播放签名地址。

[安装与使用指南](INSTALL.zh-CN.md) · [返回首页](../README.md)
