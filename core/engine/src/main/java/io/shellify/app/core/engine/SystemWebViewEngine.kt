package io.shellify.app.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.shellify.app.core.adblock.AdBlocker
import io.shellify.app.core.webview.WebViewManager
import io.shellify.app.core.webbridge.ContentProtectionBridge
import io.shellify.app.domain.model.ContentProtectionSettings
import io.shellify.app.domain.model.EngineType
import io.shellify.app.domain.model.WebApp
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.InputStream

private const val CONTENT_PROTECTION_LIBRARY_ASSET = "web_extensions/content_protection/human.js"
private const val CONTENT_PROTECTION_SMART_ASSET = "web_extensions/content_protection/smart-detector.js"
private const val CONTENT_PROTECTION_ASSET_PREFIX = "/__shellify_content_protection/models/"

// Extracted so unit tests can verify the main-frame guard and blocked flag without
// requiring a real WebView or AdBlocker (same pattern used for dispatchNotification).
internal fun dispatchInterceptedRequest(url: String, isForMainFrame: Boolean, blocked: Boolean, cb: BrowserEngineCallback?) {
    if (!isForMainFrame) {
        cb?.onRequestIntercepted(url, blocked = blocked)
    }
}

// Schemes that render web content inside the engine and must NEVER be handed to the OS as an
// external link. Besides http(s) this includes data:/blob:/about:/javascript:, which OAuth popups
// and JS-generated documents (e.g. window.open() then document.write) rely on — treating them as
// external sends them to startActivity() instead of loading, so the popup stays blank and its
// script never runs (no postMessage back to the opener, no window.close()).
private val INTERNAL_SCHEMES = listOf("http://", "https://", "data:", "blob:", "about:", "javascript:")

// Extracted for unit testing: only genuinely external schemes (tel:, mailto:, intent:, sms:,
// custom app schemes) are handed to the host; web-content schemes stay inside the WebView/popup.
internal fun isExternalScheme(url: String): Boolean =
    INTERNAL_SCHEMES.none { url.startsWith(it, ignoreCase = true) }

class SystemWebViewEngine(private val adBlocker: AdBlocker) : BrowserEngine {

    override val engineType = EngineType.SYSTEM_WEBVIEW
    private var webView: WebView? = null
    private var storedCallback: BrowserEngineCallback? = null
    private var contentProtectionSettings = ContentProtectionSettings()
    private var contentProtectionContext: Context? = null
    private var contentProtectionLibrary: String? = null
    private var contentProtectionSmartDetector: String? = null

    // Popup WebViews created for window.open() / OAuth flows. Tracked so they can be destroyed
    // with the engine even if the page never fires onCloseWindow.
    private val popups = mutableListOf<WebView>()

    override fun createView(context: Context, app: WebApp, callback: BrowserEngineCallback): View {
        storedCallback = callback
        contentProtectionContext = context.applicationContext
        contentProtectionSettings = app.contentProtection
        val wv = WebView(context)
        WebViewManager.configure(wv, app)
        installContentProtection(wv, contentProtectionSettings)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                contentProtectionAssetResponse(request.url)?.let { return it }
                val result = if (app.adBlockEnabled) adBlocker.shouldBlock(request, app.trackerBlockingEnabled) else null
                dispatchInterceptedRequest(
                    url = request.url.toString(),
                    isForMainFrame = request.isForMainFrame,
                    blocked = result != null,
                    cb = storedCallback,
                )
                return result
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isExternalScheme(url)) {
                    callback.onExternalLink(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                callback.onPageStarted(url)

            override fun onPageFinished(view: WebView, url: String) {
                injectContentProtectionFallback(view)
                callback.onPageFinished(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    callback.onError(error.errorCode, error.description.toString())
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel()
                callback.onSslError(error.toString())
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) =
                callback.onProgressChanged(newProgress)

            override fun onReceivedTitle(view: WebView, title: String) =
                callback.onTitleChanged(title)

            override fun onShowCustomView(view: View, cb: CustomViewCallback) =
                callback.onShowCustomView(view, cb)

            override fun onHideCustomView() =
                callback.onHideCustomView()

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean = handleCreateWindow(view.context, app, callback, resultMsg)
        }

        webView = wv
        return wv
    }

    // Honour window.open() / target="_blank" (OAuth, "Sign in with Google") by spawning a real
    // popup WebView. The host displays it as an overlay via onShowPopup; the popup self-dismisses
    // through onCloseWindow when the flow finishes. Returns true so the link is not also opened
    // in the parent frame.
    private fun handleCreateWindow(
        context: Context,
        app: WebApp,
        callback: BrowserEngineCallback,
        resultMsg: Message,
    ): Boolean {
        val popup = createPopupWebView(context, app, callback)
        callback.onShowPopup(popup)
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun createPopupWebView(context: Context, app: WebApp, callback: BrowserEngineCallback): WebView {
        val popup = WebView(context)
        WebViewManager.configure(popup, app)
        installContentProtection(popup, contentProtectionSettings)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                contentProtectionAssetResponse(request.url)
                    ?: if (app.adBlockEnabled) adBlocker.shouldBlock(request, app.trackerBlockingEnabled) else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isExternalScheme(url)) {
                    callback.onExternalLink(url)
                    return true
                }
                return false
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                callback.onSslError(error.toString())
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectContentProtectionFallback(view)
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean = handleCreateWindow(view.context, app, callback, resultMsg)

            override fun onCloseWindow(window: WebView) = closePopup(window, callback)
        }
        popups.add(popup)
        return popup
    }

