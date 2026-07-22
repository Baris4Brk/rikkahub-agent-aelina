package me.rerere.rikkahub.plugin

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** WebView executor hosted only in the manifest-declared :plugin_runtime process. */
class PluginRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var webViewProcessReady = false

    override fun onCreate() {
        super.onCreate()
        webViewProcessReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("plugin_runtime_v1") }.isSuccess
        } else {
            false
        }
    }

    private val binder = object : IPluginRuntimeService.Stub() {
        override fun invoke(requestJson: String, host: IPluginRuntimeHost): String = runBlocking {
            invokeInternal(requestJson, host)
        }

        override fun cancel(invocationId: String) {
            active.remove(invocationId)?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        active.values.forEach { it.cancel() }
        active.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun invokeInternal(requestJson: String, host: IPluginRuntimeHost): String {
        val started = System.nanoTime()
        val request = runCatching {
            require(requestJson.length <= MAX_REQUEST_CHARS) { "plugin_request_too_large" }
            JSON.decodeFromString<PluginRuntimeRequest>(requestJson).also(
                PluginRuntimeRequestValidator::validate
            )
        }.getOrElse { failure ->
            return encode(
                PluginRuntimeResponse(
                    ok = false,
                    invocationId = "invalid",
                    errorCode = failure.message.toPluginErrorCode(),
                )
            )
        }
        if (!webViewProcessReady) {
            return encode(request.failure("plugin_webview_process_isolation_unavailable", started))
        }
        val completion = CompletableDeferred<String>()
        if (active.putIfAbsent(request.invocationId, completion) != null) {
            return encode(request.failure("plugin_invocation_duplicate", started))
        }
        return try {
            val output = withTimeout(request.timeoutMs) {
                createInvocationWebView(request, host, completion)
                completion.await()
            }
            encode(
                PluginRuntimeResponse(
                    ok = true,
                    invocationId = request.invocationId,
                    outputJson = output,
                    durationMs = elapsedMs(started),
                )
            )
        } catch (failure: Throwable) {
            encode(request.failure(failure.message.toPluginErrorCode(), started))
        } finally {
            active.remove(request.invocationId, completion)
            // A timeout cancels the awaiting coroutine but not an independent
            // CompletableDeferred. Completing it here is what triggers deterministic WebView and
            // WebStorage cleanup for timeout, binder cancellation, and service failure alike.
            completion.cancel()
        }
    }

    private suspend fun createInvocationWebView(
        request: PluginRuntimeRequest,
        host: IPluginRuntimeHost,
        completion: CompletableDeferred<String>,
    ) = withContext(Dispatchers.Main.immediate) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "plugin_webmessage_unavailable"
        }
        val packageRoot = PluginRuntimePaths.packageRoot(this@PluginRuntimeService, request.pluginId)
            .canonicalFile
        loadAndValidateManifest(packageRoot, request)
        val entryFile = File(packageRoot, request.entry).canonicalFile
        check(entryFile.toPath().startsWith(packageRoot.toPath()) && entryFile.isFile) {
            "plugin_entry_missing"
        }
        val origin = PluginRuntimeRequestValidator.originFor(request.pluginIdHash)
        val domain = Uri.parse(origin).host ?: error("plugin_origin_invalid")
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(domain)
            .addPathHandler(
                "/",
                WebViewAssetLoader.InternalStoragePathHandler(
                    this@PluginRuntimeService,
                    packageRoot,
                ),
            )
            .build()
        var webView: WebView? = null
        try {
            val rpcCount = AtomicInteger(0)
            val expectedUrl = "$origin/${request.entry}"
            val view = WebView(applicationContext).apply {
                configureLockedDownSettings(settings)
            }
            webView = view
            WebViewCompat.addWebMessageListener(
                view,
                JS_HOST_OBJECT,
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        if (!isMainFrame || sourceOrigin.toString() != origin) return
                        handlePluginMessage(
                            request,
                            message.data.orEmpty(),
                            host,
                            replyProxy,
                            completion,
                            rpcCount,
                        )
                    }
                },
            )
            view.webChromeClient = rejectingChromeClient()
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse = assetLoader.shouldInterceptRequest(request.url)
                    ?: blockedResponse()

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest,
                ): Boolean = request.url.scheme != "https" || request.url.host != domain

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != expectedUrl) completion.completeExceptionally(
                        IllegalStateException("plugin_navigation_blocked")
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == expectedUrl && !completion.isCompleted) {
                        view?.evaluateJavascript(triggerScript(request), null)
                    }
                }
            }
            view.loadUrl(expectedUrl)
            completion.invokeOnCompletion {
                Handler(Looper.getMainLooper()).post {
                    runCatching { view.stopLoading() }
                    runCatching { view.destroy() }
                    runCatching { WebStorage.getInstance().deleteOrigin(origin) }
                }
            }
        } catch (failure: Throwable) {
            runCatching { webView?.destroy() }
            throw failure
        }
    }

    private fun loadAndValidateManifest(packageRoot: File, request: PluginRuntimeRequest) {
        val file = File(packageRoot, "plugin.json")
        check(file.isFile && file.length() <= 64 * 1024) { "plugin_manifest_missing" }
        val manifest = JSON.decodeFromString<PluginManifestV1>(file.readText(Charsets.UTF_8))
        PluginManifestValidator.validate(manifest)
        check(manifest.id == request.pluginId && manifest.entry == request.entry) {
            "plugin_manifest_mismatch"
        }
        val declared = when (request.kind) {
            PluginInvocationKind.TOOL -> manifest.tools.any { it.handler == request.handler }
            PluginInvocationKind.PROMPT_HOOK -> manifest.hooks.promptHandler == request.handler
            PluginInvocationKind.INTERCEPT_HOOK -> manifest.hooks.interceptHandler == request.handler
            PluginInvocationKind.OBSERVER_HOOK -> manifest.hooks.observerHandler == request.handler
        }
        check(declared) { "plugin_handler_not_declared" }
    }

    private fun handlePluginMessage(
        request: PluginRuntimeRequest,
        raw: String,
        host: IPluginRuntimeHost,
        replyProxy: JavaScriptReplyProxy,
        completion: CompletableDeferred<String>,
        outstandingRpcs: AtomicInteger,
    ) {
        if (raw.length > MAX_MESSAGE_CHARS || completion.isCompleted) return
        val message = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        if (message["rpcToken"]?.jsonPrimitive?.contentOrNull != request.rpcToken) return
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "result" -> completeResult(message, completion)
            "error" -> completion.completeExceptionally(
                IllegalStateException("plugin_handler_failed")
            )
            "rpc" -> dispatchHostRpc(
                request,
                message,
                host,
                replyProxy,
                completion,
                outstandingRpcs,
            )
        }
    }

    private fun completeResult(
        message: JsonObject,
        completion: CompletableDeferred<String>,
    ) {
        val output = message["outputJson"]?.jsonPrimitive?.contentOrNull ?: "null"
        if (output.length > PluginRuntimeRequestValidator.MAX_OUTPUT_CHARS ||
            runCatching { JSON.parseToJsonElement(output) }.isFailure
        ) {
            completion.completeExceptionally(IllegalStateException("plugin_output_invalid"))
        } else {
            completion.complete(output)
        }
    }

    private fun dispatchHostRpc(
        request: PluginRuntimeRequest,
        message: JsonObject,
        host: IPluginRuntimeHost,
        replyProxy: JavaScriptReplyProxy,
        completion: CompletableDeferred<String>,
        outstandingRpcs: AtomicInteger,
    ) {
        if (outstandingRpcs.incrementAndGet() > MAX_OUTSTANDING_RPCS) {
            outstandingRpcs.decrementAndGet()
            replyProxy.postMessage(rpcReply(message, "plugin_rpc_limit_exceeded"))
            return
        }
        val hostRequest = buildHostRpcRequest(request, message)
        if (hostRequest == null) {
            outstandingRpcs.decrementAndGet()
            replyProxy.postMessage(rpcReply(message, "plugin_rpc_request_too_large"))
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val hostResponse = runCatching { host.handleRpc(hostRequest) }
                    .getOrElse { "{\"ok\":false,\"error\":\"plugin_host_unavailable\"}" }
                    .takeIf { it.length <= MAX_HOST_RESPONSE_CHARS }
                    ?: "{\"ok\":false,\"error\":\"plugin_host_response_too_large\"}"
                withContext(Dispatchers.Main.immediate) {
                    if (!completion.isCompleted) {
                        replyProxy.postMessage(rpcReply(message, responseJson = hostResponse))
                    }
                }
            } finally {
                outstandingRpcs.decrementAndGet()
            }
        }
    }

    private fun buildHostRpcRequest(
        request: PluginRuntimeRequest,
        message: JsonObject,
    ): String? = buildJsonObject {
        put("protocolVersion", 1)
        put("rpcToken", request.rpcToken)
        put("invocationId", request.invocationId)
        put("pluginId", request.pluginId)
        put("requestId", message["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        put("method", message["method"]?.jsonPrimitive?.contentOrNull.orEmpty())
        put("params", message["params"] ?: JsonObject(emptyMap()))
    }.toString().takeIf { it.length <= MAX_HOST_REQUEST_CHARS }

    private fun rpcReply(
        request: JsonObject,
        errorCode: String? = null,
        responseJson: String? = null,
    ): String = buildJsonObject {
        put("type", "rpc_response")
        put("requestId", request["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        errorCode?.let { put("error", it) }
        responseJson?.let { put("responseJson", it) }
    }.toString()

    private fun configureLockedDownSettings(settings: WebSettings) {
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.blockNetworkImage = true
        @Suppress("DEPRECATION")
        settings.allowFileAccess = false
        @Suppress("DEPRECATION")
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
    }

    private fun rejectingChromeClient() = object : WebChromeClient() {
        override fun onJsAlert(
            view: WebView?, url: String?, message: String?, result: JsResult?,
        ): Boolean = true.also { result?.cancel() }

        override fun onJsConfirm(
            view: WebView?, url: String?, message: String?, result: JsResult?,
        ): Boolean = true.also { result?.cancel() }

        override fun onJsPrompt(
            view: WebView?, url: String?, message: String?, defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean = true.also { result?.cancel() }
    }

    private fun triggerScript(request: PluginRuntimeRequest): String {
        val token = JSON.encodeToString(request.rpcToken)
        val handler = JSON.encodeToString(request.handler)
        return """
            (async function() {
              try {
                const handler = window[$handler];
                if (typeof handler !== 'function') throw new Error('handler_not_found');
                const pending = new Map();
                let sequence = 0;
                $JS_HOST_OBJECT.onmessage = function(event) {
                  try {
                    const reply = JSON.parse(event.data);
                    if (reply.type !== 'rpc_response') return;
                    const waiter = pending.get(reply.requestId);
                    if (!waiter) return;
                    pending.delete(reply.requestId);
                    if (reply.error) waiter.reject(new Error(reply.error));
                    else waiter.resolve(JSON.parse(reply.responseJson));
                  } catch (_) {}
                };
                const host = Object.freeze({
                  rpc: function(method, params) {
                    return new Promise(function(resolve, reject) {
                      const requestId = 'rpc_' + (++sequence);
                      pending.set(requestId, {resolve, reject});
                      $JS_HOST_OBJECT.postMessage(JSON.stringify({
                        rpcToken:$token,
                        type:'rpc',
                        requestId,
                        method,
                        params: params || {}
                      }));
                    });
                  }
                });
                const value = await handler(${request.inputJson}, host);
                const outputJson = JSON.stringify(value === undefined ? null : value);
                $JS_HOST_OBJECT.postMessage(JSON.stringify({rpcToken:$token,type:'result',outputJson}));
              } catch (_) {
                $JS_HOST_OBJECT.postMessage(JSON.stringify({rpcToken:$token,type:'error'}));
              }
            })();
        """.trimIndent()
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("blocked".toByteArray()),
    )

    private fun PluginRuntimeRequest.failure(code: String, started: Long) =
        PluginRuntimeResponse(
            ok = false,
            invocationId = invocationId,
            errorCode = code,
            durationMs = elapsedMs(started),
        )

    private fun encode(value: PluginRuntimeResponse): String = JSON.encodeToString(value)
    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)

    private fun String?.toPluginErrorCode(): String =
        this?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }
            ?: "plugin_runtime_failed"

    private companion object {
        const val JS_HOST_OBJECT = "rikkahubHost"
        const val MAX_REQUEST_CHARS = 96 * 1024
        const val MAX_MESSAGE_CHARS = 96 * 1024
        const val MAX_HOST_REQUEST_CHARS = 16 * 1024
        const val MAX_HOST_RESPONSE_CHARS = 256 * 1024
        const val MAX_OUTSTANDING_RPCS = 4
        val JSON = Json { ignoreUnknownKeys = false; explicitNulls = false }
    }
}
