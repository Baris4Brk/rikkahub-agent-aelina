package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.SkillZipImporter
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Pinned, non-executable Skill package management for the Owner runtime. */
class OwnerSkillOperationHandler(
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
    private val httpClient: OkHttpClient,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.SKILL && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Skill action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Skill action contains an unsupported field.")
        }
        if (action.arguments.values.any { it.toString().contains(Regex("(?i)curl\\s+[^|]{0,2048}\\|\\s*(?:sh|bash)")) }) {
            return invalid("OWNER_INSTALL_PIPE_BLOCKED", "Piped remote shell installers are not allowed.")
        }
        if (action.type in setOf("skill_install", "skill_update")) {
            val pin = action.arguments.string("pin").orEmpty()
            if (!OwnerPinnedSourcePolicy.isPinned(pin)) {
                return invalid("OWNER_SOURCE_NOT_PINNED", "A fixed version, commit, or SHA-256 pin is required.")
            }
            val source = resolvedSource(action.arguments)
                ?: return invalid("SKILL_SOURCE_REQUIRED", "A pinned HTTPS source_url is required.")
            val uri = runCatching { URI(source) }.getOrNull()
                ?: return invalid("SKILL_SOURCE_INVALID", "Skill source URL is invalid.")
            if (uri.scheme?.lowercase() != "https") {
                return invalid("SKILL_SOURCE_HTTPS_REQUIRED", "Skill packages must be downloaded over HTTPS.")
            }
            if (!pin.startsWith("sha256:", true) && !pin.matches(Regex("(?i)^[0-9a-f]{64}$")) &&
                !source.contains(pin, ignoreCase = true)
            ) {
                return invalid("SKILL_SOURCE_NOT_IMMUTABLE", "The fixed version or commit must appear in the download URL, or use a SHA-256 pin.")
            }
        }
        if (action.type in setOf("skill_update", "skill_uninstall", "skill_bind", "skill_unbind", "skill_test")) {
            val name = action.arguments.string("skill_name")?.trim().orEmpty()
            if (name.isBlank() || skillManager.listSkills().none { it.name == name }) {
                return invalid("SKILL_NOT_FOUND", "Skill does not exist.")
            }
        }
        if (action.type in setOf("skill_bind", "skill_unbind")) {
            val assistantId = action.arguments.uuid("assistant_id")
                ?: return invalid("ASSISTANT_ID_REQUIRED", "assistant_id is required.")
            if (settingsStore.settingsFlow.value.assistants.none { it.id == assistantId }) {
                return invalid("ASSISTANT_NOT_FOUND", "Assistant does not exist.")
            }
        }
        return OwnerActionValidation(true, "SKILL_ACTION_VALID", "Skill action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "skill_list" -> list(index)
            "skill_install", "skill_update" -> install(index, action)
            "skill_uninstall" -> uninstall(index, action)
            "skill_bind" -> bind(index, action, true)
            "skill_unbind" -> bind(index, action, false)
            "skill_test" -> test(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported Skill action.")
        }
    }.getOrElse {
        failure(index, action.type, "SKILL_OPERATION_FAILED", "Skill operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val name = action.arguments.string("skill_name")
            ?: applied.result.data?.get("skill_name")?.jsonPrimitive?.contentOrNull
        return when (action.type) {
            "skill_install", "skill_update", "skill_bind", "skill_unbind" -> if (!name.isNullOrBlank() && skillManager.listSkills().any { it.name == name }) {
                OwnerActionValidation(true, "SKILL_ACTION_VERIFIED", "Skill state verified.")
            } else invalid("SKILL_VERIFY_FAILED", "Skill state could not be confirmed.")
            "skill_uninstall" -> if (!name.isNullOrBlank() && skillManager.listSkills().none { it.name == name }) {
                OwnerActionValidation(true, "SKILL_DELETE_VERIFIED", "Skill removal verified.")
            } else invalid("SKILL_VERIFY_FAILED", "Skill removal could not be confirmed.")
            else -> OwnerActionValidation(true, "SKILL_ACTION_VERIFIED", "Skill action completed.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? SkillReceipt
            ?: return OwnerCompensationResult(true, "SKILL_NO_COMPENSATION_REQUIRED")
        return runCatching {
            receipt.previousFiles?.let { skillManager.saveSkillFileBytesAtomically(receipt.skillName, it) }
                ?: skillManager.deleteSkill(receipt.skillName)
            settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
                receipt.bindings[assistant.id]?.let { assistant.copy(enabledSkills = it) } ?: assistant
            }) }
            OwnerCompensationResult(true, "SKILL_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "SKILL_COMPENSATION_FAILED") }
    }

    private fun list(index: Int): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, "skill_list", "SKILL_LIST", "Skill metadata returned.", buildJsonObject {
            put("skills", buildJsonArray {
                skillManager.listSkills().forEach { skill ->
                    add(buildJsonObject {
                        put("skill_name", skill.name)
                        put("description", skill.description.take(300))
                        put("auto_load", skill.autoLoad)
                        put("bound_assistant_count", settings.assistants.count { skill.name in it.enabledSkills })
                    })
                }
            })
        })
    }

    private suspend fun install(index: Int, action: OwnerAction): OwnerAppliedAction {
        val requestedName = action.arguments.string("skill_name")?.trim()?.takeIf { it.isNotBlank() }
        val existingName = requestedName?.takeIf { name -> skillManager.listSkills().any { it.name == name } }
        if (action.type == "skill_update" && existingName == null) {
            return failure(index, action.type, "SKILL_NOT_FOUND", "Skill to update does not exist.")
        }
        val source = requireNotNull(resolvedSource(action.arguments))
        val bytes = download(source)
            ?: return failure(index, action.type, "SKILL_DOWNLOAD_FAILED", "Pinned Skill package could not be downloaded.")
        val pin = action.arguments.string("pin")!!.trim()
        val expectedSha = normalizedShaPin(pin)
        if (expectedSha != null && !constantTimeEquals(sha256(bytes), expectedSha)) {
            bytes.fill(0)
            return failure(index, action.type, "SKILL_HASH_MISMATCH", "Downloaded Skill package does not match its SHA-256 pin.")
        }
        val beforeName = existingName ?: requestedName
        val receipt = beforeName?.let { snapshot(it) }
        var receiptToClose = receipt
        var handedOffReceipt = false
        try {
            val installed = if (isZip(bytes)) installZip(bytes, requestedName) else installText(bytes, source, requestedName)
            if (installed == null) {
                return failure(index, action.type, "SKILL_INSTALL_FAILED", "Skill package failed validation or atomic installation.")
            }
            val finalReceipt = receipt ?: snapshotAbsent(installed)
            receiptToClose = finalReceipt
            val applied = success(index, action.type, if (action.type == "skill_update") "SKILL_UPDATED" else "SKILL_INSTALLED", "Pinned Skill installed atomically.", buildJsonObject {
                put("skill_name", installed)
                put("pin_verified", expectedSha != null)
            }, finalReceipt)
            handedOffReceipt = true
            return applied
        } finally {
            bytes.fill(0)
            if (!handedOffReceipt) receiptToClose?.close()
        }
    }

    private suspend fun uninstall(index: Int, action: OwnerAction): OwnerAppliedAction {
        val name = action.arguments.string("skill_name")!!.trim()
        val receipt = snapshot(name)
        if (!skillManager.deleteSkill(name)) return failure(index, action.type, "SKILL_DELETE_FAILED", "Skill could not be removed.")
        return success(index, action.type, "SKILL_UNINSTALLED", "Skill and assistant bindings removed.", buildJsonObject {
            put("skill_name", name)
        }, receipt)
    }

    private suspend fun bind(index: Int, action: OwnerAction, enabled: Boolean): OwnerAppliedAction {
        val name = action.arguments.string("skill_name")!!.trim()
        val assistantId = requireNotNull(action.arguments.uuid("assistant_id"))
        val receipt = snapshot(name)
        settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
            if (assistant.id != assistantId) assistant else assistant.copy(
                enabledSkills = if (enabled) assistant.enabledSkills + name else assistant.enabledSkills - name,
            )
        }) }
        return success(index, action.type, if (enabled) "SKILL_BOUND" else "SKILL_UNBOUND", if (enabled) "Skill bound to assistant." else "Skill unbound from assistant.", buildJsonObject {
            put("skill_name", name)
            put("assistant_id", assistantId.toString())
        }, receipt)
    }

    private fun test(index: Int, action: OwnerAction): OwnerAppliedAction {
        val name = action.arguments.string("skill_name")!!.trim()
        val raw = runCatching { skillManager.readSkillContent(name) }.getOrNull()
            ?: return failure(index, action.type, "SKILL_READ_FAILED", "Skill content could not be read.")
        val meta = SkillFrontmatterParser.parse(raw)
        if (meta["name"] != name || meta["description"].isNullOrBlank()) {
            return failure(index, action.type, "SKILL_MANIFEST_INVALID", "Skill frontmatter is missing a matching name or description.")
        }
        return success(index, action.type, "SKILL_TEST_OK", "Skill manifest and bounded content are readable.", buildJsonObject {
            put("skill_name", name)
            put("content_bytes", raw.toByteArray().size)
        })
    }

    private fun installText(bytes: ByteArray, source: String, requestedName: String?): String? {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        return when (val result = SkillUrlImporter(skillManager).importFromText(text, source, requestedName)) {
            is SkillUrlImporter.Result.Ok -> result.metadata.name
            is SkillUrlImporter.Result.Err -> null
        }
    }

    private fun installZip(bytes: ByteArray, requestedName: String?): String? {
        val temp = File(skillManager.getSkillsDir(), ".owner-import-${Uuid.random()}")
        if (!temp.mkdirs()) return null
        return try {
            val root = SkillZipImporter.extractZipToDir(bytes.inputStream(), temp).getOrNull() ?: return null
            val skillMd = root.listFiles()?.firstOrNull { it.isFile && it.name.equals("SKILL.md", true) }
                ?: return null
            val metadata = SkillFrontmatterParser.parse(skillMd.readText())
            val name = requestedName ?: metadata["name"]
            if (name.isNullOrBlank() || !name.matches(Regex("^[a-z0-9_-]{1,40}$"))) return null
            val files = root.walkTopDown().filter(File::isFile).associate { file ->
                file.relativeTo(root).invariantSeparatorsPath to file.readBytes()
            }
            if (!skillManager.saveSkillFileBytesAtomically(name, files)) null else name
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "text/markdown,application/zip,application/octet-stream").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body.byteStream().use { it.readOwnerBytesAtMost(MAX_DOWNLOAD_BYTES + 1) }
            if (bytes.size > MAX_DOWNLOAD_BYTES) null else bytes
        }
    }.getOrNull()

    private fun resolvedSource(args: JsonObject): String? {
        val direct = args.string("source_url")?.trim()?.takeIf { it.isNotBlank() }
        val git = args.string("git_url")?.trim()?.takeIf { it.isNotBlank() }
        val archive = args.string("archive_url")?.trim()?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        if (archive != null) return archive
        if (git == null) return null
        val uri = runCatching { URI(git) }.getOrNull() ?: return null
        val rawPin = args.string("pin")?.trim() ?: return null
        // A content SHA identifies downloaded bytes, not a Git ref. Generic Git sources using
        // a content hash must therefore supply archive_url; never guess a branch or `latest`.
        if (normalizedShaPin(rawPin) != null) return null
        val pin = rawPin
        if (uri.host.equals("github.com", true)) {
            val segments = uri.path.trim('/').removeSuffix(".git").split('/')
            if (segments.size == 2) return "https://codeload.github.com/${segments[0]}/${segments[1]}/zip/$pin"
        }
        return null
    }

    private class SkillReceipt(
        val skillName: String,
        val previousFiles: Map<String, ByteArray>?,
        val bindings: Map<Uuid, Set<String>>,
    ) : AutoCloseable {
        override fun close() {
            previousFiles?.values?.forEach { it.fill(0) }
        }
    }

    private fun snapshot(name: String): SkillReceipt {
        val root = skillManager.getSkillDir(name)
        val files = root?.takeIf(File::exists)?.walkTopDown()?.filter(File::isFile)?.associate { file ->
            file.relativeTo(root).invariantSeparatorsPath to file.readBytes()
        }
        return SkillReceipt(name, files, settingsStore.settingsFlow.value.assistants.associate { it.id to it.enabledSkills })
    }

    private fun snapshotAbsent(name: String) = SkillReceipt(
        name,
        null,
        settingsStore.settingsFlow.value.assistants.associate { it.id to it.enabledSkills },
    )

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
    private fun normalizedShaPin(pin: String): String? {
        val trimmed = pin.trim()
        val value = if (trimmed.startsWith("sha256:", ignoreCase = true)) {
            trimmed.substringAfter(':')
        } else {
            trimmed
        }
        return value.lowercase().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun constantTimeEquals(left: String, right: String) = MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null, receipt: Any? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.uuid(key: String) = string(key)?.trim()?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
        val SOURCE_FIELDS = setOf("source_url", "git_url", "archive_url", "pin", "skill_name")
        val FIELDS = mapOf(
            "skill_list" to emptySet(),
            "skill_install" to SOURCE_FIELDS,
            "skill_update" to SOURCE_FIELDS,
            "skill_uninstall" to setOf("skill_name"),
            "skill_bind" to setOf("skill_name", "assistant_id"),
            "skill_unbind" to setOf("skill_name", "assistant_id"),
            "skill_test" to setOf("skill_name"),
        )
    }
}
