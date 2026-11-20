package io.github.tetratheta.npviewer.userscript

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.net.HttpURLConnection
import java.net.URL

class UserScriptUpdater(
  private val context: Context,
  private val repository: UserScriptRepository = UserScriptRepository(context),
) {
  fun updateAll(): UserScriptUpdateSummary {
    var updated = 0
    var unchanged = 0
    var skipped = 0
    var failed = 0
    val scripts = repository.getAll().toMutableList()
    scripts.indices.forEach { index ->
      val current = scripts[index]
      val updateUrl = current.metadata.downloadUrl ?: current.metadata.updateUrl ?: current.sourceUrl
      if (updateUrl == null) {
        skipped++
        return@forEach
      }
      runCatching {
        val source = download(updateUrl)
        val metadata = UserScriptParser.parse(source)
        val sourceHash = UserScriptParser.sha256(source)
        if (sourceHash == current.sourceHash) {
          scripts[index] = current.copy(lastUpdateError = null)
          unchanged++
        } else {
          scripts[index] =
            current.copy(
              source = source,
              sourceHash = sourceHash,
              metadata = metadata,
              updatedAt = System.currentTimeMillis(),
              lastUpdateError = null,
            )
          updated++
        }
      }.onFailure { error ->
        scripts[index] = current.copy(lastUpdateError = error.message ?: error.javaClass.simpleName)
        failed++
      }
    }
    repository.saveAll(scripts)
    val summary = UserScriptUpdateSummary(updated, unchanged, skipped, failed)
    PreferenceManager.getDefaultSharedPreferences(context).edit {
      if (summary.failed == 0) putLong(UserScriptPreferences.KEY_LAST_UPDATED_AT, System.currentTimeMillis())
      putString(UserScriptPreferences.KEY_LAST_UPDATE_SUMMARY, "${summary.updated}/${summary.unchanged}/${summary.skipped}/${summary.failed}")
    }
    return summary
  }

  fun download(url: String): String {
    UserScriptParser.requireHttpsUrl(url)
    var current = URL(url)
    var redirects = 0
    while (true) {
      val connection =
        (current.openConnection() as HttpURLConnection).apply {
          connectTimeout = 15_000
          readTimeout = 15_000
          instanceFollowRedirects = false
          setRequestProperty("User-Agent", "NPViewer UserScript")
        }
      try {
        if (connection.responseCode in 300..399) {
          require(redirects++ < MAX_REDIRECTS) { "Too many redirects" }
          val next = connection.getHeaderField("Location") ?: error("Redirect location is missing")
          current = URL(current, next)
          UserScriptParser.requireHttpsUrl(current.toString())
          continue
        }
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        return connection.inputStream.use(UserScriptParser::readUtf8)
      } finally {
        connection.disconnect()
      }
    }
  }

  companion object {
    private const val MAX_REDIRECTS = 5
  }
}
