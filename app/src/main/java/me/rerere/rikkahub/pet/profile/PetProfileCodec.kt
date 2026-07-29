package me.rerere.rikkahub.pet.profile

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.pet.CodexPetVersion
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.action.PetActionProfile
import me.rerere.rikkahub.pet.action.PetCapabilitySet
import me.rerere.rikkahub.pet.action.PetClipBinding
import me.rerere.rikkahub.pet.action.PetClipLoopMode
import me.rerere.rikkahub.pet.action.PetIdlePoolConfig
import me.rerere.rikkahub.pet.action.PetSheetBinding
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.PetImageProbe
import me.rerere.rikkahub.pet.assets.PetPackageException
import me.rerere.rikkahub.pet.assets.CODEX_ATLAS_COLUMNS
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_HEIGHT
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_WIDTH
import me.rerere.rikkahub.pet.assets.expectedRows

@Serializable
data class PetProfileDocument(
    val schemaVersion: Int = PetProfileCodec.PROFILE_SCHEMA_VERSION,
    val id: String? = null,
    val renderer: String = "codex_sprite",
    val actions: Map<String, PetProfileClipDocument> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val fallbacks: Map<String, List<String>> = emptyMap(),
    @SerialName("touch_mappings")
    val touchMappings: Map<String, String> = emptyMap(),
    @SerialName("idle_pool")
    val idlePool: PetProfileIdlePoolDocument? = null,
    val sheets: Map<String, PetProfileSheetDocument> = emptyMap(),
)

@Serializable
data class PetProfileClipDocument(
    val sheet: String = PetClipBinding.BASE_SHEET_ID,
    val row: Int,
    val frames: Int,
    val fps: Int? = null,
    @SerialName("loop")
    val loop: String = "loop",
    @SerialName("mirror_x")
    val mirrorX: Boolean = false,
)

@Serializable
data class PetProfileSheetDocument(
    val path: String,
    @SerialName("frame_width")
    val frameWidth: Int,
    @SerialName("frame_height")
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
)

@Serializable
data class PetProfileIdlePoolDocument(
    val weights: Map<String, Int>,
    @SerialName("min_interval_ms")
    val minIntervalMs: Long = 15_000L,
)

/** Result contains only safe runtime structures; it deliberately excludes raw JSON and ZIP paths. */
data class ValidatedPetProfile(
    val profile: PetActionProfile,
    val extraSheetPaths: Map<String, String>,
)

/**
 * Strict parser for package and app-private profile overrides. The app supports only fixed
 * static sprites; unknown fields are ignored for forward compatibility but cannot add code.
 */
object PetProfileCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun defaultProfile(manifest: CodexPetManifest): ValidatedPetProfile {
        val standard = PetActionProfile.standard("builtin.codex.${manifest.resolvedVersion.name.lowercase()}")
        return ValidatedPetProfile(
            profile = standard.copy(
                sheets = standard.sheets + (
                    PetClipBinding.BASE_SHEET_ID to standard.sheets.getValue(PetClipBinding.BASE_SHEET_ID).copy(
                        relativePath = manifest.resolvedSpritesheetPath,
                        rows = manifest.resolvedVersion.expectedRows(),
                    )
                ),
            ),
            extraSheetPaths = emptyMap(),
        )
    }

    fun decodeAndValidate(
        raw: String,
        manifest: CodexPetManifest,
        packageRoot: File,
        imageProbe: PetImageProbe,
    ): ValidatedPetProfile {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            throw PetPackageException("pet_profile_too_large")
        }
        val document = runCatching { json.decodeFromString<PetProfileDocument>(raw) }
            .getOrElse { throw PetPackageException("pet_profile_invalid") }
        return validate(document, manifest, packageRoot, imageProbe)
    }

    fun validate(
        document: PetProfileDocument,
        manifest: CodexPetManifest,
        packageRoot: File,
        imageProbe: PetImageProbe,
    ): ValidatedPetProfile {
        if (document.schemaVersion != PROFILE_SCHEMA_VERSION) {
            throw PetPackageException("pet_profile_version_unsupported")
        }
        if (document.renderer !in SUPPORTED_RENDERERS) {
            throw PetPackageException("pet_profile_renderer_unsupported")
        }
        if (document.actions.size > MAX_ACTIONS || document.sheets.size > MAX_EXTRA_SHEETS ||
            document.aliases.size > MAX_ACTIONS || document.fallbacks.size > MAX_ACTIONS
        ) throw PetPackageException("pet_profile_too_complex")

        val default = defaultProfile(manifest)
        val profileId = document.id
            ?.takeIf { it.matches(PROFILE_ID) }
            ?: "package.${manifest.id.lowercase()}"
        val sheets = default.profile.sheets.toMutableMap()
        val extraPaths = mutableMapOf<String, String>()
        var decodedPixels = CODEX_FRAME_WIDTH.toLong() * CODEX_ATLAS_COLUMNS *
            CODEX_FRAME_HEIGHT * manifest.resolvedVersion.expectedRows()
        document.sheets.forEach { (sheetId, sheetDocument) ->
            if (sheetId == PetClipBinding.BASE_SHEET_ID || !SHEET_ID.matches(sheetId)) {
                throw PetPackageException("pet_profile_sheet_invalid")
            }
            val relativePath = normalizedRelativePath(sheetDocument.path)
            val sheet = runCatching {
                PetSheetBinding(
                    sheetId = sheetId,
                    relativePath = relativePath,
                    frameWidth = sheetDocument.frameWidth,
                    frameHeight = sheetDocument.frameHeight,
                    columns = sheetDocument.columns,
                    rows = sheetDocument.rows,
                )
            }.getOrElse { throw PetPackageException("pet_profile_sheet_invalid") }
            val file = safeChild(packageRoot, relativePath)
            val info = imageProbe.inspect(file) ?: throw PetPackageException("pet_profile_sheet_decode_failed")
            decodedPixels += info.width.toLong() * info.height
            if (info.width != sheet.frameWidth * sheet.columns ||
                info.height != sheet.frameHeight * sheet.rows ||
                info.width.toLong() * info.height > MAX_SINGLE_SHEET_PIXELS ||
                decodedPixels > MAX_DECODE_PIXELS
            ) throw PetPackageException("pet_profile_sheet_dimensions_invalid")
            sheets[sheetId] = sheet
            extraPaths[sheetId] = relativePath
        }
        if (document.renderer == "codex_sprite" && extraPaths.isNotEmpty()) {
            throw PetPackageException("pet_profile_extra_sheet_requires_composite")
        }

        val bindings = default.profile.bindings.toMutableMap()
        document.actions.forEach { (rawActionId, clipDocument) ->
            val actionId = parseActionId(rawActionId)
            val sheet = sheets[clipDocument.sheet] ?: throw PetPackageException("pet_profile_sheet_unknown")
            val loopMode = when (clipDocument.loop.lowercase()) {
                "loop" -> PetClipLoopMode.LOOP
                "once" -> PetClipLoopMode.ONCE
                else -> throw PetPackageException("pet_profile_loop_invalid")
            }
            val clip = runCatching {
                PetClipBinding(
                    actionId = actionId,
                    sheetId = sheet.sheetId,
                    row = clipDocument.row,
                    frames = clipDocument.frames,
                    fps = clipDocument.fps,
                    loopMode = loopMode,
                    mirrorX = clipDocument.mirrorX,
                )
            }.getOrElse { throw PetPackageException("pet_profile_clip_invalid") }
            if (clip.row !in 0 until sheet.rows || clip.frames > sheet.columns) {
                throw PetPackageException("pet_profile_clip_out_of_bounds")
            }
            bindings[actionId] = clip
        }

        val aliases = document.aliases.map { (from, to) -> parseActionId(from) to parseActionId(to) }.toMap()
        val fallbacks = document.fallbacks.map { (from, chain) ->
            if (chain.size > MAX_FALLBACKS) throw PetPackageException("pet_profile_fallback_too_deep")
            parseActionId(from) to chain.map(::parseActionId)
        }.toMap()
        validateFallbackGraph(aliases, fallbacks)
        val touch = document.touchMappings.map { (region, action) ->
            if (region !in VALID_TOUCH_REGIONS) throw PetPackageException("pet_profile_touch_invalid")
            region to parseActionId(action)
        }.toMap()
        val idlePool = document.idlePool?.let { idle ->
            if (idle.weights.size !in 1..MAX_IDLE_POOL_ACTIONS) {
                throw PetPackageException("pet_profile_idle_pool_invalid")
            }
            runCatching {
                PetIdlePoolConfig(idle.weights.map { (id, weight) -> parseActionId(id) to weight }.toMap(), idle.minIntervalMs)
            }.getOrElse { throw PetPackageException("pet_profile_idle_pool_invalid") }
        }
        val capabilities = PetCapabilitySet(
            supportedActions = bindings.keys,
            supportsDirectionalLook = bindings.keys.any { it.value.startsWith("custom.look_") },
            supportsAlphaHitTest = true,
            supportsSpeechMotion = bindings.containsKey(CorePetActions.SPEAKING),
            supportsMirroring = bindings.values.any(PetClipBinding::mirrorX),
        )
        return ValidatedPetProfile(
            profile = PetActionProfile(
                profileId = profileId,
                rendererType = document.renderer,
                bindings = bindings,
                sheets = sheets,
                aliases = aliases,
                fallbacks = fallbacks,
                touchMappings = touch,
                idlePool = idlePool,
                capabilities = capabilities,
            ),
            extraSheetPaths = extraPaths,
        )
    }

    private fun parseActionId(value: String): PetActionId = PetActionId.parseOrNull(value)
        ?: throw PetPackageException("pet_profile_action_id_invalid")

    private fun validateFallbackGraph(
        aliases: Map<PetActionId, PetActionId>,
        fallbacks: Map<PetActionId, List<PetActionId>>,
    ) {
        val graph = aliases.mapValues { listOf(it.value) } + fallbacks
        graph.keys.forEach { start ->
            val visiting = mutableSetOf<PetActionId>()
            fun visit(current: PetActionId, depth: Int) {
                if (depth > MAX_FALLBACK_DEPTH || !visiting.add(current)) {
                    throw PetPackageException("pet_profile_fallback_cycle")
                }
                graph[current].orEmpty().forEach { visit(it, depth + 1) }
                visiting.remove(current)
            }
            visit(start, 0)
        }
    }

    private fun normalizedRelativePath(value: String): String {
        val normalized = value.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.contains(":/") ||
            normalized.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) throw PetPackageException("pet_profile_path_invalid")
        if (normalized.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) {
            throw PetPackageException("pet_profile_path_type_invalid")
        }
        return normalized
    }

    private fun safeChild(root: File, relative: String): File {
        val child = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!child.path.startsWith(canonicalRoot.path + File.separator)) {
            throw PetPackageException("pet_profile_path_escape")
        }
        return child
    }

    const val PROFILE_FILE_NAME = "rikkahub-profile.json"
    const val PROFILE_SCHEMA_VERSION = 1
    private const val MAX_PROFILE_BYTES = 128 * 1024
    private const val MAX_ACTIONS = 64
    private const val MAX_EXTRA_SHEETS = 8
    private const val MAX_FALLBACKS = 8
    private const val MAX_FALLBACK_DEPTH = 12
    private const val MAX_IDLE_POOL_ACTIONS = 8
    private const val MAX_SINGLE_SHEET_PIXELS = 4_000_000L
    private const val MAX_DECODE_PIXELS = 8_000_000L
    private val SUPPORTED_RENDERERS = setOf("codex_sprite", "composite_sprite")
    private val PROFILE_ID = Regex("[a-z][a-z0-9._-]{0,63}")
    private val SHEET_ID = Regex("[a-z][a-z0-9_-]{0,31}")
    private val IMAGE_EXTENSIONS = setOf("png", "webp")
    private val VALID_TOUCH_REGIONS = setOf("head", "body", "feet")
}
