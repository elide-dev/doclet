/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.model.Api.Token
import dev.elide.apidocs.model.Api.TokenKind
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.Bound
import org.jetbrains.dokka.model.Contravariance
import org.jetbrains.dokka.model.Covariance
import org.jetbrains.dokka.model.DTypeParameter
import org.jetbrains.dokka.model.DefinitelyNonNullable
import org.jetbrains.dokka.model.Dynamic
import org.jetbrains.dokka.model.FunctionalTypeConstructor
import org.jetbrains.dokka.model.GenericTypeConstructor
import org.jetbrains.dokka.model.Invariance
import org.jetbrains.dokka.model.JavaObject
import org.jetbrains.dokka.model.Nullable
import org.jetbrains.dokka.model.PrimitiveJavaType
import org.jetbrains.dokka.model.Projection
import org.jetbrains.dokka.model.Star
import org.jetbrains.dokka.model.TypeAliased
import org.jetbrains.dokka.model.TypeParameter
import org.jetbrains.dokka.model.UnresolvedBound
import org.jetbrains.dokka.model.Void

/**
 * Signature tokens: keywords, names, punctuation and types with references by id. Kotlin
 * declarations are written in Kotlin's syntax ([java] `false`), a Kotlin module's Java declarations
 * in Java's. Adjacent punctuation merges into one token.
 */
internal class Tokens(private val java: Boolean, private val identity: Identity) {
  private val out = mutableListOf<Token>()

  fun build(): List<Token> = out.toList()

  fun isEmpty(): Boolean = out.isEmpty()

  fun keyword(text: String) = add(Token.of(TokenKind.KEYWORD, text))

  fun name(text: String) = add(Token.of(TokenKind.NAME, text))

  fun param(text: String) = add(Token.of(TokenKind.PARAM, text))

  fun literal(text: String) = add(Token.of(TokenKind.LITERAL, text))

  fun typeParam(text: String) = add(Token.of(TokenKind.TYPE_PARAM, text))

  fun punct(text: String) = add(Token.of(TokenKind.PUNCT, text))

  fun all(tokens: List<Token>) = apply { tokens.forEach { add(it) } }

  private fun add(token: Token): Tokens {
    if (token.text().isEmpty()) return this
    val last = out.lastOrNull()
    if (token.kind() == TokenKind.PUNCT && last?.kind() == TokenKind.PUNCT) {
      out[out.size - 1] = Token.of(TokenKind.PUNCT, last.text() + token.text())
    } else {
      out += token
    }
    return this
  }

  /**
   * A type name linking to [dri]: its class names (`Outer.Inner`), as source code writes a nested
   * type.
   */
  fun typeName(dri: DRI, text: String = dri.classNames ?: dri.packageName.orEmpty()): Tokens {
    val ref = identity.ref(dri.copy(callable = null))
    return add(Token(TokenKind.TYPE, text, ref.id(), ref.url()))
  }

  /** An annotation's name, with a use-site [target] such as `file:`. */
  fun annotation(dri: DRI, target: String = ""): Tokens {
    val ref = identity.ref(dri)
    return add(
      Token(TokenKind.ANNOTATION, "@" + target + (dri.classNames ?: ""), ref.id(), ref.url()),
    )
  }

  /**
   * Type parameters in angle brackets, with variance, `reified` and a single bound; more bounds go
   * to [where].
   */
  fun typeParams(generics: List<DTypeParameter>): Tokens {
    if (generics.isEmpty()) return this
    punct("<")
    generics.forEachIndexed { i, g ->
      if (i > 0) punct(", ")
      if (!java) {
        when (g.variantTypeParameter) {
          is Covariance<*> -> keyword("out").punct(" ")
          is Contravariance<*> -> keyword("in").punct(" ")
          else -> {}
        }
        if ("reified" in g.extraModifiers) keyword("reified").punct(" ")
      }
      typeParam(g.name)
      val bounds = bounds(g)
      if (java && bounds.isNotEmpty()) {
        punct(" ").keyword("extends").punct(" ")
        bounds.forEachIndexed { j, b ->
          if (j > 0) punct(" & ")
          type(b)
        }
      } else if (bounds.size == 1) {
        punct(" : ").type(bounds.single())
      }
    }
    return punct(">")
  }

