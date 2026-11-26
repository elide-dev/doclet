/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.model.Doc
import org.jetbrains.dokka.links.PointingToCallableParameters
import org.jetbrains.dokka.links.PointingToGenericParameters
import org.jetbrains.dokka.model.doc.A
import org.jetbrains.dokka.model.doc.B
import org.jetbrains.dokka.model.doc.Big
import org.jetbrains.dokka.model.doc.BlockQuote
import org.jetbrains.dokka.model.doc.Br
import org.jetbrains.dokka.model.doc.Cite
import org.jetbrains.dokka.model.doc.CodeBlock
import org.jetbrains.dokka.model.doc.CodeInline
import org.jetbrains.dokka.model.doc.CustomDocTag
import org.jetbrains.dokka.model.doc.Dd
import org.jetbrains.dokka.model.doc.Dfn
import org.jetbrains.dokka.model.doc.Div
import org.jetbrains.dokka.model.doc.Dl
import org.jetbrains.dokka.model.doc.DocTag
import org.jetbrains.dokka.model.doc.DocumentationLink
import org.jetbrains.dokka.model.doc.Dt
import org.jetbrains.dokka.model.doc.Em
import org.jetbrains.dokka.model.doc.H1
import org.jetbrains.dokka.model.doc.H2
import org.jetbrains.dokka.model.doc.H3
import org.jetbrains.dokka.model.doc.H4
import org.jetbrains.dokka.model.doc.H5
import org.jetbrains.dokka.model.doc.H6
import org.jetbrains.dokka.model.doc.HorizontalRule
import org.jetbrains.dokka.model.doc.I
import org.jetbrains.dokka.model.doc.Img
import org.jetbrains.dokka.model.doc.Li
import org.jetbrains.dokka.model.doc.Mark
import org.jetbrains.dokka.model.doc.Ol
import org.jetbrains.dokka.model.doc.P
import org.jetbrains.dokka.model.doc.Pre
import org.jetbrains.dokka.model.doc.Section
import org.jetbrains.dokka.model.doc.Small
import org.jetbrains.dokka.model.doc.Span
import org.jetbrains.dokka.model.doc.Strikethrough
import org.jetbrains.dokka.model.doc.Strong
import org.jetbrains.dokka.model.doc.Sub
import org.jetbrains.dokka.model.doc.Sup
import org.jetbrains.dokka.model.doc.TBody
import org.jetbrains.dokka.model.doc.THead
import org.jetbrains.dokka.model.doc.Table
import org.jetbrains.dokka.model.doc.Td
import org.jetbrains.dokka.model.doc.Text
import org.jetbrains.dokka.model.doc.Th
import org.jetbrains.dokka.model.doc.Tr
import org.jetbrains.dokka.model.doc.Tt
import org.jetbrains.dokka.model.doc.U
import org.jetbrains.dokka.model.doc.Ul
import org.jetbrains.dokka.model.doc.Var

/**
 * Dokka's documentation tags (KDoc's Markdown, or a Java comment's HTML, as Dokka parses them) as
 * the model's documentation tree.
 *
 * Markup with no node of its own is kept as a raw node, as the doclet keeps it: inline HTML in KDoc
 * arrives as one text token per tag, so a run from an opening tag to its closing tag becomes one
 * raw node, and the renderer sanitizes it. A URL attribute in that markup must be http, https,
 * mailto or relative; anything else is refused. A construct the model cannot represent (an image,
 * an unknown tag) is a problem, which fails the run rather than dropping it.
 *
 * KDoc has no `@apiNote`. The convention: a paragraph that begins with a bold label, `**API
 * note:**`, `**Implementation requirements:**` or `**Implementation note:**` (any letter case, the
 * colon inside the bold), starts a note of kind `apiNote`, `implSpec` or `implNote`. The note runs
 * to the end of that paragraph, and takes the lists, code blocks and tables that follow it
 * directly; the next paragraph, heading or other block ends it. The label itself is dropped. A
 * label with its colon outside the bold (`**API note**:`) is a warning and stays description.
 */
