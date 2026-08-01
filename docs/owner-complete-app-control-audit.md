# P2.3 第二用户全应用 Owner 控制面审计与能力清单

基线：`feature/owner-pet-library-control@ffa466b1`，Room v42。本文仅记录脱敏的能力名称、边界与测试证据，不包含密钥、URL、路径、聊天正文或真实工具输出。

## 受保护兼容边界

- 第二用户继续使用 DIRECT 工具面；24 个 Owner 工具族从首轮直接可见，不经过目录搜索、分类开启或重复审批。
- 当前 authority、epoch、固定 Assistant/Conversation、HARDLINE、Emergency Stop、Android 系统授权与 30 分钟强生物认证明文会话保持不变。
- 可信本机第二用户的写入、删除、安装、外部连接、服务启停和配置修改仍由现有 Owner 自动执行策略处理；风险等级只用于审计、补偿与恢复。
- Provider URL 可以读取；Vault 明文只可在既有生物认证会话内通过一次性内存载荷进入绑定模型的下一次请求，不能进入 Room、日志、Doctor、备份或普通工具结果。
- Workspace、Termux、SSH、Shizuku、MCP、Skill、Workflow、插件与桌宠对话权限均未缩减；桌宠直接互动仍为零工具，Handoff 不继承 Owner。
- Room 保持 v42；未修改第二用户人物设定或现有会话数据结构。

## 统一注册表与执行语义

`OwnerActionRegistry` 是模型侧 Owner 能力的唯一清单，统一生成工具 Schema、风险元数据和紧凑参数指南。注册表覆盖 24 个工具族、158 个 action；每个工具 Schema 不超过 12 KiB，全部 Owner Schema 合计不超过 64 KiB。

每次 Owner 调用可携带 1–20 个有序 action，因此 Provider 初始化、Assistant/Workspace 切换、TTS 初始化、插件安装绑定测试和桌宠导入选择启用可以在一次模型工具调用中完成，无需再增加功能重复的巨型复合 Schema。

`OwnerOperationExecutor` 保持 requestId 幂等和 v42 操作账本，并在每个 action 前重新核验 authority、epoch、会话与来源。同一资源写入串行；不同资源及只读操作可以并行；验证阶段回读 Settings、Room、运行时或外部事实。重启恢复不会盲目重放已经发生的外部副作用。

`OwnerSelfPreservationGuard` 在 UI、Owner Handler、兼容入口、导入和恢复语义上拒绝删除第二用户 Assistant、删除/转移固定会话、改写 authority/epoch，以及由模型自行解除 Emergency Stop。Provider、模型、TTS、Workspace 等普通资源没有永久锁；删除正在使用的 Provider、模型或 TTS 时可在同一请求中验证并切换替代项，失败会恢复旧设置和 Vault 绑定。

## 完整工具族与 action

