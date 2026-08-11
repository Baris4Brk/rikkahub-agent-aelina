# RikkaHub 2.4.5 定制版功能审计与回滚索引

## 基线与保护边界

- 实施基线：`feature/second-user-linux-runtime@2b3c838c`，Room v42。
- 参考上游：`rikkahub/rikkahub` tag `2.4.5`；本轮按项移植修复思想，没有合并上游分支。
- Room 版本保持 v42；未修改 `Conversation`、`ConversationEntity`、`AppDatabase` 或 `ToolExecutionGate` 的核心职责。
- 第二用户 authority、epoch、固定会话、DIRECT 工具面、Owner 自动执行、Secret Vault、审批策略与全部既有桥接能力保持不变。
- 现有助手提示词没有迁移。`{{cur_time}}` 与 `{{cur_datetime}}` 继续解析；只有以后补入的新默认助手模板使用 `{{cur_date}}`。
- 所有验证均为本地 JVM、编译或 APK 构建；未连接主力设备，也未运行 connected、instrumentation 或 UIAutomator 测试。

## 功能审计结果

| 项目 | 初始状态 | 最终状态 | 提交 | 主要证据 |
|---|---|---|---|---|
| Responses API 函数工具与内置工具共存 | MISSING / 阻断 | IMPLEMENTED | `60edeb27` | `ResponseAPIMessageTest` 覆盖函数、搜索、生图及空组合 |
| Workspace 缺失目录保留记录 | BROKEN | IMPLEMENTED | `5d497f42` | 完整性检查只标记 BROKEN，不删除记录或创建伪目录 |
| Rootfs 与文件工具共享 mount 解析 | PARTIAL | IMPLEMENTED | `5d497f42` | `WorkspaceMountResolverTest` 覆盖最长前缀、伪文件拒绝和越界 |
| 长对话阶梯式截断 | PARTIAL | IMPLEMENTED | `481bda75` | `MessageTest`、`ManualCompressionContextPolicyTest` 覆盖轮次与工具事务边界 |
| 快速切助手与创建导航 | PARTIAL | IMPLEMENTED | `19605963` | 串行选择，只有最后请求导航；第二用户固定会话旁路普通切换 |
| 删除结果与导航一致 | PARTIAL | IMPLEMENTED | `19605963` | 仅 Deleted 导航；受保护、缺失和失败均保留正确 UI 状态 |
| HEIC/HEIF/AVIF/ICO 等图片识别 | MISSING | IMPLEMENTED | `fdd1c89f` | `ImageFormatDetectorTest` 覆盖 MIME、扩展名、魔数、brand 和损坏输入 |
| SAF copy/move | MISSING | IMPLEMENTED | `fa5a388f` | `WorkspaceDocumentFileOpsTest`；跨 Workspace 复制、哈希核验后才删源 |
| Skill 真实目录身份 | MISSING | IMPLEMENTED | `c207b220` | 导航、读取、运行和列表 key 统一使用 `skillDir.name` |
| 本地备份与云端选择解耦 | MISSING | IMPLEMENTED | `299db429` | `LocalBackupConfigTest`；本地固定含设置、数据库和应用文件 |
| 图片裁剪错误处理 | MISSING | IMPLEMENTED | `0c9782dc` | 区分成功、取消、UCrop 错误和缺失输出，并清理临时文件 |
| 历史消息稳定时间 | MISSING | IMPLEMENTED | `0c9782dc` | `TemplateTransformerTimeTest` 使用消息 `createdAt` |
| TTS 可编辑下拉兼容 | MISSING | IMPLEMENTED | `0c9782dc` | 易受输入法影响的选择项改为普通 `DropdownMenu` |
| 全局 TTS 本地播放速度 | MISSING | IMPLEMENTED | `0262f153` | `TtsPlaybackSpeedTest`；首次播放、缓存重播和 Owner 读写共用设置 |
| Provider 自动兼容规则 | PARTIAL | IMPLEMENTED | `3b95b867` | `ModelCompatibilityResolverTest`；Chat/Responses 共用温度决策 |
| 新默认助手日期占位符 | PARTIAL | IMPLEMENTED | `5e1b1861` | 只改默认模板常量，不写回现有助手 |
| Mermaid 离线与隔离 | MISSING | IMPLEMENTED | `57f1b94c` | 11.16.0 与 MIT 许可证入包；`LocalAssetRequestPolicyTest` |
| 原生 Kotlin 代码高亮 | MISSING | IMPLEMENTED | `4ae46913` | 30 种语言 golden fixtures、1 MiB 降级和性能预算 |
| 安全文件夹同步 | EXISTS | UNCHANGED | — | 继续使用 `ManagedFolderCoordinator`，失败和不安全路径不删除记录 |
| 每助手搜索开关 | EXISTS | UNCHANGED | — | `Assistant.enableWebSearch` 与现有 UI/运行时策略保持 |
| 旧/新 Android 存储权限 | EXISTS | UNCHANGED | — | Android 13+ media 权限与 Android 12- legacy read 权限继续分流 |
| usage 累计与 cached-token 方言 | EXISTS | TESTED | `3b95b867` 前既有 | 保留 OpenAI、DeepSeek、Responses 与 Codex cached-token 解析 |

## 关键行为与隐私检查

### Provider 与第二用户工具面

- Responses API 只生成一个扁平 `tools` 数组；函数、Owner、Workspace、MCP、插件和 OpenAI 内置工具不会互相覆盖。
- `customBody` 仍在自动兼容规则之后合并，用户的显式覆盖语义不变。
- `ModelCompatibilityResolver` 只产生单次请求决策，不回写 Provider 或 Model。
- Moonshot K2.6 思考开启时发送 retained thinking；Kimi K2.5/K2.6/K3 过滤温度；Grok 生图按能力省略不兼容 `size`。

