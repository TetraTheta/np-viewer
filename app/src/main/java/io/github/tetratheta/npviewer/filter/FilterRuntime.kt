package io.github.tetratheta.npviewer.filter

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import io.github.tetratheta.npviewer.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri

data class FilterCosmeticPayload(
  val css: String, val selectors: List<String>
)

class FilterRuntime private constructor(private val app: Application) {
  companion object {
    private const val TAG = "FilterRuntime"
    private const val BUNDLE_ASSET = "tsurlfilter.bundle.js"

    @Volatile
    private var instance: FilterRuntime? = null

    fun getInstance(context: Context): FilterRuntime = instance ?: synchronized(this) {
      instance ?: FilterRuntime(context.applicationContext as Application).also { instance = it }
    }
  }

  private val repository = FilterRepository(app)
  private val cosmeticCache = ConcurrentHashMap<String, FilterCosmeticPayload>()

  private var sandbox: JavaScriptSandbox? = null
  private var isolate: JavaScriptIsolate? = null
  private var bundleLoaded = false
  private var engineReady = false
  private var currentFingerprint = ""
  private var currentEngineFingerprint = ""
  private var nativeCosmeticRules: List<UserCosmeticRule> = emptyList()

  @Volatile
  private var runtimeUnsupported = false

  @Volatile
  private var unsupportedLogged = false

  @Volatile
  private var runtimeFailed = false

  @Volatile
  private var runtimeFailureLogged = false
  private val imageExtRegex = Regex(".*\\.(png|jpe?g|gif|webp|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)
  private val fontExtRegex = Regex(".*\\.(woff2?|ttf|otf)(\\?.*)?$", RegexOption.IGNORE_CASE)
  private val scriptExtRegex = Regex(".*\\.(js|mjs)(\\?.*)?$", RegexOption.IGNORE_CASE)

  @Synchronized
  fun updateSubscriptions(force: Boolean = false): FilterUpdateSummary = repository.updateSubscriptions(force)

  @Synchronized
  fun refreshEngine(force: Boolean = false) {
    if (!FilterPreferences.isEnabled(app)) {
      engineReady = false
      currentFingerprint = ""
      currentEngineFingerprint = ""
      nativeCosmeticRules = emptyList()
      runtimeUnsupported = false
      unsupportedLogged = false
      runtimeFailed = false
      runtimeFailureLogged = false
      cosmeticCache.clear()
      repository.invalidateRuleCache()
      return
    }
    val snapshot = repository.loadRuleSnapshot(forceReload = force)
    val lists = snapshot.rules
    val fingerprint = snapshot.fingerprint
    if (force || fingerprint != currentFingerprint) {
      nativeCosmeticRules = lists.flatMap(UserCosmeticRules::compile)
      currentFingerprint = fingerprint
      cosmeticCache.clear()
    }

    if (runtimeUnsupported || runtimeFailed) return
    ensureRuntimeLoaded()
    if (!force && engineReady && fingerprint == currentEngineFingerprint) return

    val payload = JSONObject().put("lists", JSONArray(lists)).toString()
    evaluateFile(writeScriptFile("init", "globalThis.NPFilterRuntimeApi.init(${JSONObject.quote(payload)});"))
    engineReady = true
    currentEngineFingerprint = fingerprint
    cosmeticCache.clear()
  }

  @Synchronized
  fun getCosmeticForUrl(url: String): FilterCosmeticPayload = cosmeticCache[normalizeUrl(url)] ?: FilterCosmeticPayload("", emptyList())

  @Synchronized
  fun preparePage(url: String, sourceUrl: String?) {
    val normalizedUrl = normalizeUrl(url)
    if (!FilterPreferences.isEnabled(app) || !repository.hasAnyActiveSource()) {
      cosmeticCache.remove(normalizedUrl)
      return
    }
    val nativePayload = UserCosmeticRules.createPayload(normalizedUrl, nativeCosmeticRules)
    if (runtimeUnsupported || runtimeFailed) {
      cosmeticCache[normalizedUrl] = nativePayload
      return
    }
    runCatching {
      refreshEngine()
      if (!engineReady) {
        cosmeticCache[normalizedUrl] = nativePayload
        return
      }
      cosmeticCache[normalizedUrl] = mergePayloads(
        getCosmeticPayload(normalizedUrl, sourceUrl), nativePayload
      )
    }.onFailure {
      markRuntimeFailure("preparePage($url)", it)
      cosmeticCache[normalizedUrl] = nativePayload
    }
  }

  @Synchronized
  fun maybeBlock(request: WebResourceRequest): WebResourceResponse? {
    if (!FilterPreferences.isEnabled(app) || !repository.hasAnyActiveSource()) return null
    if (runtimeUnsupported || runtimeFailed) return null
    return runCatching {
      refreshEngine()
      if (!engineReady) return null
      val sourceUrl = request.requestHeaders["Referer"]
      val requestType = resolveRequestType(request)
      val payload =
        JSONObject().put("url", request.url.toString()).put("sourceUrl", sourceUrl ?: JSONObject.NULL).put("requestType", requestType).toString()
      val result = JSONObject(evaluate("globalThis.NPFilterRuntimeApi.match(${JSONObject.quote(payload)});"))
      if (result.optBoolean("blocked")) emptyResponse() else null
    }.getOrElse {
      markRuntimeFailure("match(${request.url})", it)
      null
    }
  }

  fun isSupported(): Boolean = JavaScriptSandbox.isSupported()

  private fun getCosmeticPayload(url: String, sourceUrl: String?): FilterCosmeticPayload {
    val payload = JSONObject().put("url", url).put("sourceUrl", sourceUrl ?: JSONObject.NULL).toString()
    val result = JSONObject(evaluate("globalThis.NPFilterRuntimeApi.cosmetic(${JSONObject.quote(payload)});"))
    val selectors = result.optJSONArray("selectors") ?: JSONArray()
    return FilterCosmeticPayload(
      css = result.optString("css"),
      selectors = List(selectors.length()) { index -> selectors.optString(index) }.filter { it.isNotBlank() })
  }

  private fun ensureRuntimeLoaded() {
    if (!JavaScriptSandbox.isSupported()) {
      runtimeUnsupported = true
      engineReady = false
      cosmeticCache.clear()
      if (!unsupportedLogged) {
        unsupportedLogged = true
        Log.w(TAG, app.getString(R.string.msg_filter_runtime_unsupported))
      }
      return
    }
    if (sandbox == null) {
      sandbox = runCatching {
        JavaScriptSandbox.createConnectedInstanceAsync(app).get()
      }.getOrElse {
        markRuntimeFailure("createSandbox", it)
        return
      }
    }
    if (isolate == null) {
      isolate = runCatching {
        sandbox!!.createIsolate()
      }.getOrElse {
        markRuntimeFailure("createIsolate", it)
        return
      }
    }
    if (!bundleLoaded) {
      val currentSandbox = sandbox ?: error("JavaScript sandbox unavailable")
      val jsIsolate = isolate ?: error("JavaScript isolate unavailable")
      runCatching {
        if (currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD)) {
          app.assets.openFd(BUNDLE_ASSET).use { afd ->
            jsIsolate.evaluateJavaScriptAsync(afd).get()
          }
        } else {
          val code = app.assets.open(BUNDLE_ASSET).bufferedReader().use { it.readText() }
          jsIsolate.evaluateJavaScriptAsync(code).get()
        }
      }.getOrElse {
        markRuntimeFailure("loadBundle", it)
        return
      }
      bundleLoaded = true
    }
  }

