# RikkaHub 系统默认助理 — 第一阶段实施清单（优化版 · 交给执行智能体）

> **给执行智能体（如 GPT-5.4 / 5.6 / Codex 等）的完整任务书。**  
> **只做第一阶段。** 不含 STT/TTS、屏幕 Assist 截图上下文、电源键 / 「你好 YOYO」/ 气息唤醒。  
> **允许并鼓励用 ADB 自行探测、装包、看 logcat、改完再验。** 不设命令白名单；自行判断安全，避免破坏系统分区与用户数据。  
> **仓库根目录：** 以用户当前打开的 `rikkahub-agent` 为准（常见：`D:\taolun\rikkahub-agent` 或桌面 `rikkahub-agent`）。  
> **手机（2026-07 实机已连过）：** 荣耀 **AAK-AN00**，Android **16**（SDK 36），USB 调试可用。

---

## 一句话目标

```text
用户把 RikkaHub 设为 Android 默认数字助理
  → 系统唤起 VoiceInteractionSession 浮层
  → 文字消息进入「第二用户」特权会话（Assistant.privilegedConversationId）
  → 解锁态工具能力与本机 LocalChat 特权会话对齐
  → 锁屏态强制降权
```

**不是目标：** 完整替代 YOYO；电源键；「你好 YOYO」；气息唤醒；后台低功耗唤醒词。

---

## 0. 执行智能体工作方式（必读）

### 0.1 总原则

1. **先 ADB 探测 → 再改代码 → 再装包验证 → 再改。** 禁止只写代码不碰真机就宣称完成。  
2. **复用现有 Chat / 第二用户管线**，只加「系统入口 + origin + 路由」。  
3. **Session 保持轻量**：`VoiceInteractionService.onReady` 不启动模型。  
4. **可回退**：用户必须能改回 YOYO / MagicVoice。  
5. **同一步骤失败两次就换策略**，不要空转烧额度。  
6. **最终结论写在正常回答区**，附 ADB 原始输出摘要与改动文件列表。

### 0.2 ADB 环境（本机已确认可用）

```text
ADB 路径（优先）:
  C:\Users\yileina\AppData\Local\Android\Sdk\platform-tools\adb.exe
备用:
  D:\Android\Sdk\platform-tools\adb.exe

当前设备示例:
  ASUJ6R6324003410  device  model:AAK_AN00  product:AAK-AN00
```

若 `adb` 不在 PATH，用绝对路径。先执行：

```powershell
& "C:\Users\yileina\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices -l
```

无 `device` 状态则停止写 VoiceInteraction 验收结论，先解决连接。

### 0.3 ADB 使用策略（按用户要求：不设白名单）

执行智能体**可以自行使用 ADB 完成探测与验证**，包括但不限于：

- `devices` / `getprop` / `pm` / `dumpsys` / `settings` / `cmd role`
- `install -r` debug APK
- `logcat` 过滤本应用 tag
- `am start` / `am instrument` 调试入口
- 必要时 `shell` 内只读检查

**仍须克制（不是白名单，是底线）：**

- 不要 `wipe`、不要乱 `rm -rf` 用户数据、不要卸载系统关键包  
- 不要改写系统分区  
- 不要把用户的 API Key / 备份明文打进报告  
- 改默认助理前先记录当前 `voice_interaction_service`，方便回退说明  

### 0.4 构建与安装（仓库惯例）

```powershell
# 在仓库根目录
.\gradlew :app:installDebug
# 或 assembleDebug 后 adb install -r
```

包名以当前工程 `applicationId` 为准（fork 可能是 `excp.rikkahub` 或历史 id，**以 `app/build.gradle.kts` 为准**）。

---

## 1. 实机基线（已测，执行时请重跑确认）

以下为 2026-07 对 **AAK-AN00 / Android 16** 的探测结果。执行智能体开工时**必须重跑**，写入报告。