### Workspace 与文件

- 缺失 Workspace 只标记 BROKEN，数据库记录、助手绑定和执行账本均保留。
- `/workspace`、`/skills`、`/tool_outputs` 与条件满足时的 `/sdcard` 使用同一挂载表；`/dev`、`/proc`、`/sys` 拒绝普通文件读取。
- 所有 mount 解析结果继续进行 canonical 越界检查。
- 跨 Workspace move 在副本完整且 SHA-256 相同后才删除源；失败不会把不完整目标暴露为成功文件。
- 图片识别不信任单一扩展名；无法安全解码时按普通文件返回，不产生空图片消息。

### 对话、提示词与 TTS

- 阶梯窗口日志只含消息数量、截断点和脱敏 UUID 前缀哈希，不含消息正文。
- 手动摘要、当前轮、steering、恢复、活动工具事务和临时密钥引用按组保留。
- TTS 播放速度仅保存在 DataStore，不增加数据库字段；Owner 修改不增加审批步骤。
- Provider 合成语速与本地播放速度仍是两个独立概念。

### Mermaid 与高亮

- Mermaid SHA-256：`74D7C46DABCA328C2294733910A8AA1ED0C37451776E8D5295DA38A2B758FB9B`。
- 内嵌和全屏预览都从 `https://rikkahub.local/assets/mermaid/mermaid.min.js` 加载；仅该 GET 路径可用，查询、遍历、外部主机和其他脚本均返回阻断响应。
- Mermaid 使用 strict security 与 CSP；语法错误时保留源码并显示本地错误，不请求网络。
- 高亮 grammar 来自上游 2.4.5 对 highlight.js 11.11.1 的纯 Kotlin 移植；QuickJS/Prism 已从 highlight 模块删除。
- 应用仍显式依赖 QuickJS 以保留第二用户的 `eval_javascript` 工具能力。
- `HighlighterLimitsAndPerformanceTest` 在本机 JVM 中完成 100、1,000、5,000 行联合测试用时 0.493 秒；5,000 次请求序号测试用时 0.004 秒。该数据是回归预算，不冒充 Android UI 帧率。
- 直接 QuickJS Android 对照需要模拟器 instrumentation；本轮遵守主力设备禁测规则，没有在主力设备上获取该数据。

## 验证矩阵

定向验证在各提交前完成：

- `:ai:testDebugUnitTest`
- 相关 `:app:testDebugUnitTest --tests ...`
- `:app:compileDebugKotlin`

最终串行结果：

| 任务 | 结果 | 证据 |
|---|---|---|
| `:highlight:testDebugUnitTest` | PASS | 57 tests，包含 30 个语言 fixture 组 |
| `:speech:testDebugUnitTest` | PASS | 13 tests |
| `:app:testDebugUnitTest` | PASS | 2017 tests；旧 Termux fake 修正后增加未验证停止反向用例 |
| `:app:compileDebugKotlin` | PASS | 独立执行及后续构建均通过 |
| `:app:lintDebug` | INCOMPLETE | 三次均未返回任务摘要，最长一次为无 daemon/无配置缓存模式 15 分钟；没有新 lint 报告，未伪报通过 |
| `:app:assembleDebug` | PASS | 无 daemon/无配置缓存模式 1 分 52 秒完成 |
| `:app:assembleRelease` | ENVIRONMENT FAILURE | 编译、R8、资源优化和 `lintVitalRelease` 通过；最终 `packageRelease` 因缺少 release signing `storeFile` 失败 |
| `:app:compileDebugAndroidTestKotlin` | PASS | 57 秒完成；只编译源码，未连接设备或运行测试 APK |

最终 universal debug APK：

- 文件：`app/build/outputs/apk/debug/app-universal-debug.apk`
- 大小：132,661,671 bytes
- SHA-256：`D699D7A0E1CA0F6402BA177354ECA8DF557AE68BB56F13B756D6B84CFE1842AC`
- 签名：Android Debug，APK Signature Scheme v2 验证通过。
- 包内容：Mermaid runtime 与许可证存在；旧 highlight `res/raw/prism.js` 不存在；QuickJS native 库仍存在，保证 `eval_javascript` 能力未被高亮重构删除。

`lintDebug` 的结果是构建环境超时，不等同于代码通过或代码失败。release 已证明代码、R8、资源优化和 vital lint 可通过，但本机没有发布签名文件，因此不能形成 release APK；应用没有创建、猜测或导出任何私钥。后续若要补齐，应在资源更充足且配置了合法 release signing 的本机或 CI 上单独重跑，不应在主力手机上验证。

## 单独回滚索引

这些提交按依赖顺序排列，可使用普通 `git revert <commit>` 单项回滚；不要使用 reset 或清理工作树：

- `60edeb27` — Responses 工具数组
- `5d497f42` — Workspace 保留与 mount 解析
- `481bda75` — 阶梯上下文
- `19605963` — 助手切换与删除导航
- `fdd1c89f` — 图片格式识别
- `fa5a388f` — SAF copy/move
- `c207b220` — Skill 目录身份
- `299db429` — 本地备份选择
- `0c9782dc` — 裁剪、消息时间、TTS 下拉
- `0262f153` — TTS 播放速度
- `3b95b867` — Provider 兼容解析
- `5e1b1861` — 新默认助手日期模板
- `57f1b94c` — Mermaid 本地运行时
- `4ae46913` — Kotlin 高亮引擎

本审计文档本身不包含密钥、Provider URL、Workspace 路径、命令、聊天正文、设备标识或真实工具输出。
