package me.rerere.rikkahub.plugin

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AndroidPluginNetworkGateway(
    baseClient: OkHttpClient,
) : PluginNetworkGateway {
    private val baseClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): Result<PluginNetworkResponse> = runCatching {
        var current = url.toHttpUrlOrNull() ?: error("plugin_network_url_invalid")
        val authorizedHost = current.host
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            require(PluginNetworkRedirectPolicy.isAllowed(authorizedHost, current)) {
                "plugin_network_redirect_host_blocked"
            }
            val validated = validateTarget(current)
            val client = baseClient.newBuilder().dns(FixedDns(validated.host, validated.addresses)).build()
            val response = await(
                client.newCall(
                    Request.Builder()
                        .url(current)
                        .get()
                        .header("Accept", "application/json,text/plain,text/html;q=0.8,*/*;q=0.1")
                        .build()
                )
            )
            response.use { opened ->
                if (opened.code in REDIRECT_CODES) {
                    require(redirectIndex < MAX_REDIRECTS) { "plugin_network_redirect_limit" }
                    val location = opened.header("Location")
                        ?: error("plugin_network_redirect_invalid")
                    current = current.resolve(location)
                        ?: error("plugin_network_redirect_invalid")
                    return@repeat
                }
                val bytes = readBounded(opened)
                return@runCatching PluginNetworkResponse(
                    status = opened.code,
                    contentType = opened.body.contentType()?.toString(),
                    body = bytes.toString(Charsets.UTF_8),
                )
            }
        }
        error("plugin_network_redirect_limit")
    }

    private suspend fun validateTarget(url: HttpUrl): ValidatedTarget = withContext(Dispatchers.IO) {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443) {
            "plugin_network_url_blocked"
        }
        val addresses = InetAddress.getAllByName(url.host).toList()
        require(addresses.isNotEmpty() && addresses.all(PluginNetworkAddressPolicy::isPublic)) {
            "plugin_network_private_address_blocked"
        }
        ValidatedTarget(url.host, addresses)
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }

    private suspend fun readBounded(response: Response): ByteArray = withContext(Dispatchers.IO) {
        val stream = response.body.byteStream()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        stream.use { input ->
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESPONSE_BYTES) {
                    "plugin_network_response_too_large"
                }
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray()
    }

    private data class ValidatedTarget(
        val host: String,
        val addresses: List<InetAddress>,
    )

    private class FixedDns(
        private val host: String,
        private val addresses: List<InetAddress>,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            require(hostname.equals(host, ignoreCase = true)) {
                "plugin_network_dns_target_changed"
            }
            return addresses
        }
    }

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 10L
        const val MAX_REDIRECTS = 3
        const val MAX_RESPONSE_BYTES = 256 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal object PluginNetworkRedirectPolicy {
    fun isAllowed(authorizedHost: String, target: HttpUrl): Boolean =
        target.isHttps && target.port == 443 && target.username.isEmpty() &&
            target.password.isEmpty() && target.host.equals(authorizedHost, ignoreCase = true)
}

internal object PluginNetworkAddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicV4(bytes)
            is Inet6Address -> isPublicV6(bytes)
            else -> false
        }
    }

    private fun isPublicV4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicV6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first and 0xfe == 0xfc) return false // unique local fc00::/7
        if (first == 0x20 && second == 0x01 && (bytes[2].toInt() and 0xff) == 0x0d &&
            (bytes[3].toInt() and 0xff) == 0xb8
        ) return false // documentation 2001:db8::/32
        return true
    }
}
