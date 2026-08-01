package me.rerere.rikkahub.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
enum class SecretBindingKind { PROVIDER, TTS, ASR, MCP, SKILL, CHANNEL }

@Serializable
data class SecretBinding(
    val kind: SecretBindingKind,
    val targetId: String,
    /** A pet may only borrow Provider/TTS bindings that opt in explicitly. */
    val allowPetSidecar: Boolean = false,
)

@Serializable
data class SecretSlotMetadata(
    val slotId: String,
    val label: String,
    val purpose: String,
    val authoritySubjectId: String,
    val bindings: List<SecretBinding> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Serializable
private data class SecretVaultIndex(val slots: List<SecretSlotMetadata> = emptyList())

/** Only UI code with a completed BIOMETRIC_STRONG operation may create this capability. */
class SecretVaultUserAuthorization internal constructor(internal val issuedAtMs: Long)

enum class SecretLeasePurpose { EXECUTION, PET_SIDECAR }

sealed interface SecretLeaseResult<out T> {
    data class Success<T>(val value: T) : SecretLeaseResult<T>
    data object SlotMissing : SecretLeaseResult<Nothing>
    data object BindingDenied : SecretLeaseResult<Nothing>
    data object AuthorityDenied : SecretLeaseResult<Nothing>
    data object KeystoreUnavailable : SecretLeaseResult<Nothing>
    data object Corrupt : SecretLeaseResult<Nothing>
}

/**
 * A deliberately non-serializable, internal execution lease.  It only exists while the vault
 * holds its mutex and wipes its character buffer when the callback returns.
 */
internal class SecretLease internal constructor(private val chars: CharArray) {
    internal fun <T> use(block: (CharArray) -> T): T = block(chars)
}

/**
 * Private, no-backup, keystore-only storage for second-user credentials. Raw values are never
 * returned to model tools: trusted local adapters use [withLease] and the char buffer is cleared.
 */
class SecondUserSecretVault(context: Context) {
    private val root = File(context.noBackupFilesDir, "second-user-vault").apply { mkdirs() }
    private val metadataFile = File(root, "slots.json")
    private val valuesDirectory = File(root, "values").apply { mkdirs() }
    private val mutex = Mutex()

    /** The second user can enumerate only metadata belonging to its live authority epoch. */
    suspend fun listMetadata(subjectId: String): List<SecretSlotMetadata> = mutex.withLock {
        if (!isCurrentSubject(subjectId)) emptyList() else {
            readIndex().slots.filter { it.authoritySubjectId == subjectId }
        }
    }

    /** User-only inventory for strong-biometric settings UI, including unbound former epochs. */
    suspend fun listMetadataForUser(
        authorization: SecretVaultUserAuthorization,
    ): List<SecretSlotMetadata> = mutex.withLock {
        if (!isFreshUserAuthorization(authorization.issuedAtMs)) emptyList() else readIndex().slots
    }

    /** The second user can prepare an empty slot, but cannot provide a secret value. */
    suspend fun createEmptySlot(
        metadata: SecretSlotMetadata,
        subjectId: String,
    ): Boolean = mutex.withLock {
        if (!safeSlotId(metadata.slotId) || !isCurrentSubject(subjectId) || metadata.authoritySubjectId != subjectId) return@withLock false
        val index = readIndex()
        if (index.slots.any { it.slotId == metadata.slotId }) return@withLock false
        writeIndex(index.copy(slots = index.slots + metadata.copy(
            label = metadata.label.take(MAX_LABEL),
            purpose = metadata.purpose.take(MAX_PURPOSE),
            bindings = metadata.bindings.map(::sanitizeBinding),
        )))
        true
    }

    suspend fun updateBindings(
        slotId: String,
        subjectId: String,
        bindings: List<SecretBinding>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = mutex.withLock {
        if (!safeSlotId(slotId) || !isCurrentSubject(subjectId)) return@withLock false
        val index = readIndex()
        val old = index.slots.firstOrNull { it.slotId == slotId && it.authoritySubjectId == subjectId }
            ?: return@withLock false
        writeIndex(index.copy(slots = index.slots.map { slot ->
            if (slot.slotId == old.slotId) slot.copy(
                bindings = bindings.map(::sanitizeBinding),
                updatedAtMs = nowMs,
            ) else slot
        }))
        true
    }

    /**
     * User-controlled epoch reassignment.  The ciphertext remains local, but a new second user
     * never inherits it unless the user explicitly rebinds the slot after BIOMETRIC_STRONG.
     */
    suspend fun rebindForUser(
        authorization: SecretVaultUserAuthorization,
        slotId: String,
        newSubjectId: String,
        bindings: List<SecretBinding>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = mutex.withLock {
        if (
            !safeSlotId(slotId) ||
            !isFreshUserAuthorization(authorization.issuedAtMs) ||
            !isCurrentSubject(newSubjectId)
        ) return@withLock false
        val index = readIndex()
        if (index.slots.none { it.slotId == slotId }) return@withLock false
        writeIndex(index.copy(slots = index.slots.map { slot ->
            if (slot.slotId == slotId) {
                slot.copy(
                    authoritySubjectId = newSubjectId,
                    bindings = bindings.map(::sanitizeBinding),
                    updatedAtMs = nowMs,
                )
            } else {
                slot
            }
        }))
        true
    }

    /** User-only path. The caller must obtain [SecretVaultUserAuthorization] from a strong prompt. */
    suspend fun storeForUser(
        authorization: SecretVaultUserAuthorization,
        slotId: String,
        value: CharArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = mutex.withLock {
        // The caller intentionally transfers ownership of [value] to the vault.  Wipe it even
        // when an authorization expires or the slot no longer exists.
        try {
            if (!safeSlotId(slotId) || !isFreshUserAuthorization(authorization.issuedAtMs)) {
                return@withLock false
            }
            val index = readIndex()
            val metadata = index.slots.firstOrNull { it.slotId == slotId } ?: return@withLock false
            val plain = value.concatToString().encodeToByteArray()
            try {
                writeEncrypted(slotId, plain)
                writeIndex(index.copy(slots = index.slots.map {
                    if (it.slotId == metadata.slotId) it.copy(updatedAtMs = nowMs) else it
                }))
                true
            } finally {
                plain.fill(0)
            }
        } catch (_: Throwable) {
            false
        } finally {
            value.fill('\u0000')
        }
    }

    /** User-only reveal path; adapters and model tools never receive this API. */
    suspend fun <T> withUserSecret(
        authorization: SecretVaultUserAuthorization,
        slotId: String,
        block: (CharArray) -> T,
    ): SecretLeaseResult<T> = mutex.withLock {
        if (!safeSlotId(slotId) || !isFreshUserAuthorization(authorization.issuedAtMs)) {
            return@withLock SecretLeaseResult.AuthorityDenied
        }
        withDecrypted(slotId, block)
    }

    /** Internal execution path for Provider/TTS/ASR/MCP and typed Skill adapters only. */
    internal suspend fun <T> withLease(
        slotId: String,
        subjectId: String,
        binding: SecretBinding,
        purpose: SecretLeasePurpose = SecretLeasePurpose.EXECUTION,
        block: (SecretLease) -> T,
    ): SecretLeaseResult<T> = mutex.withLock {
        if (!safeSlotId(slotId) || !isCurrentSubject(subjectId)) return@withLock SecretLeaseResult.AuthorityDenied
        val metadata = readIndex().slots.firstOrNull { it.slotId == slotId }
            ?: return@withLock SecretLeaseResult.SlotMissing
        if (metadata.authoritySubjectId != subjectId || binding !in metadata.bindings) {
            return@withLock SecretLeaseResult.BindingDenied
        }
        if (purpose == SecretLeasePurpose.PET_SIDECAR &&
            (!binding.allowPetSidecar || binding.kind !in setOf(SecretBindingKind.PROVIDER, SecretBindingKind.TTS))
        ) return@withLock SecretLeaseResult.BindingDenied
        withDecrypted(slotId) { chars -> block(SecretLease(chars)) }
    }

    /** Process-local plaintext-session path. Exact session binding is enforced by its manager. */
    internal suspend fun <T> withRemoteSessionSecret(
        slotId: String,
        subjectId: String,
        block: (CharArray) -> T,
    ): SecretLeaseResult<T> = mutex.withLock {
        if (!safeSlotId(slotId) || !isCurrentSubject(subjectId)) {
            return@withLock SecretLeaseResult.AuthorityDenied
        }
        val metadata = readIndex().slots.firstOrNull {
            it.slotId == slotId && it.authoritySubjectId == subjectId
        } ?: return@withLock SecretLeaseResult.SlotMissing
        @Suppress("UNUSED_VARIABLE") val owner = metadata.authoritySubjectId
        withDecrypted(slotId, block)
    }

    /** Local transforms avoid sending a complete secret back through another model turn. */
    internal suspend fun transformForRemoteSession(
        slotId: String,
        subjectId: String,
        transform: (CharArray) -> CharArray,
    ): SecretLeaseResult<Unit> = mutex.withLock {
        if (!safeSlotId(slotId) || !isCurrentSubject(subjectId)) {
            return@withLock SecretLeaseResult.AuthorityDenied
        }
        val index = readIndex()
        val metadata = index.slots.firstOrNull {
            it.slotId == slotId && it.authoritySubjectId == subjectId
        } ?: return@withLock SecretLeaseResult.SlotMissing
        when (val current = withDecrypted(slotId) { chars ->
            val transformed = transform(chars)
            try {
                transformed.copyOf()
            } finally {
                transformed.fill('\u0000')
            }
        }) {
            is SecretLeaseResult.Success -> {
                val replacement = current.value
                val plain = replacement.concatToString().encodeToByteArray()
                try {
                    writeEncrypted(slotId, plain)
                    writeIndex(index.copy(slots = index.slots.map { slot ->
                        if (slot.slotId == metadata.slotId) slot.copy(updatedAtMs = System.currentTimeMillis()) else slot
                    }))
                    SecretLeaseResult.Success(Unit)
                } catch (_: Throwable) {
                    SecretLeaseResult.KeystoreUnavailable
                } finally {
                    plain.fill(0)
                    replacement.fill('\u0000')
                }
            }
            SecretLeaseResult.SlotMissing -> SecretLeaseResult.SlotMissing
            SecretLeaseResult.BindingDenied -> SecretLeaseResult.BindingDenied
            SecretLeaseResult.AuthorityDenied -> SecretLeaseResult.AuthorityDenied
            SecretLeaseResult.KeystoreUnavailable -> SecretLeaseResult.KeystoreUnavailable
            SecretLeaseResult.Corrupt -> SecretLeaseResult.Corrupt
        }
    }

    suspend fun deleteForUser(authorization: SecretVaultUserAuthorization, slotId: String): Boolean = mutex.withLock {
        if (!safeSlotId(slotId) || !isFreshUserAuthorization(authorization.issuedAtMs)) return@withLock false
        val index = readIndex()
        if (index.slots.none { it.slotId == slotId }) return@withLock false
        File(valuesDirectory, "$slotId.bin").delete()
        writeIndex(index.copy(slots = index.slots.filterNot { it.slotId == slotId }))
        true
    }

    private fun <T> withDecrypted(slotId: String, block: (CharArray) -> T): SecretLeaseResult<T> {
        val file = File(valuesDirectory, "$slotId.bin")
        if (!file.exists()) return SecretLeaseResult.SlotMissing
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return SecretLeaseResult.Corrupt
        if (bytes.size <= IV_SIZE) return SecretLeaseResult.Corrupt
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)),
            )
            cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size))
        } catch (_: Throwable) {
            return SecretLeaseResult.KeystoreUnavailable
        }
        val chars = plain.decodeToString().toCharArray()
        return try {
            SecretLeaseResult.Success(block(chars))
        } finally {
            plain.fill(0)
            chars.fill('\u0000')
        }
    }

    private fun writeEncrypted(slotId: String, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val file = File(valuesDirectory, "$slotId.bin")
        val temporary = File(valuesDirectory, "$slotId.tmp")
        temporary.writeBytes(cipher.iv + cipher.doFinal(plain))
        if (file.exists()) file.delete()
        check(temporary.renameTo(file)) { "vault_atomic_write_failed" }
    }

    private fun readIndex(): SecretVaultIndex = if (!metadataFile.exists()) SecretVaultIndex() else
        runCatching { JsonInstant.decodeFromString<SecretVaultIndex>(metadataFile.readText()) }
            .getOrDefault(SecretVaultIndex())

    private fun writeIndex(index: SecretVaultIndex) {
        val temporary = File(root, "slots.tmp")
        temporary.writeText(JsonInstant.encodeToString(index))
        if (metadataFile.exists()) metadataFile.delete()
        check(temporary.renameTo(metadataFile)) { "vault_metadata_atomic_write_failed" }
    }

    private fun isCurrentSubject(subjectId: String): Boolean =
        SecondUserAuthorityRegistry.current()?.subjectId == subjectId

    private fun isFreshUserAuthorization(issuedAtMs: Long): Boolean =
        System.currentTimeMillis() - issuedAtMs in 0..USER_AUTHORIZATION_WINDOW_MS

    private fun sanitizeBinding(binding: SecretBinding): SecretBinding = binding.copy(
        targetId = binding.targetId.trim().take(MAX_TARGET),
    )

    private fun safeSlotId(slotId: String): Boolean = SLOT_ID.matches(slotId)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_second_user_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val MAX_LABEL = 96
        const val MAX_PURPOSE = 160
        const val MAX_TARGET = 160
        const val USER_AUTHORIZATION_WINDOW_MS = 2 * 60 * 1000L
        val SLOT_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")
    }
}
