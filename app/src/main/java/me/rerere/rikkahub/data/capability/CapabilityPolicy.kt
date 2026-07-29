package me.rerere.rikkahub.data.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.plugin.isPluginModelToolName

/**
 * Stable, action-level capability identifier.
 *
 * [CapabilityId] remains the catalog/UI grouping. This key models the action being authorised,
 * so one catalog capability can expose independently-grantable operations such as
 * `files.read` and `files.delete`.
 */
@JvmInline
value class CapabilityKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "capability_key_invalid" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9_]{0,63}(?:\\.[a-z][a-z0-9_]{0,63}){0,5}")

        fun of(value: String): CapabilityKey = CapabilityKey(value.trim().lowercase())
    }
}

/** The principal that owns an invocation. Origins are intentionally kept separate. */
enum class SubjectType {
    LOCAL_ASSISTANT,
    LOCAL_SECOND_USER,
    WORKFLOW,
    PLUGIN,
    MCP,
    TELEGRAM,
    WEB,
    EXTERNAL_AUTOMATION,
    SYSTEM,
}

data class CapabilitySubject(
    val id: String,
    val type: SubjectType,
    /** A local second-user profile is valid only for its selected privileged conversation. */
    val privilegedConversationId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "capability_subject_id_blank" }
    }
}

/**
 * Non-secret target scope used for matching grants. It is never a place to store passwords,
 * tokens, raw command text, or unredacted file contents.
 */
sealed interface ResourceScope {
    val kind: String
    val identifier: String

    data object AllLocal : ResourceScope {
        override val kind: String = "local"
        override val identifier: String = "all"
    }

    data class Tool(override val identifier: String) : ResourceScope {
        override val kind: String = "tool"
    }

    data class Conversation(override val identifier: String) : ResourceScope {
        override val kind: String = "conversation"
    }

    data class Workspace(override val identifier: String) : ResourceScope {
        override val kind: String = "workspace"
    }

    data class Connection(override val identifier: String) : ResourceScope {
        override val kind: String = "connection"
    }

    data class Extension(override val identifier: String) : ResourceScope {
        override val kind: String = "extension"
    }

    data class FileRoot(override val identifier: String) : ResourceScope {
        override val kind: String = "file_root"
    }
}

data class CapabilityRequest(
    val subject: CapabilitySubject,
    val origin: ToolCallOrigin,
    val capabilities: Set<CapabilityKey>,
    val resource: ResourceScope,
    val catalogCapability: CapabilityId? = null,
    val conversationId: String? = null,
    val executionId: String? = null,
    val deviceUnlocked: Boolean,
    val selectedPrivilegedConversation: Boolean,
    /** Immutable capability set stamped by a workflow authoring operation. */
    val frozenCapabilities: Set<CapabilityKey> = emptySet(),
) {
    init {
        require(capabilities.isNotEmpty()) { "capability_request_empty" }
    }
}

enum class GrantScope {
    ONCE,
    CONVERSATION,
    WORKSPACE,
    EXTENSION,
    PERSISTENT,
}

/** Persistable shape; storage is introduced separately from the policy contract. */
data class AccessGrant(
    val id: String,
    val subjectId: String,
    val subjectType: SubjectType,
    val capability: CapabilityKey,
    val resourceKind: String,
    val resourceIdentifier: String,
    val allowedOrigins: Set<ToolCallOrigin>,
    val scope: GrantScope,
    val expiresAtMs: Long? = null,
    val revoked: Boolean = false,
)

sealed interface PolicyDecision {
    data class Allowed(val source: String) : PolicyDecision
    data class Denied(val code: String, val message: String) : PolicyDecision
    /** Existing non-second-user policy continues to decide the request. */
    data object Abstain : PolicyDecision
}

fun interface CapabilityPolicyEngine {
    fun evaluate(request: CapabilityRequest): PolicyDecision
}

/**
 * The second user is intentionally powerful only while physically local and unlocked. Remote
 * surfaces must have an explicit [AccessGrant]; they never inherit this profile.
 */
