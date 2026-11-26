/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api.MemberKind;
import dev.elide.apidocs.model.Api.Token;
import dev.elide.apidocs.model.Api.TokenKind;
import dev.elide.apidocs.model.Api.TypeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * {@link SignatureLayout} with fake, deterministic formatters: how laid-out whitespace maps onto
 * tokens, the stub shapes, and every fallback.
 */
public final class SignatureLayoutTest {
  private static final Declaration DRAW =
      Declaration.member(
          "Canvas.draw",
          MemberKind.METHOD,
          TypeKind.INTERFACE,
          tokens(
              "k:void",
              " ",
              "n:draw",
              "(",
              "a:@Nullable",
              " ",
              "t:Shape",
              " ",
              "p:shape",
              ", ",
              "k:int",
              " ",
              "p:x",
              ") ",
              "k:throws",
              " ",
              "t:IOException"));
  private static final Declaration NEAREST =
      Declaration.member(
          "Geometry.nearest",
          MemberKind.FUNCTION,
          TypeKind.OBJECT,
          tokens(
              "k:fun",
              " ",
              "n:nearest",
              "(",
              "p:point",
              ": ",
              "t:Point",
              ", ",
              "p:limit",
              ": ",
              "t:Int",
              " = ",
              "l:Int.MAX_VALUE",
              "): ",
              "t:Point",
              "?"));
  private static final Declaration CONSTANT =
      Declaration.member(
          "Units.METERS",
          MemberKind.ENUM_CONSTANT,
          TypeKind.ENUM,
          tokens("n:METERS", "(", "l:\"a  b\"", ")"));

  /** The stub puts each declaration in a wrapper of its container's kind, between markers. */
  @Test
  void stubShapes() {
    var ctor =
        Declaration.member(
            "Polygon()",
            MemberKind.CONSTRUCTOR,
            TypeKind.ENUM,
            tokens("n:Polygon", "(", "k:int", " ", "p:sides", ")"));
    var alias =
        Declaration.typeAlias(
            "Shapes", tokens("k:typealias", " ", "n:Shapes", " = ", "t:List", "<", "t:Shape", ">"));
    var top =
        Declaration.type("Shape", null, tokens("k:public", " ", "k:interface", " ", "n:Shape"));
    var element =
        Declaration.member(
            "Marker.value",
            MemberKind.ANNOTATION_ELEMENT,
            TypeKind.ANNOTATION,
            tokens("t:String", " ", "n:value", "()"));
    var java =
        Stub.build(Language.JAVA, List.of(ctor, top, element, CONSTANT), List.of(0, 1, 2, 3))
            .source();
    equal(
        java,
        """
        enum Polygon {
        ;
        // apidocs:0
        Polygon(int sides) {}
        // apidocs:0:end
        }

        // apidocs:1
        public interface Shape {}
        // apidocs:1:end

        @interface Stub {
        // apidocs:2
        String value();
        // apidocs:2:end
        }

        enum Stub {
        // apidocs:3
        METERS("a  b");
        // apidocs:3:end
        }

        """,
        "Java stub");
    var kotlin =
        Stub.build(Language.KOTLIN, List.of(NEAREST, alias, CONSTANT), List.of(0, 1, 2)).source();
    equal(
        kotlin,
        """
        object Stub {
        // apidocs:0
        fun nearest(point: Point, limit: Int = Int.MAX_VALUE): Point? {}
        // apidocs:0:end
        }

        // apidocs:1
        typealias Shapes = List<Shape>
        // apidocs:1:end

        enum class Stub {
        // apidocs:2
        METERS("a  b"),
        // apidocs:2:end
        }

        """,
        "Kotlin stub");
    var nested =
        Declaration.type(
            "Canvas.Layer", TypeKind.INTERFACE, tokens("k:interface", " ", "n:Canvas.Layer"));
    var field =
        Declaration.member(
            "Canvas.MAX", MemberKind.FIELD, TypeKind.INTERFACE, tokens("k:int", " ", "n:MAX"));
    equal(
        Stub.build(Language.JAVA, List.of(nested, field), List.of(0, 1)).source(),
        """
        interface Stub {
        // apidocs:0
        interface Canvas_Layer {}
        // apidocs:0:end
        }

        class Stub {
        // apidocs:1
        int MAX;
        // apidocs:1:end
        }

        """,
        "a nested name is an identifier in the stub; a field sits in a class");
    var restored =
        new SignatureLayout(fake(UnaryOperator.identity()), new Log())
            .layout(Language.JAVA, List.of(nested))
            .get(0);
    equal(restored, nested.tokens(), "the nested name comes back with its dot");
    var stub = Stub.build(Language.JAVA, List.of(ctor, top), List.of(0, 1));
    equal(String.valueOf(stub.declarationAt(4)), "0", "line 4 is the constructor's");
    equal(String.valueOf(stub.declarationAt(9)), "1", "line 9 is the interface's");
  }

