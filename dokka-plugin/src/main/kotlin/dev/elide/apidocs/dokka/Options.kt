/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

/**
 * The plugin's options, from its `pluginsConfiguration` entry (JSON). They mirror the doclet's:
 * ```json
 * {
 *   "moduleName": "library",
 *   "moduleVersion": "0.2.0",
 *   "theme": "/path/to/theme.json",
 *   "sourceRef": "v0.2.0",
 *   "jsRuntime": "/path/to/bun",
 *   "signatureFormatter": "/path/to/elide",
 *   "links": {
 *     "java.": "https://docs.oracle.com/en/java/javase/25/docs/api/{module}/",
 *     "kotlin.": {"url": "https://kotlinlang.org/api/core/kotlin-stdlib/", "layout": "dokka"}
 *   },
 *   "modelOnly": false,
 *   "pretty": false
 * }
 * ```
 *
 * `moduleName` defaults to Dokka's own. A link's value is a URL laid out as Javadoc lays out pages,
 * or an object with the URL and its `layout` (`javadoc` or `dokka`). `jsRuntime` falls back to
 * `APIDOCS_JS_RUNTIME`, then to `bun` and `node` on `PATH`. `signatureFormatter` is the Elide
 * executable whose ktfmt (Kotlin) and google-java-format (Java) lay out the signatures, as the
 * doclet's `--signature-formatter` is; `none` keeps every signature on one line, and without it the
 * plugin looks for `elide` on `PATH`. Unknown options are an error.
 */
internal data class Options(
  val moduleName: String? = null,
  val moduleVersion: String? = null,
  val theme: String? = null,
  val sourceRef: String? = null,
  val jsRuntime: String? = null,
  val signatureFormatter: String? = null,
  val links: List<Identity.Link> = listOf(),
  val modelOnly: Boolean = false,
  val pretty: Boolean = false,
) {
  companion object {
    private val KNOWN =
      setOf(
        "moduleName",
        "moduleVersion",
        "theme",
        "sourceRef",
        "jsRuntime",
        "signatureFormatter",
        "links",
        "modelOnly",
        "pretty",
      )

    fun parse(json: String?): Options {
      if (json.isNullOrBlank()) return Options()
      val value = MiniJson(json).document()
      require(value is Map<*, *>) { "The apidocs plugin's configuration must be a JSON object" }
      val unknown = value.keys.filterNot { it in KNOWN }
      require(unknown.isEmpty()) {
        "Unknown apidocs plugin option(s): $unknown; known: ${KNOWN.sorted()}"
      }
      return Options(
        moduleName = string(value, "moduleName"),
        moduleVersion = string(value, "moduleVersion"),
        theme = string(value, "theme"),
        sourceRef = string(value, "sourceRef"),
        jsRuntime = string(value, "jsRuntime"),
        signatureFormatter = string(value, "signatureFormatter"),
        links = links(value["links"]),
        modelOnly = boolean(value, "modelOnly"),
        pretty = boolean(value, "pretty"),
      )
    }

    private fun string(map: Map<*, *>, key: String): String? {
      val v = map[key]
      require(v == null || v is String) { "The apidocs plugin option $key must be a string" }
      return v
    }

    private fun boolean(map: Map<*, *>, key: String): Boolean {
      val v = map[key]
      require(v == null || v is Boolean) { "The apidocs plugin option $key must be true or false" }
      return v ?: false
    }

    private fun links(value: Any?): List<Identity.Link> {
      if (value == null) return listOf()
      require(value is Map<*, *>) {
        "The apidocs plugin option links must map a name prefix to a URL"
      }
      return value.entries.map { (prefix, target) ->
        when (target) {
          is String -> Identity.Link(prefix as String, target, Identity.Layout.JAVADOC)
          is Map<*, *> -> {
            val url =
              target["url"] as? String
                ?: throw IllegalArgumentException("The link for $prefix has no url")
            val layout =
              when (val name = target["layout"] ?: "javadoc") {
                "javadoc" -> Identity.Layout.JAVADOC
                "dokka" -> Identity.Layout.DOKKA
                else ->
                  throw IllegalArgumentException(
                    "The link for $prefix has an unknown layout $name (javadoc or dokka)",
                  )
              }
            Identity.Link(prefix as String, url, layout)
          }
          else ->
            throw IllegalArgumentException(
              "The link for $prefix must be a URL or {\"url\", \"layout\"}",
            )
        }
      }
    }
  }
}

/** Just enough JSON for the options: objects, strings, booleans and null. */
private class MiniJson(private val text: String) {
  private var at = 0

  fun document(): Any? {
    val value = value()
    space()
    check(at == text.length) { "Unexpected JSON at $at in the apidocs plugin's configuration" }
    return value
  }

  private fun value(): Any? {
    space()
    return when (text.getOrNull(at)) {
      '{' -> obj()
      '"' -> string()
      't' -> literal("true", true)
      'f' -> literal("false", false)
      'n' -> literal("null", null)
      else -> error("Unexpected JSON at $at in the apidocs plugin's configuration")
    }
  }

  private fun obj(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    at++
    space()
    if (text.getOrNull(at) == '}') return out.also { at++ }
    while (true) {
      space()
      val key = string()
      space()
      check(text.getOrNull(at++) == ':') {
        "Expected ':' at ${at - 1} in the apidocs plugin's configuration"
      }
      out[key] = value()
      space()
      when (text.getOrNull(at++)) {
        ',' -> continue
        '}' -> return out
        else -> error("Expected ',' or '}' at ${at - 1} in the apidocs plugin's configuration")
      }
    }
  }

  private fun string(): String {
    check(text.getOrNull(at++) == '"') {
      "Expected a string at ${at - 1} in the apidocs plugin's configuration"
    }
    val out = StringBuilder()
    while (true) {
      when (
        val c =
          text.getOrNull(at++) ?: error("Unterminated string in the apidocs plugin's configuration")
      ) {
        '"' -> return out.toString()
        '\\' ->
          when (val e = text.getOrNull(at++)) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> out.append('\r')
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
            null -> error("Unterminated string in the apidocs plugin's configuration")
            else -> out.append(e)
          }
        else -> out.append(c)
      }
    }
  }

  private fun literal(word: String, value: Any?): Any? {
    check(text.startsWith(word, at)) {
      "Expected $word at $at in the apidocs plugin's configuration"
    }
    at += word.length
    return value
  }

  private fun space() {
    while (at < text.length && text[at].isWhitespace()) at++
  }
}
