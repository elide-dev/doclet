/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api.Token;
import dev.elide.apidocs.model.Api.TokenKind;
import java.lang.annotation.Documented;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;

/**
 * Signature tokens: types, annotations and type parameters as token lists whose type names carry
 * their targets, so a page links every type without parsing. Adjacent punctuation merges into one
 * token.
 */
final class Tokens {
  private final Linker linker;
  private final Elements elements;
  private final Report report;
  private final List<Token> out = new ArrayList<>();

  private Tokens(Linker linker, Elements elements, Report report) {
    this.linker = linker;
    this.elements = elements;
    this.report = report;
  }

  /** A token list builder. */
  static Tokens builder(Linker linker, Elements elements, Report report) {
    return new Tokens(linker, elements, report);
  }

  List<Token> build() {
    return List.copyOf(out);
  }

  Tokens keyword(String text) {
    return add(Token.of(TokenKind.KEYWORD, text));
  }

  Tokens name(String text) {
    return add(Token.of(TokenKind.NAME, text));
  }

  Tokens param(String text) {
    return add(Token.of(TokenKind.PARAM, text));
  }

  Tokens literal(String text) {
    return add(Token.of(TokenKind.LITERAL, text));
  }

  Tokens punct(String text) {
    return add(Token.of(TokenKind.PUNCT, text));
  }

  Tokens all(List<Token> tokens) {
    tokens.forEach(this::add);
    return this;
  }

  private Tokens add(Token token) {
    if (token.kind() == TokenKind.PUNCT
        && !out.isEmpty()
        && out.getLast().kind() == TokenKind.PUNCT) {
      out.set(out.size() - 1, Token.of(TokenKind.PUNCT, out.getLast().text() + token.text()));
    } else {
      out.add(token);
    }
    return this;
  }

  /** A reference to a type by its declaration, shown as its name inside its package. */
  Tokens typeName(TypeElement type) {
    var ref = linker.ref(type, null);
    return add(new Token(TokenKind.TYPE, linker.typeName(type), ref.id(), ref.url()));
  }

  /** A type use: documented type annotations, the type and its arguments. */
  Tokens type(TypeMirror type) {
    return type(type, false);
  }

  /** A type use; {@code varargs} writes an array's last dimension as {@code ...}. */
  Tokens type(TypeMirror type, boolean varargs) {
    if (type.getKind() != TypeKind.ARRAY) {
      annotations(type.getAnnotationMirrors(), " ");
    }
    switch (type.getKind()) {
      case DECLARED -> {
        var declared = (DeclaredType) type;
        typeName((TypeElement) declared.asElement());
        var args = declared.getTypeArguments();
        if (!args.isEmpty()) {
          punct("<");
          for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
              punct(", ");
            }
            type(args.get(i));
          }
          punct(">");
        }
      }
      case ARRAY -> {
        type(((ArrayType) type).getComponentType());
        if (!type.getAnnotationMirrors().isEmpty()) {
          punct(" ");
          annotations(type.getAnnotationMirrors(), " ");
        }
        punct(varargs ? "..." : "[]");
      }
      case TYPEVAR ->
          add(
              Token.of(
                  TokenKind.TYPE_PARAM,
                  ((TypeVariable) type).asElement().getSimpleName().toString()));
      case WILDCARD -> {
        var wildcard = (WildcardType) type;
        punct("?");
        if (wildcard.getExtendsBound() != null) {
          punct(" ").keyword("extends").punct(" ").type(wildcard.getExtendsBound());
        } else if (wildcard.getSuperBound() != null) {
          punct(" ").keyword("super").punct(" ").type(wildcard.getSuperBound());
        }
      }
      case INTERSECTION -> join(((IntersectionType) type).getBounds(), " & ");
      case UNION -> join(((UnionType) type).getAlternatives(), " | ");
      case VOID -> keyword("void");
      default -> {
        if (type.getKind().isPrimitive()) {
          keyword(type.toString());
        } else {
          report.error(
              (javax.lang.model.element.Element) null,
              "A type the model cannot represent (" + type.getKind() + "): " + type);
          add(Token.of(TokenKind.TYPE, type.toString()));
        }
      }
    }
    return this;
  }

  private void join(List<? extends TypeMirror> types, String separator) {
    for (int i = 0; i < types.size(); i++) {
      if (i > 0) {
        punct(separator);
      }
      type(types.get(i));
    }
  }

  /** Type parameters with their bounds: {@code <T extends Comparable<? super T>>}. */
  Tokens typeParams(List<? extends TypeParameterElement> params) {
    if (params.isEmpty()) {
      return this;
    }
    punct("<");
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        punct(", ");
      }
      var param = params.get(i);
      annotations(param.getAnnotationMirrors(), " ");
      add(Token.of(TokenKind.TYPE_PARAM, param.getSimpleName().toString()));
      var bounds = bounds(param);
      if (!bounds.isEmpty()) {
        punct(" ").keyword("extends").punct(" ");
        for (int b = 0; b < bounds.size(); b++) {
          if (b > 0) {
            punct(" & ");
          }
          type(bounds.get(b));
        }
      }
    }
    return punct(">");
  }

  /** A type parameter's bounds, without an implicit {@code Object}. */
  static List<? extends TypeMirror> bounds(TypeParameterElement param) {
    var bounds = param.getBounds();
    if (bounds.size() == 1
        && bounds.getFirst() instanceof DeclaredType declared
        && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals("java.lang.Object")
        && declared.getAnnotationMirrors().isEmpty()) {
      return List.of();
    }
    return bounds;
  }

  /** The documented annotations of {@code mirrors}, each followed by {@code separator}. */
  Tokens annotations(List<? extends AnnotationMirror> mirrors, String separator) {
    for (var mirror : mirrors) {
      if (documented(mirror)) {
        annotation(mirror);
        punct(separator);
      }
    }
    return this;
  }

  /** Whether an annotation is part of the API contract: its type is {@code @Documented}. */
  static boolean documented(AnnotationMirror mirror) {
    return mirror.getAnnotationType().asElement().getAnnotation(Documented.class) != null;
  }

  /**
   * One annotation with its explicit values: {@code @Deprecated(since = "2", forRemoval = true)}.
   */
  Tokens annotation(AnnotationMirror mirror) {
    var type = (TypeElement) mirror.getAnnotationType().asElement();
    var ref = linker.ref(type, null);
    add(new Token(TokenKind.ANNOTATION, "@" + linker.typeName(type), ref.id(), ref.url()));
    Map<? extends ExecutableElement, ? extends AnnotationValue> values = mirror.getElementValues();
    if (values.isEmpty()) {
      return this;
    }
    punct("(");
    boolean first = true;
    for (var entry : values.entrySet()) {
      if (!first) {
        punct(", ");
      }
      first = false;
      var name = entry.getKey().getSimpleName().toString();
      if (!(values.size() == 1 && name.equals("value"))) {
        param(name).punct(" = ");
      }
      literal(entry.getValue().toString());
    }
    return punct(")");
  }

  /** The documented annotations of an element, each as tokens. */
  List<List<Token>> annotationList(Element element) {
    List<List<Token>> list = new ArrayList<>();
    for (var mirror : element.getAnnotationMirrors()) {
      if (documented(mirror)) {
        list.add(builder(linker, elements, report).annotation(mirror).build());
      }
    }
    return list;
  }

  /**
   * A constant's value as a source literal, or {@code null} when it is not a compile-time constant.
   */
  String constant(VariableElement field) {
    var value = field.getConstantValue();
    return value == null ? null : elements.getConstantExpression(value);
  }
}
