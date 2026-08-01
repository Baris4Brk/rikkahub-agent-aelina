package me.rerere.rikkahub.data.ai.execution

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ToolInvocationSurface
import me.rerere.rikkahub.plugin.isPluginModelToolName

/**
 * Resolves effects from the actual call arguments. It intentionally fails closed: a tool absent
 * from this registry is serial, unknown, and ineligible for read-only batching.
 */
class DefaultToolExecutionPolicyResolver : ToolExecutionPolicyResolver {
    override fun resolve(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy = when {
        toolName == "web_fetch" -> webFetch(args)
        toolName == "get_location" -> location(args)
        toolName == "reverse_geocode" -> reverseGeocode(args)
        toolName in FILE_READ_TOOLS -> fileRead(toolName, args)
        toolName in FILE_WRITE_TOOLS -> fileWrite(toolName, args)
        toolName in LOCAL_READ_TOOLS -> readOnly(ToolEffect.LOCAL_READ)
        toolName in SENSITIVE_READ_TOOLS -> serial(setOf(ToolEffect.SENSITIVE_READ))
        toolName in COMMUNICATION_TOOLS -> serial(
            setOf(ToolEffect.COMMUNICATION, ToolEffect.NETWORK_WRITE),
        )
        toolName in SHELL_TOOLS -> shell(toolName, args, context)
        toolName.startsWith("browser_") -> browser(toolName, args, context)
        toolName in DISPLAY_READ_TOOLS -> display(toolName, args, context, write = false)
        toolName in DISPLAY_WRITE_TOOLS -> display(toolName, args, context, write = true)
        isPluginModelToolName(toolName) -> plugin(toolName)
        toolName.startsWith("mcp__") -> serial(
            effects = setOf(ToolEffect.SENSITIVE_READ, ToolEffect.NETWORK_WRITE),
        )
        toolName == "memory_tool" -> memoryTool(args, context)
        toolName in InternalToolSecurityCatalog.READ_ONLY -> readOnly(ToolEffect.LOCAL_READ)
        toolName in InternalToolSecurityCatalog.MUTATING -> serial(
            effects = setOf(ToolEffect.PERSISTENT_STATE),
        )
        CapabilityCatalog.byToolName(toolName) != null -> catalogPolicy(toolName, args, context)
        else -> ToolExecutionPolicy.UNKNOWN
    }

    private fun plugin(toolName: String): ToolExecutionPolicy = serial(
        effects = setOf(ToolEffect.UNKNOWN),
        keys = setOf(resourceKey("plugin", toolName)),
        concurrency = ToolConcurrency.GLOBAL_SERIAL,
        cancellationCapability = ToolCancellationCapability.REAL,
    )

    private fun webFetch(args: JsonObject): ToolExecutionPolicy {
        val method = args.string("method")?.uppercase() ?: "GET"
        val key = resourceKey("network", networkAuthority(args.string("url")))
        return if (method == "GET" || method == "HEAD") {
            readOnly(ToolEffect.NETWORK_READ, setOf(key))
        } else {
            serial(setOf(ToolEffect.NETWORK_WRITE), setOf(key))
        }
    }

    private fun location(args: JsonObject): ToolExecutionPolicy {
        val includeAddress = args.boolean("include_address") == true
        val networkPermitted = includeAddress && (
            args.boolean("allow_platform_geocoder") != false ||
                args.boolean("allow_external_address") == true
            )
        return serial(
            effects = buildSet {
                add(ToolEffect.SENSITIVE_READ)
                if (networkPermitted) add(ToolEffect.NETWORK_WRITE)
            },
            keys = buildSet {
                add(resourceKey("location", "device"))
                if (includeAddress) {
                    add(resourceKey("reverse-geocoder", args.string("address_provider") ?: "auto"))
                }
            },
            concurrency = ToolConcurrency.RESOURCE_SERIAL,
        )
    }

    private fun reverseGeocode(args: JsonObject): ToolExecutionPolicy {
        val networkPermitted = args.boolean("allow_platform_geocoder") != false ||
            args.boolean("allow_external") == true
        return serial(
            effects = buildSet {
                add(ToolEffect.SENSITIVE_READ)
                if (networkPermitted) add(ToolEffect.NETWORK_WRITE)
            },
            keys = setOf(resourceKey("reverse-geocoder", args.string("provider") ?: "auto")),
            concurrency = ToolConcurrency.RESOURCE_SERIAL,
        )
    }

    private fun fileRead(toolName: String, args: JsonObject): ToolExecutionPolicy = readOnly(
        effect = ToolEffect.FILE_READ,
        keys = fileResourceKeys(toolName, args),
    )

    private fun fileWrite(toolName: String, args: JsonObject): ToolExecutionPolicy = serial(
        effects = setOf(ToolEffect.FILE_WRITE, ToolEffect.PERSISTENT_STATE),
        keys = fileResourceKeys(toolName, args),
        concurrency = ToolConcurrency.RESOURCE_SERIAL,
    )

    private fun browser(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy {
        val pageIdentity = args.string("page_id")
            ?: "${context.assistantId}:${context.conversationId}:controlled"
        val key = resourceKey("browser", pageIdentity)
        return if (toolName in BROWSER_READ_TOOLS) {
            readOnly(ToolEffect.BROWSER_READ, setOf(key))
        } else {
            serial(
                effects = setOf(ToolEffect.BROWSER_WRITE),
                keys = setOf(key),
                concurrency = ToolConcurrency.RESOURCE_SERIAL,
            )
        }
    }

    private fun display(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
        write: Boolean,
    ): ToolExecutionPolicy {
        val session = args.string("display_session_id")
            ?: "${context.assistantId}:${context.conversationId}:main"
        val key = resourceKey("display", session)
        return if (write) {
            serial(
                effects = setOf(ToolEffect.DISPLAY_WRITE),
                keys = setOf(key),
                concurrency = ToolConcurrency.RESOURCE_SERIAL,
            )
        } else {
            readOnly(ToolEffect.DISPLAY_READ, setOf(key))
        }
    }

    private fun shell(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy {
        val owner = args.string("session_id")
            ?: args.string("profile_id")
            ?: "${context.assistantId}:${context.conversationId}:shell"
        return serial(
            effects = setOf(ToolEffect.SHELL_EXECUTION, ToolEffect.PERSISTENT_STATE),
            keys = setOf(resourceKey("execution", owner)),
            cancellationCapability = if (
                toolName == "termux_run_command" &&
                args["interactive"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                ToolCancellationCapability.LOCAL_WAIT_ONLY
            } else {
                ToolCancellationCapability.REAL
            },
        )
    }

    private fun memoryTool(
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy {
        val action = args.string("action")?.lowercase()
        val key = resourceKey("memory", context.assistantId)
        return if (action in MEMORY_READ_ACTIONS) {
            readOnly(ToolEffect.LOCAL_READ, setOf(key))
        } else {
            serial(
                effects = setOf(ToolEffect.PERSISTENT_STATE),
                keys = setOf(key),
                concurrency = ToolConcurrency.RESOURCE_SERIAL,
            )
        }
    }

    private fun catalogPolicy(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy = when (CapabilityCatalog.toolInvocationSurface(toolName)) {
        ToolInvocationSurface.UnboundedExecution -> shell(toolName, args, context)
        ToolInvocationSurface.FileMutation -> fileWrite(toolName, args)
        ToolInvocationSurface.DataEgress -> serial(
            effects = setOf(ToolEffect.SENSITIVE_READ, ToolEffect.NETWORK_WRITE),
        )
        ToolInvocationSurface.DeferredExecution,
        ToolInvocationSurface.SystemMutation,
        ToolInvocationSurface.DataMutation -> serial(
            effects = setOf(ToolEffect.PERSISTENT_STATE),
        )
        ToolInvocationSurface.Activity,
        ToolInvocationSurface.SystemConsent -> serial(
            effects = setOf(ToolEffect.PERSISTENT_STATE),
        )
        ToolInvocationSurface.Background -> readOnly(ToolEffect.LOCAL_READ)
        ToolInvocationSurface.Phase1Unavailable,
        ToolInvocationSurface.Unclassified,
        null -> serial(setOf(ToolEffect.SENSITIVE_READ))
    }

    private fun readOnly(
        effect: ToolEffect,
        keys: Set<ToolResourceKey> = emptySet(),
    ) = ToolExecutionPolicy(
        effects = setOf(effect),
        concurrency = ToolConcurrency.PARALLEL_SAFE,
        resourceKeys = keys,
        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
    )

    private fun serial(
        effects: Set<ToolEffect>,
        keys: Set<ToolResourceKey> = emptySet(),
        concurrency: ToolConcurrency = ToolConcurrency.GLOBAL_SERIAL,
        cancellationCapability: ToolCancellationCapability =
            ToolCancellationCapability.LOCAL_WAIT_ONLY,
    ) = ToolExecutionPolicy(
        effects = effects,
        concurrency = concurrency,
        resourceKeys = keys,
        cancellationCapability = cancellationCapability,
    )

    private fun fileResourceKeys(toolName: String, args: JsonObject): Set<ToolResourceKey> {
        val values = FILE_ARGUMENT_NAMES.mapNotNull { name -> args.string(name) }
        return if (values.isEmpty()) {
            setOf(resourceKey("file", "$toolName:unspecified"))
        } else {
            values.mapTo(linkedSetOf()) { resourceKey("file", normalizePath(it)) }
        }
    }

    private fun resourceKey(namespace: String, value: String): ToolResourceKey {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return ToolResourceKey(namespace = namespace, opaqueId = digest)
    }

    private fun networkAuthority(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "unspecified"
        return runCatching {
            val uri = URI(rawUrl)
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}:${uri.port}"
        }.getOrDefault("invalid:${opaqueInput(rawUrl)}")
    }

    private fun opaqueInput(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun normalizePath(path: String): String = path.trim().replace('\\', '/')

    private fun JsonObject.string(name: String): String? =
        (get(name) as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun JsonObject.boolean(name: String): Boolean? =
        (get(name) as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    companion object {
        private val FILE_ARGUMENT_NAMES = listOf(
            "path", "file_path", "source", "destination", "source_path", "destination_path",
            "output_path", "directory", "archive_path",
        )

        private val FILE_READ_TOOLS = setOf(
            "read_file", "list_files", "file_info", "find_files", "show_image",
            "list_zip_contents", "workspace_read_file", "execution_logs",
        )
        private val FILE_WRITE_TOOLS = setOf(
            "write_text_file", "write_binary_file", "delete_file", "move_file", "copy_file",
            "create_directory", "batch_copy", "batch_move", "batch_delete", "zip_files",
            "unzip_file", "download_file", "workspace_write_file", "workspace_edit_file",
        )
        private val LOCAL_READ_TOOLS = setOf(
            "get_time_info", "get_battery_status", "get_audio_info", "get_telephony_info",
            "get_wifi_info", "get_storage_info", "get_brightness", "get_volume",
            "get_media_status", "list_sensors", "get_step_count", "list_jobs",
            "execution_list", "execution_status",
        )
        private val SENSITIVE_READ_TOOLS = setOf(
            "list_contacts", "search_contacts", "list_call_log",
            "list_sms_inbox", "search_sms", "list_recent_notifications",
            "list_active_notifications", "clipboard_tool",
        )
        private val COMMUNICATION_TOOLS = setOf(
            "send_sms", "telegram_send_message", "telegram_send_photo", "telegram_send_document",
            "post_notification", "send_email_intent", "notification_reply", "call_phone",
        )
        private val SHELL_TOOLS = setOf(
            "termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_kill", "ssh_exec", "ssh_exec_saved", "workspace_shell",
            "workspace_process_start", "workspace_process_stop", "workspace_process_restart",
            "external_bridge_run_command", "privileged_run_command", "execution_stop",
        )
        private val BROWSER_READ_TOOLS = setOf(
            "browser_read_page", "browser_get_page_info", "browser_screenshot",
            "browser_snapshot", "browser_page_list", "browser_dialog_state",
        )
        private val DISPLAY_READ_TOOLS = setOf(
            "read_window_tree", "find_node", "take_screenshot", "ui_wait_for_window",
            "ui_wait_for_node",
            "display_session_list", "display_session_status",
        )
        private val DISPLAY_WRITE_TOOLS = setOf(
            "tap", "long_press", "swipe", "scroll", "set_text", "click_node", "launch_app",
            "ui_click_node_verified", "ui_set_text_verified", "ui_scroll_until",
            "display_session_create", "display_session_close",
        )
        private val MEMORY_READ_ACTIONS = setOf("query", "get", "list", "history")
    }
}