  /** Breaks after commas land in the comma tokens; indentation is relative to the declaration. */
  @Test
  void breaksMapIntoPunctuation() {
    var log = new Log();
    var layout =
        new SignatureLayout(
            fake(text ->
                indentMembers(text)
                    .replace(", int", ",\n            int")
                    .replace(") throws", ")\n        throws")),
            log);
    var out = layout.layout(Language.JAVA, List.of(DRAW)).get(0);
    equal(
        show(out),
        "void draw(@Nullable Shape shape,\n        int x)\n    throws IOException",
        "laid-out text");
    equal(out.size(), DRAW.tokens().size(), "no tokens added");
    for (int i = 0; i < out.size(); i++) {
      var before = DRAW.tokens().get(i);
      var after = out.get(i);
      equal(after.kind(), before.kind(), "kind of token " + i);
      equal(after.ref(), before.ref(), "target of token " + i);
      if (after.kind() != TokenKind.PUNCT) {
        equal(after.text(), before.text(), "text of token " + i);
      }
    }
    equal(out.get(9).text(), ",\n        ", "the comma token holds the break");
    equal(out.get(13).text(), ")\n    ", "the closing token holds the break before throws");
    equal(layout.stats().multiline(), 1, "one multiline signature");
    equal(log.warnings, List.of(), "no warnings");

    // A formatter that changes nothing keeps one line.
    var same = new SignatureLayout(fake(UnaryOperator.identity()), new Log());
    equal(
        same.layout(Language.JAVA, List.of(DRAW)).get(0),
        DRAW.tokens(),
        "unchanged by an identity formatter");
    equal(same.stats().multiline(), 0, "nothing multiline");
  }

  /**
   * Whitespace where the signature had none joins a punctuation token, or becomes one between two
   * others.
   */
  @Test
  void insertedWhitespace() {
    var adjacent =
        Declaration.member(
            "Box.open",
            MemberKind.METHOD,
            TypeKind.CLASS,
            tokens("a:@A", "t:Box", " ", "n:open", "(", "t:Box", " ", "p:b", ")"));
    var layout =
        new SignatureLayout(
            fake(text -> text.replace("@ABox", "@A Box").replace("open(", "open(\n    ")),
            new Log());
    var out = layout.layout(Language.JAVA, List.of(adjacent)).get(0);
    equal(show(out), "@A Box open(\n    Box b)", "laid-out text");
    equal(
        out.get(1),
        Token.of(TokenKind.PUNCT, " "),
        "a new whitespace token between the annotation and the type");
    equal(out.get(5).text(), "(\n    ", "the break after ( joins the ( token");
    equal(out.size(), adjacent.tokens().size() + 1, "one token added");
  }

  /** ktfmt's trailing comma is kept in Kotlin and refused in Java. */
  @Test
  void trailingCommas() {
    UnaryOperator<String> ktfmt =
        text ->
            text.replace("nearest(", "nearest(\n  ")
                .replace(", limit", ",\n  limit")
                .replace("MAX_VALUE)", "MAX_VALUE,\n)");
    var log = new Log();
    var out =
        new SignatureLayout(fake(ktfmt), log).layout(Language.KOTLIN, List.of(NEAREST)).get(0);
    equal(
        show(out),
        "fun nearest(\n  point: Point,\n  limit: Int = Int.MAX_VALUE,\n): Point?",
        "laid-out text");
    equal(out.get(out.size() - 3).text(), ",\n): ", "the trailing comma joins the closing token");
    equal(log.warnings, List.of(), "no warnings");

    var javaLog = new Log();
    var java =
        Declaration.member(
            "f",
            MemberKind.METHOD,
            TypeKind.CLASS,
            tokens("k:void", " ", "n:f", "(", "k:int", " ", "p:x", ")"));
    var kept =
        new SignatureLayout(fake(text -> text.replace("int x)", "int x,\n)")), javaLog)
            .layout(Language.JAVA, List.of(java))
            .get(0);
    equal(kept, java.tokens(), "Java keeps one line");
    contains(javaLog.warnings, "Signature of f kept on one line: the formatter changed the text");
  }