| 探测 | 当时结果 | 含义 |
|------|----------|------|
| `getprop ro.product.model` | AAK-AN00 | 荣耀 WIN RT 系列 |
| `getprop ro.build.version.release` | 16 | Android 16 |
| `settings get secure voice_interaction_service` | `com.hihonor.magicvoice/...MagicVoiceInteractionService` | **当前系统 Voice Interactor = 荣耀 MagicVoice（YOYO 系）** |
| `dumpsys voiceinteraction` | mEnableService=true，component=magicvoice | VoiceInteraction 框架**已启用** |
| `cmd role get-role-holders android.app.role.ASSISTANT` | 空 | holder 未通过该命令暴露，或未用 Role holder 表达 |
| `settings get secure assistant` | 空 | 旧式 assistant 字段为空 |
| `dumpsys role` 中 `android.app.role.ASSISTANT` | 角色存在，`fallback_enabled=true` | **标准 ASSISTANT 角色在系统中存在** |

### 1.1 对方案的影响（提高成功率的判断）

1. **框架层支持 VoiceInteraction** → 标准三件套路线值得做。  
2. **现任是 magicvoice 系统级服务** → 导航条/电源键可能仍走私有路径，**不能把「电源键/你好 YOYO」当验收**。  
3. **ROLE_ASSISTANT 角色存在** → 优先走 `RoleManager` 申请；装包后必须验证授权页是否出现 RikkaHub。  
4. 若授权页永远不出现 RikkaHub → 先修 Manifest/XML，再考虑结果 C 降级（§7）。

### 1.2 开工必跑探测脚本（复制即用）

```powershell
$adb = "C:\Users\yileina\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb shell getprop ro.product.model
& $adb shell getprop ro.build.version.release
& $adb shell getprop ro.build.version.sdk
& $adb shell settings get secure voice_interaction_service
& $adb shell settings get secure assistant
& $adb shell cmd role get-role-holders android.app.role.ASSISTANT
& $adb shell dumpsys voiceinteraction | Select-Object -First 50
& $adb shell dumpsys role | Select-String -Pattern "ASSISTANT" -Context 0,5
& $adb shell pm list packages | Select-String -Pattern "rikkahub|excp.rikkahub|magicvoice"
```

把输出贴进最终报告「环境」一节。

---

## 2. 源码现状（实现必须对齐，禁止臆造）

### 2.1 已有、直接复用

| 能力 | 路径 | 要点 |
|------|------|------|
| 第二用户配置 | `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt` | `privilegedConversationId`、`privilegedIdentityName`（默认「第二用户」）、`unrestricted` |
| 特权判定 | `.../privilege/PrivilegedSession.kt` | `DefaultPrivilegedSessionResolver`：会话 id == privilegedConversationId 且 assistant 匹配 → `isPrivileged` |
| 来源枚举 | `.../data/ai/ToolCallOrigin.kt` | 现有：LocalChat / TrustedWorkflow / Telegram / WebServer / MCP / ExternalIntent |
| 工具门禁 | `.../data/ai/ToolExecutionGate.kt` | 全工具必经；含锁机、紧急停止、按 origin 限制 |
| 特权工具注入 | `StructuredPrivilegedTools.kt` 等 | **多处 `origin == LocalChat` 才注入** |
| 电话硬限制 | `phoneCallHardBlockReason` | **仅 LocalChat + 未锁机** |
| 聊天管线 | `service/ChatService.kt` + `service/chat/*` | DurableCommandQueue / ConversationRuntime |
| 设置挂点 | `ui/pages/setting/*`、`AssistantBasicPage.kt` | 挂诊断页 / 申请按钮 |

### 2.2 必须新建

| 项 | 说明 |
|----|------|
| `VoiceInteractionService` | 无 |
| `VoiceInteractionSessionService` | 无 |
| `VoiceInteractionSession` | 无 |
| `res/xml/voice_interaction_service.xml` | 无 |
| Manifest `BIND_VOICE_INTERACTION` | 无 |
| `RoleManager.ROLE_ASSISTANT` 申请 | 无 |
| `ToolCallOrigin.SystemAssistant` / `SystemAssistantKeyguard` | 无 |
| 系统助手 → 特权会话 Submitter | 无 |
| 系统助理诊断页 | 无 |

