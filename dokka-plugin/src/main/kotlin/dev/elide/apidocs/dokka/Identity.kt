/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.model.Api
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.Bound
import org.jetbrains.dokka.model.DEnumEntry
import org.jetbrains.dokka.model.DFunction
import org.jetbrains.dokka.model.DProperty
import org.jetbrains.dokka.model.DefinitelyNonNullable
import org.jetbrains.dokka.model.Documentable
import org.jetbrains.dokka.model.FunctionalTypeConstructor
import org.jetbrains.dokka.model.GenericTypeConstructor
import org.jetbrains.dokka.model.JavaObject
import org.jetbrains.dokka.model.Nullable
import org.jetbrains.dokka.model.PrimitiveJavaType
import org.jetbrains.dokka.model.TypeAliased
import org.jetbrains.dokka.model.TypeParameter
import org.jetbrains.dokka.model.UnresolvedBound
import org.jetbrains.dokka.model.Void

/**
 * Ids, anchors and links.
 *
 * An id is Dokka's DRI as `DRI.toString()` writes it, without the DRI's `extra` part:
 * `package/Class/callable/signature/ target/`. The extra part only marks enum entries (as JSON),
 * and leaving it out keeps ids readable; ids stay unique, which [ModelBuilder] checks.
 *
 * An anchor is readable and stable, in the JDK doclet's style rather than Dokka's hashed one: a
 * function's name and its parameter types (qualified, generic arguments erased, type parameters by
 * name, a `vararg` as `T...`), with an extension's receiver type in front
 * (`fixture.kotlin.Shape.scaled(kotlin.Double)`); `<init>(...)` for a constructor; the name for a
 * property (an extension property's with its receiver in front) or an enum entry. Where two members
 * of one page would share an anchor (overloads that differ only in type arguments, kept apart on
 * the JVM by `@JvmName`), both use their parameter types with type arguments.
 */
internal class Identity(private val links: List<Link>) {
  private val documented = HashSet<String>()

  /**
   * An external documentation site: names starting with [prefix] link below [url], laid out as
   * [layout] lays out.
   */
  data class Link(val prefix: String, val url: String, val layout: Layout)

  /** How an external site lays out its pages. */
  enum class Layout {
    /**
     * The JDK's Javadoc: `<module>/<package path>/<Outer.Inner>.html`; `{module}` in the URL is the
     * module.
     */
    JAVADOC,

    /** Dokka's HTML: `<package>/-outer/-inner/`, with members at `member-name.html` beside it. */
    DOKKA,
  }

  /** Records [id] as part of the model, so references to it stay local. */
  fun document(id: String): Boolean = documented.add(id)

  fun isDocumented(id: String): Boolean = id in documented

  fun id(dri: DRI): String = dri.copy(extra = null).toString()

  /**
   * A reference to [dri] with [text] to show, and its external URL when the model does not have it.
   */
  fun ref(dri: DRI, text: String? = null): Api.Ref {
    val id = id(dri)
    return Api.Ref(id, text, if (id in documented) null else url(dri))
  }

  /**
   * [dri]'s page through the first (longest) link whose prefix its qualified name starts with, or
   * `null`.
   */
  fun url(dri: DRI): String? {
    val pkg = dri.packageName.orEmpty()
    val classes = dri.classNames.orEmpty()
    val qualified = listOf(pkg, classes).filter { it.isNotEmpty() }.joinToString(".")
    val link =
      links
        .filter { qualified.startsWith(it.prefix) || pkg == it.prefix.removeSuffix(".") }
        .maxByOrNull { it.prefix.length } ?: return null
    val callable = dri.callable?.name
    return when (link.layout) {
      Layout.DOKKA -> {
        val base = link.url.withSlash() + pkg + "/"
        val type =
          classes.split('.').filter { it.isNotEmpty() }.joinToString("") { "-" + kebab(it) + "/" }
        when {
          callable == null -> base + type
          else -> base + type + kebab(callable) + ".html"
        }
      }
      Layout.JAVADOC -> {
        val base = link.url.replace("{module}", jdkModule(pkg)).withSlash()
        val dir = base + pkg.replace('.', '/') + "/"
        if (classes.isEmpty()) dir + "package-summary.html" else "$dir$classes.html"
      }
    }
  }

  /**
   * A Kotlin declaration's anchor (see the class comment); [typeArguments] keeps generic arguments.
   */
  fun anchor(d: Documentable, java: Boolean, typeArguments: Boolean = false): String =
    when (d) {
      is DFunction -> {
        val receiver = d.receiver?.let { erased(it.type, java, typeArguments) + "." }.orEmpty()
        val name = if (d.isConstructor) "<init>" else d.name
        receiver +
          name +
          d.parameters.joinToString(",", "(", ")") {
            erased(it.type, java, typeArguments, varargs = isVararg(it))
          }
      }
      is DProperty ->
        d.receiver?.let { erased(it.type, java, typeArguments) + "." }.orEmpty() + d.name
      is DEnumEntry -> d.name
      else -> d.name ?: id(d.dri)
    }