  /**
   * A formatter that changes anything but whitespace leaves the declaration on one line, with a
   * warning naming it.
   */
  @Test
  void changedTextFallsBack() {
    var reordered =
        Declaration.member(
            "Registry.of",
            MemberKind.METHOD,
            TypeKind.CLASS,
            tokens("k:static", " ", "k:public", " ", "k:void", " ", "n:of", "()"));
    var log = new Log();
    var layout =
        new SignatureLayout(
            fake(
                text -> text.replace("static public", "public static").replace(", int", ",\n int")),
            log);
    var out = layout.layout(Language.JAVA, List.of(reordered, DRAW));
    equal(out.get(0), reordered.tokens(), "the reordered declaration keeps one line");
    equal(show(out.get(1)).contains("\n"), true, "the other one is laid out");
    contains(
        log.warnings,
        "Signature of Registry.of kept on one line: the formatter changed the text at \"static"
            + " public");
    equal(layout.stats().fallbacks(), 1, "one fallback");
  }

  /** Whitespace inside a literal must come back unchanged. */
  @Test
  void changedLiteralWhitespaceFallsBack() {
    var log = new Log();
    var out =
        new SignatureLayout(fake(text -> text.replace("\"a  b\"", "\"a b\"")), log)
            .layout(Language.JAVA, List.of(CONSTANT))
            .get(0);
    equal(out, CONSTANT.tokens(), "unchanged");
    contains(log.warnings, "Signature of Units.METERS kept on one line: the formatter changed");
  }

  /** A declaration whose markers the formatter drops keeps one line; the rest are laid out. */
  @Test
  void lostMarkerFallsBack() {
    var log = new Log();
    var layout =
        new SignatureLayout(
            fake(text -> text.replace("// apidocs:0:end", "").replace(", int", ",\n int")), log);
    var out = layout.layout(Language.JAVA, List.of(CONSTANT, DRAW));
    equal(out.get(0), CONSTANT.tokens(), "the first keeps one line");
    equal(show(out.get(1)).contains("\n"), true, "the second is laid out");
    contains(log.warnings, "Signature of Units.METERS kept on one line: its markers are missing");
  }

  /** A failure that names a line drops that line's declaration and runs again. */
  @Test
  void failureNamingALine() {
    var log = new Log();
    var bad =
        Declaration.member(
            "Bad.f",
            MemberKind.METHOD,
            TypeKind.CLASS,
            tokens("k:void", " ", "n:f", "(", "k:int", ")"));
    var formatter = new Fake(text -> text.replace(", int", ",\n int"), "int)");
    var layout = new SignatureLayout(formatter, log);
    var out = layout.layout(Language.JAVA, List.of(DRAW, bad, CONSTANT));
    equal(out.get(1), bad.tokens(), "the failing declaration keeps one line");
    equal(show(out.get(0)).contains("\n"), true, "the others are laid out");
    equal(layout.stats().formatterRuns(), 2, "one retry");
    contains(log.warnings, "Signature of Bad.f kept on one line: fake failed");
    equal(log.warnings.size(), 1, "one warning");
  }

  /** A failure without a line splits the batch until the failing declaration is alone. */
  @Test
  void failureWithoutALine() {
    var log = new Log();
    var bad =
        Declaration.member(
            "Bad.f",
            MemberKind.METHOD,
            TypeKind.CLASS,
            tokens("k:void", " ", "n:f", "(", "k:int", ")"));
    var formatter = new Fake(text -> text.replace(", int", ",\n int"), "int)");
    formatter.reportLines = false;
    var layout = new SignatureLayout(formatter, log);
    List<Declaration> batch = new ArrayList<>(List.of(DRAW, DRAW, DRAW, bad, DRAW));
    var out = layout.layout(Language.JAVA, batch);
    equal(out.get(3), bad.tokens(), "the failing declaration keeps one line");
    for (int i : List.of(0, 1, 2, 4)) {
      equal(show(out.get(i)).contains("\n"), true, "declaration " + i + " is laid out");
    }
    equal(log.warnings.size(), 1, "one warning");
    equal(layout.stats().fallbacks(), 1, "one fallback");
  }

