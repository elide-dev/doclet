package dev.elide.apidocs.doclet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.elide.apidocs.model.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MarkdownTest {
  private final List<String> problems = new ArrayList<>();

  private String parse(String text, List<List<Comments.Event>> slots) {
    var events = new Markdown(slots, i -> "slot" + i, problems::add).parse(text.strip());
    return events.stream().map(MarkdownTest::show).collect(Collectors.joining());
  }

  private String parse(String text) {
    return parse(text, List.of());
  }

  /** Events as compact HTML-like text. */
  private static String show(Comments.Event event) {
    if (event instanceof Comments.TextEvent t) {
      return t.text();
    }
    if (event instanceof Comments.StartEvent s) {
      return "<"
          + s.name()
          + s.attributes().entrySet().stream()
              .map(e -> " " + e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining())
          + (s.selfClosing() ? "/>" : ">");
    }
    if (event instanceof Comments.EndEvent e) {
      return "</" + e.name() + ">";
    }
    if (event instanceof Comments.InlineEvent i && i.inline() instanceof Doc.Code c) {
      return "{code " + c.text() + "}";
    }
    if (event instanceof Comments.BlockEvent b && b.block() instanceof Doc.CodeBlock c) {
      return "{codeblock " + c.language() + ": " + c.text() + "}";
    }
    return "{" + event + "}";
  }

  @Test
  void singleParagraphStaysInline() {
    assertEquals("Hello {code world}.", parse("Hello `world`."));
  }

  @Test
  void paragraphsAndSoftBreaks() {
    assertEquals("<p>One\ntwo.</p><p>Three.</p>", parse("One\n  two.\n\nThree."));
  }

  @Test
  void hardBreaks() {
    assertEquals("<p>a<br/>b<br/>c</p><p>d</p>", parse("a  \nb\\\nc\n\nd"));
  }

  @Test
  void headings() {
    assertEquals(
        "<h2>Title</h2><h1>Setext</h1><h2>Two</h2><p>x</p>",
        parse("## Title ##\nSetext\n===\nTwo\n---\nx"));
  }

  @Test
  void emphasis() {
    assertEquals(
        "<em>a</em> <strong>b</strong> <em><strong>c</strong></em> snake_case_name * x",
        parse("*a* __b__ ***c*** snake_case_name * x"));
    assertEquals("<em>a <strong>b</strong> c</em>", parse("*a **b** c*"));
  }

  @Test
  void codeSpans() {
    assertEquals("{code a ` b} {code x}", parse("`` a ` b `` ` x `"));
    assertEquals("{code *not emphasis*}", parse("`*not emphasis*`"));
  }

  @Test
  void escapesAndEntities() {
    assertEquals("*literal* & © &bogus; <", parse("\\*literal\\* &amp; &copy; &bogus; &#60;"));
  }

  @Test
  void fencedAndIndentedCode() {
    assertEquals(
        "{codeblock java: int x = 1;\n  y();}<p>between</p>{codeblock null: indented\n  more}",
        parse(
            """
            ```java
            int x = 1;
              y();
            ```
            between

                indented
                  more
            """));
    assertEquals("{codeblock null: ~~~\nstill code}", parse("~~~~\n~~~\nstill code\n~~~~"));
  }

  @Test
  void lists() {
    assertEquals(
        "<ul><li><p>one</p></li><li><p>two</p><ul><li><p>nested</p></li></ul></li></ul><ol><li>"
            + "<p>first</p></li><li><p>second\ncontinued</p></li></ol>",
        parse(
            """
            - one
            - two
              - nested

            1. first
            2. second
            continued
            """));
  }

  @Test
  void looseListItemsKeepParagraphs() {
    assertEquals(
        "<ul><li><p>a</p><p>more a</p></li><li><p>b</p></li></ul>",
        parse(
            """
            * a

              more a
            * b
            """));
  }

  @Test
  void blockQuoteAndThematicBreak() {
    assertEquals(
        "<blockquote><p>quoted\nlazy</p><p>second</p></blockquote><hr/><p>after</p>",
        parse("> quoted\nlazy\n>\n> second\n\n---\n\nafter"));
  }

  @Test
  void tables() {
    assertEquals(
        "<table><thead><tr><th>a</th><th>{code b|c}</th></tr></thead><tbody><tr><td>1</td>"
            + "<td><em>2</em></td></tr></tbody></table>",
        parse(
            """
            | a | `b|c` |
            |---|:-----:|
            | 1 | *2*   |
            """));
  }

  @Test
  void links() {
    assertEquals(
        "<a href=https://example.com/a_(b)>the <em>site</em></a> <a"
            + " href=https://x.dev>https://x.dev</a> <a"
            + " href=mailto:me@example.com>me@example.com</a> <a href=/docs>ref</a> <img src=i.png"
            + " alt=alt text/> [not a link]",
        parse(
            """
            [the *site*](https://example.com/a_(b) "title") <https://x.dev> <me@example.com> \
            [ref][docs] ![alt *text*](i.png) [not a link]

            [docs]: /docs
            """));
  }

  @Test
  void unsafeUrlsAreProblems() {
    assertEquals(
        "<p>click</p><p><a>x</a></p>",
        parse("[click](javascript:alert(1))\n\n<a href='javascript:x'>x</a>"));
    assertEquals(2, problems.size(), problems::toString);
  }

  @Test
  void inlineAndBlockHtml() {
    assertEquals(
        "<p>a <b class=x>bold</b> b</p><div><p>html</p></div><pre>\n\n  x\n</pre>",
        parse(
            """
            a <b class="x">bold</b> <!-- c -->b

            <div><p>html</p></div>

            <pre>

              x
            </pre>
            """));
  }

  @Test
  void slotsSpliceTheirEvents() {
    var link = new Comments.InlineEvent(new Doc.Code("String"));
    var block = new Comments.BlockEvent(new Doc.CodeBlock("java", "snippet()"));
    assertEquals(
        "<p>See {code String} and <em>{code String}</em>.</p>{codeblock java: snippet()}"
            + "{codeblock null: slot0}",
        parse(
            "See "
                + Markdown.slot(0)
                + " and *"
                + Markdown.slot(0)
                + "*.\n\n"
                + Markdown.slot(1)
                + "\n\n    "
                + Markdown.slot(0),
            List.of(List.of(link), List.of(block))));
    assertTrue(problems.isEmpty());
  }
}