  /**
   * A type in an anchor: qualified, nullability dropped, type arguments only when [typeArguments].
   */
  private fun erased(
    b: Bound,
    java: Boolean,
    typeArguments: Boolean,
    varargs: Boolean = false,
  ): String {
    if (varargs) {
      val element = varargElement(b, java)
      return erased(element, java, typeArguments) + "..."
    }
    return when (b) {
      is GenericTypeConstructor -> {
        if (java)
          javaArray(b)?.let {
            return erased(it.first, true, typeArguments) + "[]".repeat(it.second)
          }
        if (java && b.dri.packageName == "kotlin" && b.dri.classNames in PRIMITIVE_ARRAYS) {
          return PRIMITIVE_ARRAYS.getValue(b.dri.classNames!!) + "[]"
        }
        qualified(b.dri) +
          arguments(b.projections.mapNotNull { projected(it) }, java, typeArguments)
      }
      is FunctionalTypeConstructor ->
        qualified(b.dri) +
          arguments(b.projections.mapNotNull { projected(it) }, java, typeArguments)
      is TypeParameter -> b.name
      is Nullable -> erased(b.inner, java, typeArguments)
      is DefinitelyNonNullable -> erased(b.inner, java, typeArguments)
      is TypeAliased -> erased(b.typeAlias, java, typeArguments)
      is PrimitiveJavaType -> b.name
      is JavaObject -> "java.lang.Object"
      is Void -> if (java) "void" else "kotlin.Unit"
      is UnresolvedBound -> b.name
      else -> b.toString()
    }
  }

  private fun arguments(args: List<Bound>, java: Boolean, typeArguments: Boolean): String =
    if (!typeArguments || args.isEmpty()) ""
    else args.joinToString(",", "<", ">") { erased(it, java, true) }

  companion object {
    private val PRIMITIVE_ARRAYS =
      mapOf(
        "IntArray" to "int",
        "LongArray" to "long",
        "ShortArray" to "short",
        "ByteArray" to "byte",
        "CharArray" to "char",
        "BooleanArray" to "boolean",
        "FloatArray" to "float",
        "DoubleArray" to "double",
      )

    /**
     * The JDK module of a package, for `{module}` in a Javadoc link: `java.base` unless known
     * otherwise.
     */
    private val JDK_MODULES =
      listOf(
        "java.sql" to "java.sql",
        "javax.sql" to "java.sql",
        "java.util.logging" to "java.logging",
        "java.awt" to "java.desktop",
        "javax.swing" to "java.desktop",
        "java.beans" to "java.desktop",
        "java.net.http" to "java.net.http",
        "java.lang.management" to "java.management",
        "javax.management" to "java.management",
        "java.rmi" to "java.rmi",
        "javax.naming" to "java.naming",
        "javax.tools" to "java.compiler",
        "javax.lang.model" to "java.compiler",
        "javax.annotation.processing" to "java.compiler",
        "java.util.prefs" to "java.prefs",
        "javax.xml" to "java.xml",
        "org.w3c.dom" to "java.xml",
        "org.xml.sax" to "java.xml",
        "jdk.javadoc" to "jdk.javadoc",
        "com.sun.source" to "jdk.compiler",
      )

    fun jdkModule(pkg: String): String =
      JDK_MODULES.firstOrNull { pkg == it.first || pkg.startsWith(it.first + ".") }?.second
        ?: "java.base"

    fun qualified(dri: DRI): String =
      listOfNotNull(dri.packageName?.takeIf { it.isNotEmpty() }, dri.classNames).joinToString(".")

    /** `KotlinTypeName` as Dokka's pages name it: `-kotlin-type-name`. */
    fun kebab(name: String): String =
      name.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }.removePrefix("-")

    private fun String.withSlash() = if (endsWith("/")) this else "$this/"

    /** A Java array as Dokka gives it (`kotlin.Array<T>`): its element and depth, or `null`. */
    fun javaArray(b: GenericTypeConstructor): Pair<Bound, Int>? {
      if (b.dri.packageName != "kotlin" || b.dri.classNames != "Array") return null
      val element = b.projections.singleOrNull()?.let(::projected) ?: return null
      val inner = (element as? GenericTypeConstructor)?.let(::javaArray)
      return if (inner != null) inner.first to inner.second + 1 else element to 1
    }

    /** A vararg parameter's element type: Dokka gives either the element or its array. */
    fun varargElement(b: Bound, java: Boolean): Bound {
      if (b is GenericTypeConstructor && b.dri.packageName == "kotlin") {
        if (b.dri.classNames == "Array")
          b.projections.singleOrNull()?.let(::projected)?.let {
            return it
          }
        PRIMITIVE_ARRAYS[b.dri.classNames]?.let { primitive ->
          return if (java) PrimitiveJavaType(primitive)
          else
            GenericTypeConstructor(
              DRI("kotlin", b.dri.classNames!!.removeSuffix("Array")),
              listOf(),
            )
        }
      }
      return b
    }
  }
}