  /**
   * Without a formatter, signatures come back unchanged and that is reported once, as information.
   */
  @Test
  void noFormatter() {
    var log = new Log();
    var layout = SignatureLayout.elide("/nonexistent/elide", log);
    equal(layout.enabled(), false, "no formatter");
    equal(layout.layout(Language.JAVA, List.of(DRAW)).get(0), DRAW.tokens(), "unchanged");
    equal(layout.layout(Language.KOTLIN, List.of(NEAREST)).get(0), NEAREST.tokens(), "unchanged");
    equal(log.infos.size(), 1, "reported once");
    contains(
        log.infos,
        "Signatures stay on one line: no source formatter (/nonexistent/elide) was found");
    equal(log.warnings, List.of(), "not a warning");
    equal(ElideFormatter.locate(null, Map.of("PATH", "/nonexistent")), null, "nothing on PATH");

    var off = new Log();
    var none = SignatureLayout.elide("none", off);
    none.layout(Language.JAVA, List.of(DRAW));
    none.layout(Language.JAVA, List.of(DRAW));
    equal(
        off.infos,
        List.of("Signatures stay on one line: no source formatter is configured"),
        "turned off, reported once");
  }

  /**
   * Indents every line between a marker pair by four spaces, as a formatter indents a wrapper's
   * members.
   */
  private static String indentMembers(String text) {
    var out = new StringBuilder();
    boolean inside = false;
    for (var line : text.split("\n", -1)) {
      if (line.startsWith("// apidocs:")) {
        inside = !line.endsWith(":end");
        out.append("    ").append(line).append('\n');
        continue;
      }
      out.append(inside ? "    " : "").append(line).append('\n');
    }
    return out.substring(0, out.length() - 1);
  }

  private static SourceFormatter fake(UnaryOperator<String> transform) {
    return new Fake(transform, null);
  }

  /** A formatter that applies a text transform, or fails on any line containing {@code poison}. */
  private static final class Fake implements SourceFormatter {
    private final UnaryOperator<String> transform;
    private final String poison;
    boolean reportLines = true;

    Fake(UnaryOperator<String> transform, String poison) {
      this.transform = transform;
      this.poison = poison;
    }

    @Override
    public String format(Language language, String source) throws FormatterException {
      if (poison != null) {
        var lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if (lines[i].contains(poison)) {
            throw new FormatterException(
                "fake failed at line " + (i + 1), reportLines ? List.of(i + 1) : List.of());
          }
        }
      }
      return transform.apply(source);
    }

    @Override
    public String describe() {
      return "fake";
    }
  }

  private static final class Log implements SignatureLayout.Log {
    final List<String> infos = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void warning(String message) {
      warnings.add(message);
    }
  }

  private static void equal(Object actual, Object expected, String what) {
    if (!java.util.Objects.equals(actual, expected)) {
      throw new AssertionError(
          what + ":\n  expected: " + escape(expected) + "\n  actual:   " + escape(actual));
    }
  }

  private static void contains(List<String> messages, String prefix) {
    for (var message : messages) {
      if (message.startsWith(prefix)) {
        return;
      }
    }
    throw new AssertionError("No message starts with \"" + prefix + "\": " + messages);
  }

  private static String escape(Object value) {
    return String.valueOf(value).replace("\n", "\\n");
  }

  static List<Token> tokens(String... pieces) {
    List<Token> out = new ArrayList<>();
    for (var piece : pieces) {
      int colon = piece.indexOf(':');
      var prefix = colon > 0 && colon <= 2 ? piece.substring(0, colon) : "";
      var kind =
          switch (prefix) {
            case "k" -> TokenKind.KEYWORD;
            case "n" -> TokenKind.NAME;
            case "t" -> TokenKind.TYPE;
            case "tp" -> TokenKind.TYPE_PARAM;
            case "a" -> TokenKind.ANNOTATION;
            case "l" -> TokenKind.LITERAL;
            case "p" -> TokenKind.PARAM;
            default -> TokenKind.PUNCT;
          };
      var text = kind == TokenKind.PUNCT ? piece : piece.substring(colon + 1);
      if (kind == TokenKind.PUNCT
          && !out.isEmpty()
          && out.get(out.size() - 1).kind() == TokenKind.PUNCT) {
        out.set(out.size() - 1, Token.of(TokenKind.PUNCT, out.get(out.size() - 1).text() + text));
      } else {
        out.add(
            kind == TokenKind.TYPE
                ? new Token(kind, text, "ref:" + text, null)
                : Token.of(kind, text));
      }
    }
    return out;
  }

  static String show(List<Token> tokens) {
    var text = new StringBuilder();
    for (var token : tokens) {
      text.append(token.text());
    }
    return text.toString();
  }
}