  /** Kotlin's `where` clause, for type parameters with more than one bound. */
  fun where(generics: List<DTypeParameter>): Tokens {
    val multi = generics.filter { bounds(it).size > 1 }
    if (java || multi.isEmpty()) return this
    punct(" ").keyword("where").punct(" ")
    var first = true
    for (g in multi) for (b in bounds(g)) {
      if (!first) punct(", ")
      first = false
      typeParam(g.name).punct(" : ").type(b)
    }
    return this
  }

  /**
   * A type in this declaration's syntax. [varargs] writes a variable-arity parameter's element
   * type.
   */
  fun type(b: Bound, varargs: Boolean = false): Tokens {
    if (varargs) {
      type(Identity.varargElement(b, java))
      if (java) punct("...")
      return this
    }
    when (b) {
      is FunctionalTypeConstructor -> function(b)
      is GenericTypeConstructor -> {
        if (java) {
          Identity.javaArray(b)?.let { (element, depth) ->
            return type(element).punct("[]".repeat(depth))
          }
        }
        typeName(b.dri)
        if (b.projections.isNotEmpty()) {
          punct("<")
          b.projections.forEachIndexed { i, p ->
            if (i > 0) punct(", ")
            projection(p)
          }
          punct(">")
        }
      }
      is TypeParameter -> typeParam(b.name)
      is Nullable -> {
        if (java) return type(b.inner)
        val parenthesized = b.inner is FunctionalTypeConstructor
        if (parenthesized) punct("(")
        type(b.inner)
        if (parenthesized) punct(")")
        punct("?")
      }
      is DefinitelyNonNullable -> type(b.inner).punct(" & ").typeName(DRI("kotlin", "Any"))
      is TypeAliased -> type(b.typeAlias)
      is PrimitiveJavaType -> keyword(b.name)
      is JavaObject ->
        if (java) typeName(DRI("java.lang", "Object")) else typeName(DRI("kotlin", "Any"))
      is Void -> if (java) keyword("void") else typeName(DRI("kotlin", "Unit"))
      is Dynamic -> keyword("dynamic")
      is UnresolvedBound -> add(Token.of(TokenKind.TYPE, b.name))
    }
    return this
  }

  /** `suspend R.(A, B) -> C`. */
  private fun function(b: FunctionalTypeConstructor) {
    val args = b.projections.map { projected(it) ?: JavaObject() }
    if (b.isSuspendable) keyword("suspend").punct(" ")
    var params = args.dropLast(1)
    if (b.isExtensionFunction && params.isNotEmpty()) {
      type(params.first()).punct(".")
      params = params.drop(1)
    }
    punct("(")
    params.forEachIndexed { i, p ->
      if (i > 0) punct(", ")
      type(p)
    }
    punct(") -> ")
    args.lastOrNull()?.let { type(it) }
  }

  private fun projection(p: Projection) {
    when (p) {
      is Star -> punct(if (java) "?" else "*")
      is Covariance<*> -> {
        if (java) punct("? ").keyword("extends").punct(" ") else keyword("out").punct(" ")
        type(p.inner)
      }
      is Contravariance<*> -> {
        if (java) punct("? ").keyword("super").punct(" ") else keyword("in").punct(" ")
        type(p.inner)
      }
      is Invariance<*> -> type(p.inner)
      is Bound -> type(p)
    }
  }

  companion object {
    /** A type parameter's bounds without the implicit `Any?` (Kotlin) or `Object` (Java). */
    fun bounds(g: DTypeParameter): List<Bound> =
      g.bounds.filterNot { b ->
        (b is Nullable &&
          (b.inner as? GenericTypeConstructor)?.dri?.let {
            it.packageName == "kotlin" && it.classNames == "Any"
          } == true) ||
          (b is GenericTypeConstructor &&
            b.dri.packageName == "java.lang" &&
            b.dri.classNames == "Object") ||
          b is JavaObject
      }
  }
}
