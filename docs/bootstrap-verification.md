# 初始化验证

日期：2026-10-10。

## 已执行

| 检查 | 实际结果 |
| --- | --- |
| 独立仓库 | 项目独立 Git 仓库，`main` 分支 |
| Compose 配置 | `docker compose config --quiet` 成功 |
| 容器镜像 | `docker compose build` 成功，固定镜像 tag 和 digest |
| 实际容器工具链 | Temurin 25.0.1、Gradle 9.3.0，运行于 Linux 容器 |
| Gradle Wrapper | 官方生成，分发包 SHA-256 已写入 Wrapper 配置 |
| PyCharm SDK | 2026.2.0.1，解析得到 build `262.8665.369`，作为固定构建基线 |
| 多模块与完整构建 | `projects build :plugin:verifyPluginProjectConfiguration` 成功，容器退出码 0 |
| 插件产物 | `plugin/build/distributions/plugin-0.1.0-SNAPSHOT.zip`，仅为工程骨架 |
| Git 文件检查 | 缓存、产物、IDE 配置与 `.env` 均被忽略；暂存差异无空白错误 |

最终验证命令：

```powershell
docker compose run --rm builder projects build :plugin:verifyPluginProjectConfiguration
```

## 验证边界

- `core` 与 `plugin` 当前没有业务源码或测试用例，编译和测试显示 `NO-SOURCE`；构建成功不代表解析、转换或界面功能已经实现。
- 工程配置校验提示官方建议移除 `until-build`。此阶段有意保留 `262.*`，避免在未验证时扩大兼容范围；后续兼容测试完成后再调整。
- 未执行完整 `verifyPlugin` 二进制兼容验证，未安装到真实 IDE，未完成 UI 验收，未发布 Marketplace 插件。
- 首次下载构建期间 Docker 引擎连接中断，随后重试发现 Gradle 派生缓存元数据不完整；仅重建项目专用缓存后完成验证。引擎中断的根因未确认。
- 最终 Compose 限制构建容器内存为 2 GiB，Gradle 堆为 1 GiB，使用单 worker；未修改 Docker 全局设置或其他项目配置。
