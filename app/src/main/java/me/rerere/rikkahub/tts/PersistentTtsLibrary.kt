package me.rerere.rikkahub.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.datastore.normalizedTtsPlaybackSpeed
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.security.SecretBindingResolution
import me.rerere.rikkahub.security.resolveTtsBinding
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager

/** Metadata for one permanently retained TTS request. */
@Serializable
data class TtsLibraryEntry(
    val artifactId: String,
    val text: String,
    val createdAtMs: Long,
    val chunks: List<TtsLibraryChunk>,
    /** Optional private scope used only for desktop-pet speaking behaviour. */
    val ownerKey: String? = null,
) {
    val totalBytes: Long get() = chunks.sumOf(TtsLibraryChunk::sizeBytes)
}

@Serializable
data class TtsLibraryChunk(
    val fileName: String,
    val format: AudioFormat,
    val sampleRate: Int? = null,
    val duration: Float? = null,
    val sizeBytes: Long,
    val sha256: String,
)

data class LoadedTtsArtifact(
    val entry: TtsLibraryEntry,
    val audio: List<TTSResponse>,
)

sealed interface TtsPlaybackState {
    data object Silent : TtsPlaybackState
    data class Speaking(
        val artifactId: String,
        val ownerKey: String?,
    ) : TtsPlaybackState
}

/** Stable, private ownership key for the bound second-user assistant and conversation. */
object TtsPlaybackOwner {
    fun secondUser(assistantId: String, conversationId: String): String =
        "second_user:$assistantId:$conversationId"
}

/**
 * File-backed TTS archive. Entries are never pruned automatically.
 *
 * The root is deliberately under filesDir rather than cacheDir: Android may evict cache files,
 * which would make a replay unexpectedly contact the synthesis provider again. Each request is
 * committed through a temporary directory so an interrupted synthesis never becomes a visible
 * library entry.
 */
