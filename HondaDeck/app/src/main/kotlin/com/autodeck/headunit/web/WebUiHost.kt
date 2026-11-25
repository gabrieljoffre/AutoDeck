package com.autodeck.headunit.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.autodeck.headunit.BuildConfig
import com.autodeck.headunit.service.HeadUnitRuntime

private const val TAG = "WebUiHost"
private const val ENTRY_URL = "https://appassets.androidplatform.net/assets/web/index.html"
private const val ALLOWED_HOST = "appassets.androidplatform.net"

/**
 * Hospeda a interface web Órbita em WebView em tela cheia usando `WebViewAssetLoader`
 * para servir `/assets/`, `/cover/`, `/tiles/` e `/labels/` na mesma origem segura.
 * Microfone web fica bloqueado (reconhecimento de voz é 100% nativo no Kotlin).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUiHost(
    runtime: HeadUnitRuntime,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val holder = remember(context, runtime) {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/cover/", runtime.tileRouter.coverHandler)
            .addPathHandler("/tiles/", runtime.tileRouter.tilesHandler)
            .addPathHandler("/labels/", runtime.tileRouter.labelsHandler)
            .build()

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#06090F"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host.orEmpty()
                    return host != ALLOWED_HOST
                }
            }

            webChromeClient = object : WebChromeClient() {
                // Voz é nativa no Kotlin: nunca liberar microfone/câmera para páginas web
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e(
                            TAG,
                            "JS [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]: ${consoleMessage.message()}"
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }

        val bridge = WebBridge(
            activityContext = context,
            runtime = runtime,
            webView = webView
        )
        webView.addJavascriptInterface(bridge, "HondaBridge")
        bridge.start()
        webView.loadUrl(ENTRY_URL)

        WebHostHolder(webView = webView, bridge = bridge)
    }

    DisposableEffect(holder) {
        onDispose {
            holder.bridge.detach()
            holder.webView.apply {
                stopLoading()
                removeJavascriptInterface("HondaBridge")
                destroy()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { holder.webView }
    )
}

private class WebHostHolder(
    val webView: WebView,
    val bridge: WebBridge
)
