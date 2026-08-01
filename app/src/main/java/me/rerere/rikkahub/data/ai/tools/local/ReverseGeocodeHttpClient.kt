package me.rerere.rikkahub.data.ai.tools.local

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal sealed interface ReverseGeocodeHttpResult {
    data class Json(val statusCode: Int, val body: String) : ReverseGeocodeHttpResult
    data class Failure(val code: String) : ReverseGeocodeHttpResult
}

internal class ReverseGeocodeHttpClient(baseClient: OkHttpClient) {
    internal val client: OkHttpClient = baseClient.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun getJson(request: Request): ReverseGeocodeHttpResult = try {
        val response = await(client.newCall(request))
        response.use { opened ->
            when (opened.code) {
                401, 403 -> ReverseGeocodeHttpResult.Failure("PROVIDER_AUTH_FAILED")
                429 -> ReverseGeocodeHttpResult.Failure("PROVIDER_RATE_LIMITED")
                in 200..299 -> {
                    val contentType = opened.body.contentType()
                    if (contentType == null || !contentType.subtype.contains("json", ignoreCase = true)) {
                        ReverseGeocodeHttpResult.Failure("PROVIDER_RESPONSE_INVALID")
                    } else {
                        val bytes = readBounded(opened)
                            ?: return@use ReverseGeocodeHttpResult.Failure("PROVIDER_RESPONSE_INVALID")
                        ReverseGeocodeHttpResult.Json(opened.code, bytes.toString(Charsets.UTF_8))
                    }
                }
                else -> ReverseGeocodeHttpResult.Failure("PROVIDER_NETWORK_FAILED")
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        ReverseGeocodeHttpResult.Failure("PROVIDER_NETWORK_FAILED")
    } catch (_: Exception) {
        ReverseGeocodeHttpResult.Failure("PROVIDER_NETWORK_FAILED")
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private suspend fun readBounded(response: Response): ByteArray? = withContext(Dispatchers.IO) {
        val declared = response.body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) return@withContext null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        response.body.byteStream().use { input ->
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) return@withContext null
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
