package io.shellify.app.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.shellify.app.core.adblock.AdBlocker
import io.shellify.app.core.webbridge.ContentProtectionBridge
import io.shellify.app.core.webview.WebViewManager
import io.shellify.app.domain.model.ContentProtectionSettings
import io.shellify.app.domain.model.EngineType
import io.shellify.app.domain.model.WebApp
import java.io.InputStream
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "SystemWebViewEngine"
private const val CONTENT_PROTECTION_LIBRARY_ASSET = "web_extensions/content_protection/human.js"
private const val CONTENT_PROTECTION_SMART_ASSET = "web_extensions/content_protection/smart-detector.js"
private const val CONTENT_PROTECTION_ASSET_PREFIX = "/__shellify_content_protection/models/"
private const val CONTENT_PROTECTION_MAIN_VIEW = "main"
private const val CONTENT_PROTECTION_POPUP_VIEW = "popup"

private const val CONTENT_PROTECTION_STATUS_SCRIPT = """
(function() {
  var bridge = window['__shellifyContentProtection'];
  var detector = window['__shellifySmartDetection'];
  var state = 'missing';
  var backend = 'none';
  var hasError = false;
  if (detector && typeof detector.status === 'function') {
    try {
      var detectorStatus = detector.status();
      state = detectorStatus.ready ? 'ready' :
        (detectorStatus.loading ? 'loading' : (detectorStatus.error ? 'error' : 'idle'));
      backend = detectorStatus.backend || 'none';
      hasError = Boolean(detectorStatus.error);
    } catch (_) {
      state = 'status-error';
      hasError = true;
    }
  }
  return JSON.stringify({
    library: Boolean(window['Human'] && window['Human'].Human),
    bridge: Boolean(bridge && typeof bridge.update === 'function'),
    detector: Boolean(detector && typeof detector.status === 'function'),
    state: state,
    backend: backend,
    error: hasError
  });
})()
"""

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
    private val contentProtectionViewStates = mutableMapOf<WebView, ContentProtectionViewState>()

    // Popup WebViews created for window.open() / OAuth flows. Tracked so they can be destroyed
    // with the engine even if the page never fires onCloseWindow.
    private val popups = mutableListOf<WebView>()

    override fun createView(context: Context, app: WebApp, callback: BrowserEngineCallback): View {
        storedCallback = callback
        contentProtectionContext = context.applicationContext
        contentProtectionSettings = app.contentProtection
        val wv = WebView(context)
        WebViewManager.configure(wv, app)
        installContentProtection(wv, contentProtectionSettings, CONTENT_PROTECTION_MAIN_VIEW)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                contentProtectionAssetResponse(request.url, CONTENT_PROTECTION_MAIN_VIEW)?.let { return it }
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                resetContentProtectionDocument(view)
                callback.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                inspectContentProtection(view)
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
        installContentProtection(popup, contentProtectionSettings, CONTENT_PROTECTION_POPUP_VIEW)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                contentProtectionAssetResponse(request.url, CONTENT_PROTECTION_POPUP_VIEW)
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
                inspectContentProtection(view)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                resetContentProtectionDocument(view)
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
        contentProtectionViewStates.remove(popup)
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

    private fun installContentProtection(
        view: WebView,
        settings: ContentProtectionSettings,
        viewName: String,
    ) {
        val state = ContentProtectionViewState(viewName)
        contentProtectionViewStates[view] = state
        state.isDocumentStartSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.onFailure { error ->
            Log.w(TAG, "Content protection DOCUMENT_START_SCRIPT check failed " +
                "view=$viewName error=${error::class.java.simpleName}")
        }.getOrDefault(false)
        Log.d(TAG, "Content protection DOCUMENT_START_SCRIPT supported=" +
            "${state.isDocumentStartSupported} view=$viewName")

        val detectorScripts = runCatching { contentProtectionScripts(view.context) }
            .onFailure { error ->
                Log.w(TAG, "Content protection script assets failed view=$viewName " +
                    "error=${error::class.java.simpleName}")
            }
            .getOrNull()
        if (state.isDocumentStartSupported && detectorScripts != null) {
            val libraryRegistered = registerDocumentStartScript(view, state, "library", detectorScripts.library)
            val detectorRegistered = registerDocumentStartScript(view, state, "detector", detectorScripts.smartDetector)
            val bridgeRegistered = registerDocumentStartScript(
                view,
                state,
                "bridge",
                ContentProtectionBridge.buildDocumentStartScript(settings),
            )
            state.isDocumentStartRegistered = libraryRegistered && detectorRegistered && bridgeRegistered
        }
        injectContentProtectionUpdate(view, settings)
    }

    private fun registerDocumentStartScript(
        view: WebView,
        state: ContentProtectionViewState,
        scriptName: String,
        script: String,
    ): Boolean = runCatching {
        // WebViewCompat has no main-frame-only registration option, so keep subframes out of the
        // detector while retaining the all-origin rule needed by arbitrary PWA hosts.
        WebViewCompat.addDocumentStartJavaScript(view, mainFrameOnlyScript(script), setOf("*"))
    }.onSuccess {
        Log.d(TAG, "Content protection document-start registered view=${state.viewName} script=$scriptName")
    }.onFailure { error ->
        Log.w(TAG, "Content protection document-start failed view=${state.viewName} " +
            "script=$scriptName error=${error::class.java.simpleName}")
    }.isSuccess

    private fun mainFrameOnlyScript(script: String): String =
        "if (window === window.top) {\n$script\n}"

    private fun inspectContentProtection(
        view: WebView,
        allowFallback: Boolean = true,
        updateWhenReady: Boolean = true,
    ) {
        val state = contentProtectionViewStates[view] ?: return
        if (state.isStatusCheckInFlight) return
        state.isStatusCheckInFlight = true
        val generation = state.documentGeneration
        runCatching {
            view.evaluateJavascript(CONTENT_PROTECTION_STATUS_SCRIPT) { result ->
                if (state.documentGeneration == generation) {
                    state.isStatusCheckInFlight = false
                    handleContentProtectionStatus(view, state, generation, result, allowFallback, updateWhenReady)
                }
            }
        }.onFailure { error ->
            if (state.documentGeneration == generation) {
                state.isStatusCheckInFlight = false
                Log.w(TAG, "Content protection bootstrap check failed view=${state.viewName} " +
                    "error=${error::class.java.simpleName}")
                if (allowFallback) injectContentProtectionFallback(view, state, generation, null)
            }
        }
    }

    private fun handleContentProtectionStatus(
        view: WebView,
        state: ContentProtectionViewState,
        generation: Int,
        result: String?,
        allowFallback: Boolean,
        updateWhenReady: Boolean,
    ) {
        val status = parseContentProtectionStatus(result)
        if (status == null) {
            Log.w(TAG, "Content protection bootstrap status unavailable view=${state.viewName}")
            if (allowFallback) injectContentProtectionFallback(view, state, generation, null)
            return
        }
        Log.d(TAG, "Content protection bootstrap view=${state.viewName} " +
            "documentStartRegistered=${state.isDocumentStartRegistered} " +
            "library=${status.isLibraryInstalled} bridge=${status.isBridgeInstalled} " +
            "detector=${status.isDetectorInstalled}")
        Log.d(TAG, "Content protection detector view=${state.viewName} state=${status.detectorState} " +
            "backend=${status.detectorBackend} error=${status.hasDetectorError}")
        if (!status.isBootstrapped && allowFallback) {
            injectContentProtectionFallback(view, state, generation, status)
        } else if (status.isBootstrapped && updateWhenReady) {
            injectContentProtectionUpdate(view, contentProtectionSettings)
        }
    }

    private fun injectContentProtectionFallback(
        view: WebView,
        state: ContentProtectionViewState,
        generation: Int,
        status: ContentProtectionBootstrapStatus?,
    ) {
        if (state.documentGeneration != generation || state.isFallbackInjected) return
        val detectorScripts = runCatching { contentProtectionScripts(view.context) }
            .onFailure { error ->
                Log.w(TAG, "Content protection fallback assets failed view=${state.viewName} " +
                    "error=${error::class.java.simpleName}")
            }
            .getOrNull() ?: return
        state.isFallbackInjected = true
        val reason = when {
            !state.isDocumentStartSupported -> "unsupported"
            !state.isDocumentStartRegistered -> "registration-failed"
            else -> "missing-script"
        }
        Log.w(TAG, "Content protection fallback injected view=${state.viewName} reason=$reason")
        val updateAfterInjection = status?.isBridgeInstalled != false
        runCatching {
            view.evaluateJavascript(
                buildFallbackScript(detectorScripts, contentProtectionSettings),
            ) {
                if (state.documentGeneration == generation) {
                    if (updateAfterInjection) injectContentProtectionUpdate(view, contentProtectionSettings)
                    inspectContentProtection(view, allowFallback = false, updateWhenReady = false)
                }
            }
        }.onFailure { error ->
            if (state.documentGeneration == generation) {
                Log.w(TAG, "Content protection fallback evaluation failed view=${state.viewName} " +
                    "error=${error::class.java.simpleName}")
            }
        }
    }

    private fun buildFallbackScript(
        detectorScripts: ContentProtectionScripts,
        settings: ContentProtectionSettings,
    ): String = mainFrameOnlyScript(
        """
        if (!window['Human'] || !window['Human'].Human) {
        ${detectorScripts.library}
        }
        if (!window['__shellifySmartDetection']) {
        ${detectorScripts.smartDetector}
        }
        if (!window['__shellifyContentProtection'] ||
            typeof window['__shellifyContentProtection'].update !== 'function') {
        ${ContentProtectionBridge.buildDocumentStartScript(settings)}
        }
        """.trimIndent(),
    )

    private fun injectContentProtectionUpdate(view: WebView, settings: ContentProtectionSettings) {
        runCatching {
            view.evaluateJavascript(ContentProtectionBridge.buildUpdateScript(settings), null)
        }.onFailure { error ->
            val viewName = contentProtectionViewStates[view]?.viewName ?: "unknown"
            Log.w(TAG, "Content protection update failed view=$viewName error=${error::class.java.simpleName}")
        }
    }

    private fun resetContentProtectionDocument(view: WebView) {
        contentProtectionViewStates[view]?.apply {
            documentGeneration++
            isFallbackInjected = false
            isStatusCheckInFlight = false
        }
    }

    private fun parseContentProtectionStatus(result: String?): ContentProtectionBootstrapStatus? = runCatching {
        val rawResult = result ?: return@runCatching null
        val jsonText = JSONTokener(rawResult).nextValue() as? String ?: return@runCatching null
        val json = JSONObject(jsonText)
        val detectorState = json.optString("state", "unknown")
            .takeIf { it in CONTENT_PROTECTION_DETECTOR_STATES } ?: "unknown"
        val detectorBackend = json.optString("backend", "unknown")
            .takeIf { it in CONTENT_PROTECTION_DETECTOR_BACKENDS } ?: "unknown"
        ContentProtectionBootstrapStatus(
            isLibraryInstalled = json.optBoolean("library"),
            isBridgeInstalled = json.optBoolean("bridge"),
            isDetectorInstalled = json.optBoolean("detector"),
            detectorState = detectorState,
            detectorBackend = detectorBackend,
            hasDetectorError = json.optBoolean("error"),
        )
    }.getOrNull()

    private data class ContentProtectionScripts(
        val library: String,
        val smartDetector: String,
    )

    private class ContentProtectionViewState(val viewName: String) {
        var isDocumentStartSupported = false
        var isDocumentStartRegistered = false
        var isFallbackInjected = false
        var isStatusCheckInFlight = false
        var documentGeneration = 0
    }

    private data class ContentProtectionBootstrapStatus(
        val isLibraryInstalled: Boolean,
        val isBridgeInstalled: Boolean,
        val isDetectorInstalled: Boolean,
        val detectorState: String,
        val detectorBackend: String,
        val hasDetectorError: Boolean,
    ) {
        val isBootstrapped: Boolean
            get() = isLibraryInstalled && isBridgeInstalled && isDetectorInstalled
    }

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

    private fun contentProtectionAssetResponse(url: Uri, viewName: String): WebResourceResponse? {
        val relativePath = url.path?.removePrefix(CONTENT_PROTECTION_ASSET_PREFIX) ?: return null
        if (relativePath == url.path || relativePath.contains("..") || relativePath !in CONTENT_PROTECTION_MODEL_ASSETS) {
            return null
        }
        val context = contentProtectionContext
        if (context == null) {
            Log.w(TAG, "Content protection model request view=$viewName asset=$relativePath result=no-context")
            return null
        }
        val stream: InputStream = runCatching {
            context.assets.open("web_extensions/content_protection/models/$relativePath")
        }.getOrNull() ?: run {
            Log.w(TAG, "Content protection model request view=$viewName asset=$relativePath result=missing")
            return null
        }
        Log.d(TAG, "Content protection model request view=$viewName asset=$relativePath result=served")
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
        contentProtectionViewStates.clear()
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

private val CONTENT_PROTECTION_DETECTOR_STATES = setOf(
    "missing",
    "idle",
    "loading",
    "ready",
    "error",
    "status-error",
    "unknown",
)

private val CONTENT_PROTECTION_DETECTOR_BACKENDS = setOf(
    "none",
    "humangl",
    "webgl",
    "cpu",
    "unknown",
)