    private fun closePopup(popup: WebView, callback: BrowserEngineCallback) {
        popups.remove(popup)
        callback.onClosePopup(popup)
        popup.stopLoading()
        popup.destroy()
    }

    override fun closeTopPopup(): Boolean {
        val popup = popups.lastOrNull() ?: return false
        val callback = storedCallback ?: return false
        closePopup(popup, callback)
        return true
    }

    fun getWebView(): WebView? = webView

    override fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?) {
        webView?.evaluateJavascript(script, resultCallback)
    }

    override fun updateContentProtection(settings: ContentProtectionSettings) {
        contentProtectionSettings = settings
        webView?.let { injectContentProtectionUpdate(it, settings) }
        popups.forEach { injectContentProtectionUpdate(it, settings) }
    }

    private fun installContentProtection(view: WebView, settings: ContentProtectionSettings) {
        val detectorScripts = contentProtectionScripts(view.context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, detectorScripts.library, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, detectorScripts.smartDetector, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(
                view,
                ContentProtectionBridge.buildDocumentStartScript(settings),
                setOf("*"),
            )
        }
        injectContentProtectionUpdate(view, settings)
    }

    private fun injectContentProtectionFallback(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            injectContentProtectionUpdate(view, contentProtectionSettings)
        } else {
            val detectorScripts = contentProtectionScripts(view.context)
            view.evaluateJavascript(
                detectorScripts.library + detectorScripts.smartDetector +
                    ContentProtectionBridge.buildDocumentStartScript(contentProtectionSettings),
                null,
            )
        }
    }

    private fun injectContentProtectionUpdate(view: WebView, settings: ContentProtectionSettings) {
        view.evaluateJavascript(ContentProtectionBridge.buildUpdateScript(settings), null)
    }

    private data class ContentProtectionScripts(
        val library: String,
        val smartDetector: String,
    )

    private fun contentProtectionScripts(context: Context): ContentProtectionScripts {
        val library = contentProtectionLibrary ?: readContentProtectionAsset(context, CONTENT_PROTECTION_LIBRARY_ASSET).also {
            contentProtectionLibrary = it
        }
        val smartDetector = contentProtectionSmartDetector
            ?: readContentProtectionAsset(context, CONTENT_PROTECTION_SMART_ASSET).also {
                contentProtectionSmartDetector = it
            }
        return ContentProtectionScripts(library, smartDetector)
    }

    private fun readContentProtectionAsset(context: Context, asset: String): String =
        context.applicationContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun contentProtectionAssetResponse(url: Uri): WebResourceResponse? {
        val relativePath = url.path?.removePrefix(CONTENT_PROTECTION_ASSET_PREFIX) ?: return null
        if (relativePath == url.path || relativePath.contains("..") || relativePath !in CONTENT_PROTECTION_MODEL_ASSETS) {
            return null
        }
        val context = contentProtectionContext ?: return null
        val stream: InputStream = runCatching {
            context.assets.open("web_extensions/content_protection/models/$relativePath")
        }.getOrNull() ?: return null
        val mimeType = if (relativePath.endsWith(".json")) "application/json" else "application/octet-stream"
        val encoding = if (relativePath.endsWith(".json")) "UTF-8" else null
        return WebResourceResponse(mimeType, encoding, stream)
    }

    override fun canGoBack() = webView?.canGoBack() ?: false
    override fun goBack() {
        webView?.goBack()
    }

    override fun reload() {
        webView?.reload()
    }

    override fun stopLoading() {
        webView?.stopLoading()
    }

    override fun getCurrentUrl() = webView?.url
    override fun getView(): View? = webView

    override fun destroy() {
        popups.toList().forEach { it.stopLoading(); it.removeAllViews(); it.destroy() }
        popups.clear()
        webView?.apply { stopLoading(); clearHistory(); removeAllViews(); destroy() }
        webView = null
        contentProtectionContext = null
        contentProtectionLibrary = null
        contentProtectionSmartDetector = null
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        webView?.clearCache(includeDiskFiles)
    }
}

private val CONTENT_PROTECTION_MODEL_ASSETS = setOf(
    "blazeface.json",
    "blazeface.bin",
    "faceres.json",
    "faceres.bin",
    "movenet-multipose.json",
    "movenet-multipose.bin",
)
