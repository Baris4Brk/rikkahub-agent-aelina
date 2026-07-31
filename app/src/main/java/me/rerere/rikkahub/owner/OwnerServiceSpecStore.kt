package me.rerere.rikkahub.owner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
data class OwnerLocalServiceSpec(
    val runtime: String,
    val command: String? = null,
    val executable: String? = null,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String,
    /** Kept in the encrypted no-backup restart spec, never in the Room projection. */
    val healthUrl: String? = null,
    val workspaceId: String? = null,
    val name: String,
    val keepAwake: Boolean,
    val restartPolicy: String,
)

/** Stable, one-way integrity fingerprint used by the Room projection. */
internal fun ownerServiceSpecHash(spec: OwnerLocalServiceSpec): String {
    val material = buildString {
        append(spec.runtime).append('\u0000')
        append(spec.command.orEmpty()).append('\u0000')
        append(spec.executable.orEmpty()).append('\u0000')
        spec.arguments.forEach { append(it).append('\u0000') }
        append(spec.workingDirectory).append('\u0000')
        append(spec.healthUrl.orEmpty())
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Encrypted no-backup storage for restartable service argv; never exposed to tools. */
class OwnerServiceSpecStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "owner-service-specs").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun put(serviceId: String, spec: OwnerLocalServiceSpec): Boolean = mutex.withLock {
        if (!SAFE_ID.matches(serviceId)) return@withLock false
        runCatching {
            val plain = JsonInstant.encodeToString(spec).encodeToByteArray()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key())
                    updateAAD(serviceId.encodeToByteArray())
                }
                val encrypted = cipher.doFinal(plain)
                val out = ByteArray(1 + cipher.iv.size + encrypted.size)
                out[0] = cipher.iv.size.toByte()
                cipher.iv.copyInto(out, 1)
                encrypted.copyInto(out, 1 + cipher.iv.size)
                val atomic = AtomicFile(file(serviceId))
                val stream = atomic.startWrite()
                try {
                    stream.write(out)
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                } finally {
                    encrypted.fill(0)
                    out.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        }.isSuccess
    }

    suspend fun get(serviceId: String): OwnerLocalServiceSpec? = mutex.withLock {
        if (!SAFE_ID.matches(serviceId)) return@withLock null
        runCatching {
            val encoded = AtomicFile(file(serviceId)).readFully()
            val ivLength = encoded.firstOrNull()?.toInt()?.and(0xff) ?: return@runCatching null
            if (ivLength !in 12..32 || encoded.size <= 1 + ivLength) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, encoded.copyOfRange(1, 1 + ivLength)),
                )
                updateAAD(serviceId.encodeToByteArray())
            }
            val plain = cipher.doFinal(encoded.copyOfRange(1 + ivLength, encoded.size))
            try {
                JsonInstant.decodeFromString<OwnerLocalServiceSpec>(plain.decodeToString())
            } finally {
                plain.fill(0)
                encoded.fill(0)
            }
        }.getOrNull()
    }

    suspend fun delete(serviceId: String): Boolean = mutex.withLock {
        SAFE_ID.matches(serviceId) && (!file(serviceId).exists() || file(serviceId).delete())
    }

    private fun file(serviceId: String) = File(root, "$serviceId.bin")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "rikkahub_owner_service_specs_v1"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
    }
}