### 2.3 致命坑（不做必失败）

```text
PrivilegedSession.kt:
  unrestrictedOverride = isPrivileged && origin == LocalChat

多处特权注入:
  origin == ToolCallOrigin.LocalChat

phoneCallHardBlockReason:
  origin != LocalChat → 禁电话
```

若系统助手只用新 origin、不改上述逻辑 → **消息进了第二用户，但特权工具几乎全无**。

**正确做法：** 抽扩展函数，例如：

```kotlin
fun ToolCallOrigin.isLocalTrustedUnlocked(): Boolean =
    this == ToolCallOrigin.LocalChat || this == ToolCallOrigin.SystemAssistant
```

- `SystemAssistant`（解锁）：与 LocalChat 同等（特权注入 + unrestrictedOverride）  
- `SystemAssistantKeyguard`：**不**走 unrestricted；第一阶段默认 **禁工具 / 仅提示解锁或纯文本**  
- 电话：第一阶段可继续仅 LocalChat，降低纠纷  

**禁止**为省事把系统助手全部标成 `LocalChat`（锁屏审计会失真）。

---

## 3. 架构与数据流

```text
[系统] ROLE_ASSISTANT / VoiceInteraction
        ↓
RikkaVoiceInteractionService   (轻量，onReady 不拉模型)
        ↓ showSession(args)
RikkaVoiceSessionService
        ↓
RikkaVoiceInteractionSession   (Compose 最小面板：输入框 + 发送 + 回复区)
        ↓
SystemAssistantSubmitter
  - 读当前/指定 Assistant.privilegedConversationId
  - null → UI 引导去助理设置绑定第二用户，禁止瞎建特权会话
  - origin = Keyguard ? SystemAssistantKeyguard : SystemAssistant
        ↓
现有 ChatService / ConversationRuntime 生成管线
        ↓
ToolExecutionGate + PrivilegedSessionResolver（已按 §2.3 改造）
```

---

## 4. 新增文件清单（建议包名 `me.rerere.rikkahub.assistant`）

| 文件 | 职责 |
|------|------|
| `assistant/RikkaVoiceInteractionService.kt` | VoiceInteractionService；keyguard 入口 showSession |
| `assistant/RikkaVoiceSessionService.kt` | 创建 Session |
| `assistant/RikkaVoiceInteractionSession.kt` | 浮层 UI + 提交 |
| `assistant/AssistantRoleController.kt` | isSupported / isCurrentAssistant / createRequestIntent |
| `assistant/SystemAssistantSubmitter.kt` | 路由到 privilegedConversationId |
| `res/xml/voice_interaction_service.xml` | sessionService + supportsAssist + supportsLaunchVoiceAssistFromKeyguard |
| `AndroidManifest.xml` 增补 | 两 Service + permission + meta-data |
| `ui/.../SystemAssistantProbePage.kt`（路径可调） | 诊断 + 申请 + 测试唤起 + 恢复说明 |
| 扩展 `ToolCallOrigin.kt` + Gate + Resolver + 所有 LocalChat-only 注入点 | §2.3 |
| 单测 | PrivilegedSession / Gate / 注入 / Submitter |

### 4.1 Manifest 结构（实现时对齐）

```xml
<service
    android:name=".assistant.RikkaVoiceInteractionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/voice_interaction_service" />
</service>

<service
    android:name=".assistant.RikkaVoiceSessionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true" />
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<voice-interaction-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="me.rerere.rikkahub.assistant.RikkaVoiceSessionService"
    android:recognitionService=""
    android:supportsAssist="true"
    android:supportsLaunchVoiceAssistFromKeyguard="true"
    android:supportsLocalInteraction="true" />
```

（`sessionService` 全限定名以实际包名/类名为准。）

### 4.2 Role 申请

