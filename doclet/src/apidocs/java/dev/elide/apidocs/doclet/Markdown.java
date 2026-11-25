/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import dev.elide.apidocs.doclet.Comments.BlockEvent;
import dev.elide.apidocs.doclet.Comments.EndEvent;
import dev.elide.apidocs.doclet.Comments.Event;
import dev.elide.apidocs.doclet.Comments.InlineEvent;
import dev.elide.apidocs.doclet.Comments.StartEvent;
import dev.elide.apidocs.doclet.Comments.TextEvent;
import dev.elide.apidocs.model.Doc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown documentation comments ({@code ///}, JEP 467) as the event stream {@link Comments}
 * builds the model from, so Markdown and HTML comments share one path to blocks and inlines.
 *
 * <p>Javadoc hands over a {@code ///} comment as Markdown text with its tags ({@code {@link}},
 * reference links such as {@code [String]}, {@code {@inheritDoc}}) already parsed. The caller
 * converts each tag to events and leaves a <em>slot</em> character in the text where it was; this
 * class parses the text, as CommonMark with GitHub tables (what the standard doclet accepts), and
 * splices each slot's events back where the character is.
 *
 * <p>Blocks: paragraphs, ATX and setext headings, fenced and indented code, block quotes, bullet
 * and ordered lists, thematic breaks, tables, HTML blocks and link reference definitions. Inlines:
 * code spans, emphasis, links and images (inline and by reference), autolinks, inline HTML,
 * entities, backslash escapes and hard line breaks.
 */
final class Markdown {
  /** The first slot character: slot {@code i} is {@code SLOT + i}, in the private use area. */
  static final char SLOT = '\uE000';

  private static final int MAX_SLOTS = 0xF8FF - SLOT;
  private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
  private static final Pattern ATX =
      Pattern.compile("^(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
  private static final Pattern BREAK =
      Pattern.compile("^(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})$");
  private static final Pattern SETEXT = Pattern.compile("^(=+|-+)[ \\t]*$");
  private static final Pattern BULLET = Pattern.compile("^([-+*])([ \\t]+|$)");
  private static final Pattern ORDERED = Pattern.compile("^(\\d{1,9})([.)])([ \\t]+|$)");
  private static final Pattern TABLE_DELIMITER =
      Pattern.compile("^\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$");
  private static final Pattern DEFINITION =
      Pattern.compile(
          "^\\[((?:[^\\]\\\\]|\\\\.)+)\\]:[ \\t]*(<[^>]*>|\\S+)(?:["
              + " \\t]+(\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?[ \\t]*$");
  private static final Pattern HTML_BLOCK =
      Pattern.compile("^(?:<!--|</?([A-Za-z][A-Za-z0-9-]*)(?:[ \\t/>]|$))");
  private static final Pattern OPEN_TAG =
      Pattern.compile(
          "\\G<([A-Za-z][A-Za-z0-9-]*)((?:\\s+[A-Za-z_:][A-Za-z0-9_.:-]*"
              + "(?:\\s*=\\s*(?:[^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*)\\s*(/?)>");
  private static final Pattern ATTRIBUTE =
      Pattern.compile(
          "([A-Za-z_:][A-Za-z0-9_.:-]*)(?:\\s*=\\s*([^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?");
  private static final Pattern CLOSE_TAG = Pattern.compile("\\G</([A-Za-z][A-Za-z0-9-]*)\\s*>");
  private static final Pattern COMMENT = Pattern.compile("\\G<!--.*?-->", Pattern.DOTALL);
  private static final Pattern DECLARATION =
      Pattern.compile("\\G<(?:![A-Za-z][^>]*|\\?.*?\\?)>", Pattern.DOTALL);
  private static final Pattern AUTOLINK =
      Pattern.compile("\\G<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*)>");
  private static final Pattern EMAIL =
      Pattern.compile(
          "\\G<([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
              + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*)>");
  private static final Pattern ENTITY =
      Pattern.compile("\\G&(?:#[xX]([0-9a-fA-F]{1,6})|#([0-9]{1,7})|([A-Za-z][A-Za-z0-9]{0,31}));");
  private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
  private static final Set<String> HTML_BLOCK_TAGS =
      Set.of(
          "address",
          "article",
          "aside",
          "blockquote",
          "caption",
          "center",
          "col",
          "colgroup",
          "dd",
          "details",
          "dialog",
          "div",
          "dl",
          "dt",
          "figcaption",
          "figure",
          "footer",
          "form",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "header",
          "hr",
          "li",
          "main",
          "nav",
          "ol",
          "p",
          "pre",
          "section",
          "summary",
          "table",
          "tbody",
          "td",
          "tfoot",
          "th",
          "thead",
          "tr",
          "ul");

  private final List<List<Event>> slots;
  private final IntFunction<String> slotText;
  private final Consumer<String> problem;
  private final Map<String, String[]> definitions = new HashMap<>();

  /**
   * @param slots each slot's events, by index
   * @param slotText a slot's plain text, for a slot inside code
   * @param problem reports content that cannot be represented (an unsafe URL)
   */
  Markdown(List<List<Event>> slots, IntFunction<String> slotText, Consumer<String> problem) {
    this.slots = slots;
    this.slotText = slotText;
    this.problem = problem;
  }

  /** The slot character for slot {@code index}. */
  static char slot(int index) {
    if (index >= MAX_SLOTS) {
      throw new IllegalArgumentException("Too many tags in one Markdown comment");
    }
    return (char) (SLOT + index);
  }

  private boolean isSlot(char c) {
    return c >= SLOT && c - SLOT < slots.size();
  }

  /**
   * {@code text} as events. A document that is one paragraph gives that paragraph's inlines without
   * the paragraph, so tag descriptions and labels stay inline.
   */
  List<Event> parse(String text) {
    var lines = definitions(lines(text));
    List<List<Event>> blocks = new ArrayList<>();
    blocks(lines, blocks);
    if (blocks.size() == 1
        && blocks.getFirst().getFirst() instanceof StartEvent start
        && start.name().equals("p")) {
      var only = blocks.getFirst();
      return new ArrayList<>(only.subList(1, only.size() - 1));
    }
    List<Event> events = new ArrayList<>();
    blocks.forEach(events::addAll);
    return events;
  }

  // ---- Lines.

  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    for (var line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
      lines.add(expandTabs(line));
    }
    return lines;
  }

  /** Leading tabs as spaces to the next multiple of four. */
  private static String expandTabs(String line) {
    if (line.indexOf('\t') < 0) {
      return line;
    }
    var out = new StringBuilder();
    int i = 0;
    for (; i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'); i++) {
      if (line.charAt(i) == '\t') {
        out.append(" ".repeat(4 - out.length() % 4));
      } else {
        out.append(' ');
      }
    }
    return out.append(line, i, line.length()).toString();
  }

  private static boolean blank(String line) {
    return line.isBlank();
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  /** Removes link reference definitions outside code, recording them. */
  private List<String> definitions(List<String> lines) {
    List<String> kept = new ArrayList<>();
    String fence = null;
    boolean paragraph = false;
    for (var line : lines) {
      var stripped = line.strip();
      if (fence != null) {
        var marker = fence.charAt(0);
        if (stripped.startsWith(fence) && stripped.chars().allMatch(c -> c == marker)) {
          fence = null;
        }
        kept.add(line);
        continue;
      }
      var open = indent(line) <= 3 ? FENCE.matcher(stripped) : null;
      if (open != null && open.matches()) {
        fence = open.group(1);
        kept.add(line);
        paragraph = false;
        continue;
      }
      var definition = indent(line) <= 3 && !paragraph ? DEFINITION.matcher(stripped) : null;
      if (definition != null && definition.matches()) {
        var label = normalizeLabel(definition.group(1));
        var url = definition.group(2);
        if (url.startsWith("<")) {
          url = url.substring(1, url.length() - 1);
        }
        definitions.putIfAbsent(label, new String[] {unescape(url)});
        continue;
      }
      paragraph = !blank(line) && indent(line) < 4;
      kept.add(line);
    }
    return kept;
  }

  private static String normalizeLabel(String label) {
    return label.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  // ---- Blocks.

  private void blocks(List<String> lines, List<List<Event>> into) {
    int i = 0;
    int n = lines.size();
    while (i < n) {
      var line = lines.get(i);
      if (blank(line)) {
        i++;
        continue;
      }
      int indent = indent(line);
      if (indent >= 4) {
        List<String> code = new ArrayList<>();
        while (i < n && (blank(lines.get(i)) || indent(lines.get(i)) >= 4)) {
          var l = lines.get(i++);
          code.add(l.length() >= 4 ? l.substring(4) : "");
        }
        while (!code.isEmpty() && blank(code.getLast())) {
          code.removeLast();
        }
        into.add(List.of(new BlockEvent(new Doc.CodeBlock(null, codeText(code)))));
        continue;
      }
      var s = line.substring(indent);
      var fence = FENCE.matcher(s);
      if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).contains("`"))) {
        i = fenced(lines, i, indent, fence.group(1), fence.group(2).strip(), into);
        continue;
      }
      var atx = ATX.matcher(s);
      if (atx.matches()) {
        var level = atx.group(1).length();
        var content = atx.group(2) == null ? "" : atx.group(2);
        into.add(wrap("h" + level, inlines(content.strip())));
        i++;
        continue;
      }
      if (BREAK.matcher(s).matches()) {
        into.add(List.of(new StartEvent("hr", Map.of(), true)));
        i++;
        continue;
      }
      if (s.startsWith(">")) {
        i = quote(lines, i, into);
        continue;
      }
      if (BULLET.matcher(s).find() || ORDERED.matcher(s).find()) {
        i = list(lines, i, into);
        continue;
      }
      if (i + 1 < n && s.contains("|") && TABLE_DELIMITER.matcher(lines.get(i + 1)).matches()) {
        var head = cells(s);
        if (head.size() == cells(lines.get(i + 1)).size()) {
          i = table(lines, i, head, into);
          continue;
        }
      }
      var html = HTML_BLOCK.matcher(s);
      if (html.find()
          && (html.group(1) == null
              || HTML_BLOCK_TAGS.contains(html.group(1).toLowerCase(Locale.ROOT)))) {
        i = htmlBlock(lines, i, html.group(1), into);
        continue;
      }
      i = paragraph(lines, i, into);
    }
  }

  private int fenced(
      List<String> lines, int i, int indent, String fence, String info, List<List<Event>> into) {
    List<String> code = new ArrayList<>();
    i++;
    while (i < lines.size()) {
      var line = lines.get(i);
      var stripped = line.strip();
      if (indent(line) <= 3
          && stripped.length() >= fence.length()
          && stripped.chars().allMatch(c -> c == fence.charAt(0))) {
        i++;
        break;
      }
      int remove = Math.min(indent, indent(line));
      code.add(line.substring(remove));
      i++;
    }
    var language = info.isEmpty() ? null : unescape(info.split("\\s+")[0]);
    into.add(List.of(new BlockEvent(new Doc.CodeBlock(language, codeText(code)))));
    return i;
  }

  /** Code lines as text; a slot inside code becomes its text. */
  private String codeText(List<String> code) {
    return slotsAsText(String.join("\n", code));
  }

  private String slotsAsText(String text) {
    var out = new StringBuilder();
    for (int k = 0; k < text.length(); k++) {
      var c = text.charAt(k);
      if (isSlot(c)) {
        out.append(slotText.apply(c - SLOT));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private int quote(List<String> lines, int i, List<List<Event>> into) {
    List<String> inner = new ArrayList<>();
    while (i < lines.size()) {
      var line = lines.get(i);
      int indent = indent(line);
      if (indent <= 3 && line.substring(indent).startsWith(">")) {
        var rest = line.substring(indent + 1);
        inner.add(rest.startsWith(" ") ? rest.substring(1) : rest);
        i++;
      } else if (!blank(line)
          && !inner.isEmpty()
          && !blank(inner.getLast())
          && !interruptsParagraph(line)) {
        inner.add(line);
        i++;
      } else {
        break;
      }
    }
    List<List<Event>> blocks = new ArrayList<>();
    blocks(inner, blocks);
    List<Event> events = new ArrayList<>();
    events.add(new StartEvent("blockquote", Map.of(), false));
    blocks.forEach(events::addAll);
    events.add(new EndEvent("blockquote"));
    into.add(events);
    return i;
  }

  /** A list item's marker: whether it is ordered, its delimiter, and its content's column. */
  private record Marker(boolean ordered, char delimiter, int width, boolean empty) {}

  private static Marker marker(String line) {
    int indent = indent(line);
    if (indent > 3) {
      return null;
    }
    var s = line.substring(indent);
    Matcher m;
    boolean ordered;
    char delimiter;
    int markerLength;
    if ((m = BULLET.matcher(s)).find()) {
      ordered = false;
      delimiter = m.group(1).charAt(0);
      markerLength = 1;
    } else if ((m = ORDERED.matcher(s)).find()) {
      ordered = true;
      delimiter = m.group(2).charAt(0);
      markerLength = m.group(1).length() + 1;
    } else {
      return null;
    }
    var spaces = m.group(m.groupCount()).length();
    boolean empty = s.substring(m.end()).isBlank();
    if (spaces == 0 || spaces > 4 || empty) {
      spaces = 1;
    }
    return new Marker(ordered, delimiter, indent + markerLength + spaces, empty);
  }

  private int list(List<String> lines, int i, List<List<Event>> into) {
    var first = marker(lines.get(i));
    List<Event> events = new ArrayList<>();
    var name = first.ordered() ? "ol" : "ul";
    events.add(new StartEvent(name, Map.of(), false));
    int n = lines.size();
    while (i < n) {
      var marker = marker(lines.get(i));
      if (marker == null
          || marker.ordered() != first.ordered()
          || marker.delimiter() != first.delimiter()
          || BREAK.matcher(lines.get(i).strip()).matches()) {
        break;
      }
      var line = lines.get(i);
      List<String> item = new ArrayList<>();
      item.add(marker.empty() ? "" : line.substring(Math.min(marker.width(), line.length())));
      i++;
      while (i < n) {
        var l = lines.get(i);
        if (blank(l)) {
          int j = i;
          while (j < n && blank(lines.get(j))) {
            j++;
          }
          if (j < n && indent(lines.get(j)) >= marker.width()) {
            while (i < j) {
              item.add("");
              i++;
            }
            continue;
          }
          break;
        }
        if (indent(l) >= marker.width()) {
          item.add(l.substring(marker.width()));
          i++;
        } else if (!blank(item.getLast()) && !interruptsParagraph(l) && marker(l) == null) {
          item.add(l.strip());
          i++;
        } else {
          break;
        }
      }
      List<List<Event>> blocks = new ArrayList<>();
      blocks(item, blocks);
      events.add(new StartEvent("li", Map.of(), false));
      blocks.forEach(events::addAll);
      events.add(new EndEvent("li"));
      int j = i;
      while (j < n && blank(lines.get(j))) {
        j++;
      }
      var next = j < n ? marker(lines.get(j)) : null;
      if (next == null
          || next.ordered() != first.ordered()
          || next.delimiter() != first.delimiter()) {
        break;
      }
      i = j;
    }
    events.add(new EndEvent(name));
    into.add(events);
    return i;
  }

  private static List<String> cells(String row) {
    var s = row.strip();
    if (s.startsWith("|")) {
      s = s.substring(1);
    }
    if (s.endsWith("|") && !s.endsWith("\\|")) {
      s = s.substring(0, s.length() - 1);
    }
    List<String> cells = new ArrayList<>();
    var cell = new StringBuilder();
    boolean code = false;
    for (int k = 0; k < s.length(); k++) {
      var c = s.charAt(k);
      if (c == '\\' && k + 1 < s.length() && s.charAt(k + 1) == '|') {
        cell.append('|');
        k++;
      } else if (c == '`') {
        code = !code;
        cell.append(c);
      } else if (c == '|' && !code) {
        cells.add(cell.toString().strip());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString().strip());
    return cells;
  }

  private int table(List<String> lines, int i, List<String> head, List<List<Event>> into) {
    List<Event> events = new ArrayList<>();
    events.add(new StartEvent("table", Map.of(), false));
    events.add(new StartEvent("thead", Map.of(), false));
    row(head, "th", head.size(), events);
    events.add(new EndEvent("thead"));
    i += 2;
    boolean body = false;
    while (i < lines.size() && !blank(lines.get(i)) && !interruptsParagraph(lines.get(i))) {
      if (!body) {
        events.add(new StartEvent("tbody", Map.of(), false));
        body = true;
      }
      row(cells(lines.get(i)), "td", head.size(), events);
      i++;
    }
    if (body) {
      events.add(new EndEvent("tbody"));
    }
    events.add(new EndEvent("table"));
    into.add(events);
    return i;
  }

  private void row(List<String> cells, String cell, int width, List<Event> events) {
    events.add(new StartEvent("tr", Map.of(), false));
    for (int k = 0; k < width; k++) {
      events.add(new StartEvent(cell, Map.of(), false));
      if (k < cells.size()) {
        events.addAll(inlines(cells.get(k)));
      }
      events.add(new EndEvent(cell));
    }
    events.add(new EndEvent("tr"));
  }

  /**
   * An HTML block: a comment ends at {@code -->}, {@code <pre>} at {@code </pre>}, any other
   * block-level element at a blank line.
   */
  private int htmlBlock(List<String> lines, int i, String tag, List<List<Event>> into) {
    var name = tag == null ? null : tag.toLowerCase(Locale.ROOT);
    var end = name == null ? "-->" : name.equals("pre") ? "</pre>" : null;
    List<String> html = new ArrayList<>();
    while (i < lines.size()) {
      var line = lines.get(i);
      if (end == null && blank(line)) {
        break;
      }
      html.add(line);
      i++;
      if (end != null && line.toLowerCase(Locale.ROOT).contains(end)) {
        break;
      }
    }
    into.add(html(String.join("\n", html)));
    return i;
  }

  private int paragraph(List<String> lines, int i, List<List<Event>> into) {
    List<String> text = new ArrayList<>();
    text.add(lines.get(i).stripLeading());
    i++;
    while (i < lines.size()) {
      var line = lines.get(i);
      if (blank(line)) {
        break;
      }
      var setext = indent(line) <= 3 ? SETEXT.matcher(line.strip()) : null;
      if (setext != null && setext.matches()) {
        var level = setext.group(1).charAt(0) == '=' ? 1 : 2;
        into.add(wrap("h" + level, inlines(String.join("\n", text))));
        return i + 1;
      }
      if (interruptsParagraph(line)) {
        break;
      }
      text.add(line.stripLeading());
      i++;
    }
    var content = inlines(String.join("\n", text).stripTrailing());
    if (content.stream()
        .allMatch(e -> e instanceof BlockEvent || e instanceof TextEvent t && t.text().isBlank())) {
      // A paragraph of block tags alone ({@snippet}) is those blocks.
      if (content.stream().anyMatch(BlockEvent.class::isInstance)) {
        into.add(content);
      }
    } else {
      into.add(wrap("p", content));
    }
    return i;
  }

  /** Whether {@code line} starts a block that ends a paragraph before it. */
  private static boolean interruptsParagraph(String line) {
    int indent = indent(line);
    if (indent > 3) {
      return false;
    }
    var s = line.substring(indent);
    if (FENCE.matcher(s).matches()
        || ATX.matcher(s).matches()
        || BREAK.matcher(s).matches()
        || s.startsWith(">")) {
      return true;
    }
    var html = HTML_BLOCK.matcher(s);
    if (html.find()
        && (html.group(1) == null
            || HTML_BLOCK_TAGS.contains(html.group(1).toLowerCase(Locale.ROOT)))) {
      return true;
    }
    var marker = marker(line);
    if (marker == null || marker.empty()) {
      return false;
    }
    if (!marker.ordered()) {
      return true;
    }
    var m = ORDERED.matcher(s);
    return m.find() && m.group(1).equals("1");
  }

  private static List<Event> wrap(String name, List<Event> content) {
    List<Event> events = new ArrayList<>();
    events.add(new StartEvent(name, Map.of(), false));
    events.addAll(content);
    events.add(new EndEvent(name));
    return events;
  }

  // ---- HTML (blocks and inline).

  private List<Event> html(String text) {
    List<Event> events = new ArrayList<>();
    var plain = new StringBuilder();
    int k = 0;
    while (k < text.length()) {
      var c = text.charAt(k);
      if (c == '<') {
        var end = tag(text, k, events, plain);
        if (end > k) {
          k = end;
          continue;
        }
      } else if (c == '&') {
        var m = ENTITY.matcher(text);
        if (m.find(k) && m.start() == k) {
          plain.append(entity(m));
          k = m.end();
          continue;
        }
      } else if (isSlot(c)) {
        flushText(plain, events);
        events.addAll(slots.get(c - SLOT));
        k++;
        continue;
      }
      plain.append(c);
      k++;
    }
    flushText(plain, events);
    return events;
  }

  private static void flushText(StringBuilder plain, List<Event> events) {
    if (!plain.isEmpty()) {
      events.add(new TextEvent(plain.toString()));
      plain.setLength(0);
    }
  }

  /**
   * An HTML tag, comment or declaration at {@code k} as events; returns the index after it, or
   * {@code k} when there is none.
   */
  private int tag(String text, int k, List<Event> events, StringBuilder plain) {
    var comment = COMMENT.matcher(text);
    if (comment.find(k) && comment.start() == k) {
      flushText(plain, events);
      return comment.end();
    }
    var declaration = DECLARATION.matcher(text);
    if (declaration.find(k) && declaration.start() == k) {
      flushText(plain, events);
      return declaration.end();
    }
    var open = OPEN_TAG.matcher(text);
    if (open.find(k) && open.start() == k) {
      flushText(plain, events);
      var name = open.group(1).toLowerCase(Locale.ROOT);
      events.add(new StartEvent(name, attributes(open.group(2)), !open.group(3).isEmpty()));
      return open.end();
    }
    var close = CLOSE_TAG.matcher(text);
    if (close.find(k) && close.start() == k) {
      flushText(plain, events);
      events.add(new EndEvent(close.group(1).toLowerCase(Locale.ROOT)));
      return close.end();
    }
    return k;
  }

  private Map<String, String> attributes(String text) {
    Map<String, String> attributes = new LinkedHashMap<>();
    var m = ATTRIBUTE.matcher(text);
    while (m.find()) {
      var name = m.group(1).toLowerCase(Locale.ROOT);
      var value = m.group(2) == null ? "" : m.group(2);
      if (value.startsWith("\"") || value.startsWith("'")) {
        value = value.substring(1, value.length() - 1);
      }
      value = decodeEntities(value);
      if (Comments.URL_ATTRIBUTES.contains(name) && !Comments.safeUrl(value)) {
        problem.accept(
            "Unsafe URL in " + name + ": only http, https, mailto and relative URLs are allowed");
        continue;
      }
      attributes.put(name, value);
    }
    return attributes;
  }

  private static String decodeEntities(String text) {
    if (text.indexOf('&') < 0) {
      return text;
    }
    var out = new StringBuilder();
    var m = ENTITY.matcher(text);
    int k = 0;
    while (k < text.length()) {
      if (text.charAt(k) == '&' && m.find(k) && m.start() == k) {
        out.append(entity(m));
        k = m.end();
      } else {
        out.append(text.charAt(k++));
      }
    }
    return out.toString();
  }

  /** An entity's text; an unknown named entity stays as written, as in CommonMark. */
  private static String entity(Matcher m) {
    try {
      if (m.group(1) != null) {
        return Character.toString(Integer.parseInt(m.group(1), 16));
      }
      if (m.group(2) != null) {
        return Character.toString(Integer.parseInt(m.group(2)));
      }
    } catch (IllegalArgumentException invalid) {
      return "\uFFFD";
    }
    var text = Comments.ENTITIES.get(m.group(3));
    return text == null ? m.group() : text;
  }

  // ---- Inlines.

  /**
   * One piece of inline content while emphasis is resolved: events, or a run of {@code *} or {@code
   * _} that may open or close emphasis.
   */
  private static final class Piece {
    final List<Event> events = new ArrayList<>();
    final char delimiter;
    int count;
    final int length;
    final boolean canOpen;
    final boolean canClose;
    boolean active = true;
    Piece previous;
    Piece next;
    final List<Event> before = new ArrayList<>();
    final List<Event> after = new ArrayList<>();

    Piece(List<Event> events) {
      this.events.addAll(events);
      this.delimiter = 0;
      this.length = 0;
      this.canOpen = false;
      this.canClose = false;
    }

    Piece(char delimiter, int count, boolean canOpen, boolean canClose) {
      this.delimiter = delimiter;
      this.count = count;
      this.length = count;
      this.canOpen = canOpen;
      this.canClose = canClose;
    }

    boolean isDelimiter() {
      return delimiter != 0;
    }
  }

  private List<Event> inlines(String text) {
    List<Piece> pieces = new ArrayList<>();
    var plain = new StringBuilder();
    int k = 0;
    int n = text.length();
    while (k < n) {
      var c = text.charAt(k);
      switch (c) {
        case '\\' -> {
          if (k + 1 < n && text.charAt(k + 1) == '\n') {
            flush(plain, pieces);
            pieces.add(new Piece(List.of(new StartEvent("br", Map.of(), true))));
            k += 2;
            continue;
          }
          if (k + 1 < n && PUNCTUATION.indexOf(text.charAt(k + 1)) >= 0) {
            plain.append(text.charAt(k + 1));
            k += 2;
            continue;
          }
        }
        case '`' -> {
          int run = run(text, k, '`');
          int close = closingBackticks(text, k + run, run);
          if (close < 0) {
            plain.append("`".repeat(run));
            k += run;
            continue;
          }
          flush(plain, pieces);
          var code = text.substring(k + run, close).replace('\n', ' ');
          if (code.length() > 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
            code = code.substring(1, code.length() - 1);
          }
          pieces.add(new Piece(List.of(new InlineEvent(new Doc.Code(slotsAsText(code))))));
          k = close + run;
          continue;
        }
        case '*', '_' -> {
          int run = run(text, k, c);
          flush(plain, pieces);
          var before = k == 0 ? '\n' : text.charAt(k - 1);
          var after = k + run >= n ? '\n' : text.charAt(k + run);
          boolean left =
              !Character.isWhitespace(after)
                  && (!punctuation(after) || Character.isWhitespace(before) || punctuation(before));
          boolean right =
              !Character.isWhitespace(before)
                  && (!punctuation(before) || Character.isWhitespace(after) || punctuation(after));
          boolean open = c == '*' ? left : left && (!right || punctuation(before));
          boolean close = c == '*' ? right : right && (!left || punctuation(after));
          pieces.add(new Piece(c, run, open, close));
          k += run;
          continue;
        }
        case '!', '[' -> {
          boolean image = c == '!';
          if (!image || (k + 1 < n && text.charAt(k + 1) == '[')) {
            var link = link(text, image ? k + 1 : k, image);
            if (link != null) {
              flush(plain, pieces);
              pieces.add(new Piece(link.events()));
              k = link.end();
              continue;
            }
          }
        }
        case '<' -> {
          var auto = AUTOLINK.matcher(text);
          var email = EMAIL.matcher(text);
          if (auto.find(k) && auto.start() == k) {
            flush(plain, pieces);
            pieces.add(new Piece(urlLink(auto.group(1), auto.group(1))));
            k = auto.end();
            continue;
          }
          if (email.find(k) && email.start() == k) {
            flush(plain, pieces);
            pieces.add(new Piece(urlLink("mailto:" + email.group(1), email.group(1))));
            k = email.end();
            continue;
          }
          List<Event> events = new ArrayList<>();
          var end = tag(text, k, events, plain);
          if (end > k) {
            flush(plain, pieces);
            pieces.add(new Piece(events));
            k = end;
            continue;
          }
        }
        case '&' -> {
          var m = ENTITY.matcher(text);
          if (m.find(k) && m.start() == k) {
            plain.append(entity(m));
            k = m.end();
            continue;
          }
        }
        case '\n' -> {
          int spaces = 0;
          while (plain.length() - spaces > 0 && plain.charAt(plain.length() - 1 - spaces) == ' ') {
            spaces++;
          }
          plain.setLength(plain.length() - spaces);
          if (spaces >= 2) {
            flush(plain, pieces);
            pieces.add(new Piece(List.of(new StartEvent("br", Map.of(), true))));
          } else {
            plain.append('\n');
          }
          k++;
          while (k < n && text.charAt(k) == ' ') {
            k++;
          }
          continue;
        }
        default -> {
          if (isSlot(c)) {
            flush(plain, pieces);
            pieces.add(new Piece(slots.get(c - SLOT)));
            k++;
            continue;
          }
        }
      }
      plain.append(c);
      k++;
    }
    flush(plain, pieces);
    return emphasis(pieces);
  }

  private static void flush(StringBuilder plain, List<Piece> pieces) {
    if (!plain.isEmpty()) {
      pieces.add(new Piece(List.of(new TextEvent(plain.toString()))));
      plain.setLength(0);
    }
  }

  private static boolean punctuation(char c) {
    if (PUNCTUATION.indexOf(c) >= 0) {
      return true;
    }
    return switch (Character.getType(c)) {
      case Character.CONNECTOR_PUNCTUATION,
          Character.DASH_PUNCTUATION,
          Character.START_PUNCTUATION,
          Character.END_PUNCTUATION,
          Character.INITIAL_QUOTE_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION,
          Character.OTHER_PUNCTUATION,
          Character.MATH_SYMBOL,
          Character.CURRENCY_SYMBOL,
          Character.MODIFIER_SYMBOL,
          Character.OTHER_SYMBOL ->
          true;
      default -> false;
    };
  }

  private static int run(String text, int k, char c) {
    int end = k;
    while (end < text.length() && text.charAt(end) == c) {
      end++;
    }
    return end - k;
  }

  /** The start of the backtick run of exactly {@code length} after {@code from}, or -1. */
  private static int closingBackticks(String text, int from, int length) {
    int k = from;
    while (k < text.length()) {
      if (text.charAt(k) == '`') {
        int run = run(text, k, '`');
        if (run == length) {
          return k;
        }
        k += run;
      } else {
        k++;
      }
    }
    return -1;
  }

  private record Parsed(List<Event> events, int end) {}

  /**
   * A link or image whose {@code [} is at {@code k}: inline ({@code [text](url)}), full ({@code
   * [text][label]}), collapsed ({@code [label][]}) or shortcut ({@code [label]}) by reference.
   */
  private Parsed link(String text, int k, boolean image) {
    int close = closingBracket(text, k);
    if (close < 0) {
      return null;
    }
    var label = text.substring(k + 1, close);
    int after = close + 1;
    String url = null;
    int end = -1;
    if (after < text.length() && text.charAt(after) == '(') {
      var destination = destination(text, after + 1);
      if (destination != null) {
        url = destination[0];
        end = Integer.parseInt(destination[1]);
      }
    }
    if (url == null && after < text.length() && text.charAt(after) == '[') {
      int refClose = closingBracket(text, after);
      if (refClose > 0) {
        var ref = text.substring(after + 1, refClose);
        var definition = definitions.get(normalizeLabel(ref.isEmpty() ? label : ref));
        if (definition != null) {
          url = definition[0];
          end = refClose + 1;
        }
      }
    }
    if (url == null && !label.isBlank()) {
      var definition = definitions.get(normalizeLabel(label));
      if (definition != null) {
        url = definition[0];
        end = after;
      }
    }
    if (url == null) {
      return null;
    }
    var content = inlines(label);
    if (image) {
      var alt = new StringBuilder();
      for (var event : content) {
        if (event instanceof TextEvent t) {
          alt.append(t.text());
        } else if (event instanceof InlineEvent i && i.inline() instanceof Doc.Code code) {
          alt.append(code.text());
        }
      }
      if (!Comments.safeUrl(url)) {
        problem.accept(
            "Unsafe URL in an image: only http, https, mailto and relative URLs are allowed");
        return new Parsed(List.of(new TextEvent(alt.toString())), end);
      }
      Map<String, String> attributes = new LinkedHashMap<>();
      attributes.put("src", url);
      attributes.put("alt", alt.toString());
      return new Parsed(List.of(new StartEvent("img", attributes, true)), end);
    }
    return new Parsed(anchor(url, content), end);
  }

  private List<Event> urlLink(String url, String text) {
    return anchor(url, List.of(new TextEvent(text)));
  }

  private List<Event> anchor(String url, List<Event> content) {
    if (!Comments.safeUrl(url)) {
      problem.accept(
          "Unsafe URL in a link: only http, https, mailto and relative URLs are allowed");
      return content;
    }
    List<Event> events = new ArrayList<>();
    events.add(new StartEvent("a", Map.of("href", url), false));
    events.addAll(content);
    events.add(new EndEvent("a"));
    return events;
  }

  /** The {@code ]} matching the {@code [} at {@code k}, skipping code spans and escapes, or -1. */
  private static int closingBracket(String text, int k) {
    int depth = 0;
    for (int i = k; i < text.length(); i++) {
      var c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '`') {
        int run = run(text, i, '`');
        int close = closingBackticks(text, i + run, run);
        i = close < 0 ? i + run - 1 : close + run - 1;
      } else if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * An inline link's destination and optional title starting after {@code (}: the URL and the index
   * after {@code )}, or {@code null}.
   */
  private static String[] destination(String text, int k) {
    int n = text.length();
    while (k < n && Character.isWhitespace(text.charAt(k))) {
      k++;
    }
    var url = new StringBuilder();
    if (k < n && text.charAt(k) == '<') {
      k++;
      while (k < n && text.charAt(k) != '>' && text.charAt(k) != '\n') {
        if (text.charAt(k) == '\\' && k + 1 < n) {
          k++;
        }
        url.append(text.charAt(k++));
      }
      if (k >= n || text.charAt(k) != '>') {
        return null;
      }
      k++;
    } else {
      int parens = 0;
      while (k < n && !Character.isWhitespace(text.charAt(k))) {
        var c = text.charAt(k);
        if (c == '\\' && k + 1 < n && PUNCTUATION.indexOf(text.charAt(k + 1)) >= 0) {
          url.append(text.charAt(k + 1));
          k += 2;
          continue;
        }
        if (c == '(') {
          parens++;
        } else if (c == ')') {
          if (parens == 0) {
            break;
          }
          parens--;
        }
        url.append(c);
        k++;
      }
    }
    while (k < n && Character.isWhitespace(text.charAt(k))) {
      k++;
    }
    if (k < n && (text.charAt(k) == '"' || text.charAt(k) == '\'' || text.charAt(k) == '(')) {
      var closer = text.charAt(k) == '(' ? ')' : text.charAt(k);
      k++;
      while (k < n && text.charAt(k) != closer) {
        if (text.charAt(k) == '\\') {
          k++;
        }
        k++;
      }
      if (k >= n) {
        return null;
      }
      k++;
      while (k < n && Character.isWhitespace(text.charAt(k))) {
        k++;
      }
    }
    if (k >= n || text.charAt(k) != ')') {
      return null;
    }
    return new String[] {decodeEntities(url.toString()), Integer.toString(k + 1)};
  }

  /** Backslash escapes removed. */
  private static String unescape(String text) {
    return decodeEntities(text.replaceAll("\\\\([!-/:-@\\[-`{-~])", "$1"));
  }

  /** CommonMark's emphasis resolution over the pieces, then the pieces as events. */
  private static List<Event> emphasis(List<Piece> list) {
    Piece head = null;
    Piece last = null;
    for (var piece : list) {
      if (head == null) {
        head = piece;
      } else {
        last.next = piece;
        piece.previous = last;
      }
      last = piece;
    }
    for (var closer = head; closer != null; closer = closer.next) {
      if (!closer.isDelimiter() || !closer.canClose) {
        continue;
      }
      while (closer.count > 0) {
        Piece opener = null;
        for (var candidate = closer.previous; candidate != null; candidate = candidate.previous) {
          if (candidate.isDelimiter()
              && candidate.active
              && candidate.canOpen
              && candidate.count > 0
              && candidate.delimiter == closer.delimiter
              && !((candidate.canClose || closer.canOpen)
                  && (candidate.length + closer.length) % 3 == 0
                  && !(candidate.length % 3 == 0 && closer.length % 3 == 0))) {
            opener = candidate;
            break;
          }
        }
        if (opener == null) {
          break;
        }
        int use = closer.count >= 2 && opener.count >= 2 ? 2 : 1;
        var name = use == 2 ? "strong" : "em";
        opener.count -= use;
        closer.count -= use;
        opener.after.addFirst(new StartEvent(name, Map.of(), false));
        closer.before.add(new EndEvent(name));
        for (var between = opener.next; between != closer; between = between.next) {
          between.active = false;
        }
      }
    }
    List<Event> events = new ArrayList<>();
    for (var piece = head; piece != null; piece = piece.next) {
      events.addAll(piece.before);
      if (piece.isDelimiter()) {
        if (piece.count > 0) {
          events.add(new TextEvent(String.valueOf(piece.delimiter).repeat(piece.count)));
        }
      } else {
        events.addAll(piece.events);
      }
      events.addAll(piece.after);
    }
    return events;
  }
}
