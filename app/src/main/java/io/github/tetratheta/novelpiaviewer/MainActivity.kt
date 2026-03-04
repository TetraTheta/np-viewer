package io.github.tetratheta.novelpiaviewer

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {
  private lateinit var errorView: LinearLayout
  private lateinit var retryButton: Button
  private lateinit var swipeRefresh: SwipeRefreshLayout
  private lateinit var webView: WebView
  private var lastBackPress = 0L

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)

    errorView = findViewById(R.id.errorView)
    retryButton = findViewById(R.id.retryButton)
    swipeRefresh = findViewById(R.id.swipeRefresh)
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

    webView.webChromeClient = android.webkit.WebChromeClient()
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
      }

      override fun onPageFinished(view: WebView, url: String?) {
        swipeRefresh.isRefreshing = false
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
      }

      override fun onReceivedError(
        view: WebView, request: WebResourceRequest, error: WebResourceError
      ) {
        if (!request.isForMainFrame) return
        swipeRefresh.isRefreshing = false
        swipeRefresh.visibility = View.GONE
        errorView.visibility = View.VISIBLE
      }
    }

    swipeRefresh.setOnRefreshListener {
      webView.reload()
    }

    retryButton.setOnClickListener {
      errorView.visibility = View.GONE
      swipeRefresh.visibility = View.VISIBLE
      webView.reload()
    }

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl("https://novelpia.com/mybook")
    }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
          webView.goBack()
          return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
          finish()
        } else {
          lastBackPress = now
          android.widget.Toast.makeText(
            this@MainActivity, R.string.press_twice_to_exit, android.widget.Toast.LENGTH_SHORT
          ).show()
        }
      }
    })
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val url = webView.url ?: ""
    if (url.contains(Regex("novelpia\\.com/viewer/"))) {
      when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
          webView.evaluateJavascript(
            "document.querySelector('#novel_drawing_left')?.click()", null
          )
          return true
        }

        KeyEvent.KEYCODE_VOLUME_DOWN -> {
          webView.evaluateJavascript(
            "document.querySelector('#novel_drawing_right')?.click()", null
          )
          return true
        }
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }
}
