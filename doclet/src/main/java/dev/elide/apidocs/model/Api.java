/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.model;

import java.util.List;

/**
 * The {@code apidocs/1} model of one module's public API: what the Javadoc doclet and the Dokka
 * plugin extract and the renderer reads. {@link Json} writes it; {@code
 * schema/apidocs-1.schema.json} in {@code @elide/apidocs} describes the same shape.
 *
 * <p>Conventions shared by every record: a {@code null} component, an empty list and {@code false}
 * are left out of the JSON, so each is the default when absent. Lists keep the order the extractor
 * gives them (declaration order for members); nothing is sorted on write.
 */
public final class Api {
  /** The schema name every model carries. */
  public static final String SCHEMA = "apidocs/1";

  private Api() {}

  /**
   * One module's model.
   *
   * @param schema always {@link #SCHEMA}
   * @param module the module's name, version and languages
   * @param externals where references outside this model point, by name prefix
   * @param packages the documented packages, sorted by name
   */
  public record Model(
      String schema, Module module, List<External> externals, List<Package> packages) {}

  /**
   * The documented module.
   *
   * @param name the module's name, as pages show it
   * @param version the module's version, or {@code null}
   * @param javaModule the named JPMS module the packages belong to, or {@code null} for the unnamed
   *     module
   * @param languages the source languages, sorted ({@code java}, {@code kotlin})
   * @param doc the module's documentation, or {@code null}
   */
  public record Module(
      String name,
      String version,
      String javaModule,
      List<String> languages,
      Doc doc,
      List<Guide> guides) {
    public Module(String name, String version, String javaModule, List<String> languages, Doc doc) {
      this(name, version, javaModule, languages, doc, List.of());
    }
  }

  /** An authored page cross-linked with declarations in the model. */
  public record Guide(
      String id, String slug, String title, List<Doc.Inline> summary, List<Doc.Block> body) {}

  /**
   * Documentation outside this model: a reference whose id starts with {@code prefix} links below
   * {@code url}.
   *
   * @param prefix a package or name prefix, such as {@code java.}
   * @param url the documentation's base URL (absolute) or path (relative to the model's root)
   */
  public record External(String prefix, String url) {}

  /**
   * A package (Java) or a package's top-level declarations (Kotlin).
   *
   * @param id the package's id: its qualified name
   * @param name the qualified name; empty for the unnamed package
   * @param doc the package documentation ({@code package-info.java}), or {@code null}
   * @param since the {@code @since} text, or {@code null}
   * @param deprecated the package's deprecation, or {@code null}
   * @param nullMarked whether the package is JSpecify {@code @NullMarked}
   * @param types the package's types, nested types included, sorted by {@link Type#name()}
   * @param functions top-level functions (Kotlin)
   * @param properties top-level properties (Kotlin)
   * @param typeAliases type aliases (Kotlin)
   */
  public record Package(
      String id,
      String name,
      Doc doc,
      String since,
      Deprecation deprecated,
      boolean nullMarked,
      List<Type> types,
      List<Member> functions,
      List<Member> properties,
      List<TypeAlias> typeAliases) {}

  /** What a type declaration is. */
  public enum TypeKind {
    /** A class, abstract or not. */
    CLASS,
    /** An interface. */
    INTERFACE,
    /** A Java record. */
    RECORD,
    /** An enum class. */
    ENUM,
    /** An annotation interface. */
    ANNOTATION,
    /** A Kotlin object declaration. */
    OBJECT,
  }

