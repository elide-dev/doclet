/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api;
import java.util.List;

/**
 * One declaration whose signature {@link SignatureLayout} lays out: its signature tokens, what it
 * declares and what encloses it. Together these decide the stub the formatter sees (see {@link
 * Stub}).
 *
 * @param label names the declaration in warnings, such as its model id
 * @param kind what the declaration declares
 * @param container the kind of the type that encloses it, or {@code null} at the top level
 * @param tokens the signature, on one line, as the model holds it
 */
public record Declaration(String label, Kind kind, Api.TypeKind container, List<Api.Token> tokens) {
  /** What a declaration declares. */
  public enum Kind {
    /** A class, interface, record, enum, annotation type or object. */
    TYPE,
    /** A Kotlin type alias. */
    TYPE_ALIAS,
    /** A constructor. */
    CONSTRUCTOR,
    /** A Java method or a Kotlin function. */
    FUNCTION,
    /** A Java field or a Kotlin property. */
    FIELD,
    /** An enum constant or entry. */
    ENUM_CONSTANT,
    /** An element of a Java annotation type. */
    ANNOTATION_ELEMENT;

    /**
     * The kind of a model member.
     *
     * @param kind the member's kind in the model
     * @return the declaration kind its stub uses
     */
    public static Kind of(Api.MemberKind kind) {
      return switch (kind) {
        case CONSTRUCTOR -> CONSTRUCTOR;
        case METHOD, FUNCTION -> FUNCTION;
        case FIELD, PROPERTY -> FIELD;
        case ENUM_CONSTANT -> ENUM_CONSTANT;
        case ANNOTATION_ELEMENT -> ANNOTATION_ELEMENT;
      };
    }
  }

  /** Copies the token list. */
  public Declaration {
    tokens = List.copyOf(tokens);
  }

  /**
   * A type declaration.
   *
   * @param label names the declaration in warnings
   * @param container the enclosing type's kind, or {@code null} for a top-level type
   * @param tokens the type's signature
   * @return the declaration
   */
  public static Declaration type(String label, Api.TypeKind container, List<Api.Token> tokens) {
    return new Declaration(label, Kind.TYPE, container, tokens);
  }

  /**
   * A member of a type, or a top-level Kotlin function or property.
   *
   * @param label names the declaration in warnings
   * @param kind the member's kind in the model
   * @param container the owner's kind, or {@code null} at the top level
   * @param tokens the member's signature
   * @return the declaration
   */
  public static Declaration member(
      String label, Api.MemberKind kind, Api.TypeKind container, List<Api.Token> tokens) {
    return new Declaration(label, Kind.of(kind), container, tokens);
  }

  /**
   * A Kotlin type alias (always top level).
   *
   * @param label names the declaration in warnings
   * @param tokens the alias's signature
   * @return the declaration
   */
  public static Declaration typeAlias(String label, List<Api.Token> tokens) {
    return new Declaration(label, Kind.TYPE_ALIAS, null, tokens);
  }
}