internal class KDocs(
  private val identity: Identity,
  private val problem: (String) -> Unit,
  private val warning: (String) -> Unit,
) {
  private val noteLabels =
    mapOf(
      "api note:" to Doc.NoteKind.API_NOTE,
      "implementation requirements:" to Doc.NoteKind.IMPL_SPEC,
      "implementation note:" to Doc.NoteKind.IMPL_NOTE,
    )

  /** A description's blocks, with labeled notes split out. */
  fun description(root: DocTag): Pair<List<Doc.Block>, List<Doc.Note>> {
    val notes = mutableListOf<Doc.Note>()
    val body = mutableListOf<Doc.Block>()
    val tags = flatten(root)
    var i = 0
    while (i < tags.size) {
      val kind = noteKind(tags[i])
      if (kind == null) {
        body += blocks(listOf(tags[i]))
        i++
        continue
      }
      val paragraph = tags[i] as P
      val note = mutableListOf<Doc.Block>()
      inlines(paragraph.children.drop(1))
        .takeIf { it.isNotEmpty() }
        ?.let { note += Doc.Paragraph(it) }
      i++
      while (i < tags.size && continuesNote(tags[i])) {
        note += blocks(listOf(tags[i]))
        i++
      }
      notes += Doc.Note(kind, note)
    }
    return body to notes
  }

  /**
   * The top-level blocks of a comment: Dokka wraps them in a `CustomDocTag` (KDoc) or a
   * `Div`/`Section`.
   */
  private fun flatten(root: DocTag): List<DocTag> =
    if (root is CustomDocTag || root is Div || root is Section) root.children.flatMap(::flatten)
    else listOf(root)

  private fun continuesNote(tag: DocTag): Boolean =
    tag is Ul || tag is Ol || tag is CodeBlock || tag is Pre || tag is Table || tag is Dl

  private fun noteKind(tag: DocTag): Doc.NoteKind? {
    if (tag !is P) return null
    val first = tag.children.firstOrNull() ?: return null
    if (first !is B && first !is Strong) return null
    val label = plain(first.children).trim().lowercase()
    noteLabels[label]?.let {
      return it
    }
    val next = (tag.children.getOrNull(1) as? Text)?.body.orEmpty()
    if ("$label:" in noteLabels && next.startsWith(":")) {
      warning(
        "a note label needs its colon inside the bold (**${plain(first.children).trim()}:**); kept as description",
      )
    }
    return null
  }

  fun blocks(tags: List<DocTag>): List<Doc.Block> {
    val out = mutableListOf<Doc.Block>()
    val pending = mutableListOf<DocTag>()
    fun flush() {
      val inlines = inlines(pending)
      if (inlines.isNotEmpty()) out += Doc.Paragraph(inlines)
      pending.clear()
    }
    for (tag in tags) {
      when {
        tag is CustomDocTag || tag is Div || tag is Section -> {
          flush()
          out += blocks(tag.children)
        }
        tag is P -> {
          flush()
          if (tag.children.any(::isBlock)) out += blocks(tag.children)
          else inlines(tag.children).takeIf { it.isNotEmpty() }?.let { out += Doc.Paragraph(it) }
        }
        tag is Ul -> {
          flush()
          out += Doc.ListBlock(false, items(tag.children))
        }
        tag is Ol -> {
          flush()
          out += Doc.ListBlock(true, items(tag.children))
        }
        tag is Dl -> {
          flush()
          out += definitions(tag.children)
        }
        tag is CodeBlock -> {
          flush()
          out +=
            Doc.CodeBlock(
              tag.params["lang"]?.takeIf { it.isNotBlank() },
              dedent(plain(tag.children)),
            )
        }
        tag is Pre -> {
          flush()
          out += Doc.CodeBlock(null, dedent(plain(tag.children)))
        }
        tag is H1 || tag is H2 || tag is H3 || tag is H4 || tag is H5 || tag is H6 -> {
          flush()
          out += Doc.Heading(tag::class.simpleName!!.substring(1).toInt(), inlines(tag.children))
        }
        tag is BlockQuote -> {
          flush()
          out += Doc.Quote(blocks(tag.children))
        }
        tag is Table -> {
          flush()
          out += table(tag)
        }
        tag is HorizontalRule -> {
          flush()
          out += Doc.RawBlock("<hr>", "")
        }
        tag is Text && isHtml(tag) && pending.isEmpty() -> {
          // Raw HTML at block level (a Markdown HTML block).
          checkUrls(tag.body)
          out += Doc.RawBlock(tag.body.trim(), htmlText(tag.body).trim())
        }
        else -> pending += tag
      }
    }
    flush()
    return out
  }

  private fun isBlock(tag: DocTag): Boolean =
    tag is Ul ||
      tag is Ol ||
      tag is CodeBlock ||
      tag is Pre ||
      tag is Table ||
      tag is BlockQuote ||
      tag is P ||
      tag is Dl ||
      tag is H1 ||
      tag is H2 ||
      tag is H3 ||
      tag is H4 ||
      tag is H5 ||
      tag is H6 ||
      tag is HorizontalRule

  private fun items(children: List<DocTag>): List<List<Doc.Block>> = children.mapNotNull {
    if (it is Li) blocks(it.children)
    else if (it is Text && it.body.isBlank()) null else blocks(listOf(it))
  }

  private fun definitions(children: List<DocTag>): Doc.ListBlock {
    val items = mutableListOf<MutableList<Doc.Block>>()
    fun last() = items.lastOrNull() ?: mutableListOf<Doc.Block>().also { items += it }
    for (child in children) {
      when {
        child is Dt ->
          items +=
            mutableListOf<Doc.Block>(Doc.Paragraph(listOf(Doc.Strong(inlines(child.children)))))
        child is Dd -> last() += blocks(child.children)
        child is Text && child.body.isBlank() -> {}
        else -> last() += blocks(listOf(child))
      }
    }
    return Doc.ListBlock(false, items.map { it.toList() })
  }

  /**
   * A table. Dokka's Markdown parser gives the header row as a `Th` of `Td` cells; HTML gives `Tr`s
   * of `Th`.
   */
  private fun table(table: Table): Doc.Table {
    val head = mutableListOf<List<List<Doc.Block>>>()
    val rows = mutableListOf<List<List<Doc.Block>>>()
    for (row in
      table.children.flatMap { if (it is THead || it is TBody) it.children else listOf(it) }) {
      when (row) {
        is Th -> head += row.children.filter { it is Td || it is Th }.map { blocks(it.children) }
        is Tr -> {
          val cells = row.children.filter { it is Td || it is Th }
          val entry = cells.map { blocks(it.children) }
          if (rows.isEmpty() && head.isEmpty() && cells.isNotEmpty() && cells.all { it is Th })
            head += entry
          else rows += entry
        }
        else ->
          if (!(row is Text && row.body.isBlank()))
            problem("The API model cannot represent ${row::class.simpleName} in a table")
      }
    }
    return Doc.Table(listOf(), head, rows)
  }

  fun inlines(tags: List<DocTag>): List<Doc.Inline> {
    val out = mutableListOf<Doc.Inline>()
    var i = 0
    while (i < tags.size) {
      val tag = tags[i]
      if (tag is Text && isHtml(tag)) {
        i = rawRun(tags, i, out)
      } else {
        out += inline(tag)
        i++
      }
    }
    return normalize(out)
  }

  /**
   * Inline HTML from [start]: Dokka gives each tag as its own text token, so the run continues
   * until the tags it opened are closed (or the paragraph ends). The run becomes one raw node;
   * returns the index after it.
   */
  private fun rawRun(tags: List<DocTag>, start: Int, out: MutableList<Doc.Inline>): Int {
    val html = StringBuilder()
    val text = StringBuilder()
    var depth = 0
    var i = start
    do {
      val tag = tags[i]
      if (tag is Text && isHtml(tag)) {
        html.append(tag.body)
        text.append(htmlText(tag.body))
        depth += depth(tag.body)
      } else {
        val plain = plain(listOf(tag))
        html.append(escape(plain))
        text.append(plain)
      }
      i++
    } while (depth > 0 && i < tags.size)
    checkUrls(html.toString())
    out += Doc.RawInline(html.toString(), text.toString())
    return i
  }

  private fun inline(tag: DocTag): List<Doc.Inline> =
    when (tag) {
      is Text -> listOf(Doc.Text(tag.body))
      is CodeInline -> listOf(Doc.Code(plain(tag.children)))
      is B,
      is Strong -> listOf(Doc.Strong(inlines(tag.children)))
      is I,
      is Em,
      is Cite,
      is Dfn,
      is Var -> listOf(Doc.Emphasis(inlines(tag.children)))
      is Tt -> listOf(Doc.Code(plain(tag.children)))
      is Br -> listOf(Doc.Break())
      is DocumentationLink -> link(tag)
      is A -> {
        val href = tag.params["href"]
        when {
          href == null -> inlines(tag.children)
          !safeUrl(href) -> {
            problem(
              "Unsafe URL in href: only http, https, mailto and relative URLs are allowed: $href",
            )
            inlines(tag.children)
          }
          else -> listOf(Doc.Link(null, href, false, inlines(tag.children)))
        }
      }
      is Strikethrough,
      is Sup,
      is Sub,
      is Small,
      is Big,
      is Span,
      is U,
      is Mark -> {
        // No node of their own: kept as markup, as the doclet keeps them.
        val name = if (tag is Strikethrough) "s" else tag::class.simpleName!!.lowercase()
        val text = plain(tag.children)
        listOf(Doc.RawInline("<$name>${escape(text)}</$name>", text))
      }
      is Img -> {
        problem(
          "The API model cannot represent an image (${tag.params["href"] ?: tag.params["src"] ?: ""}); link to it instead",
        )
        listOf()
      }
      is P,
      is CustomDocTag,
      is Div,
      is Section -> inlines(tag.children)
      is CodeBlock,
      is Pre -> listOf(Doc.Code(plain(tag.children)))
      else -> {
        problem("The API model cannot represent the documentation tag ${tag::class.simpleName}")
        listOf()
      }
    }

  /**
   * A link to a declaration. A link to a parameter or a type parameter (`[name]` in KDoc) is code,
   * since neither has a page. The label is code when it is the target as written (`[Shape.area]`),
   * prose when the link names it (`[shapes][Shape]`).
   */
  private fun link(tag: DocumentationLink): List<Doc.Inline> {
    val label = inlines(tag.children)
    val text = plain(tag.children)
    if (
      tag.dri.target is PointingToCallableParameters ||
        tag.dri.target is PointingToGenericParameters
    ) {
      return listOf(Doc.Code(text))
    }
    val written = tag.params["href"]?.removeSurrounding("[", "]")
    val code = written == null || written == text
    return listOf(
      Doc.Link(
        identity.ref(tag.dri),
        null,
        code,
        label.ifEmpty { listOf(Doc.Text(tag.dri.classNames ?: tag.dri.packageName.orEmpty())) },
      ),
    )
  }

  /** Merges adjacent text and collapses whitespace, trimming the run's ends. */
  private fun normalize(inlines: List<Doc.Inline>): List<Doc.Inline> {
    val out = mutableListOf<Doc.Inline>()
    for (inline in inlines) {
      val next =
        if (inline is Doc.Text) Doc.Text(inline.text().replace(WHITESPACE, " ")) else inline
      val last = out.lastOrNull()
      if (next is Doc.Text && last is Doc.Text)
        out[out.size - 1] = Doc.Text((last.text() + next.text()).replace(WHITESPACE, " "))
      else out += next
    }
    (out.firstOrNull() as? Doc.Text)?.let { out[0] = Doc.Text(it.text().trimStart()) }
    (out.lastOrNull() as? Doc.Text)?.let { out[out.size - 1] = Doc.Text(it.text().trimEnd()) }
    return out.filterNot { it is Doc.Text && it.text().isEmpty() }
  }

  /**
   * Refuses a URL attribute in raw HTML that is not http, https, mailto or relative, as the doclet
   * does.
   */
  private fun checkUrls(html: String) {
    for (tag in TAG.findAll(html)) {
      for (attribute in ATTRIBUTE.findAll(tag.groupValues[3])) {
        val name = attribute.groupValues[1].lowercase()
        if (name !in URL_ATTRIBUTES) continue
        val value =
          decode(
            attribute.groupValues[3]
              .ifEmpty { attribute.groupValues[4] }
              .ifEmpty { attribute.groupValues[5] },
          )
        if (!safeUrl(value))
          problem(
            "Unsafe URL in $name: only http, https, mailto and relative URLs are allowed: $value",
          )
      }
    }
  }

  companion object {
    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>")
    private val ATTRIBUTE =
      Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val URL_ATTRIBUTES =
      setOf("href", "src", "action", "formaction", "srcdoc", "data", "poster", "xlink:href")
    private val VOID =
      setOf(
        "br",
        "hr",
        "img",
        "wbr",
        "input",
        "meta",
        "link",
        "area",
        "base",
        "col",
        "embed",
        "source",
        "track",
      )
    private val ENTITIES =
      mapOf(
        "lt" to "<",
        "gt" to ">",
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00a0",
      )

    fun isHtml(tag: Text): Boolean = tag.params["content-type"] == "html"

    /**
     * How many elements [html] leaves open: start tags minus end tags, void and self-closing tags
     * not counted.
     */
    private fun depth(html: String): Int =
      TAG.findAll(html).sumOf { m ->
        when {
          m.groupValues[1] == "/" -> -1
          m.groupValues[2].lowercase() in VOID || m.groupValues[3].trimEnd().endsWith("/") -> 0
          else -> 1
        }
      }

    /** The text of an HTML fragment: tags and comments removed, entities decoded. */
    fun htmlText(html: String): String =
      decode(html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").replace(TAG, ""))

    fun decode(text: String): String =
      Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
        val name = m.groupValues[1]
        when {
          name.startsWith("#x") || name.startsWith("#X") ->
            name.substring(2).toIntOrNull(16)?.let { Character.toString(it) } ?: m.value
          name.startsWith("#") ->
            name.substring(1).toIntOrNull()?.let { Character.toString(it) } ?: m.value
          else -> ENTITIES[name] ?: m.value
        }
      }

    fun plain(tags: List<DocTag>): String =
      tags.joinToString("") {
        if (it is Text) (if (isHtml(it)) htmlText(it.body) else it.body)
        else if (it is Br) "\n" else plain(it.children)
      }

    fun escape(text: String): String =
      text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun dedent(code: String): String {
      val lines = code.trimEnd().removePrefix("\n").lines()
      val indent =
        lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
      return lines.joinToString("\n") {
        if (it.length >= indent) it.substring(indent).trimEnd() else it.trim()
      }
    }

    /**
     * The first sentence of the first paragraph: up to a period followed by whitespace or the end.
     */
    fun summary(body: List<Doc.Block>): List<Doc.Inline> {
      val first = body.firstOrNull() as? Doc.Paragraph ?: return listOf()
      val out = mutableListOf<Doc.Inline>()
      for (inline in first.children()) {
        if (inline is Doc.Text) {
          val end = Regex("\\.(\\s|$)").find(inline.text())
          if (end != null) {
            out += Doc.Text(inline.text().substring(0, end.range.first + 1))
            return out
          }
        }
        out += inline
      }
      return out
    }

    /** http, https, mailto or relative; the doclet and the renderer apply the same rule. */
    fun safeUrl(url: String): Boolean {
      val cleaned = url.trimStart { it <= ' ' }.replace(Regex("[\\t\\n\\r]"), "")
      if (cleaned.any { it < ' ' || it == '\u007f' } || cleaned.startsWith("\\")) return false
      val scheme =
        Regex("^([^:/?#]*):").find(cleaned)?.groupValues?.get(1)?.lowercase() ?: return true
      return scheme in setOf("http", "https", "mailto")
    }
  }
}
