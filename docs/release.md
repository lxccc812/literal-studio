# 发布与分发

工程已开源，当前不配置 CI、Marketplace token 或自动发布。

## 首版基线

首版版本号为 `0.1.0`，包含数据字面量检查、格式化、格式互转及 Literal Studio 工具窗口。已在 PyCharm 完成从磁盘安装和基本使用确认；完整跨主题、缩放、生命周期及其他产品兼容验收仍按开发清单记录。

首版源码已推送到远程 `main`，`v0.1.0` 已在 GitHub Releases 公开发布并标记为 Latest，tag 指向首版提交。分发资产为 `plugin-0.1.0.zip` 和 `SHA256SUMS`，安装 ZIP 通过 Docker Compose 构建，不提交到 Git。

从 Releases 的 Assets 下载插件 ZIP 后，可在 PyCharm 中通过 Install Plugin from Disk 安装。Marketplace 尚未发布，仍在后续单独确认范围内。

## 本地阶段

1. 实现初版并通过核心测试、插件构建和真实 PyCharm 界面验收。
2. 对计划支持的产品和 build 范围运行 Plugin Verifier，记录验证结果后更新兼容声明。
3. 确定最终插件名称、plugin ID 与版本号；Literal Studio 暂为工程名称，尚未检查 Marketplace 名称可用性。

## 远程与 Marketplace

- 远程推送与 Marketplace 发布分别授权；不得将允许 push 理解为允许发布插件。
- 首次发布前补充项目级 Vendor 联系方式、英文主描述、图标、截图、许可证与必要政策；不使用个人联系方式。
- 确认第三方依赖的许可证，提供开源仓库链接。
- 可以先使用测试渠道或隐藏发布，Marketplace 审核通过后再公开。
- token、签名密钥和私密信息不得写入仓库；CI/发布流程在需要时单独接入。

官方参考：[上传插件](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)、[审核要求](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)。
