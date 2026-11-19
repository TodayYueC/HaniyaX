package dev.haniya.x.dsh

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.haniya.x.App

/**
 * One WebView for the whole process, so DSH keeps its in-page state (open session, scroll, drafts) while the
 * user switches to a terminal tab and back. It is re-parented into the current Activity when shown.
 */
@SuppressLint("SetJavaScriptEnabled")
object DshWebView {
    private val ctx by lazy { MutableContextWrapper(App.context) }
    private var web: WebView? = null
    private var themeHandler: ScriptHandler? = null
    private var themeScript: String = ""

    /** Set by the screen; receives file-chooser requests (callback, accept types). */
    var onFileChooser: ((ValueCallback<Array<Uri>>, Array<String>) -> Unit)? = null
    var onPageState: ((loading: Boolean, url: String?) -> Unit)? = null

    /** Currently loaded authenticated base (to avoid re-exchanging the token on every visit). */
    var authedFor: String? = null

    fun obtain(activity: Activity): WebView {
        ctx.baseContext = activity
        val w = web ?: create().also { web = it }
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    /** Detaches from the Activity (keeps the page alive). */
    fun release() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ctx.baseContext = App.context
    }

    fun current(): WebView? = web

    private fun create(): WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        settings.textZoom = 100
        CookieManager.getInstance().setAcceptCookie(true)
        isVerticalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val local = uri.host == "127.0.0.1" || uri.host == "localhost"
                if (local && (uri.scheme == "http" || uri.scheme == "https")) return false
                // Anything else (docs, provider consoles, OAuth pages) opens outside the app.
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) { }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (themeHandler == null && themeScript.isNotEmpty()) view.evaluateJavascript(themeScript, null)
                onPageState?.invoke(true, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (themeScript.isNotEmpty()) view.evaluateJavascript(themeScript, null)
                onPageState?.invoke(false, url)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val handler = onFileChooser ?: return false
                handler(callback, params.acceptTypes ?: emptyArray())
                return true
            }
        }
    }

    /** Applies the Relief theme now and for every future document (document-start script when supported). */
    fun setTheme(script: String) {
        if (script == themeScript) return
        themeScript = script
        val w = web ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            themeHandler?.remove()
            themeHandler = WebViewCompat.addDocumentStartJavaScript(w, script, setOf("http://127.0.0.1:${DshManager.PORT}"))
        }
        w.evaluateJavascript(script, null)
    }

    /** (Re)authenticates with [token] and loads the app; used on first show and by 重新加载. */
    fun load(token: String) {
        val w = web ?: return
        authedFor = "${DshManager.baseUrl}#$token"
        w.stopLoading()
        // GET /?token= mints the auth cookie and redirects to the app.
        w.loadUrl("${DshManager.baseUrl}/?token=${android.net.Uri.encode(token)}")
    }

    /** Ensures the document-start script is registered after the WebView was created. */
    fun installThemeIfNeeded() {
        val s = themeScript
        if (s.isEmpty() || web == null) return
        themeScript = ""
        setTheme(s)
    }
}