  /**
   * A type declaration.
   *
   * @param id the stable id: the binary name ({@code p.Outer$Inner}) for Java, Dokka's DRI for
   *     Kotlin
   * @param name the name inside its package, nesting joined with dots ({@code Outer.Inner}); its
   *     page is {@code <package path>/<name>.html}
   * @param kind what the declaration is
   * @param language the source language: {@code java} or {@code kotlin}
   * @param modifiers modifiers in canonical order, without implied ones ({@code public}, {@code
   *     abstract}, {@code sealed}, ...)
   * @param annotations documented annotations on the declaration, each as tokens
   * @param typeParams type parameters
   * @param signature the declaration line as tokens; when the source formatter laid it out, its
   *     punctuation tokens hold the line breaks and indentation (tools/docs/format)
   * @param superclass the superclass as tokens, or empty for none (interfaces, {@code Object},
   *     records, enums use their implicit one)
   * @param interfaces implemented or extended interfaces, each as tokens
   * @param ancestors the superclass chain, nearest first, ending at the root class
   * @param permits permitted subtypes of a sealed type
   * @param enclosing the enclosing type's id, or {@code null} for a top-level type
   * @param components record components (a record's header), with their {@code @param}
   *     documentation
   * @param members the declared members, in declaration order
   * @param inherited members inherited from supertypes, grouped by declaring type
   * @param doc the type's documentation, or {@code null}
   * @param deprecated the deprecation, or {@code null}
   * @param since the {@code @since} text, or {@code null}
   * @param nullMarked whether the declaration is in a JSpecify {@code @NullMarked} scope
   * @param source where the declaration is, or {@code null}
   */
  public record Type(
      String id,
      String name,
      TypeKind kind,
      String language,
      List<String> modifiers,
      List<List<Token>> annotations,
      List<TypeParam> typeParams,
      List<Token> signature,
      List<Token> superclass,
      List<List<Token>> interfaces,
      List<Ref> ancestors,
      List<Ref> permits,
      String enclosing,
      List<Param> components,
      List<Member> members,
      List<Inherited> inherited,
      Doc doc,
      Deprecation deprecated,
      String since,
      boolean nullMarked,
      Source source) {
    public Type withDoc(Doc replacement) {
      return new Type(
          id,
          name,
          kind,
          language,
          modifiers,
          annotations,
          typeParams,
          signature,
          superclass,
          interfaces,
          ancestors,
          permits,
          enclosing,
          components,
          members,
          inherited,
          replacement,
          deprecated,
          since,
          nullMarked,
          source);
    }
  }

  /** What a member is. */
  public enum MemberKind {
    /** A constructor. */
    CONSTRUCTOR,
    /** A Java method. */
    METHOD,
    /** A Java field. */
    FIELD,
    /** An enum constant. */
    ENUM_CONSTANT,
    /** An annotation interface element. */
    ANNOTATION_ELEMENT,
    /** A Kotlin function. */
    FUNCTION,
    /** A Kotlin property. */
    PROPERTY,
  }

  /**
   * A member of a type, or a Kotlin top-level function or property.
   *
   * @param id the stable id: {@code <type id>#<anchor>} for Java, Dokka's DRI for Kotlin
   * @param anchor the fragment of the member on its page; for Java the JDK doclet's ({@code
   *     read(java.io.InputStream)}, {@code <init>(int)})
   * @param kind what the member is
   * @param name the simple name ({@code <init>} is not used: a constructor is named after its type)
   * @param modifiers modifiers in canonical order, without implied ones
   * @param annotations documented annotations on the member, each as tokens
   * @param typeParams type parameters
   * @param receiver the receiver of a Kotlin extension function or property, or {@code null}
   * @param signature the declaration as tokens, on one line unless the source formatter laid it
   *     out: then its punctuation tokens hold the line breaks and indentation (tools/docs/format)
   * @param params value parameters
   * @param returns the return type and its documentation; {@code null} for constructors, fields and
   *     {@code void}
   * @param type the type of a field, property or enum constant
   * @param nullness the nullness of a field's or property's type, or {@code null} when unknown or
   *     not applicable
   * @param exceptions the exceptions: declared ({@code throws}) and documented ({@code @throws}),
   *     merged
   * @param constant a compile-time constant's value as a source literal, or {@code null}
   * @param defaultValue an annotation element's default as source text, or {@code null}
   * @param overrides the member this one overrides or implements, or {@code null}
   * @param implicit whether the compiler declares it (a record's accessors, an enum's {@code
   *     values()})
   * @param doc the documentation, with {@code {@inheritDoc}} resolved, or {@code null}
   * @param deprecated the deprecation, or {@code null}
   * @param since the {@code @since} text, or {@code null}
   * @param source where the declaration is, or {@code null}
   */
  public record Member(
      String id,
      String anchor,
      MemberKind kind,
      String name,
      List<String> modifiers,
      List<List<Token>> annotations,
      List<TypeParam> typeParams,
      Param receiver,
      List<Token> signature,
      List<Param> params,
      Returns returns,
      List<Token> type,
      Nullness nullness,
      @Json.Name("throws") List<Thrown> exceptions,
      String constant,
      String defaultValue,
      Ref overrides,
      boolean implicit,
      Doc doc,
      Deprecation deprecated,
      String since,
      Source source) {}

