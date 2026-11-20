package io.github.tetratheta.npviewer.userscript

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class UserScriptRepository(
  context: Context,
) {
  private val file = AtomicFile(File(context.filesDir, "userscripts.json"))

  @Synchronized
  fun getAll(): List<UserScript> {
    if (!file.baseFile.exists()) return emptyList()
    return file.openRead().bufferedReader(Charsets.UTF_8).use { reader -> parseArray(JSONArray(reader.readText())) }
  }

  @Synchronized
  fun install(
    source: String,
    sourceUrl: String?,
    replaceId: String? = null,
  ): UserScript {
    sourceUrl?.let(UserScriptParser::requireHttpsUrl)
    val metadata = UserScriptParser.parse(source)
    val now = System.currentTimeMillis()
    val existing = getAll()
    val replacement = replaceId?.let { id -> existing.firstOrNull { it.id == id } }
    val script =
      UserScript(
        id = replacement?.id ?: UUID.randomUUID().toString(),
        enabled = replacement?.enabled ?: true,
        source = source,
        sourceHash = UserScriptParser.sha256(source),
        sourceUrl = sourceUrl ?: replacement?.sourceUrl,
        installedAt = replacement?.installedAt ?: now,
        updatedAt = now,
        lastUpdateError = null,
        metadata = metadata,
      )
    saveAll(if (replacement == null) existing + script else existing.map { if (it.id == replacement.id) script else it })
    return script
  }

  fun findDuplicate(metadata: UserScriptMetadata): UserScript? =
    getAll().firstOrNull { it.metadata.name == metadata.name && it.metadata.namespace == metadata.namespace }

  fun setEnabled(
    id: String,
    enabled: Boolean,
  ) = saveAll(getAll().map { if (it.id == id) it.copy(enabled = enabled) else it })

  fun delete(id: String) = saveAll(getAll().filterNot { it.id == id })

  @Synchronized
  fun saveAll(scripts: List<UserScript>) {
    val output = file.startWrite()
    try {
      output.write(toJson(scripts).toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(output)
    } catch (error: Exception) {
      file.failWrite(output)
      throw error
    }
  }

  fun validateAndRestore(scripts: List<UserScript>) {
    scripts.forEach { script ->
      require(UserScriptParser.parse(script.source) == script.metadata) { "Invalid user script backup" }
      require(UserScriptParser.sha256(script.source) == script.sourceHash) { "Invalid user script hash" }
      script.sourceUrl?.let(UserScriptParser::requireHttpsUrl)
    }
    require(scripts.map(UserScript::id).distinct().size == scripts.size) { "Duplicate user script id" }
    saveAll(scripts)
  }

  companion object {
    fun toJson(scripts: List<UserScript>): JSONArray = JSONArray().apply { scripts.forEach { put(it.toJson()) } }

    fun parseArray(array: JSONArray): List<UserScript> =
      buildList {
        for (index in 0 until array.length()) add(array.getJSONObject(index).toUserScript())
      }.also { scripts ->
        require(scripts.map(UserScript::id).distinct().size == scripts.size) { "Duplicate user script id" }
        scripts.forEach { script ->
          require(UserScriptParser.parse(script.source) == script.metadata) { "Invalid user script data" }
          require(UserScriptParser.sha256(script.source) == script.sourceHash) { "Invalid user script hash" }
          script.sourceUrl?.let(UserScriptParser::requireHttpsUrl)
        }
      }

    private fun UserScript.toJson(): JSONObject =
      JSONObject()
        .put("id", id)
        .put("enabled", enabled)
        .put("source", source)
        .put("sourceHash", sourceHash)
        .put("sourceUrl", sourceUrl)
        .put("installedAt", installedAt)
        .put("updatedAt", updatedAt)
        .put("lastUpdateError", lastUpdateError)
        .put("metadata", metadata.toJson())

    private fun UserScriptMetadata.toJson(): JSONObject =
      JSONObject()
        .put("name", name)
        .put("namespace", namespace)
        .put("version", version)
        .put("description", description)
        .put("matches", JSONArray(matches))
        .put("includes", JSONArray(includes))
        .put("excludes", JSONArray(excludes))
        .put("runAt", runAt.name)
        .put("updateUrl", updateUrl)
        .put("downloadUrl", downloadUrl)

    private fun JSONObject.toUserScript(): UserScript =
      UserScript(
        id = getString("id"),
        enabled = getBoolean("enabled"),
        source = getString("source"),
        sourceHash = getString("sourceHash"),
        sourceUrl = optStringOrNull("sourceUrl"),
        installedAt = getLong("installedAt"),
        updatedAt = getLong("updatedAt"),
        lastUpdateError = optStringOrNull("lastUpdateError"),
        metadata = getJSONObject("metadata").toMetadata(),
      )

    private fun JSONObject.toMetadata(): UserScriptMetadata =
      UserScriptMetadata(
        name = getString("name"),
        namespace = getString("namespace"),
        version = getString("version"),
        description = getString("description"),
        matches = getJSONArray("matches").strings(),
        includes = getJSONArray("includes").strings(),
        excludes = getJSONArray("excludes").strings(),
        runAt = UserScriptRunAt.valueOf(getString("runAt")),
        updateUrl = optStringOrNull("updateUrl"),
        downloadUrl = optStringOrNull("downloadUrl"),
      )

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
  }
}
