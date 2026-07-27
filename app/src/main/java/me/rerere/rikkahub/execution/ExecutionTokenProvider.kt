package me.rerere.rikkahub.execution

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

fun interface ExecutionTokenProvider {
    fun tokenFor(nativeId: String): String

    /** 128-bit externally visible owner proof; production overrides with direct Keystore HMAC. */
    fun ownerTokenFor(
        domain: String,
        assistantId: String,
        conversationId: String,
        origin: String,
    ): String {
        val seed = listOf(domain, assistantId, conversationId, origin)
            .joinToString("\u0000")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(tokenFor("owner_seed").toByteArray(), "HmacSHA256"))
        return mac.doFinal(seed.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

class AndroidKeystoreExecutionTokenProvider : ExecutionTokenProvider {
    override fun tokenFor(nativeId: String): String {
        require(nativeId.matches(NATIVE_ID)) { "invalid managed execution id" }
        // Preserve the v1 supervisor token so already-running managed processes remain operable.
        return hmac(nativeId)
    }

    override fun ownerTokenFor(
        domain: String,
        assistantId: String,
        conversationId: String,
        origin: String,
    ): String {
        require(domain.matches(NATIVE_ID)) { "invalid owner token domain" }
        require(assistantId.isNotBlank() && conversationId.isNotBlank() && origin.isNotBlank())
        return hmac("owner-v1\u0000$domain\u0000$assistantId\u0000$conversationId\u0000$origin")
            .take(32)
    }

    private fun hmac(input: String): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(loadOrCreateKey())
        return mac.doFinal(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_managed_execution_hmac_v1"
        val NATIVE_ID = Regex("[A-Za-z0-9_-]{8,96}")
    }
}
