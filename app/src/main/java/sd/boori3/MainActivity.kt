package sd.boori3

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var synth: Synth

    /** الجسر بين الصفحة والمحرك الصوتي الأصيل */
    inner class Bridge {
        @JavascriptInterface fun setPresets(json: String) { synth.setPresets(json) }
        @JavascriptInterface fun noteOn(id: String, freq: Double, preset: Int) { synth.noteOn(id, freq.toFloat(), preset) }
        @JavascriptInterface fun noteOff(id: String) { synth.noteOff(id) }
        @JavascriptInterface fun setVolume(v: Double) { synth.setVolume(v.toFloat()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        synth = Synth(this)
        synth.start()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.setBackgroundColor(0xFF121110.toInt())
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("file:///android_asset/index.html")
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { r ->
            if (r != "true") finish()
        }
    }

    override fun onPause() { super.onPause(); web.onPause(); synth.stop() }
    override fun onResume() { super.onResume(); web.onResume(); synth.start() }
    override fun onDestroy() { super.onDestroy(); synth.stop() }
}