class TtsArtifactStore(
    private val root: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutationMutex = Mutex()

    suspend fun save(
        text: String,
        responses: List<TTSResponse>,
        ownerKey: String? = null,
        createdAtMs: Long = System.currentTimeMillis(),
        artifactId: String = UUID.randomUUID().toString(),
    ): TtsLibraryEntry = mutationMutex.withLock {
        require(text.isNotBlank()) { "TTS text must not be blank" }
        require(responses.isNotEmpty()) { "TTS synthesis produced no audio" }
        require(SAFE_ID.matches(artifactId)) { "Invalid TTS artifact id" }
        require(ownerKey == null || OWNER_KEY.matches(ownerKey)) { "Invalid TTS playback owner" }

        withContext(Dispatchers.IO) {
            root.mkdirsOrThrow()
            val temporary = File(root, ".$artifactId.tmp")
            val destination = File(root, artifactId)
            check(!temporary.exists() && !destination.exists()) { "TTS artifact already exists" }
            temporary.mkdirsOrThrow()
            try {
                val chunks = responses.mapIndexed { index, response ->
                    require(response.audioData.isNotEmpty()) { "TTS synthesis produced an empty audio chunk" }
                    val fileName = "%03d.%s".format(index, response.format.extension())
                    val audioFile = File(temporary, fileName)
                    audioFile.writeBytes(response.audioData)
                    TtsLibraryChunk(
                        fileName = fileName,
                        format = response.format,
                        sampleRate = response.sampleRate,
                        duration = response.duration,
                        sizeBytes = response.audioData.size.toLong(),
                        sha256 = response.audioData.sha256(),
                    )
                }
                val entry = TtsLibraryEntry(
                    artifactId = artifactId,
                    text = text,
                    createdAtMs = createdAtMs,
                    chunks = chunks,
                    ownerKey = ownerKey,
                )
                File(temporary, MANIFEST_FILE).writeText(json.encodeToString(entry), Charsets.UTF_8)
                check(temporary.renameTo(destination)) { "Unable to commit TTS artifact" }
                entry
            } catch (error: Throwable) {
                temporary.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun get(artifactId: String): TtsLibraryEntry? = withContext(Dispatchers.IO) {
        readEntry(artifactId)
    }

    suspend fun load(artifactId: String): LoadedTtsArtifact? = withContext(Dispatchers.IO) {
        val entry = readEntry(artifactId) ?: return@withContext null
        val directory = File(root, artifactId)
        val responses = entry.chunks.map { chunk ->
            val file = File(directory, chunk.fileName)
            val bytes = file.takeIf(File::isFile)?.readBytes()
                ?: throw IllegalStateException("TTS audio file is missing")
            check(bytes.size.toLong() == chunk.sizeBytes && bytes.sha256() == chunk.sha256) {
                "TTS audio file failed integrity verification"
            }
            TTSResponse(
                audioData = bytes,
                format = chunk.format,
                sampleRate = chunk.sampleRate,
                duration = chunk.duration,
            )
        }
        LoadedTtsArtifact(entry = entry, audio = responses)
    }

    fun contains(artifactId: String): Boolean = SAFE_ID.matches(artifactId) &&
        File(File(root, artifactId), MANIFEST_FILE).isFile

    suspend fun list(limit: Int = 50, offset: Int = 0): List<TtsLibraryEntry> = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && SAFE_ID.matches(it.name) }
            .mapNotNull { readEntry(it.name) }
            .sortedByDescending(TtsLibraryEntry::createdAtMs)
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    private fun readEntry(artifactId: String): TtsLibraryEntry? {
        if (!SAFE_ID.matches(artifactId)) return null
        val manifest = File(File(root, artifactId), MANIFEST_FILE)
        if (!manifest.isFile) return null
        return runCatching {
            json.decodeFromString<TtsLibraryEntry>(manifest.readText(Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.artifactId == artifactId && it.chunks.isNotEmpty() }
    }

    private fun File.mkdirsOrThrow() {
        check(isDirectory || mkdirs()) { "Unable to create TTS library directory" }
    }

    private fun AudioFormat.extension(): String = when (this) {
        AudioFormat.MP3 -> "mp3"
        AudioFormat.WAV -> "wav"
        AudioFormat.OGG -> "ogg"
        AudioFormat.AAC -> "aac"
        AudioFormat.OPUS -> "opus"
        AudioFormat.PCM -> "pcm"
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        val SAFE_ID = Regex("[A-Za-z0-9_-]{8,80}")
        val OWNER_KEY = Regex("[A-Za-z0-9:_-]{1,160}")
    }
}

/** Application-scoped synthesis, persistence, and exact-file replay coordinator. */
class PersistentTtsLibrary(
    context: Context,
    private val settingsStore: SettingsStore,
    ttsManager: TTSManager,
    private val appScope: AppScope,
    private val secretVault: SecondUserSecretVault? = null,
) {
    private val applicationContext = context.applicationContext
    private val store = TtsArtifactStore(File(applicationContext.filesDir, LIBRARY_FOLDER))
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val chunker = TextChunker(maxChunkLength = 160)
    private val playbackMutex = Mutex()
    private val playbackJobLock = Any()
    private var audioPlayer: AudioPlayer? = null
    private var playbackJob: Job? = null
    private var playbackGeneration = 0L
    private val _playbackState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Silent)
    val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    suspend fun synthesizeSaveAndQueue(text: String, ownerKey: String? = null): TtsLibraryEntry {
        require(text.isNotBlank()) { "text is required" }
        val configuredProvider = settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?: error("No TTS provider is configured")
        val provider = resolveSecondUserTtsBinding(configuredProvider, ownerKey)
        val chunks = chunker.split(text)
        val responses = chunks.map { chunk -> synthesizer.synthesize(provider, chunk) }
        val entry = store.save(text = text, responses = responses, ownerKey = ownerKey)
        check(queueReplay(entry.artifactId, ownerKey)) { "Saved TTS audio could not be queued" }
        return entry
    }

    /** Owner-runtime path for testing a typed Provider before making it the global default. */
    suspend fun synthesizeProviderSaveAndQueue(
        provider: me.rerere.tts.provider.TTSProviderSetting,
        text: String,
        ownerKey: String? = null,
    ): TtsLibraryEntry {
        require(text.isNotBlank()) { "text is required" }
        val responses = chunker.split(text).map { chunk -> synthesizer.synthesize(provider, chunk) }
        val entry = store.save(text = text, responses = responses, ownerKey = ownerKey)
        check(queueReplay(entry.artifactId, ownerKey)) { "Saved TTS audio could not be queued" }
        return entry
    }

    private suspend fun resolveSecondUserTtsBinding(
        configuredProvider: me.rerere.tts.provider.TTSProviderSetting,
        ownerKey: String?,
    ): me.rerere.tts.provider.TTSProviderSetting {
        val active = SecondUserAuthorityRegistry.current() ?: return configuredProvider
        val expectedOwner = TtsPlaybackOwner.secondUser(
            assistantId = active.assistantId.toString(),
            conversationId = active.conversationId.toString(),
        )
        if (ownerKey != expectedOwner) return configuredProvider
        val vault = secretVault ?: return configuredProvider
        return when (val resolution = vault.resolveTtsBinding(
            provider = configuredProvider,
            subjectId = active.subjectId,
        )) {
            SecretBindingResolution.NotBound -> configuredProvider
            is SecretBindingResolution.Ready -> resolution.value
            is SecretBindingResolution.Unavailable -> error("second_user_secret_${resolution.code}")
        }
    }

    /** Returns only after the entry is known to exist; playback then runs in application scope. */
    fun queueReplay(artifactId: String, ownerKey: String? = null): Boolean {
        if (!store.contains(artifactId)) return false
        synchronized(playbackJobLock) {
            val generation = ++playbackGeneration
            playbackJob?.cancel()
            playbackJob = appScope.launchPlayback {
                try {
                    val artifact = store.load(artifactId) ?: return@launchPlayback
                    val scopedOwner = ownerKey ?: artifact.entry.ownerKey
                    publishPlaybackState(generation, TtsPlaybackState.Speaking(artifactId, scopedOwner))
                    playbackMutex.withLock {
                        val player = withContext(Dispatchers.Main.immediate) {
                            audioPlayer ?: AudioPlayer(applicationContext).also { audioPlayer = it }
                        }
                        withContext(Dispatchers.Main.immediate) {
                            player.setSpeed(
                                settingsStore.settingsFlow.value.defaultTTSPlaybackSpeed
                                    .normalizedTtsPlaybackSpeed(),
                            )
                        }
                        artifact.audio.forEach { response ->
                            withContext(Dispatchers.Main.immediate) { player.play(response) }
                        }
                    }
                } finally {
                    publishPlaybackState(generation, TtsPlaybackState.Silent)
                }
            }
        }
        return true
    }

    fun stopPlayback() {
        synchronized(playbackJobLock) {
            playbackGeneration += 1
            playbackJob?.cancel()
            playbackJob = null
            _playbackState.value = TtsPlaybackState.Silent
        }
    }

    private fun publishPlaybackState(generation: Long, state: TtsPlaybackState) {
        synchronized(playbackJobLock) {
            if (generation == playbackGeneration) {
                _playbackState.value = state
            }
        }
    }

    suspend fun exists(artifactId: String): Boolean = store.get(artifactId) != null

    suspend fun list(limit: Int = 50, offset: Int = 0): List<TtsLibraryEntry> =
        store.list(limit = limit, offset = offset)

    private fun AppScope.launchPlayback(block: suspend () -> Unit): Job =
        launch(context = Dispatchers.Main.immediate) { block() }

    companion object {
        const val LIBRARY_FOLDER = "tts_library"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{8,80}")
    }
}
