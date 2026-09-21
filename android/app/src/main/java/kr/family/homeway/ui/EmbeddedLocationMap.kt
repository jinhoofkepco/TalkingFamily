package kr.family.homeway.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import kr.family.homeway.BuildConfig
import java.io.ByteArrayInputStream

/** A local, bridge-free map whose only remote resources are public OSM map tiles. */
@Composable
fun EmbeddedLocationMap(
    latitude: Double,
    longitude: Double,
    accuracy: Double,
    modifier: Modifier = Modifier,
    focusToken: String? = null,
) {
    val location = EmbeddedMapPolicy.location(latitude, longitude, accuracy)
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (location == null) {
            Text(
                "표시할 수 있는 위치 정보가 없어요.",
                Modifier.align(Alignment.Center).padding(20.dp),
                textAlign = TextAlign.Center,
            )
        } else if (failed) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("지도를 불러오지 못했어요.\n인터넷 연결을 확인한 뒤 다시 시도해 주세요.", textAlign = TextAlign.Center)
                TextButton(onClick = { loading = true; attempt++; failed = false }) {
                    Text("지도 다시 불러오기")
                }
            }
        } else {
            key(attempt) {
                val lifecycleOwner = LocalLifecycleOwner.current
                val controller = remember {
                    MapViewController(onReady = { loading = false }, onFailure = { failed = true })
                }
                DisposableEffect(lifecycleOwner, controller) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> controller.resume()
                            Lifecycle.Event.ON_PAUSE -> controller.pause()
                            Lifecycle.Event.ON_DESTROY -> controller.release()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        controller.release()
                    }
                }
                AndroidView(
                    factory = { context -> controller.create(context) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { controller.release() },
                    update = {
                        controller.setLocation(location, focusToken)
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            controller.resume()
                        } else {
                            controller.pause()
                        }
                    },
                )
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

private class MapViewController(
    private val onReady: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private var webView: WebView? = null
    private var location: EmbeddedMapLocation? = null
    private var deliveredLocation: EmbeddedMapLocation? = null
    private var focusToken: String? = null
    private var focusRequested = false
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val loadTimeout = Runnable { if (webView != null && !ready) fail() }

    fun create(context: Context): View = try {
        createWebView(context)
    } catch (_: RuntimeException) {
        // A disabled or broken WebView provider must not take down the family screen.
        handler.post { fail() }
        View(context)
    }

    @SuppressLint("SetJavaScriptEnabled") // Only bundled scripts execute; no Java/Kotlin bridge exists.
    private fun createWebView(context: Context): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return MapWebView(context).also { view ->
            webView = view
            // WebView forces HTML percentage heights to zero with WRAP_CONTENT, even
            // when Compose supplies an exact measured height. Use the map card bounds.
            view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            view.contentDescription = "선택한 기록의 위치 지도. 손가락으로 이동하거나 두 손가락으로 확대할 수 있어요."
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.overScrollMode = View.OVER_SCROLL_NEVER
            view.settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(false) // Leaflet handles map zoom; do not zoom the surrounding HTML page.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                domStorageEnabled = false
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = true
                userAgentString = "$userAgentString TalkingFamily/${BuildConfig.VERSION_NAME} (+https://github.com/jinhoofkepco/TalkingFamily)"
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(false)
                setAcceptThirdPartyCookies(view, false)
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (request.method != "GET" || (request.isForMainFrame && !EmbeddedMapPolicy.isDocument(url))) {
                        return blockedResponse()
                    }
                    return when (EmbeddedMapPolicy.resource(url)) {
                        EmbeddedMapPolicy.Resource.BUNDLED_ASSET ->
                            assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
                        // The page's CSP also restricts images to this origin, including redirects.
                        // Keeping these on WebView's network stack preserves normal HTTP tile caching.
                        EmbeddedMapPolicy.Resource.TILE -> null
                        EmbeddedMapPolicy.Resource.BLOCKED -> blockedResponse()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (request.isForMainFrame && request.hasGesture() && EmbeddedMapPolicy.isCopyright(url)) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EmbeddedMapPolicy.COPYRIGHT_URL)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            // The map remains usable when the device has no browser available.
                        }
                    }
                    return !(request.isForMainFrame && EmbeddedMapPolicy.isDocument(url))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    ready = false
                    deliveredLocation = null
                    handler.removeCallbacks(loadTimeout)
                    handler.postDelayed(loadTimeout, 15_000L)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (webView !== view || !EmbeddedMapPolicy.isDocument(url.orEmpty())) return
                    view.evaluateJavascript("Boolean(window.FamilyMap && typeof window.FamilyMap.setLocation === 'function')") { result ->
                        if (webView !== view) return@evaluateJavascript
                        if (result != "true") {
                            fail()
                        } else {
                            ready = true
                            handler.removeCallbacks(loadTimeout)
                            deliverLocation()
                            onReady()
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && webView === view) fail()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && webView === view) fail()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (webView === view) fail()
                    return true
                }
            }
            handler.postDelayed(loadTimeout, 15_000L)
            view.loadUrl(EmbeddedMapPolicy.DOCUMENT_URL)
        }
    }

    fun setLocation(value: EmbeddedMapLocation, focusToken: String?) {
        location = value
        if (this.focusToken != focusToken) {
            this.focusToken = focusToken
            focusRequested = true
        }
        deliverLocation()
    }

    private fun deliverLocation() {
        val value = location ?: return
        val view = webView ?: return
        if (!ready || (deliveredLocation == value && !focusRequested)) return
        val script = buildString {
            if (deliveredLocation != value) append(value.javascriptCall())
            // Focus identity stays native. Only an explicit selection change recentres the map.
            if (focusRequested) append("window.FamilyMap.recenter();")
        }
        view.evaluateJavascript(script, null)
        deliveredLocation = value
        focusRequested = false
    }

    fun resume() { webView?.onResume() }

    fun pause() { webView?.onPause() }

    private fun fail() {
        release()
        onFailure()
    }

    fun release() {
        handler.removeCallbacks(loadTimeout)
        val view = webView ?: return
        webView = null
        ready = false
        deliveredLocation = null
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }
}

private fun blockedResponse() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

private class MapWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility") // WebView dispatches clicks and accessible actions to the HTML document.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