class DefaultCapabilityPolicyEngine(
    private val grants: () -> Collection<AccessGrant> = { emptyList() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CapabilityPolicyEngine {
    override fun evaluate(request: CapabilityRequest): PolicyDecision {
        if (request.subject.type == SubjectType.WORKFLOW) {
            return if (
                request.frozenCapabilities.isNotEmpty() &&
                request.capabilities.all(request.frozenCapabilities::contains)
            ) {
                PolicyDecision.Allowed("workflow_capability_snapshot")
            } else {
                PolicyDecision.Denied(
                    code = "workflow_capability_not_in_snapshot",
                    message = "The workflow was not approved with this capability.",
                )
            }
        }
        if (request.subject.type == SubjectType.LOCAL_SECOND_USER) {
            if (!request.selectedPrivilegedConversation) {
                return PolicyDecision.Denied(
                    code = "second_user_conversation_required",
                    message = "The local second-user profile is limited to its selected privileged conversation.",
                )
            }
            if (
                request.subject.privilegedConversationId != null &&
                request.subject.privilegedConversationId != request.conversationId
            ) {
                return PolicyDecision.Denied(
                    code = "second_user_subject_mismatch",
                    message = "The grant belongs to a different selected privileged conversation.",
                )
            }
            if (
                !request.deviceUnlocked ||
                request.origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
            ) {
                return PolicyDecision.Denied(
                    code = "second_user_local_unlocked_required",
                    message = "The local second-user profile is available only from an unlocked local surface.",
                )
            }
            val requiresGrant = request.capabilities.any { capability ->
                capability.value.startsWith("linux.") ||
                    capability.value.startsWith("phone.shared.")
            }
            if (!requiresGrant) return PolicyDecision.Allowed("local_second_user_profile")
            val matched = request.capabilities.all { capability ->
                grants().any { grant -> grant.matches(request, capability, nowMs()) }
            }
            return if (matched) {
                PolicyDecision.Allowed("second_user_scoped_grant")
            } else {
                PolicyDecision.Denied(
                    code = "second_user_grant_required",
                    message = "Enable the persistent Linux/shared-storage grant for this selected conversation.",
                )
            }
        }

        val matched = request.capabilities.all { capability ->
            grants().any { grant -> grant.matches(request, capability, nowMs()) }
        }
        if (matched) return PolicyDecision.Allowed("scoped_grant")

        return when (request.subject.type) {
            SubjectType.LOCAL_ASSISTANT,
            SubjectType.SYSTEM,
            -> PolicyDecision.Abstain

            else -> PolicyDecision.Denied(
                code = "capability_grant_required",
                message = "${request.subject.type.name.lowercase()} does not have the required scoped capability grant.",
            )
        }
    }

    private fun AccessGrant.matches(
        request: CapabilityRequest,
        requestedCapability: CapabilityKey,
        now: Long,
    ): Boolean = !revoked &&
        subjectId == request.subject.id &&
        subjectType == request.subject.type &&
        capability == requestedCapability &&
        request.origin in allowedOrigins &&
        (expiresAtMs == null || now < expiresAtMs) &&
        resourceKind == request.resource.kind &&
        (resourceIdentifier == request.resource.identifier || resourceIdentifier == "*")

}

data class ResolvedToolCapability(
    val capabilities: Set<CapabilityKey>,
    val resource: ResourceScope,
    val catalogCapability: CapabilityId?,
)

/** Conservative mapping used by policy and audit. Unknown tools remain individually scoped. */
object ToolCapabilityResolver {
    fun resolve(toolName: String, args: JsonObject = JsonObject(emptyMap())): ResolvedToolCapability {
        val normalized = toolName.trim().lowercase()
        val catalog = CapabilityCatalog.byToolName(normalized)
        val capability = when {
            normalized == "conversation_send_message" -> CapabilityKey.of("conversation.write")
            normalized.startsWith("conversation_") -> CapabilityKey.of("conversation.read")
            normalized == "memory_tool" -> memoryCapability(args)
            normalized == "linux_grant_request" || normalized == "linux_grant_list" ||
                normalized == "linux_grant_revoke" || normalized == "linux_profile_list" ->
                CapabilityKey.of("tool.$normalized")
            normalized == "linux_session_inspect" || normalized == "linux_session_list" ->
                CapabilityKey.of("linux.execute")
            normalized == "linux_run" && (
                args.boolean("package_install") || args.string("command")?.looksLikePackageInstall() == true
            ) ->
                CapabilityKey.of("linux.package_install")
            normalized == "linux_run" -> if (args.boolean("background")) {
                CapabilityKey.of("linux.background")
            } else {
                CapabilityKey.of("linux.execute")
            }
            normalized.startsWith("linux_session_") -> CapabilityKey.of("linux.background")
            normalized.startsWith("termux_session_") -> CapabilityKey.of("linux.background")
            normalized.startsWith("termux_") -> CapabilityKey.of("linux.execute")
            normalized.startsWith("workspace_process_") -> CapabilityKey.of("linux.background")
            normalized.startsWith("workspace_") -> CapabilityKey.of("linux.execute")
            normalized.startsWith("ssh_") -> CapabilityKey.of("ssh.execute")
            normalized == "external_bridge_run_command" || normalized.startsWith("privileged_") ->
                CapabilityKey.of("shizuku.execute")
            normalized.startsWith("workflow_") -> CapabilityKey.of("workflow.execute")
            normalized.startsWith("mcp__") -> CapabilityKey.of("mcp.execute")
            isPluginModelToolName(normalized) -> CapabilityKey.of("plugin.execute")
            normalized.startsWith("browser_") -> CapabilityKey.of("browser.execute")
            normalized in FILE_MUTATION_TOOLS -> if (args.hasSharedStoragePath()) {
                CapabilityKey.of("phone.shared.write")
            } else CapabilityKey.of("files.write")
            normalized in FILE_READ_TOOLS -> if (args.hasSharedStoragePath()) {
                CapabilityKey.of("phone.shared.read")
            } else CapabilityKey.of("files.read")
            else -> catalog?.let { CapabilityKey.of("device.${it.id.name.toCapabilitySegment()}") }
                ?: CapabilityKey.of("tool.${normalized.toCapabilitySegment()}")
        }
        return ResolvedToolCapability(
            capabilities = setOf(capability),
            resource = resourceFor(normalized, args),
            catalogCapability = catalog?.id,
        )
    }

    private fun memoryCapability(args: JsonObject): CapabilityKey = when (
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase()
    ) {
        "delete", "remove" -> CapabilityKey.of("memory.delete")
        "query", "get", "list", "history" -> CapabilityKey.of("memory.read")
        else -> CapabilityKey.of("memory.write")
    }

    private fun resourceFor(toolName: String, args: JsonObject): ResourceScope = when {
        toolName == "conversation_send_message" ->
            ResourceScope.Conversation(args.string("conversation_id") ?: "unspecified")
        toolName.startsWith("conversation_") ->
            ResourceScope.Conversation(args.string("conversation_id") ?: "*")
        toolName.startsWith("ssh_") -> ResourceScope.Connection(
            args.string("profile_name") ?: args.string("host") ?: "unspecified",
        )
        toolName.startsWith("mcp__") -> ResourceScope.Extension(
            toolName.substringAfter("mcp__", "mcp").substringBefore("__").ifBlank { "mcp" },
        )
        isPluginModelToolName(toolName) -> ResourceScope.Extension(
            toolName.substringAfter("plugin__", "plugin").substringBefore("__").ifBlank { "plugin" },
        )
        toolName.startsWith("workspace_") -> ResourceScope.Workspace(
            args.string("workspace_id") ?: "default",
        )
        toolName.startsWith("linux_") || toolName.startsWith("termux_") -> ResourceScope.Workspace(
            args.string("workspace_id") ?: args.string("profile") ?: "*",
        )
        toolName in FILE_MUTATION_TOOLS || toolName in FILE_READ_TOOLS -> ResourceScope.FileRoot(
            if (args.hasSharedStoragePath()) SHARED_STORAGE_ROOT else
                args.string("path") ?: args.string("root") ?: args.string("source") ?: "unspecified",
        )
        else -> ResourceScope.Tool(toolName)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun String.toCapabilitySegment(): String =
        replace(Regex("([a-z])([A-Z])"), "\$1_\$2")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }

    private fun String.looksLikePackageInstall(): Boolean =
        Regex("(?:^|[;&|]\\s*)(?:sudo\\s+)?(?:pkg|apt|apt-get|dnf|yum|pacman|apk)\\s+(?:install|add|upgrade|update)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(this)

    private val FILE_MUTATION_TOOLS = setOf(
        "write_text_file", "write_binary_file", "delete_file", "move_file", "copy_file",
        "create_directory", "batch_copy", "batch_move", "batch_delete", "zip_files",
        "unzip_file", "download_file", "workspace_write_file", "workspace_edit_file",
    )
    private val FILE_READ_TOOLS = setOf(
        "read_file", "list_files", "file_info", "find_files", "show_image", "list_zip_contents",
        "workspace_read_file",
    )
    private fun JsonObject.boolean(name: String): Boolean =
        string(name)?.toBooleanStrictOrNull() == true

    private fun JsonObject.hasSharedStoragePath(): Boolean =
        values.any(::containsSharedStoragePath)

    private fun containsSharedStoragePath(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonObject -> element.values.any(::containsSharedStoragePath)
        is kotlinx.serialization.json.JsonArray -> element.any(::containsSharedStoragePath)
        is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull?.let { raw ->
            val path = raw.removePrefix("file://").replace('\\', '/')
            path == SHARED_STORAGE_ROOT || path.startsWith("$SHARED_STORAGE_ROOT/") ||
                path == "/sdcard" || path.startsWith("/sdcard/") ||
                path == "/storage/self/primary" || path.startsWith("/storage/self/primary/")
        } == true
    }

    const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
}
