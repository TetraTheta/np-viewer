package io.github.tetratheta.npviewer.activity

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
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.tetratheta.npviewer.R
import io.github.tetratheta.npviewer.layout.TopSwipeRefreshLayout
import io.github.tetratheta.npviewer.update.UpdateChecker
import io.github.tetratheta.npviewer.update.UpdateNotifier
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
  private lateinit var errorView: LinearLayout
  private lateinit var progressBar: LinearProgressIndicator
  private lateinit var retryButton: Button
  private lateinit var swipeRefresh: TopSwipeRefreshLayout
  private lateinit var webView: WebView

  /** 페이지별 스크롤 위치 캐시 (Least Recentely Used 방식) */
  private val scrollPositions = LinkedHashMap<String, Int>(16, 0.75f, true)

  /** DocumentStartScript API 지원 여부 */
  private val supportsDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
  private var lastBackPress = 0L
  private var restoringFromViewer = false

  companion object {
    private const val MAX_SCROLL_CACHE = 10
    private const val VIEWER_URL_PART = "novelpia.com/viewer/"
  }

  /** JS에서 호출하여 이전 스크롤 위치를 복원하는 인터페이스 */
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

    bindViews()
    setupEdgeToEdge()
    setupWebView()
    setupListeners()
    setupUpdate()

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl(intent?.data?.toString() ?: "https://novelpia.com/mybook")
    }

    setupBackHandler()
  }

  // region 초기화

  private fun bindViews() {
    errorView = findViewById(R.id.error_view)
    progressBar = findViewById(R.id.progress_bar)
    retryButton = findViewById(R.id.retry_button)
    swipeRefresh = findViewById(R.id.swipe_refresh)
    webView = findViewById(R.id.webview)
  }

  private fun setupEdgeToEdge() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  private fun setupWebView() {
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
    }

    webView.addJavascriptInterface(ScrollRestoreInterface(), "_ScrollRestore")

    // DOMContentLoaded에서 스크롤 복원 (DocumentStartScript 지원 시)
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
        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        progressBar.progress = newProgress
      }
    }

    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return false
        if (host.endsWith("novelpia.com")) {
          saveScrollPosition()
          return false
        }
        // 외부 링크는 기본 브라우저로
        startActivity(Intent(Intent.ACTION_VIEW, request.url))
        return true
      }

      override fun onPageFinished(view: WebView, url: String?) {
        swipeRefresh.isRefreshing = false
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE

        // DocumentStartScript 미지원 시 fallback 스크롤 복원
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
  }

  private fun setupListeners() {
    retryButton.setOnClickListener {
      errorView.visibility = View.GONE
      swipeRefresh.visibility = View.VISIBLE
      webView.reload()
    }

    swipeRefresh.setOnRefreshListener { webView.reload() }

    webView.setOnLongClickListener {
      AlertDialog.Builder(this).setItems(arrayOf(getString(R.string.menu_settings))) { _, which ->
        if (which == 0) startActivity(Intent(this, SettingsActivity::class.java))
      }.show()
      true
    }
  }

  private fun setupUpdate() {
    UpdateNotifier.initChannel(this)
    UpdateChecker.processAppUpdate(this)

    val autoCheck = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("auto_check_update", true)
    if (autoCheck) {
      lifecycleScope.launch { UpdateChecker.checkAndNotify(applicationContext) }
    }
  }

  private fun setupBackHandler() {
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

  // endregion

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val url = webView.url ?: ""
    if (url.contains(VIEWER_URL_PART)) {
      val prefs = PreferenceManager.getDefaultSharedPreferences(this)
      if (prefs.getString("volume_behavior", "move_page") == "move_page") {
        val upPrev = prefs.getString("volume_direction", "up_prev") == "up_prev"
        // 볼륨 업/다운에 따라 이전/다음 페이지 클릭
        val selector = when (keyCode) {
          KeyEvent.KEYCODE_VOLUME_UP -> if (upPrev) "#novel_drawing_left" else "#novel_drawing_right"
          KeyEvent.KEYCODE_VOLUME_DOWN -> if (upPrev) "#novel_drawing_right" else "#novel_drawing_left"
          else -> null
        }
        if (selector != null) {
          webView.evaluateJavascript("document.querySelector('$selector')?.click()", null)
          return true
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
    swipeRefresh.triggerFraction = prefs.getString("swipe_fraction", null)?.toFloatOrNull() ?: TopSwipeRefreshLayout.DEFAULT_TRIGGER_FRACTION
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  private fun saveScrollPosition() {
    val url = webView.url ?: return
    if (url.contains(VIEWER_URL_PART)) return
    if (scrollPositions.size >= MAX_SCROLL_CACHE && !scrollPositions.containsKey(url)) {
      scrollPositions.remove(scrollPositions.keys.first())
    }
    scrollPositions[url] = webView.scrollY
  }
}
