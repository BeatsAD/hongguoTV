# 贡献指南

本仓库维护 Kotlin 原生 Android TV 客户端，生产代码位于 `kotlin-tv/`。欢迎提交可复现的 Bug、具体使用场景和范围集中的改进。

## 反馈与建议

- 普通问题使用 [Bug 反馈模板](https://github.com/BeatsAD/hongguoTV/issues/new?template=bug_report.yml)，提供应用版本、构建号、设备、Android 版本与复现步骤。
- 功能建议使用[功能建议模板](https://github.com/BeatsAD/hongguoTV/issues/new?template=feature_request.yml)，说明要解决的问题、遥控器使用方式和兼容性要求。
- 安全漏洞请先阅读[安全政策](SECURITY.md)，使用[私密报告入口](https://github.com/BeatsAD/hongguoTV/security/advisories/new)，不要先公开漏洞细节、密钥、设备身份或私人数据。

## 开发约定

1. 阅读 [AGENTS.md](AGENTS.md)、[原生版说明](kotlin-tv/README.md)和[设计规范](kotlin-tv/DESIGN.md)。保留他人的本地改动；工作分支使用 `codex/` 前缀。
2. 复用 Kotlin、原生 View、Media3 与 app/core 分层，不顺带升级依赖或引入框架。根目录 React Native / Node / iOS 工程作为历史参考。
3. 变更保持最小范围，补充必要验证。原生界面比较须保持相同窗口、字号和焦点状态；编译成功不等于视觉、遥控器或设备验收。
4. 不提交密钥、令牌、私人备份、APK、日志或设计过程材料。本地证据放在被忽略的 `outputs/`；对外展示图片先检查隐私和来源。
5. 禁止批量或递归删除，也不得通过循环或拆分命令绕过。需要清理多个路径时，列出影响并交由用户手动处理。

## 验证与 PR

需要 JDK 17、Android SDK 36、Build Tools 36.0.0。在仓库根目录运行：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

按变更范围说明测试结果、模拟器或真机环境，以及未验证内容。文档修改至少检查链接和命令；UI 修改提供对比截图。不要把历史测试结论扩展为当前版本验收。

PR 描述请使用仓库模板，写清问题、修改后的行为、必要取舍和验证。涉及包名、签名、更新或本机记录时，说明升级与数据保留影响。提交代码不包含发布或设备安装授权。

## 来源与许可

应用及 GPL 派生实现继续按 GPL-3.0-only 提供。保留上游版权、许可证和[第三方声明](kotlin-tv/NOTICE.md)，不得将整仓或媒体处理派生实现改标为 MIT。独立社区模板的许可范围见[中文许可说明](docs/LICENSING.zh-CN.md)。
