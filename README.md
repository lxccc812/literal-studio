# Literal Studio

JetBrains IDE 插件项目，目标是在 IDE 内直接预览和转换 JSON、JS/TS Object/Array 与 Python dict/list 数据。

**当前状态：工程初始化。尚未实现输入/输出界面、识别、检查、格式化或转换。**

## 初版目标

- Tool Window 上方输入、下方格式化输出，可拖动分隔条调整高度。
- 自动识别 JSON、JS/TS 数据字面量、Python dict/list；允许手动指定输入格式。
- 输入停止后自动检查和格式化；提供独立的“格式检查”和“格式化”按钮。
- 错误显示原因、行列位置；不自动修补不符合结构的输入。
- JSON、JS Object/Array、Python dict/list 之间相互转换。
- 本地处理，不执行输入代码，不依赖在线服务。

详见 [初版需求](docs/requirements.md) 与 [架构设计](docs/architecture.md)。

## 工程结构

```text
core/                 独立 Kotlin/JVM 核心，预留解析与转换测试目录
plugin/               IntelliJ Platform 接入与插件描述文件
scripts/              容器构建入口
gradle/wrapper/       官方 Gradle Wrapper
docs/                 需求、架构、开发与发布说明
Dockerfile            Gradle 9.3.0 + JDK 25 构建镜像
compose.yaml          单次构建服务和项目专用下载缓存
```

## 快速开始

宿主机只需现有 Git、Docker Desktop（Linux containers）与 Docker Compose。不需要安装 JDK、Kotlin、Gradle、Python 或 Node.js。

克隆仓库后，在包含 `literal-studio` 目录的 PowerShell 中执行：

```powershell
Set-Location ./literal-studio
docker compose build
docker compose run --rm builder help
docker compose run --rm builder :core:check
docker compose run --rm builder :plugin:buildPlugin
```

生成的安装包位于 `plugin/build/distributions/`。当前包仅是元数据工程骨架，安装后没有工具窗口或格式化功能。

初次构建会联网下载镜像、Gradle、Maven 依赖和 PyCharm SDK；项目运行时的离线目标不意味着首次构建不需要网络。

## 版本基线

| 项目 | 固定版本 |
| --- | --- |
| JDK | 25（容器内） |
| Gradle | 9.3.0 |
| Kotlin | 2.3.21 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |
| 初始构建目标 | PyCharm 2026.2.0.1 |
| 当前声明的 IDE build 范围 | 262.* |

这些版本分别在 `Dockerfile`、Wrapper、根 `build.gradle.kts` 和 `gradle.properties` 中固定。
PyCharm 2026.2 平台要求 Java 25，见 [官方 API 版本要求](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)。
其他 JetBrains 产品属于后续兼容验证目标，暂未声明已验证。

开发与验收方式见 [开发说明](docs/development.md)。首次开发前运行 `sh scripts/setup-public-git.sh`，启用匿名提交身份和公开内容检查；详见 [公开仓库约定](docs/public-repository.md)。

## 许可证

MIT，详见 [LICENSE](LICENSE)。
