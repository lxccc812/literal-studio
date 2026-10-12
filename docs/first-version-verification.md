# 初版验证

日期：2026-10-12。

## 本阶段范围

实现已确认的上下输入/输出工具窗口、JSON / JS/TS / Python 数据字面量识别、检查、格式化和互转。核心不依赖 IDE 或外部解释器，输入不执行、不上传、不记录历史。

## 初版功能自动验证（历史记录）

全部编译和测试通过 Docker Compose 执行，未在宿主机添加开发环境。

| 检查 | 实际结果 |
| --- | --- |
| 核心测试 | 27 个用例，失败 0、错误 0、跳过 0 |
| 插件编译、完整 check 与打包 | 成功，容器退出码 0；plugin 模块尚无自动 UI 测试 |
| 项目配置校验 | 成功，保留 until-build 提示；范围维持 262.* |
| Plugin Verifier | 1.410，针对 PyCharm 2026.2.0.1 / PY-262.8665.369，结论 Compatible |
| API 提示 | 4 条弃用、6 条实验 API 提示，均涉及 ToolWindowFactory 的接口默认方法桥接；没有二进制兼容错误 |
| 公开内容与提交身份 | 检查通过，使用临时备用索引覆盖新增文件，未改变真实暂存区 |
| 插件内容与隐私核查 | ZIP 仅包含插件及核心 JAR；类与资源内容未发现个人标识或本机路径 |

最终功能验证命令：

```powershell
docker compose run --rm builder check :plugin:buildPlugin :plugin:verifyPluginProjectConfiguration :plugin:verifyPlugin
```

初始功能包：`plugin/build/distributions/plugin-0.1.0-SNAPSHOT.zip`，63532 字节。相同路径现已由下方的样式优化构建替换。

SHA-256：`44274C2D0006975074E7E7CA5D30A45AE592D05D71FA278208C2DA4652792BE9`。

自动用例覆盖九种方向互转、自动识别歧义、严格 JSON、注释与语言常量、禁止表达式求值、字符串转义与 Unicode、重复 key、非字符串 key、成员顺序、精度与范围、错误行列、检查模式以及输入/深度/节点/输出/时间限制和取消。

Verifier 的 SDK layout 日志还提示部分可选组件 JAR 缺失，最终依赖解析和二进制验证成功。上述结果不替代 IDE 交互验收，也不证明其他产品或整个 262 分支的每一个 build 均兼容。

## 人工验收

首个目标保持 PyCharm 2026.2.0.1，声明范围保持 262.*，未扩大兼容范围。
使用 [开发说明](development.md) 的清单安装 ZIP 后验收，并记录实际结果。

上述开发阶段仅在本地验证；首版提交阶段按后文记录推送源码，Marketplace 尚未发布。

## 样式优化补充

日期：2026-10-12。初始包已在 PyCharm 从磁盘安装，使用者反馈功能无异常；本次聚焦界面样式，未修改解析与转换核心。

- 顶部使用紧凑的原生选择控件与图标按钮，宽窗口并排、窄窗口折行，加入主题与缩放感知留白。
- 输入/输出区统一标题层次与格式标记，复制操作移到输出区标题栏，使用平台 OnePixelSplitter 替换通用分隔条。
- 识别信息和底部提示使用 UI 字体，信息、成功、警告与错误分别带有平台图标和主题色，保留完整文字诊断。

| 检查 | 实际结果 |
| --- | --- |
| 核心测试 | 原 27 个用例维持通过，本次命中 Gradle 缓存，核心未改变 |
| 布局边界测试 | 3 个真实 headless Swing / EDT 用例通过，无失败或跳过；覆盖自然控件宽度、窄窗口边界、操作对齐及缩放窗口后的折行 |
| 完整检查、编译及 ZIP 打包 | Docker Compose 成功，退出码 0 |
| Plugin Verifier | 当前 PyCharm 基线 Compatible；仍为 4 条弃用、6 条实验 API 默认方法桥接提示，无二进制兼容错误 |
| 公开内容与安装包核查 | 检查通过，安装包仍只含核心与插件 JAR，未发现个人标识或本机路径 |
| 真实 IDE 样式验收 | 待安装更新 ZIP；headless 布局检查不替代实际主题、字体、缩放和交互验收 |

样式优化包（历史记录）：`plugin/build/distributions/plugin-0.1.0-SNAPSHOT.zip`，75526 字节。该路径现已由下面的复制提示构建替换。

SHA-256：`50C4A6236D9F1F5297DF981072CF8A04566FDEF33A0F9DB0D5336E13C0B27DE5`。

## 复制成功反馈补充

日期：2026-10-12。剪贴板写入完成后，按钮显示“复制成功”与成功图标，2 秒后恢复；再次复制延长提示，输入变化立即清除提示。下方的转换及精度诊断不被覆盖，窗口内容释放时停止反馈计时器。

Docker Compose 执行 `check :plugin:buildPlugin` 成功，插件 3 个布局用例通过，核心检查命中缓存。此轮没有新增解析或平台 API，未重跑 Plugin Verifier；上方兼容性报告属于此前的样式优化包。复制提示和实际剪贴板内容仍需在 PyCharm 重新安装后人工确认。

复制提示开发包（历史记录）：`plugin/build/distributions/plugin-0.1.0-SNAPSHOT.zip`，75795 字节。

SHA-256：`B01CD4AEDBC6DE0A479DF3B24A89B810C5C4D87B92D93199C91A6E90B3B66BEC`。

## 0.1.0 首版提交基线

日期：2026-10-12。版本由开发快照定为 `0.1.0`，功能、原生界面及复制反馈纳入首版源码提交。已有 PyCharm 安装与基本使用反馈通过，使用者确认可作为首版；跨主题、缩放及生命周期完整清单未逐项记录，不据此扩大兼容声明。

Docker Compose 再次执行 `check :plugin:buildPlugin :plugin:verifyPluginProjectConfiguration :plugin:verifyPlugin`，退出码 0。27 个核心用例的成功结果命中缓存，3 个布局用例重新执行通过；正式版本 Plugin Verifier 结论为 Compatible，仍有 4 条弃用、6 条实验 API 默认方法桥接提示，无二进制兼容错误。

源码、中文注释、测试说明及通用 Git 身份完成公开内容核查，安装包类与资源未发现个人标识或本机路径。首版源码推送到 `main`，不提交构建产物，不创建 tag、GitHub Release 或 Marketplace 发布。

正式安装包：`plugin/build/distributions/plugin-0.1.0.zip`，75739 字节。

SHA-256：`A0E7E2BC50300F4E2DBC86D29F0CB91C92103155A6EB3ACB34911E4A2C77F0D8`。
