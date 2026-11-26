/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The synthetic source file the formatter lays out: every declaration of a batch as a real
 * declaration, each inside a wrapper type of its container's kind and between two marker comments,
 * so the formatter treats it as it would in source and its lines can be found again.
 *
 * <p>Stub shapes. Java: a type gets an empty body ({@code {}}); a method or constructor an empty
 * body; a field, an enum constant and an annotation element end with {@code ;}. A constructor's
 * wrapper takes the constructor's name, which javac's parser requires; a field always sits in a
 * class, since the parser wants an interface's fields initialized. A nested type's name ({@code
 * Outer.Inner}) is written {@code Outer_Inner}, the same width but an identifier. Members of an
 * enum follow a lone {@code ;}, which ends the (empty) constant list. Kotlin: a function or
 * constructor gets an empty body; an enum entry ends with {@code ,} as ktfmt writes it; a type, a
 * property and a type alias get none (the parser accepts a property without an initializer). Type
 * aliases stay at the top level. Top-level declarations have no wrapper.
 *
 * <pre>{@code
 * class Stub {
 *     // apidocs:3
 *     public void draw(Shape shape, int x, int y) {}
 *     // apidocs:3:end
 * }
 * }</pre>
 */
final class Stub {
  private static final String MARKER = "// apidocs:";
  private static final String END = ":end";

  private final String source;
  private final Map<Integer, int[]> lines;

  private Stub(String source, Map<Integer, int[]> lines) {
    this.source = source;
    this.lines = lines;
  }

  /** The stub's text. */
  String source() {
    return source;
  }

  /**
   * Builds the stub for the declarations at {@code indices} (each the declaration's index in {@code
   * declarations}, used in its markers).
   */
  static Stub build(Language language, List<Declaration> declarations, List<Integer> indices) {
    var text = new StringBuilder();
    Map<Integer, int[]> lines = new HashMap<>();
    int line = 1;
    for (int index : indices) {
      var declaration = declarations.get(index);
      int first = line;
      List<String> chunk = new ArrayList<>();
      var wrapper = wrapper(language, declaration);
      if (wrapper != null) {
        chunk.add(wrapper);
        if (declaration.container() == Api.TypeKind.ENUM
            && declaration.kind() != Declaration.Kind.ENUM_CONSTANT) {
          chunk.add(";");
        }
      }
      chunk.add(MARKER + index);
      chunk.add(String.join("", texts(declaration.tokens())) + body(language, declaration));
      chunk.add(MARKER + index + END);
      if (wrapper != null) {
        chunk.add("}");
      }
      chunk.add("");
      for (var l : chunk) {
        text.append(l).append('\n');
        line++;
      }
      lines.put(index, new int[] {first, line - 1});
    }
    return new Stub(text.toString(), lines);
  }

  /** The declaration whose part of the stub holds {@code line} (1-based), or {@code null}. */
  Integer declarationAt(int line) {
    for (var entry : lines.entrySet()) {
      if (line >= entry.getValue()[0] && line <= entry.getValue()[1]) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Each declaration's laid-out text in {@code formatted}, by index: the lines between its markers,
   * blank lines at either end dropped and the first line's indentation removed from every line. A
   * declaration whose markers are missing is absent.
   */
  static Map<Integer, String> read(String formatted) {
    Map<Integer, String> blocks = new HashMap<>();
    var all = formatted.lines().toList();
    for (int i = 0; i < all.size(); i++) {
      var begin = all.get(i).strip();
      if (!begin.startsWith(MARKER) || begin.endsWith(END)) {
        continue;
      }
      int index;
      try {
        index = Integer.parseInt(begin.substring(MARKER.length()));
      } catch (NumberFormatException e) {
        continue;
      }
      int end = i + 1;
      while (end < all.size() && !all.get(end).strip().equals(begin + END)) {
        end++;
      }
      if (end == all.size()) {
        continue;
      }
      blocks.put(index, dedent(all.subList(i + 1, end)));
      i = end;
    }
    return blocks;
  }

  private static String dedent(List<String> block) {
    int from = 0;
    int to = block.size();
    while (from < to && block.get(from).isBlank()) {
      from++;
    }
    while (to > from && block.get(to - 1).isBlank()) {
      to--;
    }
    if (from == to) {
      return "";
    }
    var first = block.get(from);
    int indent = first.length() - first.stripLeading().length();
    var out = new StringBuilder();
    for (int i = from; i < to; i++) {
      var l = block.get(i).stripTrailing();
      int lead = l.length() - l.stripLeading().length();
      out.append(l.substring(Math.min(indent, lead)));
      if (i < to - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  /** The text after the signature that completes the declaration. */
  static String body(Language language, Declaration declaration) {
    if (language == Language.JAVA) {
      return switch (declaration.kind()) {
        case TYPE, CONSTRUCTOR, FUNCTION -> " {}";
        case FIELD, ENUM_CONSTANT, ANNOTATION_ELEMENT, TYPE_ALIAS -> ";";
      };
    }
    return switch (declaration.kind()) {
      case CONSTRUCTOR, FUNCTION -> " {}";
      // ktfmt ends an enum entry with a comma; giving it one keeps the stub stable.
      case ENUM_CONSTANT -> ",";
      case TYPE, TYPE_ALIAS, FIELD, ANNOTATION_ELEMENT -> "";
    };
  }

  /** The wrapper's opening line, or {@code null} at the top level. */
  private static String wrapper(Language language, Declaration declaration) {
    var container = declaration.container();
    if (container == null || declaration.kind() == Declaration.Kind.TYPE_ALIAS) {
      return null;
    }
    if (language == Language.JAVA && declaration.kind() == Declaration.Kind.FIELD) {
      // javac's parser wants an initializer on an interface's field; a field lays out the same in a
      // class.
      container = Api.TypeKind.CLASS;
    }
    var name = "Stub";
    if (declaration.kind() == Declaration.Kind.CONSTRUCTOR && language == Language.JAVA) {
      for (var token : declaration.tokens()) {
        if (token.kind() == Api.TokenKind.NAME) {
          name = token.text().replace('.', '_');
          break;
        }
      }
    }
    if (language == Language.JAVA) {
      return switch (container) {
        case CLASS, OBJECT -> "class " + name + " {";
        case INTERFACE -> "interface " + name + " {";
        case RECORD -> "record " + name + "() {";
        case ENUM -> "enum " + name + " {";
        case ANNOTATION -> "@interface " + name + " {";
      };
    }
    return switch (container) {
      case CLASS, RECORD -> "class " + name + " {";
      case INTERFACE -> "interface " + name + " {";
      case OBJECT -> "object " + name + " {";
      case ENUM -> "enum class " + name + " {";
      case ANNOTATION -> "annotation class " + name + " {";
    };
  }

  /**
   * Each token's text as the stub writes it: a nested type's name ({@code Outer.Inner}) is not an
   * identifier, so its dots become underscores, which keeps its width.
   */
  static List<String> texts(List<Api.Token> tokens) {
    List<String> texts = new ArrayList<>();
    for (var token : tokens) {
      texts.add(token.kind() == Api.TokenKind.NAME ? token.text().replace('.', '_') : token.text());
    }
    return texts;
  }
}