```kotlin
// 概念代码
roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
// ActivityResultLauncher 启动，用户确认
```

应用**不能**静默抢角色。

---

## 5. Origin 策略矩阵（第一阶段）

| Origin | 进特权会话 | 特权工具注入 | unrestrictedOverride | 高危工具 | 电话 |
|--------|------------|--------------|----------------------|----------|------|
| LocalChat | 是 | 是 | 是 | 现有门禁 | 仅解锁 LocalChat |
| SystemAssistant（解锁） | 是 | **同 LocalChat** | **同 LocalChat** | 现有门禁 | 第一阶段可暂禁或同 LocalChat |
| SystemAssistantKeyguard | 是（会话可写） | **否** | **否** | **否** | **否** |
| Telegram / Web / MCP / … | 现状 | 现状 | 现状 | 现状 | 否 |

---

## 6. 实施顺序（严格按依赖）

### P0 — 重跑 ADB 基线

- 跑 §1.2 脚本  
- 记录 magicvoice 是否仍为当前 interactor  
- 确认 applicationId  

### P1 — Manifest + 空三件套可安装

- 最小 Service 实现（可空 Session UI）  
- `installDebug`  
- 用 ADB 确认包已装：`pm path <applicationId>`  
- **用户/智能体**打开系统「默认数字助理」：是否出现 RikkaHub  

| 结果 | 行动 |
|------|------|
| 出现 | 继续 P2 |
| 不出现 | 修 exported/permission/xml/sessionService 名，最多两轮；仍不行记结果 B |
| `isRoleAvailable=false`（应用内 Log） | 结果 C → §7 降级，勿死磕导航条 |

### P2 — Role 申请 UI + 诊断页骨架

- AssistantRoleController  
- 诊断页：available / held / 当前 voice_interaction_service 文案 / 是否配置 privilegedConversationId  
- 按钮：申请默认助理、打开系统默认应用设置、复制探测结果  

### P3 — Origin + Resolver + Gate + 注入（可与 P1 并行写，但合并前单测绿）

- 扩展 ToolCallOrigin  
- `isLocalTrustedUnlocked()`  
- 改 PrivilegedSessionResolver  
- 改所有 LocalChat-only 注入  
- 更新 ToolExecutionGate 注释与分支  
- 单测必绿  

### P4 — Submitter 接 Chat 管线

- 查找现有「UI 发送用户消息」入口，**增加 origin 参数**或专用 API  
- 禁止伪造 LocalChat  
- privilegedConversationId 空 → 明确错误  

### P5 — Session 最小 UI

- 输入 + 发送 + 显示回复（可先订阅 conversation flow）  
- args `from_keyguard` 分支  
- **不接麦克风**  

### P6 — 装包 + ADB 验收

```text
1. installDebug
2. 申请默认助理（需用户点系统页 — 智能体应用内 launch，用户确认）
3. adb: settings get secure voice_interaction_service
   期望：变为 RikkaHub 的 VoiceInteractionService（若系统允许切换）
4. 诊断页「测试唤起」或系统助手手势
5. 发「ping」→ 在第二用户会话中可见
6. 锁屏策略抽测（可模拟 Keyguard 参数）
7. 指导改回 MagicVoice / YOYO，记录回退步骤
```

### P7 — 报告

见 §10。

---

## 7. 结果 C / 导航条不接管时的降级（仍算第一阶段可交付）

若标准 Role 不可用或永远进不了助理列表：

**仍交付：**

- 诊断页诚实显示「系统未开放 / 未识别为可申请助理」  
- 快捷入口打开第二用户会话（Activity deep link / 设置按钮）  
- 可选：Tile / 桌面快捷方式（若时间允许）  

**不交付假象：** 不要假装已是系统默认助理。

导航条长按仍进 YOYO、但 Role 已 held 且测试唤起成功 → **第一阶段算通过**，报告注明「手势未转交」。

---

## 8. 测试清单

### 8.1 单元测试（必做）