  private fun evaluate(script: String): String = isolate?.evaluateJavaScriptAsync(script)?.get().orEmpty()

  private fun evaluateFile(file: File): String {
    val currentSandbox = sandbox ?: error("JavaScript sandbox unavailable")
    val jsIsolate = isolate ?: error("JavaScript isolate unavailable")
    return if (currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD)) {
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        jsIsolate.evaluateJavaScriptAsync(pfd).get()
      }
    } else {
      jsIsolate.evaluateJavaScriptAsync(file.readText()).get()
    }
  }

  @Suppress("SameParameterValue")
  private fun writeScriptFile(prefix: String, script: String): File {
    val file = File(app.cacheDir, "$prefix-filter-runtime.js")
    FileOutputStream(file).bufferedWriter(StandardCharsets.UTF_8).use { it.write(script) }
    return file
  }

  private fun resolveRequestType(request: WebResourceRequest): Int {
    if (request.isForMainFrame) return 1
    val destination = request.requestHeaders["Sec-Fetch-Dest"].orEmpty()
    return when {
      destination == "iframe" -> 2
      destination == "script" -> 4
      destination == "style" -> 8
      destination == "image" -> 32
      destination == "font" -> 256
      destination == "video" || destination == "audio" -> 128
      destination == "object" || destination == "embed" -> 16
      destination == "empty" -> 64
      request.url.toString().endsWith(".css", ignoreCase = true) -> 8
      request.url.toString().matches(imageExtRegex) -> 32
      request.url.toString().matches(fontExtRegex) -> 256
      request.url.toString().matches(scriptExtRegex) -> 4
      else -> 4096
    }
  }

  private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))
  )

  private fun normalizeUrl(url: String): String = runCatching {
    url.toUri().buildUpon().fragment(null).build().toString()
  }.getOrDefault(url)

  private fun mergePayloads(primary: FilterCosmeticPayload, fallback: FilterCosmeticPayload): FilterCosmeticPayload {
    val selectors = (primary.selectors + fallback.selectors).distinct()
    val css = buildList {
      if (primary.css.isNotBlank()) add(primary.css)
      if (fallback.css.isNotBlank()) add(fallback.css)
    }.joinToString("\n")
    return FilterCosmeticPayload(css = css, selectors = selectors)
  }

  private fun markRuntimeFailure(stage: String, throwable: Throwable) {
    engineReady = false
    runtimeFailed = true
    currentEngineFingerprint = ""
    cosmeticCache.clear()
    if (!runtimeFailureLogged) {
      runtimeFailureLogged = true
      Log.w(TAG, "Filter runtime disabled after $stage failure: ${summarizeThrowable(throwable)}")
    }
  }

  private fun summarizeThrowable(throwable: Throwable): String {
    val message = throwable.message.orEmpty().replace(Regex("\\s+"), " ").trim()
    val looksLikeBundledCode =
      message.length > 120 && (message.count { it == ';' } > 3 || message.count { it == '{' } > 3 || message.contains("class "))
    val summary = when {
      message.isBlank() -> ""
      looksLikeBundledCode -> "JavaScript bundle evaluation failed"
      message.length > 160 -> "${message.take(160)}..."
      else -> message
    }
    return "${throwable::class.java.simpleName}${if (summary.isNotEmpty()) ": $summary" else ""}"
  }
}
