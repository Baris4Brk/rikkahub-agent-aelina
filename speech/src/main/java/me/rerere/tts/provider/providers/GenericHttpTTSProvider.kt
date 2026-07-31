package me.rerere.tts.provider.providers

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.GenericHttpBodyEncoding
import me.rerere.tts.provider.GenericHttpMethod
import me.rerere.tts.provider.GenericHttpResponseMode
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class GenericHttpTTSProvider : TTSProvider<TTSProviderSetting.GenericHttp> {
    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.GenericHttp,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        validateSetting(providerSetting)
        val client = client(providerSetting.allowPrivateNetwork)
        val primaryBytes = client.newCall(buildRequest(providerSetting, request)).execute().use { response ->
            requireSuccess(response)
            readBounded(response, providerSetting.maxResponseBytes)
        }
        val audio = when (providerSetting.responseMode) {
            GenericHttpResponseMode.RAW_AUDIO -> primaryBytes
            GenericHttpResponseMode.BASE64_JSON -> {
                val encoded = jsonPath(primaryBytes, providerSetting.responseJsonPath).jsonPrimitive.content
                val decoded = Base64.getDecoder().decode(encoded)
                check(decoded.size <= providerSetting.maxResponseBytes) { "generic_tts_response_too_large" }
                decoded
            }
            GenericHttpResponseMode.URL_JSON -> {
                val url = jsonPath(primaryBytes, providerSetting.responseJsonPath).jsonPrimitive.content
                validateEndpoint(url, providerSetting.allowPrivateNetwork)
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    requireSuccess(response)
                    readBounded(response, providerSetting.maxResponseBytes)
                }
            }
        }
        emit(
            AudioChunk(
                data = audio,
                format = providerSetting.audioFormat,
                isLast = true,
                metadata = mapOf("provider" to "generic_http"),
            ),
        )
    }

    internal fun validateSetting(setting: TTSProviderSetting.GenericHttp) {
        validateEndpoint(setting.endpoint, setting.allowPrivateNetwork)
        check(setting.bodyTemplate.length <= MAX_TEMPLATE_CHARS) { "generic_tts_template_too_large" }
        check("{{secret}}" !in setting.bodyTemplate) { "generic_tts_secret_body_forbidden" }
        check(setting.headers.size <= MAX_HEADERS) { "generic_tts_too_many_headers" }
        setting.headers.forEach { header ->
            check(HEADER_NAME.matches(header.name)) { "generic_tts_header_invalid" }
            check(header.valueTemplate.length <= MAX_HEADER_VALUE_CHARS) { "generic_tts_header_too_large" }
            check(!header.name.equals("Host", true) && !header.name.equals("Content-Length", true)) {
                "generic_tts_header_reserved"
            }
        }
        check(setting.maxResponseBytes in 1..MAX_RESPONSE_BYTES) { "generic_tts_response_limit_invalid" }
    }

    internal fun renderBody(setting: TTSProviderSetting.GenericHttp, text: String): String {
        val encode: (String) -> String = when (setting.bodyEncoding) {
            GenericHttpBodyEncoding.JSON -> ::jsonEscape
            GenericHttpBodyEncoding.FORM -> ::urlEncode
        }
        return renderTemplate(
            template = setting.bodyTemplate,
            values = mapOf(
                "text" to encode(text),
                "voice" to encode(setting.voice),
                "language" to encode(setting.language),
            ),
        )
    }

    private fun buildRequest(setting: TTSProviderSetting.GenericHttp, request: TTSRequest): Request {
        val url = if (setting.method == GenericHttpMethod.GET) {
            renderTemplate(
                template = setting.endpoint,
                values = mapOf(
                    "text" to urlEncode(request.text),
                    "voice" to urlEncode(setting.voice),
                    "language" to urlEncode(setting.language),
                ),
            )
        } else setting.endpoint
        validateEndpoint(url, setting.allowPrivateNetwork)
        val builder = Request.Builder().url(url)
        setting.headers.forEach { header ->
            builder.addHeader(header.name, header.valueTemplate.replace("{{secret}}", setting.runtimeSecret))
        }
        return when (setting.method) {
            GenericHttpMethod.GET -> builder.get().build()
            GenericHttpMethod.POST -> {
                val media = when (setting.bodyEncoding) {
                    GenericHttpBodyEncoding.JSON -> "application/json"
                    GenericHttpBodyEncoding.FORM -> "application/x-www-form-urlencoded"
                }.toMediaType()
                builder.post(renderBody(setting, request.text).toRequestBody(media)).build()
            }
        }
    }

    private fun client(allowPrivate: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(Dns { hostname ->
            Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (!allowPrivate) check(addresses.none(::isPrivate)) { "generic_tts_private_address_denied" }
            }
        })
        .build()

    private fun validateEndpoint(raw: String, allowPrivate: Boolean) {
        val url = raw.toHttpUrlOrNull() ?: error("generic_tts_url_invalid")
        check(url.username.isEmpty() && url.password.isEmpty()) { "generic_tts_url_credentials_forbidden" }
        check(url.scheme == "https" || (allowPrivate && url.scheme == "http")) { "generic_tts_https_required" }
        val literal = runCatching { InetAddress.getByName(url.host) }.getOrNull()
        if (!allowPrivate && literal != null) check(!isPrivate(literal)) { "generic_tts_private_address_denied" }
    }

    private fun isPrivate(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            address.hostAddress?.let { it == "0.0.0.0" || it == "::" } == true

    private fun requireSuccess(response: Response) {
        check(response.isSuccessful) { "generic_tts_http_${response.code}" }
        check(!response.isRedirect) { "generic_tts_redirect_denied" }
    }

    private fun readBounded(response: Response, limit: Int): ByteArray {
        val body = response.body
        if (body.contentLength() > limit) error("generic_tts_response_too_large")
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                check(total <= limit) { "generic_tts_response_too_large" }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun jsonPath(bytes: ByteArray, path: String): JsonElement {
        var current: JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString())
        path.split('.').filter { it.isNotBlank() }.forEach { segment ->
            current = when (val value = current) {
                is JsonObject -> value[segment] ?: error("generic_tts_json_path_missing")
                is JsonArray -> value.getOrNull(segment.toIntOrNull() ?: -1)
                    ?: error("generic_tts_json_path_missing")
                is JsonPrimitive -> error("generic_tts_json_path_missing")
            }
        }
        return current
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * Render both RikkaHub's `{{name}}` tokens and the `{name}` form used by Operit and many
     * OpenAI-compatible TTS examples. A single regex pass is intentional: values inserted from
     * the request are never scanned again, so user text containing `{text}` remains unchanged.
     */
    private fun renderTemplate(template: String, values: Map<String, String>): String =
        TEMPLATE_TOKEN.replace(template) { match ->
            val key = match.groups[1]?.value ?: match.groups[2]?.value
            key?.let(values::get) ?: match.value
        }

    private companion object {
        const val MAX_TEMPLATE_CHARS = 32 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_CHARS = 4096
        const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
        val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
        val TEMPLATE_TOKEN = Regex("\\{\\{(text|voice|language)\\}\\}|\\{(text|voice|language)\\}")
    }
}