| Owner 工具族 | 数量 | action |
|---|---:|---|
| `owner_assistant_manage` | 10 | `assistant_create`, `assistant_clone`, `assistant_update`, `assistant_delete`, `assistant_set_default`, `assistant_toggle_tool`, `assistant_update_skills`, `assistant_update_mcp_servers`, `assistant_switch_model`, `assistant_switch_tts` |
| `owner_conversation_manage` | 9 | `conversation_create`, `conversation_branch`, `conversation_archive`, `conversation_restore`, `conversation_update`, `conversation_search`, `conversation_export`, `conversation_open`, `conversation_delete` |
| `owner_provider_manage` | 11 | `provider_list`, `provider_create`, `provider_update`, `provider_delete`, `provider_refresh_models`, `provider_test`, `provider_set_default`, `provider_model_list`, `provider_model_upsert`, `provider_model_delete`, `provider_route_set` |
| `owner_secret_manage` | 12 | `secret_vault_list`, `secret_vault_create_slot`, `secret_vault_set_binding`, `secret_vault_test_binding`, `secret_session_status`, `secret_provider_credentials_reveal`, `secret_plaintext_reveal`, `secret_replace`, `secret_trim`, `secret_remove_prefix`, `secret_remove_quotes`, `secret_remove_newlines` |
| `owner_tts_manage` | 12 | `tts_list`, `tts_create_generic_http`, `tts_update`, `tts_delete`, `tts_test`, `tts_play`, `tts_library_list`, `tts_library_delete`, `tts_stop`, `tts_set_default`, `tts_get_playback_speed`, `tts_set_playback_speed` |
| `owner_service_manage` | 8 | `service_list`, `service_register`, `service_start`, `service_stop`, `service_restart`, `service_status`, `service_delete`, `emotion_tts_setup` |
| `owner_mcp_manage` | 8 | `mcp_list`, `mcp_discover`, `mcp_install`, `mcp_update`, `mcp_delete`, `mcp_bind`, `mcp_unbind`, `mcp_test` |
| `owner_skill_manage` | 7 | `skill_list`, `skill_install`, `skill_update`, `skill_uninstall`, `skill_bind`, `skill_unbind`, `skill_test` |
| `owner_workflow_manage` | 17 | `workflow_list`, `workflow_create`, `workflow_update`, `workflow_delete`, `workflow_set_enabled`, `workflow_run`, `schedule_list`, `schedule_create`, `schedule_update`, `schedule_set_enabled`, `schedule_run_now`, `schedule_delete`, `alarm_list`, `alarm_create`, `alarm_update`, `alarm_set_enabled`, `alarm_delete` |
| `owner_ui` | 5 | `ui_navigate`, `ui_open_conversation`, `ui_open_provider`, `ui_open_tts`, `ui_open_settings` |
| `owner_doctor` | 4 | `rikkahub_state_get`, `doctor_check`, `doctor_repair`, `doctor_recover_operation` |
| `owner_run_manage` | 4 | `run_list`, `run_get`, `run_cancel`, `run_retry` |
| `owner_quick_capture_manage` | 3 | `quick_capture_get`, `quick_capture_update`, `quick_capture_trigger` |
| `owner_plugin_manage` | 7 | `plugin_list`, `plugin_runtime_set`, `plugin_install_managed`, `plugin_approve`, `plugin_set_enabled`, `plugin_bind`, `plugin_uninstall` |
| `owner_memory_manage` | 3 | `memory_list`, `memory_configure_assistant`, `memory_delete` |
| `owner_prompt_library_manage` | 9 | `prompt_library_list`, `prompt_injection_upsert`, `prompt_injection_delete`, `quick_message_create`, `quick_message_update`, `quick_message_delete`, `lorebook_list`, `lorebook_upsert`, `lorebook_delete` |
| `owner_asr_manage` | 5 | `asr_list`, `asr_create`, `asr_update`, `asr_delete`, `asr_set_default` |
| `owner_channel_manage` | 3 | `channel_get`, `web_channel_update`, `telegram_channel_update` |
| `owner_search_manage` | 3 | `search_get`, `search_set_enabled`, `search_select` |
| `owner_backup_storage_manage` | 3 | `backup_storage_get`, `backup_local_export`, `backup_restore_preserving_owner` |
| `owner_app_settings_manage` | 3 | `app_settings_get`, `app_settings_update`, `app_display_update` |
| `owner_runtime_manage` | 3 | `runtime_get`, `runtime_update`, `runtime_permissions_open` |
| `owner_safety_manage` | 3 | `safety_get`, `safety_capabilities_update`, `safety_emergency_stop_activate` |
| `owner_pet_manage` | 6 | `pet_list`, `pet_import_managed`, `pet_select`, `pet_configure`, `pet_delete`, `pet_dialogue_state` |

## 已接入的真实业务状态

- Assistant、Conversation、Provider/Model 路由、Vault、TTS/缓存库/播放速度、ASR、Search、Web/Telegram Channel。
- Run 队列和执行取消/重试、MCP、Skill、Workflow、定时任务、闹钟、本地服务与 EmotionTTS。
- Quick Capture、插件 Runtime/安装/绑定、Memory 修订、Prompt Injection、快捷消息与 Lorebook。
- App 显示与运行参数、Android 权限页面、Emergency Stop、Doctor 与类型化导航。
- 桌宠 ZIP 进入 `managed_files` 的 `pet_packages` 分类，安装目录和 Profile 覆盖保持私有；支持 5%–300% 缩放、全局选择、删除替换和短对话状态读取。
- 本地备份导出会生成应用私有 managed file；数据库恢复后的兼容检查固定在 Room v42。
- Automation UI 与 Owner 共用 `AutomationControlFacade`；其余已接入领域通过既有 Repository/Settings/运行时服务落到同一权威存储。全面移除所有历史 ViewModel 直写并不是本轮为 Owner 扩权所必需，未为了形式重构而改动稳定 UI。

## 安全恢复边界

`backup_restore_preserving_owner` 已注册并执行身份保护检查，但不会在仍持有 Room/Owner 操作账本事务的前台进程中直接覆盖正在使用的数据库。安全的完整恢复需要独立冷启动恢复器：验证归档、快照当前 authority 与固定会话、停止运行时、替换文件、启动后回读 v42、恢复受保护身份并核验。该协调器尚未实现时，Handler 返回 `NEEDS_USER_ACTION`，不会伪报成功或冒险损坏当前第二用户身份。

这是本轮唯一保守保留的破坏性路径；本地备份创建、普通资源删除与替代项原子切换均已实现。

## 验证证据

- `:speech:testDebugUnitTest`：通过。
- `:app:testDebugUnitTest`：通过，共 2043 项 JVM 测试。
- `:app:compileDebugKotlin`：通过。
- `:app:assembleDebug`：通过。
- `:app:compileDebugAndroidTestKotlin`：通过；只编译，未连接或操作真机。
- `:app:assembleRelease`：Release Kotlin、R8、资源优化和 Vital Lint 均通过；最终打包因本机没有配置 `release.storeFile` 发布签名而停止。
- `:app:lintDebug`：执行完成，但仓库现有基线仍有 796 errors / 560 warnings / 7 hints，主要是 678 项历史 MissingTranslation 与 89 项 Compose LocalContext 资源规则；本轮没有批量改写这些无关 UI/本地化文件。

Honor AAK-AN00 未运行 ADB、instrumentation、UIAutomator、connected 测试或测试 APK。
