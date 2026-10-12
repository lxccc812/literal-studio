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
docker compose run --rm builder :plugin:verifyPlugin
```

`core` 使用纯 JVM 测试验证识别、互转、字符串、数值、重复 key、错误位置及限额；测试不依赖 IDE 或外部解释器。
生成的 ZIP 在 `plugin/build/distributions/`，工具窗口注册为 Literal Studio。容器编译和打包通过不等于 IDE 界面已验收。

## 后续界面验收

容器内的 `runIde` 不能直接在 Windows 显示图形窗口。本项目采用“容器构建 ZIP → 已有 IDE 从磁盘安装”的流程。
在 PyCharm 的 Settings → Plugins → 齿轮 → Install Plugin from Disk 中选择 ZIP，按 IDE 提示重启并执行人工验收。
仅为测试插件，可以使用现有 IDE 的独立配置目录；是否需要该流程在界面实现阶段再决定。

人工验收清单（使用虚构输入，截图不包含真实项目或账号）：

- 从 View → Tool Windows 打开 Literal Studio，上下编辑器可拖动分隔条；上方可编辑，下方只读可选择复制。
- 将窗口从窄侧栏调整为宽窗口，确认选择框、标题和按钮完整可见，不横向拉满；“复制输出”在输出区标题栏。
- 检查深色/浅色主题与 IDE 缩放，确认字体、边框、分隔条及信息/成功/警告/错误提示统一且易读。
- 输入 `{"items":[1,true,null]}`、`{items:[1,true,null,]}`、`{'items':[1,True,None,]}`，确认自动识别与三种输出模式。
- 输入停止约 400 ms 后自动出结果；“格式检查”只显示诊断，“格式化”生成输出，上方原文保持不变。
- 点击“复制输出”后，按钮短暂显示“复制成功”和勾号；粘贴验证内容一致，约 2 秒后按钮恢复。连续点击及复制后修改输入时，检查提示延期和及时清除。
- 在成功输入后追加非法字符，确认旧输出和复制按钮立即失效，并展示错误行列。
- 快速连续粘贴、切换输入/输出模式并点击按钮，确认旧后台任务不会覆盖最新输入。
- 重复 key、Python 非字符串 key 转 JSON、变量表达式、大整数和浮点范围分别按需求报错或提示。
- 关闭项目、重新打开窗口及卸载插件，检查编辑器和后台任务释放情况。窗口隐藏只隐藏界面；内容释放时才销毁资源。

## 缓存与排查

Compose 使用项目专用的 `literal-studio_gradle-home` volume。常规运行不需要常驻服务，`run --rm` 退出后容器被移除。
缓存删除会导致重新下载依赖，未经排查不要删除 volume。
首次构建失败时先区分 Docker 引擎、镜像下载、Gradle/Maven 网络、SDK 版本或编译问题，不通过在本机安装环境来绕过。
构建服务的内存上限为 2 GiB，Gradle 堆上限为 1 GiB，减少对同机其他容器的资源占用。

## 验证记录

初始化的历史验证结果记录在 [初始化验证](bootstrap-verification.md)。本阶段自动验证记录与尚未完成的人工验收见 [初版验证](first-version-verification.md)。
