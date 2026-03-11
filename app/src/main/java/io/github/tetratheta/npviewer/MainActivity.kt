package io.github.tetratheta.npviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {
  private lateinit var errorView: LinearLayout
  private lateinit var progressBar: LinearProgressIndicator
  private lateinit var retryButton: Button
  private lateinit var swipeRefresh: TopSwipeRefreshLayout
  private lateinit var webView: WebView
  private val scrollPositions = LinkedHashMap<String, Int>(16, 0.75f, true)
  private val supportsDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
  private var lastBackPress = 0L
  private var restoringFromViewer = false

  companion object {
    private const val MAX_SCROLL_CACHE_SIZE = 50
    private const val VIEWER_URL_PART = "novelpia.com/viewer/"
  }

  inner class ScrollRestoreInterface {
    @JavascriptInterface
    fun getScrollY(url: String): Int {
      if (!restoringFromViewer) return 0
      restoringFromViewer = false
      return scrollPositions[url] ?: 0
    }
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)

    errorView = findViewById(R.id.error_view)
    progressBar = findViewById(R.id.progress_bar)
    retryButton = findViewById(R.id.retry_button)
    swipeRefresh = findViewById(R.id.swipe_refresh)
    webView = findViewById(R.id.webview)

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
    }

    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
    }

    webView.addJavascriptInterface(ScrollRestoreInterface(), "_ScrollRestore")

    if (supportsDocumentStartScript) {
      WebViewCompat.addDocumentStartJavaScript(
        webView, """
        (function () {
          var y = window._ScrollRestore ? window._ScrollRestore.getScrollY(location.href) : 0;
          if (y > 0) {
            document.addEventListener('DOMContentLoaded', function () {
              window.scrollTo(0, y);
            }, { once: true });
          }
        })();
      """.trimIndent(), setOf("*")
      )
    }

    webView.webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (newProgress < 100) {
          progressBar.visibility = View.VISIBLE
          progressBar.progress = newProgress
        } else {
          progressBar.visibility = View.GONE
        }
      }
    }

    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return false
        return if (host.endsWith("novelpia.com")) {
          saveScrollPosition()
          false
        } else {
          startActivity(Intent(Intent.ACTION_VIEW, request.url))
          true
        }
      }

      override fun onPageFinished(view: WebView, url: String?) {
        swipeRefresh.isRefreshing = false
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE

        if (!supportsDocumentStartScript && restoringFromViewer) {
          restoringFromViewer = false
          url?.let { scrollPositions[it]?.let { y -> view.scrollTo(0, y) } }
        }
      }

      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        swipeRefresh.isRefreshing = false
        swipeRefresh.visibility = View.GONE
        errorView.visibility = View.VISIBLE
      }
    }

    retryButton.setOnClickListener {
      errorView.visibility = View.GONE
      swipeRefresh.visibility = View.VISIBLE
      webView.reload()
    }

    swipeRefresh.setOnRefreshListener {
      webView.reload()
    }

    webView.setOnLongClickListener {
      AlertDialog.Builder(this).setItems(arrayOf(getString(R.string.menu_settings))) { _, which ->
        if (which == 0) startActivity(Intent(this, SettingsActivity::class.java))
      }.show()
      true
    }

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      val deepLink = intent?.data?.toString()
      webView.loadUrl(deepLink ?: "https://novelpia.com/mybook")
    }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
          if (webView.url?.contains(VIEWER_URL_PART) == true) {
            restoringFromViewer = true
          }
          saveScrollPosition()
          webView.goBack()
          return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
          finish()
        } else {
          lastBackPress = now
          Toast.makeText(this@MainActivity, R.string.press_twice_to_exit, Toast.LENGTH_SHORT).show()
        }
      }
    })
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val url = webView.url ?: ""
    if (url.contains(VIEWER_URL_PART)) {
      val prefs = PreferenceManager.getDefaultSharedPreferences(this)
      if (prefs.getString("volume_behavior", "move_page") == "move_page") {
        val upPrev = prefs.getString("volume_direction", "up_prev") == "up_prev"
        when (keyCode) {
          KeyEvent.KEYCODE_VOLUME_UP -> {
            val sel = if (upPrev) "#novel_drawing_left" else "#novel_drawing_right"
            webView.evaluateJavascript("document.querySelector('$sel')?.click()", null)
            return true
          }

          KeyEvent.KEYCODE_VOLUME_DOWN -> {
            val sel = if (upPrev) "#novel_drawing_right" else "#novel_drawing_left"
            webView.evaluateJavascript("document.querySelector('$sel')?.click()", null)
            return true
          }
        }
      }

    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { webView.loadUrl(it.toString()) }
  }

  override fun onResume() {
    super.onResume()
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    swipeRefresh.triggerFraction =
      prefs.getString("swipe_fraction", null)?.toFloatOrNull() ?: TopSwipeRefreshLayout.DEFAULT_TRIGGER_FRACTION
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  private fun saveScrollPosition() {
    val url = webView.url ?: return
    if (url.contains(VIEWER_URL_PART)) return
    val scrollY = webView.scrollY
    if (scrollPositions.size >= MAX_SCROLL_CACHE_SIZE && !scrollPositions.containsKey(url)) {
      scrollPositions.remove(scrollPositions.keys.first())
    }
    scrollPositions[url] = scrollY
  }
}
