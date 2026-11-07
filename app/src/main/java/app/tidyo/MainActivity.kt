package app.tidyo

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var holder: FrameLayout
    private lateinit var bridge: Bridge

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        holder = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#F1ECE1")) }
        setContentView(holder)
        ViewCompat.setOnApplyWindowInsetsListener(holder) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, maxOf(nav.bottom, ime.bottom))
            insets
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
        }
        holder.addView(web)

        bridge = Bridge(this)
        web.addJavascriptInterface(bridge, "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back && window.__back()") { r ->
                    if (r != "true") finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) js("window.__emit && window.__emit('resume', '{}')")
    }

    fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    fun setBars(bg: String, lightIcons: Boolean) {
        val c = Color.parseColor(bg)
        holder.setBackgroundColor(c)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = !lightIcons
        ctl.isAppearanceLightNavigationBars = !lightIcons
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.removeJavascriptInterface("Android"); web.destroy() }
        super.onDestroy()
    }
}
