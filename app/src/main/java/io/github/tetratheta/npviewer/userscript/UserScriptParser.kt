package io.github.tetratheta.npviewer.userscript

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object UserScriptParser {
  const val MAX_SCRIPT_BYTES = 1024 * 1024
  private val headerRegex = Regex("(?s)//\\s*==UserScript==\\s*(.*?)//\\s*==/UserScript==")
  private val metadataRegex = Regex("^\\s*//\\s*@([\\w-]+)\\s*(.*?)\\s*$")
  private val matchPatternRegex = Regex("^(\\*|https?)://(\\*|\\*\\.[^/]+|[^/]+)/.*$")

  fun parse(source: String): UserScriptMetadata {
    require(source.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) { "Script exceeds 1 MiB" }
    val header = requireNotNull(headerRegex.find(source)) { "UserScript header is missing" }.groupValues[1]
    val values = linkedMapOf<String, MutableList<String>>()
    header.lineSequence().forEach { line ->
      val match = metadataRegex.matchEntire(line) ?: return@forEach
      values.getOrPut(match.groupValues[1].lowercase()) { mutableListOf() }.add(match.groupValues[2].trim())
    }

    val name = values["name"]?.firstOrNull().orEmpty()
    require(name.isNotBlank()) { "@name is required" }
    val matches = values["match"].orEmpty().filter(String::isNotBlank)
    val includes = values["include"].orEmpty().filter(String::isNotBlank)
    val excludes = values["exclude"].orEmpty().filter(String::isNotBlank)
    matches.forEach(::compileMatchPattern)
    (includes + excludes).forEach(::compileGlobPattern)
    val updateUrl = values["updateurl"]?.firstOrNull()?.takeIf(String::isNotBlank)?.also(::requireHttpsUrl)
    val downloadUrl = values["downloadurl"]?.firstOrNull()?.takeIf(String::isNotBlank)?.also(::requireHttpsUrl)

    return UserScriptMetadata(
      name = name,
      namespace = values["namespace"]?.firstOrNull().orEmpty(),
      version = values["version"]?.firstOrNull().orEmpty(),
      description = values["description"]?.firstOrNull().orEmpty(),
      matches = matches,
      includes = includes,
      excludes = excludes,
      runAt =
        when (values["run-at"]?.firstOrNull()) {
          "document-start" -> UserScriptRunAt.DOCUMENT_START
          "document-idle" -> UserScriptRunAt.DOCUMENT_IDLE
          else -> UserScriptRunAt.DOCUMENT_END
        },
      updateUrl = updateUrl,
      downloadUrl = downloadUrl,
    )
  }

  fun matches(
    metadata: UserScriptMetadata,
    url: String,
  ): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme != "https" || host != "novelpia.com" && !host.endsWith(".novelpia.com")) return false
    val hasNoIncludes = metadata.matches.isEmpty() && metadata.includes.isEmpty()
    val included =
      hasNoIncludes ||
        metadata.matches.any { compileMatchPattern(it).matches(url) } ||
        metadata.includes.any { compileGlobPattern(it).matches(url) }
    return included && metadata.excludes.none { compileGlobPattern(it).matches(url) }
  }

  fun javascriptMatchExpression(metadata: UserScriptMetadata): String {
    val included =
      buildList {
        metadata.matches.forEach { add(compileMatchPattern(it)) }
        metadata.includes.forEach { add(compileGlobPattern(it)) }
      }.joinToString("||") { "new RegExp(${quote(it.pattern)},'i').test(location.href)" }
        .ifEmpty { "true" }
    val excluded =
      metadata.excludes
        .joinToString("||") { "new RegExp(${quote(compileGlobPattern(it).pattern)},'i').test(location.href)" }
        .ifEmpty { "false" }
    return "(($included)&&!($excluded))"
  }

  fun requireHttpsUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Only HTTPS URLs are supported" }
  }

  fun sha256(source: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(source.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  fun decodeUtf8(bytes: ByteArray): String =
    Charsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()

  fun readUtf8(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (output.size() <= MAX_SCRIPT_BYTES) {
      val count = input.read(buffer, 0, minOf(buffer.size, MAX_SCRIPT_BYTES + 1 - output.size()))
      if (count == -1) break
      output.write(buffer, 0, count)
    }
    require(output.size() <= MAX_SCRIPT_BYTES) { "Script exceeds 1 MiB" }
    return decodeUtf8(output.toByteArray())
  }

  internal fun compileMatchPattern(value: String): Regex {
    require(matchPatternRegex.matches(value)) { "Invalid @match pattern" }
    val scheme = value.substringBefore("://")
    val hostAndPath = value.substringAfter("://")
    val host = hostAndPath.substringBefore('/')
    val path = "/${hostAndPath.substringAfter('/', "")}"
    val schemePattern = if (scheme == "*") "https?" else escapeRegexLiteral(scheme)
    val hostPattern =
      when {
        host == "*" -> "[^/]+"
        host.startsWith("*.") -> "(?:[^/]+\\.)?${escapeRegexLiteral(host.removePrefix("*."))}"
        else -> escapeRegexLiteral(host)
      }
    return Regex("^$schemePattern://$hostPattern${globToRegex(path)}$", RegexOption.IGNORE_CASE)
  }

  private fun compileGlobPattern(value: String): Regex {
    require(value.isNotBlank()) { "Empty URL pattern" }
    return Regex("^${globToRegex(value)}$", RegexOption.IGNORE_CASE)
  }

  private fun globToRegex(value: String): String =
    buildString {
      value.forEach { character ->
        if (character == '*') {
          append(".*")
        } else {
          append(escapeRegexLiteral(character.toString()))
        }
      }
    }

  private fun escapeRegexLiteral(value: String): String =
    buildString {
      value.forEach { character ->
        if (character in regexMetaCharacters) append('\\')
        append(character)
      }
    }

  private fun quote(value: String): String = org.json.JSONObject.quote(value)

  private val regexMetaCharacters = setOf('\\', '.', '^', '$', '|', '?', '+', '(', ')', '[', ']', '{', '}')
}
