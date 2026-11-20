package io.github.tetratheta.npviewer.userscript

data class UserScript(
  val id: String,
  val enabled: Boolean,
  val source: String,
  val sourceHash: String,
  val sourceUrl: String?,
  val installedAt: Long,
  val updatedAt: Long,
  val lastUpdateError: String?,
  val metadata: UserScriptMetadata,
)

data class UserScriptMetadata(
  val name: String,
  val namespace: String,
  val version: String,
  val description: String,
  val matches: List<String>,
  val includes: List<String>,
  val excludes: List<String>,
  val runAt: UserScriptRunAt,
  val updateUrl: String?,
  val downloadUrl: String?,
)

enum class UserScriptRunAt {
  DOCUMENT_START,
  DOCUMENT_END,
  DOCUMENT_IDLE,
}

data class UserScriptUpdateSummary(
  val updated: Int,
  val unchanged: Int,
  val skipped: Int,
  val failed: Int,
)
