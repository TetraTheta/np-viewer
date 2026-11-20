package io.github.tetratheta.npviewer.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UserScriptParserTest {
  @Test
  fun parsesSupportedMetadata() {
    val metadata =
      UserScriptParser.parse(
        script(
          """
          // @name Example
          // @namespace np-viewer
          // @version 1.2.3
          // @match https://novelpia.com/viewer/*
          // @exclude https://novelpia.com/viewer/blocked*
          // @run-at document-start
          // @downloadURL https://example.com/example.user.js
          """.trimIndent(),
        ),
      )

    assertEquals("Example", metadata.name)
    assertEquals(UserScriptRunAt.DOCUMENT_START, metadata.runAt)
    assertTrue(UserScriptParser.matches(metadata, "https://novelpia.com/viewer/1"))
    assertFalse(UserScriptParser.matches(metadata, "https://novelpia.com/viewer/blocked-1"))
    assertFalse(UserScriptParser.matches(metadata, "https://example.com/viewer/1"))
  }

  @Test
  fun defaultsToNovelpiaAndDocumentEnd() {
    val metadata = UserScriptParser.parse(script("// @name Example"))

    assertEquals(UserScriptRunAt.DOCUMENT_END, metadata.runAt)
    assertTrue(UserScriptParser.matches(metadata, "https://novelpia.com/mybook"))
    assertTrue(UserScriptParser.matches(metadata, "https://api.novelpia.com/path"))
    assertFalse(UserScriptParser.matches(metadata, "http://novelpia.com/mybook"))
  }

  @Test
  fun matchSubdomainWildcardIncludesRootDomain() {
    val metadata = UserScriptParser.parse(script("// @name Example\n// @match *://*.novelpia.com/*"))

    assertTrue(UserScriptParser.matches(metadata, "https://novelpia.com/"))
    assertTrue(UserScriptParser.matches(metadata, "https://chat.novelpia.com/"))
    assertFalse(UserScriptParser.matches(metadata, "https://notnovelpia.com/"))
    assertFalse(UserScriptParser.compileMatchPattern("*://*.novelpia.com/*").pattern.contains("\\Q"))
  }

  @Test
  fun rejectsMissingNameAndInsecureUpdateUrl() {
    assertThrows(IllegalArgumentException::class.java) { UserScriptParser.parse(script("// @version 1")) }
    assertThrows(IllegalArgumentException::class.java) {
      UserScriptParser.parse(script("// @name Example\n// @updateURL http://example.com/a.js"))
    }
  }

  @Test
  fun rejectsInvalidMatchAndUtf8() {
    assertThrows(IllegalArgumentException::class.java) {
      UserScriptParser.parse(script("// @name Example\n// @match not-a-match-pattern"))
    }
    assertThrows(Exception::class.java) { UserScriptParser.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28)) }
  }

  @Test
  fun rejectsOversizedScript() {
    val source = script("// @name Example") + "x".repeat(UserScriptParser.MAX_SCRIPT_BYTES)

    assertThrows(IllegalArgumentException::class.java) { UserScriptParser.parse(source) }
    assertThrows(IllegalArgumentException::class.java) {
      UserScriptParser.readUtf8(ByteArrayInputStream(ByteArray(UserScriptParser.MAX_SCRIPT_BYTES + 1)))
    }
  }

  private fun script(metadata: String): String =
    """
    // ==UserScript==
    $metadata
    // ==/UserScript==
    console.log('ok');
    """.trimIndent()
}