- Resolver + SystemAssistant / SystemAssistantKeyguard  
- Gate 对 Keyguard 拒绝高危  
- 注入条件覆盖 SystemAssistant  
- Submitter：null id 失败；有 id 则 conversationId 正确  

### 8.2 实机（必做，用 ADB 辅助）

- 安装成功  
- 申请 held（用户确认后）  
- Session 可显示  
- 文字进第二用户  
- 可说明如何恢复 MagicVoice  

### 8.3 明确不做

- 电源键、你好 YOYO、气息、STT/TTS、截图 Assist  

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| MagicOS 手势绑死 magicvoice | 验收不依赖导航条；依赖 Role + 应用内测试唤起 |
| Manifest 不合格 | P1 单独验证授权列表 |
| origin 漏改 | `isLocalTrustedUnlocked` + 全仓 grep LocalChat 注入 |
| Session 崩溃 | 捕获异常 + 诊断页恢复说明 |
| 锁屏滥用 | Keyguard origin 禁工具 |
| 未绑第二用户 | 禁止静默创建特权会话 |
| 切换助理后用户不会改回 | 诊断页写清设置路径 + ADB 记录原 service 组件名 |

原组件名（回退参考，以重跑探测为准）：

```text
com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService
```

---

## 10. 完成定义（DoD）

- [ ] VoiceInteraction 三件套 + XML + Manifest 在仓库中  
- [ ] 在 available 的机型上可申请 ROLE_ASSISTANT（或诚实报告 C 并交付降级）  
- [ ] ToolCallOrigin 扩展且 Gate/Resolver/注入/测试已更新  
- [ ] 文字从 Session 进入 privilegedConversationId  
- [ ] 解锁 SystemAssistant 特权能力对齐 LocalChat  
- [ ] 锁屏不扩大攻击面  
- [ ] 诊断页：申请 / 状态 / 恢复说明  
- [ ] ADB 探测输出与安装验证写入报告  
- [ ] 文案明确：**不是完整 YOYO 替代**  
- [ ] **无** STT/TTS / 截图 Assist / 电源键唤醒实现  

---

## 11. 给执行智能体的开场指令（可直接粘贴）

```text
你是实现智能体。请严格按「桌面/opencode 的小洞/system-assistant-phase1-checklist.md」
只做第一阶段系统默认助理（VoiceInteraction + ROLE_ASSISTANT + 第二用户路由）。

1. 用本机 ADB（路径见文档）重跑 §1.2 探测，确认 AAK-AN00 连接。
2. 阅读 Assistant.kt / PrivilegedSession.kt / ToolCallOrigin.kt / ToolExecutionGate.kt
   及所有 origin == LocalChat 的注入点。
3. 按 P0→P6 实现；每步失败最多两次；用 installDebug + ADB 自测。
4. 不要实现 STT/TTS/电源键/YOYO 唤醒。
5. 完成后输出：改动文件、测试结果、ADB 关键输出、未完成项、如何恢复 MagicVoice。
```

---

## 12. 相对旧版清单的优化点（给人类）

1. 写入 **实机 ADB 基线**（magicvoice 现任 interactor）。  
2. 明确 **执行智能体用 ADB 自测**，不设命令白名单（仅保留破坏性底线）。  
3. 强化 **LocalChat 注入致命坑** 与 `isLocalTrustedUnlocked`。  
4. 验收改为 **Role + 测试唤起优先**，导航条为加分项。  
5. 增加 **可粘贴开场指令**、探测脚本、回退组件名。  
6. 结果 C 降级路径写清，避免死磕。  
7. 去掉后阶段内容，只保留第一阶段可交付闭环。  

---

*源码依据：`Assistant.kt`、`PrivilegedSession.kt`、`ToolCallOrigin.kt`、`ToolExecutionGate.kt`、`StructuredPrivilegedTools.kt`、`ChatService`/`service/chat/*`。*  
*实机依据：ADB 连接 AAK-AN00 / Android 16 / MagicVoice 为当前 VoiceInteraction。*
