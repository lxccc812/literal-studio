# 开发与验证

## 宿主机边界

只使用已安装的 Git、Docker Desktop 和 JetBrains IDE。构建、测试与 SDK 下载发生在容器或 Docker volume 内；源码和构建产物在仓库目录。
不要直接在 Windows 执行 `gradlew.bat`，也不要启用 IDE 自动下载 JDK/Gradle。可以先将工程作为普通目录打开，使用终端中的 Compose 命令开发。
Kotlin IDE 索引、补全与断点调试的容器接入不属于本次初始化；不要把已搭建构建容器描述成完整的远程 IDE 环境。

## 常用命令

```powershell
docker compose config --quiet
docker compose build
docker compose run --rm builder --version
docker compose run --rm builder projects
docker compose run --rm builder :core:check
docker compose run --rm builder check
docker compose run --rm builder :plugin:buildPlugin
docker compose run --rm builder :plugin:verifyPluginProjectConfiguration
```

`core` 当前没有测试用例，`check` 成功或 `test NO-SOURCE` 只说明工程任务可以运行，不证明任何解析行为正确。
生成的 ZIP 在 `plugin/build/distributions/`。初始化阶段没有 Tool Window 注册，安装后不会出现界面入口。

## 后续界面验收

容器内的 `runIde` 不能直接在 Windows 显示图形窗口。本项目采用“容器构建 ZIP → 已有 IDE 从磁盘安装”的流程。
后续实现界面后，在 PyCharm 的 Settings → Plugins → 齿轮 → Install Plugin from Disk 中选择 ZIP，按 IDE 提示重启并执行人工验收。
仅为测试插件，可以使用现有 IDE 的独立配置目录；是否需要该流程在界面实现阶段再决定。

## 缓存与排查

Compose 使用项目专用的 `literal-studio_gradle-home` volume。常规运行不需要常驻服务，`run --rm` 退出后容器被移除。
缓存删除会导致重新下载依赖，未经排查不要删除 volume。
首次构建失败时先区分 Docker 引擎、镜像下载、Gradle/Maven 网络、SDK 版本或编译问题，不通过在本机安装环境来绕过。
构建服务的内存上限为 2 GiB，Gradle 堆上限为 1 GiB，减少对同机其他容器的资源占用。

## 验证记录

初始化的实际验证结果记录在 [初始化验证](bootstrap-verification.md)。需求文档中的能力仍属于待实现内容。
