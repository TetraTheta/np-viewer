package io.github.tetratheta.npviewer.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// object: Singleton class
object UpdateChecker {
  const val KEY_APK_PATH = "apk_path"
  const val KEY_APK_VERSION = "apk_version"
  const val KEY_DOWNLOAD_ID = "download_id"
  const val KEY_PENDING_URL = "pending_download_url"
  private const val API_URL = "https://api.github.com/repos/TetraTheta/np-viewer/releases/latest"
  private const val CACHE_TTL_MS = 3_600_000L // 1 hour
  private const val KEY_DOWNLOAD_URL = "cache_download_url"
  private const val KEY_TIMESTAMP = "cache_timestamp"
  private const val KEY_VERSION = "cache_version"
  private const val PREFS_NAME = "update_prefs"

  fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun hasFreshCache(context: Context): Boolean {
    val timestamp = prefs(context).getLong(KEY_TIMESTAMP, -1L)
    return timestamp != -1L && System.currentTimeMillis() - timestamp <= CACHE_TTL_MS
  }

  fun getCachedInfo(context: Context): UpdateInfo? {
    val p = prefs(context)
    val version = p.getString(KEY_VERSION, "") ?: ""
    if (version.isEmpty()) return null
    val url = p.getString(KEY_DOWNLOAD_URL, "") ?: ""
    if (url.isEmpty()) return null
    return UpdateInfo(version, url)
  }

  fun saveCache(context: Context, result: UpdateResult) {
    prefs(context).edit {
      putLong(KEY_TIMESTAMP, System.currentTimeMillis())
      when (result) {
        is UpdateResult.Available -> putString(KEY_VERSION, result.info.version)
          .putString(KEY_DOWNLOAD_URL, result.info.downloadUrl)
          .putString(KEY_PENDING_URL, result.info.downloadUrl)

        is UpdateResult.UpToDate -> putString(KEY_VERSION, "").putString(KEY_DOWNLOAD_URL, "")
        is UpdateResult.Error -> return // don't cache errors
      }
    }
  }

  suspend fun fetchLatest(context: Context): UpdateResult = withContext(Dispatchers.IO) {
    try {
      val conn = URL(API_URL).openConnection() as HttpURLConnection
      conn.requestMethod = "GET"
      conn.setRequestProperty("Accept", "application/vnd.github+json")
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000

      if (conn.responseCode != 200) {
        conn.disconnect()
        return@withContext UpdateResult.Error
      }

      val json = JSONObject(conn.inputStream.bufferedReader().readText())
      conn.disconnect()

      val tagName = json.getString("tag_name").removePrefix("v")
      val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: return@withContext UpdateResult.Error

      if (!isNewer(tagName, currentVersion)) return@withContext UpdateResult.UpToDate

      val assets = json.getJSONArray("assets")
      val downloadUrl =
        (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
          ?.getString("browser_download_url") ?: return@withContext UpdateResult.Error

      UpdateResult.Available(UpdateInfo(tagName, downloadUrl))
    } catch (_: Exception) {
      UpdateResult.Error
    }
  }

  fun isNewer(remote: String, current: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, c.size)) {
      val rv = r.getOrElse(i) { 0 }
      val cv = c.getOrElse(i) { 0 }
      if (rv != cv) return rv > cv
    }
    return false
  }
}
