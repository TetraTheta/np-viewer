package io.github.tetratheta.npviewer.userscript

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class UserScriptRuntime(
  private val repository: UserScriptRepository,
) {
  private var startScriptHandler: ScriptHandler? = null

  fun refresh(webView: WebView) {
    startScriptHandler?.remove()
    startScriptHandler = null
    if (!UserScriptPreferences.isEnabled(webView.context)) return
    val scripts = repository.getAll().filter { it.enabled && it.metadata.runAt == UserScriptRunAt.DOCUMENT_START }
    if (scripts.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    startScriptHandler =
      WebViewCompat.addDocumentStartJavaScript(
        webView,
        scripts.joinToString("\n") { wrap(it, checkUrlInJavaScript = true) },
        setOf("https://novelpia.com", "https://*.novelpia.com"),
      )
  }

  fun runPageFinished(
    webView: WebView,
    url: String,
  ) {
    if (!UserScriptPreferences.isEnabled(webView.context)) return
    val scripts = repository.getAll().filter { it.enabled && UserScriptParser.matches(it.metadata, url) }
    val documentStartUnsupported = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    scripts
      .filter {
        when (it.metadata.runAt) {
          UserScriptRunAt.DOCUMENT_START -> documentStartUnsupported
          UserScriptRunAt.DOCUMENT_END -> true
          UserScriptRunAt.DOCUMENT_IDLE -> false
        }
      }.forEach { webView.evaluateJavascript(wrap(it), null) }
    scripts.filter { it.metadata.runAt == UserScriptRunAt.DOCUMENT_IDLE }.forEach { script ->
      val body = wrap(script)
      webView.evaluateJavascript("(window.requestIdleCallback||function(f){setTimeout(f,1)})(function(){$body});", null)
    }
  }

  private fun wrap(
    script: UserScript,
    checkUrlInJavaScript: Boolean = false,
  ): String {
    val condition = if (checkUrlInJavaScript) "&&${UserScriptParser.javascriptMatchExpression(script.metadata)}" else ""
    val execution =
      "try{if(window.top===window$condition){(function(){${script.source}\n}).call(window)}}" +
        "catch(e){console.error('NP Viewer userscript',e)}"
    if (!checkUrlInJavaScript) return execution
    return "(function(run){if(document.documentElement){run();return}const observer=new MutationObserver(function(){" +
      "if(document.documentElement){observer.disconnect();run()}});observer.observe(document,{childList:true})})(function(){$execution});"
  }

  fun logFailure(error: Throwable) {
    Log.e("UserScriptRuntime", "Failed to load user scripts", error)
  }
}
