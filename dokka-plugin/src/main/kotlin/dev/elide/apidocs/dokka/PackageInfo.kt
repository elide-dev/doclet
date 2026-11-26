/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.model.Api
import dev.elide.apidocs.model.Doc
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Kotlin package's description from the `package-info.java` in its directory, which Dokka's model
 * leaves out. It reads the comment's common Javadoc: paragraphs, `<ul>`/`<ol>` lists, `<pre>`,
 * `<h2>`-`<h6>`, `{@link}`, `{@linkplain}`, `{@code}`, `{@literal}`, `<code>`, `<em>`/`<i>`,
 * `<strong>`/`<b>`, and the `@since` and `@see` tags. Anything else is a problem, which fails the
 * run rather than dropping it.
 */
internal class PackageInfo(
  private val resolve: (String) -> Api.Ref?,
  private val problem: (String) -> Unit,
) {
  fun parse(file: Path): Pair<Doc, String?>? {
    val source = Files.readString(file, StandardCharsets.UTF_8)
    val comment =
      Regex(
          "/\\*\\*(.*?)\\*/\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*package\\s",
          RegexOption.DOT_MATCHES_ALL,
        )
        .find(source)
        ?.groupValues
        ?.get(1) ?: return null
    val text = comment.lines().joinToString("\n") { it.replace(Regex("^\\s*\\*\\s?"), "") }.trim()
    val tagStart = Regex("(?m)^@\\w+").find(text)?.range?.first ?: text.length
    val description = text.substring(0, tagStart)
    var since: String? = null
    val see = mutableListOf<List<Doc.Inline>>()
    for (tag in Regex("(?ms)^@(\\w+)\\s+(.*?)(?=^@\\w+|\\z)").findAll(text.substring(tagStart))) {
      val value = tag.groupValues[2].trim()
      when (tag.groupValues[1]) {
        "since" -> since = value
        "see" ->
          see += reference(value.substringBefore(' '), value.substringAfter(' ', "").trim(), true)
        else ->
          problem("the API model cannot represent @${tag.groupValues[1]} in a package description")
      }
    }
    val body = blocks(description, file)
    val summary = firstSentence(body)
    return Doc(summary, body, listOf(), see, null) to since
  }

  private fun blocks(html: String, file: Path): List<Doc.Block> {
    val out = mutableListOf<Doc.Block>()
    // Split on block-level markup; what lies between is paragraph text.
    val pattern =
      Regex("(?is)<pre>(.*?)</pre>|<(ul|ol)>(.*?)</\\2>|<h([2-6])>(.*?)</h\\4>|<p>|</p>|\\n\\s*\\n")
    var at = 0
    fun paragraph(text: String) {
      val inlines = inlines(text, file)
      if (inlines.isNotEmpty()) out += Doc.Paragraph(inlines)
    }
    for (m in pattern.findAll(html)) {
      paragraph(html.substring(at, m.range.first))
      at = m.range.last + 1
      when {
        m.groupValues[1].isNotEmpty() ->
          out += Doc.CodeBlock(null, KDocs.dedent(unwrapCode(m.groupValues[1])))
        m.groupValues[2].isNotEmpty() ->
          out +=
            Doc.ListBlock(
              m.groupValues[2].equals("ol", ignoreCase = true),
              m.groupValues[3].split(Regex("(?i)<li>")).drop(1).map {
                listOf(Doc.Paragraph(inlines(it.replace(Regex("(?i)</li>"), ""), file)))
              },
            )
        m.groupValues[4].isNotEmpty() ->
          out += Doc.Heading(m.groupValues[4].toInt(), inlines(m.groupValues[5], file))
      }
    }
    paragraph(html.substring(at))
    return out
  }

  private fun unwrapCode(text: String): String =
    Regex("(?s)^\\s*\\{@code(.*)}\\s*$").find(text)?.groupValues?.get(1) ?: text

  private fun inlines(html: String, file: Path): List<Doc.Inline> {
    val out = mutableListOf<Doc.Inline>()
    val pattern =
      Regex(
        "(?s)\\{@(link|linkplain)\\s+([^}\\s]+)(?:\\s+([^}]*))?}|\\{@(code|literal)\\s?([^}]*)}|<code>(.*?)</code>|<(em|i)>(.*?)</\\7>|<(strong|b)>(.*?)</\\9>|<[^>]+>|\\{@\\w+[^}]*}|&(\\w+);",
      )
    var at = 0
    for (m in pattern.findAll(html)) {
      if (m.range.first > at) out += Doc.Text(html.substring(at, m.range.first))
      at = m.range.last + 1
      val g = m.groupValues
      when {
        g[1].isNotEmpty() -> out += reference(g[2], g[3].trim(), g[1] == "link")
        g[4] == "code" -> out += Doc.Code(g[5])
        g[4] == "literal" -> out += Doc.Text(g[5])
        g[6].isNotEmpty() || m.value == "<code></code>" -> out += Doc.Code(g[6])
        g[7].isNotEmpty() -> out += Doc.Emphasis(inlines(g[8], file))
        g[9].isNotEmpty() -> out += Doc.Strong(inlines(g[10], file))
        g[11].isNotEmpty() ->
          out +=
            Doc.Text(
              entities[g[11]]
                ?: run {
                  problem("unknown HTML entity &${g[11]};")
                  m.value
                },
            )
        else -> problem("the API model cannot represent ${m.value} in a package description")
      }
    }
    if (at < html.length) out += Doc.Text(html.substring(at))
    val merged =
      out
        .map { if (it is Doc.Text) Doc.Text(it.text().replace(Regex("\\s+"), " ")) else it }
        .toMutableList()
    (merged.firstOrNull() as? Doc.Text)?.let { merged[0] = Doc.Text(it.text().trimStart()) }
    (merged.lastOrNull() as? Doc.Text)?.let {
      merged[merged.size - 1] = Doc.Text(it.text().trimEnd())
    }
    return merged.filterNot { it is Doc.Text && it.text().isEmpty() }
  }

  private fun reference(target: String, label: String, code: Boolean): List<Doc.Inline> {
    val text = label.ifEmpty { target.removePrefix("#").replace('#', '.') }
    val ref = resolve(target.substringBefore('#'))
    if (ref == null) problem("Cannot resolve the reference $target in a package description")
    return listOf(
      if (ref != null) Doc.Link(ref, null, code, listOf(Doc.Text(text)))
      else if (code) Doc.Code(text) else Doc.Text(text),
    )
  }

  private fun firstSentence(body: List<Doc.Block>): List<Doc.Inline> {
    val first = body.firstOrNull() as? Doc.Paragraph ?: return listOf()
    val out = mutableListOf<Doc.Inline>()
    for (inline in first.children()) {
      if (inline is Doc.Text) {
        val end = Regex("\\.(\\s|$)").find(inline.text())
        if (end != null) return out + Doc.Text(inline.text().substring(0, end.range.first + 1))
      }
      out += inline
    }
    return out
  }

  private val entities =
    mapOf(
      "lt" to "<",
      "gt" to ">",
      "amp" to "&",
      "quot" to "\"",
      "nbsp" to " ",
      "mdash" to "—",
      "ndash" to "–",
    )
}