  /**
   * A Kotlin type alias.
   *
   * @param id Dokka's DRI
   * @param name the alias's name
   * @param typeParams type parameters
   * @param signature the declaration as tokens
   * @param target the aliased type as tokens
   * @param doc the documentation, or {@code null}
   * @param deprecated the deprecation, or {@code null}
   * @param since the {@code @since} text, or {@code null}
   * @param source where the declaration is, or {@code null}
   */
  public record TypeAlias(
      String id,
      String name,
      List<TypeParam> typeParams,
      List<Token> signature,
      List<Token> target,
      Doc doc,
      Deprecation deprecated,
      String since,
      Source source) {}

  /** Whether a reference type admits {@code null}. */
  public enum Nullness {
    /** Annotated {@code @Nullable}, or a Kotlin {@code T?}. */
    NULLABLE,
    /** Annotated non-null, in a {@code @NullMarked} scope, or a Kotlin non-null type. */
    NON_NULL,
  }

  /**
   * A value parameter or record component.
   *
   * @param name the parameter's name
   * @param type its type as tokens; a variable-arity parameter's ends with {@code ...}
   * @param varargs whether it is the variable-arity parameter
   * @param defaultValue a Kotlin default argument as source text, or {@code null}
   * @param nullness its nullness, or {@code null} when unknown or a primitive
   * @param annotations documented annotations on the parameter, each as tokens
   * @param doc its {@code @param} documentation
   */
  public record Param(
      String name,
      List<Token> type,
      boolean varargs,
      String defaultValue,
      Nullness nullness,
      List<List<Token>> annotations,
      List<Doc.Block> doc) {}

  /**
   * A type parameter.
   *
   * @param name its name
   * @param bounds its upper bounds, each as tokens (none for an implicit {@code Object})
   * @param doc its {@code @param <T>} documentation
   */
  public record TypeParam(String name, List<List<Token>> bounds, List<Doc.Block> doc) {}

  /**
   * A return value.
   *
   * @param type the return type as tokens
   * @param nullness its nullness, or {@code null} when unknown or a primitive
   * @param doc the {@code @return} documentation
   */
  public record Returns(List<Token> type, Nullness nullness, List<Doc.Block> doc) {}

  /**
   * An exception a member throws.
   *
   * @param type the exception type as tokens
   * @param declared whether the {@code throws} clause names it
   * @param doc its {@code @throws} documentation
   */
  public record Thrown(List<Token> type, boolean declared, List<Doc.Block> doc) {}

  /**
   * A deprecation.
   *
   * @param doc the {@code @deprecated} text
   * @param since {@code @Deprecated(since)}, or {@code null}
   * @param forRemoval {@code @Deprecated(forRemoval = true)}
   */
  public record Deprecation(List<Doc.Block> doc, String since, boolean forRemoval) {}

  /**
   * Members a type inherits from one supertype.
   *
   * @param from the supertype declaring them
   * @param kind {@code class} or {@code interface}
   * @param members the inherited members, sorted by name
   */
  public record Inherited(Ref from, String kind, List<Ref> members) {}

  /**
   * A declaration's place in its source.
   *
   * @param path the source file's path below its root ({@code p/q/T.java})
   * @param line the 1-based line of the declaration
   */
  public record Source(String path, int line) {}

  /**
   * A reference to a declaration.
   *
   * @param id the target's model id; it may be outside this model
   * @param text the text to show, or {@code null} for the target's own name
   * @param url the target's absolute (or root-relative) URL when it is outside this model and an
   *     {@link External} covers it; {@code null} otherwise
   */
  public record Ref(String id, String text, String url) {}

  /** What a signature token is. */
  public enum TokenKind {
    /** A keyword or modifier. */
    KEYWORD,
    /** The declared name. */
    NAME,
    /** A type name; links when it has a reference. */
    TYPE,
    /** A type variable. */
    TYPE_PARAM,
    /** An annotation, {@code @} included. */
    ANNOTATION,
    /** A literal value. */
    LITERAL,
    /** Punctuation and spaces. */
    PUNCT,
    /** A parameter name. */
    PARAM,
  }

  /**
   * One token of a signature.
   *
   * @param kind what the token is
   * @param text its text
   * @param ref the target's model id for a type or annotation, or {@code null}
   * @param url the target's URL when outside this model and an {@link External} covers it, or
   *     {@code null}
   */
  public record Token(TokenKind kind, String text, String ref, String url) {
    /** A token without a target. */
    public static Token of(TokenKind kind, String text) {
      return new Token(kind, text, null, null);
    }
  }
}
