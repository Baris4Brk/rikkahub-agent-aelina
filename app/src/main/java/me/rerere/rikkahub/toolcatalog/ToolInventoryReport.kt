package me.rerere.rikkahub.toolcatalog

import java.io.File
import java.time.Instant
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.execution.InternalToolSecurityCatalog
import me.rerere.rikkahub.data.capability.CapabilityCatalog

/**
 * One immutable product of the ChatService/GenerationHandler tool assembly. The definitions and
 * their metadata snapshot are created together, so callers cannot accidentally expose a tool
 * which is absent from the directory or diagnostics surface.
 */
data class ToolSurface(
    val definitions: List<Tool>,
    val snapshot: ToolCatalogSnapshot,
)

/** The single bridge from assembled provider definitions to directory and audit views. */
object ToolSurfaceBuilder {
    fun build(definitions: List<Tool>): ToolSurface {
        val canonical = definitions.distinctBy(Tool::name)
        return ToolSurface(
            definitions = canonical,
            snapshot = ToolCatalogSnapshot.fromDefinitions(canonical),
        )
    }

    fun snapshot(definitions: List<Tool>): ToolCatalogSnapshot = build(definitions).snapshot

    /**
     * Source/configuration baseline used before a particular Provider turn exists. Dynamic
     * tools deliberately remain declarations in the report because their schemas arrive only at
     * runtime. The synthetic definitions never execute and exist solely to reuse the exact same
     * catalogue metadata path as a live surface.
     */
    fun staticCapabilityBaseline(): ToolSurface = build(
        staticToolNames().map { toolName ->
            val descriptor = CapabilityCatalog.byToolName(toolName)
            Tool(
                name = toolName,
                description = descriptor?.let { "RikkaHub capability ${it.id.name}." }
                    ?: "RikkaHub internal host tool.",
                execute = { emptyList<UIMessagePart>() },
            )
        },
    )

    /**
     * All built-in names that can be assembled without a user-installed MCP server or plugin.
     * This is deliberately the union of the two security registries, rather than a hand-written
     * report list. Runtime-only MCP/plugin definitions are declared separately in the exporter.
     */
    fun staticToolNames(): List<String> = buildSet {
        CapabilityCatalog.allCapabilities().forEach { addAll(it.toolNames) }
        addAll(InternalToolSecurityCatalog.ALL)
    }.sorted()
}

/** Privacy-safe Markdown exporter used for the requested developer desktop baseline. */
object ToolInventoryReport {
    const val DEFAULT_FILE_NAME = "RikkaHub_第二用户_权限与工具完整清单.md"

    fun renderCompiledBaseline(generatedAt: Instant = Instant.now()): String {
        val baseline = ToolSurfaceBuilder.staticCapabilityBaseline().snapshot
        val entries = baseline.entries
        val capabilityCount = entries.mapNotNull { it.capabilityId }.distinct().size
        val internalCount = entries.count { it.source.name == "INTERNAL" }
        return buildString {
            appendLine("# RikkaHub 第二用户：权限与工具完整清单")
            appendLine()
            appendLine("生成时间：$generatedAt")
            appendLine("范围：代码与配置基线；未读取手机、ADB、密钥、聊天、路径、命令或工具输出。")
            appendLine()
            appendLine("## 第二用户自动执行边界")
            appendLine()
            appendLine("- 仅全局 ACTIVE 第二用户、设备已解锁、且入口属于：" +
                InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER.joinToString { it.name })
            appendLine("- 即使满足上述条件，Emergency Stop、HARDLINE、系统权限、前台/锁屏条件、能力可用性和审批规则仍生效。")
            appendLine("- Telegram、Web、MCP、外部 Intent、桌宠直接互动与自动桌宠转交不会继承第二用户身份。")
            appendLine()
            appendLine("## 统计")
            appendLine()
            appendLine("- 已分类 capability：$capabilityCount")
            appendLine("- 内置静态工具名总数：${entries.size}（CapabilityCatalog 与受控宿主工具）")
            appendLine("- 动态外部工具来源：MCP 和插件；Provider、Workspace、Termux、SSH、Shizuku 通过内置名按条件注入。")
            appendLine()
            appendLine("## 工具与权限")
            appendLine()
            appendLine("| 分类 | 工具 | 风险 | 审批 | 允许入口 | 前置条件 |")
            appendLine("|---|---|---|---|---|---|")
            entries.forEach { entry ->
                append('|').append(entry.categoryPath)
                append('|').append(entry.toolName)
                append('|').append(entry.risk?.name ?: "UNKNOWN")
                append('|').append(entry.approval.name)
                append('|').append(entry.allowedOrigins.joinToString { it.name })
                append('|').append(entry.requirements.joinToString())
                appendLine("|")
            }
            appendLine()
            appendLine("## Complete static inventory metadata")
            appendLine()
            appendLine("- Built-in static tool names: ${entries.size}")
            appendLine("- Capability groups: $capabilityCount; controlled internal host tools: $internalCount")
            appendLine("- `currently_injectable` means this definition is present in the captured tool surface. Runtime permission, unlock, bridge, approval, HARDLINE and Emergency Stop checks still apply.")
            appendLine()
            appendLine("| Category | Tool | Source | Risk | Approval | currently_injectable | Allowed origins | Requirements |")
            appendLine("|---|---|---|---|---|---|---|---|")
            entries.forEach { entry ->
                append('|').append(entry.categoryPath)
                append('|').append(entry.toolName)
                append('|').append(entry.source.name)
                append('|').append(entry.risk?.name ?: "UNKNOWN")
                append('|').append(entry.approval.name)
                append('|').append(entry.currentlyInjectable)
                append('|').append(entry.allowedOrigins.joinToString { it.name })
                append('|').append(entry.requirements.joinToString())
                appendLine("|")
            }
            appendLine()
            appendLine("## Dynamic definition declarations")
            appendLine()
            appendLine("- MCP: runtime `mcp__*` names; external and untrusted; every call needs approval; never auto-learned.")
            appendLine("- Plugin: runtime `plugin__*` names; external and untrusted; every call needs approval; never auto-learned.")
            appendLine("- Skill: fixed `use_skill` / `skill_get_content` entries; installed skill content changes, but it is not an arbitrary extra tool schema.")
            appendLine("- Workspace, Termux, SSH and Shizuku use built-in names above. Their actual injection still depends on bridge reachability, unlock state, origin policy and capability readiness.")
            appendLine()
            appendLine("## 动态工具来源")
            appendLine()
            appendLine("- MCP：运行时命名空间 `mcp__*`，外部定义、不可信、每次审批；仅目录浏览，不自动学习。")
            appendLine("- 插件：运行时命名空间 `plugin__*`，外部定义、不可信、每次审批；仅目录浏览，不自动学习。")
            appendLine("- Workspace、Shizuku、结构化特权工具：仅在对应桥接、解锁与入口策略满足时装配。")
            appendLine()
            appendLine("## 完整性规则")
            appendLine()
            appendLine("Provider 实际工具面、工具目录和诊断必须来自同一 ToolSurfaceBuilder 快照；新增工具若没有目录记录或动态来源声明，覆盖率测试必须失败。")
        }
    }

    fun writeDesktopBaseline(
        desktop: File = File(System.getProperty("user.home"), "Desktop"),
    ): File {
        val target = File(desktop, DEFAULT_FILE_NAME)
        target.parentFile?.mkdirs()
        target.writeText(renderCompiledBaseline())
        return target
    }
}
