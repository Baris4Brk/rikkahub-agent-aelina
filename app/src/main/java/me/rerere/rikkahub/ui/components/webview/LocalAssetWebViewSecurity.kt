package me.rerere.rikkahub.ui.components.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

internal const val RIKKAHUB_LOCAL_ORIGIN = "https://rikkahub.local/"
internal const val MERMAID_ASSET_PATH = "/assets/mermaid/mermaid.min.js"
internal const val MERMAID_RENDERER_MARKER = "rikkahub-mermaid-11.16.0"

internal data class LocalAssetRequest(
    val method: String,
    val scheme: String?,
    val host: String?,
    val encodedPath: String?,
    val encodedQuery: String?,
)

internal class LocalAssetRequestPolicy(
    private val allowedPaths: Set<String>,
) {
    fun allows(request: LocalAssetRequest): Boolean =
        request.method.equals("GET", ignoreCase = true) &&
            request.scheme.equals("https", ignoreCase = true) &&
            request.host.equals("rikkahub.local", ignoreCase = true) &&
            request.encodedQuery.isNullOrEmpty() &&
            request.encodedPath in allowedPaths
}

@Composable
internal fun rememberRikkaHubAssetWebViewClient(
    state: WebViewState,
    allowedPaths: Set<String>,
): MyWebViewClient {
    val context = LocalContext.current.applicationContext
    return remember(context, state, allowedPaths) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain("rikkahub.local")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        RestrictedLocalAssetWebViewClient(
            state = state,
            loader = loader,
            policy = LocalAssetRequestPolicy(allowedPaths),
        )
    }
}

internal fun isBundledMermaidHtml(html: String): Boolean =
    html.contains("name=\"rikkahub-renderer\" content=\"$MERMAID_RENDERER_MARKER\"")

private class RestrictedLocalAssetWebViewClient(
    state: WebViewState,
    private val loader: WebViewAssetLoader,
    private val policy: LocalAssetRequestPolicy,
) : MyWebViewClient(state) {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url
        val allowed = policy.allows(
            LocalAssetRequest(
                method = request.method,
                scheme = uri.scheme,
                host = uri.host,
                encodedPath = uri.encodedPath,
                encodedQuery = uri.encodedQuery,
            ),
        )
        if (!allowed) return blockedResponse()
        return loader.shouldInterceptRequest(uri) ?: notFoundResponse()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("Blocked local renderer request".toByteArray()),
    )

    private fun notFoundResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("Missing local renderer asset".toByteArray()),
    )
}
