/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.model.Api
import dev.elide.apidocs.model.Api.Token
import dev.elide.apidocs.model.Doc
import java.nio.file.Path
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.AnnotationParameterValue
import org.jetbrains.dokka.model.AnnotationValue
import org.jetbrains.dokka.model.Annotations
import org.jetbrains.dokka.model.ArrayValue
import org.jetbrains.dokka.model.BooleanConstant
import org.jetbrains.dokka.model.BooleanValue
import org.jetbrains.dokka.model.Bound
import org.jetbrains.dokka.model.CheckedExceptions
import org.jetbrains.dokka.model.ClassValue
import org.jetbrains.dokka.model.ComplexExpression
import org.jetbrains.dokka.model.Contravariance
import org.jetbrains.dokka.model.Covariance
import org.jetbrains.dokka.model.DAnnotation
import org.jetbrains.dokka.model.DClasslike
import org.jetbrains.dokka.model.DEnum
import org.jetbrains.dokka.model.DEnumEntry
import org.jetbrains.dokka.model.DFunction
import org.jetbrains.dokka.model.DInterface
import org.jetbrains.dokka.model.DModule
import org.jetbrains.dokka.model.DObject
import org.jetbrains.dokka.model.DParameter
import org.jetbrains.dokka.model.DProperty
import org.jetbrains.dokka.model.DTypeAlias
import org.jetbrains.dokka.model.DTypeParameter
import org.jetbrains.dokka.model.DefaultValue
import org.jetbrains.dokka.model.DefinitelyNonNullable
import org.jetbrains.dokka.model.Documentable
import org.jetbrains.dokka.model.DoubleConstant
import org.jetbrains.dokka.model.Dynamic
import org.jetbrains.dokka.model.EnumValue
import org.jetbrains.dokka.model.ExceptionInSupertypes
import org.jetbrains.dokka.model.Expression
import org.jetbrains.dokka.model.FloatConstant
import org.jetbrains.dokka.model.GenericTypeConstructor
import org.jetbrains.dokka.model.InheritedMember
import org.jetbrains.dokka.model.IntegerConstant
import org.jetbrains.dokka.model.IsAlsoParameter
import org.jetbrains.dokka.model.IsVar
import org.jetbrains.dokka.model.JavaClassKindTypes
import org.jetbrains.dokka.model.JavaModifier
import org.jetbrains.dokka.model.JavaVisibility
import org.jetbrains.dokka.model.KotlinClassKindTypes
import org.jetbrains.dokka.model.KotlinModifier
import org.jetbrains.dokka.model.KotlinVisibility
import org.jetbrains.dokka.model.LiteralValue
import org.jetbrains.dokka.model.Nullable
import org.jetbrains.dokka.model.ObviousMember
import org.jetbrains.dokka.model.PrimaryConstructorExtra
import org.jetbrains.dokka.model.PrimitiveJavaType
import org.jetbrains.dokka.model.StringConstant
import org.jetbrains.dokka.model.StringValue
import org.jetbrains.dokka.model.TypeConstructorWithKind
import org.jetbrains.dokka.model.TypeParameter
import org.jetbrains.dokka.model.UnresolvedBound
import org.jetbrains.dokka.model.Void
import org.jetbrains.dokka.model.WithCompanion
import org.jetbrains.dokka.model.WithConstructors
import org.jetbrains.dokka.model.WithGenerics
import org.jetbrains.dokka.model.WithSupertypes
import org.jetbrains.dokka.model.doc.Author
import org.jetbrains.dokka.model.doc.Constructor
import org.jetbrains.dokka.model.doc.CustomTagWrapper
import org.jetbrains.dokka.model.doc.Deprecated
import org.jetbrains.dokka.model.doc.Description
import org.jetbrains.dokka.model.doc.Param
import org.jetbrains.dokka.model.doc.Property
import org.jetbrains.dokka.model.doc.Receiver
import org.jetbrains.dokka.model.doc.Return
import org.jetbrains.dokka.model.doc.Sample
import org.jetbrains.dokka.model.doc.See
import org.jetbrains.dokka.model.doc.Since
import org.jetbrains.dokka.model.doc.Suppress
import org.jetbrains.dokka.model.doc.Throws
import org.jetbrains.dokka.model.doc.Version

/**
 * Builds the `apidocs/1` model from Dokka's merged module: the Kotlin declarations, and a Kotlin
 * module's Java ones in Java's syntax. Ids, anchors and links come from [Identity], signatures from
 * [Tokens] (on one line; the renderer lays them out afterwards, with
 * `dev.elide.apidocs.format.ModelLayout`), documentation from [KDocs] and, for a package Dokka has
 * no documentation for, its `package-info.java` ([PackageInfo]).
 *
 * `@JvmName`, `@JvmStatic`, `@JvmOverloads`, `@JvmField`, `@JvmInline`, `@JvmRecord`,
 * `@JvmSynthetic` and `@Throws` change what a Java caller sees, so they are shown with the
 * declaration's annotations, beside every annotation that is `@MustBeDocumented`; anchors and
 * signatures stay Kotlin's.
 */
internal class ModelBuilder(
  private val module: DModule,
  private val options: Options,
  private val sourceRoots: List<Path>,
  private val samples: (Documentable, String) -> String?,
  private val classPath: ClassPath = ClassPath(listOf()),
) {
  val errors = mutableListOf<String>()
  val warnings = mutableListOf<String>()
  private val identity = Identity(options.links)
  private val types = LinkedHashMap<String, DClasslike>()
  private var where = ""
  private val kdocs = KDocs(identity, { errors += "$where: $it" }, { warnings += "$where: $it" })

  fun build(): Api.Model {
    val packages = module.packages.sortedBy { it.packageName }
    for (pkg in packages) {
      identity.document(pkg.packageName)
      for (type in allTypes(pkg.classlikes)) types[identity.id(type.dri)] = type
    }
    for ((id, type) in types) {
      identity.document(id)
      for (member in members(type)) identity.document(identity.id(member.dri))
    }
    for (pkg in packages) {
      (pkg.functions.filter(::documentable) +
          pkg.properties.filter(::documentable) +
          pkg.typealiases)
        .forEach { identity.document(identity.id(it.dri)) }
    }
    val languages = sortedSetOf<String>()
    val models = packages.map { pkg ->
      val pkgTypes =
        allTypes(pkg.classlikes)
          .map { type(it).also { t -> languages += t.language() } }
          .sortedBy { it.name() }
      where = pkg.packageName
      val docs = docs(pkg) ?: packageInfo(pkg.packageName)
      val functions =
        disambiguate(pkg.functions.filter(::documentable), false) { function(null, it) }
      val properties =
        disambiguate(pkg.properties.filter(::documentable), false) { property(null, it) }
      val aliases = pkg.typealiases.filter(::documentable).map(::typeAlias)
      if (functions.isNotEmpty() || properties.isNotEmpty() || aliases.isNotEmpty())
        languages += "kotlin"
      val order = compareBy<Api.Member>({ it.name() }, { it.anchor() })
      uniqueAnchors(
        pkg.packageName,
        functions.map { it.anchor() } + properties.map { it.anchor() } + aliases.map { it.name() },
      )
      Api.Package(
        pkg.packageName,
        pkg.packageName,
        docs?.doc,
        docs?.since,
        null,
        false,
        pkgTypes,
        functions.sortedWith(order),
        properties.sortedWith(order),
        aliases.sortedBy { it.name() },
      )
    }
    if (languages.isEmpty()) languages += "kotlin"
    uniqueIds(models)
    where = module.name
    return Api.Model(
      Api.SCHEMA,
      Api.Module(
        options.moduleName ?: module.name,
        options.moduleVersion,
        null,
        languages.toList(),
        docs(module)?.doc,
      ),
      options.links.sortedBy { it.prefix }.map { Api.External(it.prefix, it.url) },
      models,
    )
  }

  // ---- Types.

  /** Every type of a package, nested types and companions included. */
  private fun allTypes(classlikes: List<DClasslike>): List<DClasslike> {
    val out = LinkedHashMap<String, DClasslike>()
    fun visit(type: DClasslike) {
      if (!documentable(type) || out.putIfAbsent(identity.id(type.dri), type) != null) return
      (type as? WithCompanion)?.companion?.let(::visit)
      type.classlikes.forEach(::visit)
    }
    classlikes.forEach(::visit)
    return out.values.toList()
  }

  private fun language(d: Documentable): String =
    if (d.sourcePath?.endsWith(".java") == true) "java" else "kotlin"

  private fun isCompanion(type: DClasslike): Boolean {
    val outer =
      type.dri.classNames?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() } ?: return false
    return (types[identity.id(type.dri.copy(classNames = outer))] as? WithCompanion)
      ?.companion
      ?.dri == type.dri
  }

  private fun kind(type: DClasslike): Api.TypeKind =
    when (type) {
      is DInterface -> Api.TypeKind.INTERFACE
      is DEnum -> Api.TypeKind.ENUM
      is DObject -> Api.TypeKind.OBJECT
      is DAnnotation -> Api.TypeKind.ANNOTATION
      else -> Api.TypeKind.CLASS
    }

  /**
   * Supertypes a declaration does not write: `Any`, `Object`, an enum's `Enum`, an annotation's
   * `Annotation`.
   */
  private fun implicitSupertype(dri: DRI): Boolean = Identity.qualified(dri) in IMPLICIT_SUPERTYPES

  private fun supertypes(type: DClasslike): List<TypeConstructorWithKind> =
    (type as? WithSupertypes)?.supertypes?.values?.firstOrNull().orEmpty().filterNot {
      implicitSupertype(it.typeConstructor.dri)
    }

  private fun isClassKind(t: TypeConstructorWithKind): Boolean =
    t.kind == KotlinClassKindTypes.CLASS || t.kind == JavaClassKindTypes.CLASS

  private fun type(type: DClasslike): Api.Type {
    val java = language(type) == "java"
    val kind = kind(type)
    val name = type.dri.classNames.orEmpty()
    where = location(type)
    val docs = docs(type)
    val modifiers = typeModifiers(type, java)
    val generics = (type as? WithGenerics)?.generics.orEmpty()
    val supertypes = supertypes(type)
    val superclass =
      if (kind == Api.TypeKind.INTERFACE) null else supertypes.firstOrNull(::isClassKind)
    val interfaces = supertypes.filter { it !== superclass }

    val signature = Tokens(java, identity)
    modifiers.forEach { signature.keyword(it).punct(" ") }
    if (java) {
      signature
        .keyword(
          when (kind) {
            Api.TypeKind.INTERFACE -> "interface"
            Api.TypeKind.ENUM -> "enum"
            Api.TypeKind.ANNOTATION -> "@interface"
            else -> "class"
          },
        )
        .punct(" ")
        .name(name)
        .typeParams(generics)
      superclass?.let {
        signature.punct(" ").keyword("extends").punct(" ").type(it.typeConstructor)
      }
      if (interfaces.isNotEmpty()) {
        signature
          .punct(" ")
          .keyword(if (kind == Api.TypeKind.INTERFACE) "extends" else "implements")
          .punct(" ")
        interfaces.forEachIndexed { i, t ->
          if (i > 0) signature.punct(", ")
          signature.type(t.typeConstructor)
        }
      }
    } else {
      signature
        .keyword(
          when (kind) {
            Api.TypeKind.INTERFACE -> "interface"
            Api.TypeKind.ENUM -> "enum class"
            Api.TypeKind.ANNOTATION -> "annotation class"
            Api.TypeKind.OBJECT -> "object"
            else -> "class"
          },
        )
        .punct(" ")
        .name(name.substringAfterLast('.'))
        .typeParams(generics)
      primaryConstructor(type)
        ?.takeIf { it.parameters.isNotEmpty() }
        ?.let { constructor ->
          signature.punct("(")
          constructor.parameters.forEachIndexed { i, p ->
            if (i > 0) signature.punct(", ")
            type.properties
              .firstOrNull {
                it.name == p.name && it.extra<IsAlsoParameter>() != null && documentable(it)
              }
              ?.let {
                signature.keyword(if (it.extra<IsVar>() != null) "var" else "val").punct(" ")
              }
            parameter(signature, p)
          }
          signature.punct(")")
        }
      if (supertypes.isNotEmpty()) {
        signature.punct(" : ")
        supertypes.forEachIndexed { i, t ->
          if (i > 0) signature.punct(", ")
          signature.type(t.typeConstructor)
        }
      }
      signature.where(generics)
    }

    val members = disambiguate(members(type), java) { member(type, it) }
    uniqueAnchors(name, members.map { it.anchor() })
    where = location(type)
    return Api.Type(
      identity.id(type.dri),
      name,
      kind,
      language(type),
      modifiers,
      annotations(type, java),
      typeParams(generics, docs, java),
      signature.build(),
      superclass?.let { Tokens(java, identity).type(it.typeConstructor).build() } ?: listOf(),
      interfaces.map { Tokens(java, identity).type(it.typeConstructor).build() },
      ancestors(type, superclass, java),
      permits(type),
      enclosing(type),
      listOf(),
      members,
      inherited(type),
      docs?.doc,
      deprecation(type, docs),
      docs?.since,
      false,
      source(type),
    )
  }

  private fun primaryConstructor(type: DClasslike): DFunction? =
    (type as? WithConstructors)?.constructors?.firstOrNull {
      it.extra<PrimaryConstructorExtra>() != null && documentable(it)
    }

  /** The superclass chain, nearest first, as far as the model knows it; ends at the root class. */
  private fun ancestors(
    type: DClasslike,
    superclass: TypeConstructorWithKind?,
    java: Boolean,
  ): List<Api.Ref> {
    val kind = kind(type)
    if (kind != Api.TypeKind.CLASS && kind != Api.TypeKind.ENUM && kind != Api.TypeKind.OBJECT)
      return listOf()
    val out = mutableListOf<Api.Ref>()
    var parent = superclass?.typeConstructor?.dri
    while (parent != null) {
      out += identity.ref(parent.copy(callable = null), parent.classNames)
      val local = types[identity.id(parent)] ?: break
      parent = supertypes(local).firstOrNull(::isClassKind)?.typeConstructor?.dri
    }
    // A throwable's chain reaches Throwable even where the model does not know its supertypes.
    if (type.extra<ExceptionInSupertypes>() != null && out.none { it.text() == "Throwable" }) {
      out +=
        identity.ref(
          if (java) DRI("java.lang", "Throwable") else DRI("kotlin", "Throwable"),
          "Throwable",
        )
    }
    if (kind == Api.TypeKind.ENUM)
      out += identity.ref(if (java) DRI("java.lang", "Enum") else DRI("kotlin", "Enum"), "Enum")
    out +=
      identity.ref(
        if (java) DRI("java.lang", "Object") else DRI("kotlin", "Any"),
        if (java) "Object" else "Any",
      )
    return out
  }

  /** A sealed type's direct subtypes in this model, by name. */
  private fun permits(type: DClasslike): List<Api.Ref> {
    if (type.modality !is KotlinModifier.Sealed) return listOf()
    val id = identity.id(type.dri)
    return types.values
      .filter { other -> supertypes(other).any { identity.id(it.typeConstructor.dri) == id } }
      .sortedBy { it.dri.classNames }
      .map { identity.ref(it.dri, it.dri.classNames) }
  }

  private fun enclosing(type: DClasslike): String? =
    type.dri.classNames
      ?.substringBeforeLast('.', "")
      ?.takeIf { it.isNotEmpty() }
      ?.let { identity.id(type.dri.copy(classNames = it)) }

  /**
   * A type's own members (inherited ones are listed apart), in declaration order: by source line, a
   * primary constructor's properties in parameter order after it; members the compiler declares (an
   * enum's `entries`, `values()` and `valueOf()`) last.
   */
  private fun members(type: DClasslike): List<Documentable> {
    val out = mutableListOf<Documentable>()
    val annotationClass = type is DAnnotation && language(type) == "kotlin"
    if (type is WithConstructors && !annotationClass)
      out += type.constructors.filter(::documentable)
    if (type is DEnum) out += type.entries
    out += type.properties.filter { documentable(it) && !inherited(it) }
    out += type.functions.filter { documentable(it) && !inherited(it) }
    val primary = primaryConstructor(type)?.parameters?.map { it.name }.orEmpty()
    val index = out.withIndex().associate { it.value to it.index }
    // Dokka gives enum entries no source position: they come right after the primary constructor,
    // as written.
    val typeLine = type.line ?: 0
    return out.sortedWith(
      compareBy<Documentable>(
        {
          if (implicit(type, it)) Int.MAX_VALUE
          else if (it is DEnumEntry) typeLine else it.line ?: Int.MAX_VALUE
        },
        {
          when {
            it is DFunction && it.extra<PrimaryConstructorExtra>() != null -> -1
            it is DEnumEntry -> primary.size
            else -> primary.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i }
          }
        },
        { index.getValue(it) },
      ),
    )
  }

  private fun inherited(d: Documentable): Boolean =
    d.extra<InheritedMember>()?.inheritedFrom?.values?.firstOrNull() != null

  private fun documentable(d: Documentable): Boolean =
    when (d.declaredVisibility) {
      null,
      is KotlinVisibility.Public,
      is JavaVisibility.Public,
      is KotlinVisibility.Protected,
      is JavaVisibility.Protected -> true
      else -> false
    }

  /** Members inherited from supertypes, grouped by the type that declares them. */
  private fun inherited(type: DClasslike): List<Api.Inherited> {
    val groups = sortedMapOf<String, Pair<DRI, MutableList<Documentable>>>()
    for (member in type.functions + type.properties) {
      if (!documentable(member)) continue
      val from = member.extra<InheritedMember>()?.inheritedFrom?.values?.firstOrNull() ?: continue
      groups.getOrPut(identity.id(from)) { from to mutableListOf() }.second += member
    }
    val direct = (type as? WithSupertypes)?.supertypes?.values?.firstOrNull().orEmpty()
    return groups.values.map { (from, members) ->
      val local = types[identity.id(from)]
      val isInterface =
        local is DInterface ||
          direct.any {
            it.typeConstructor.dri == from &&
              (it.kind == KotlinClassKindTypes.INTERFACE || it.kind == JavaClassKindTypes.INTERFACE)
          }
      Api.Inherited(
        identity.ref(from, from.classNames),
        if (isInterface) "interface" else "class",
        members
          .sortedWith(compareBy({ it.name }, { identity.id(it.dri) }))
          .map {
            identity.ref(
              it.dri.copy(packageName = from.packageName, classNames = from.classNames),
              it.name,
            )
          }
          .distinctBy { it.id() },
      )
    }
  }

  // ---- Members.

  private fun member(owner: DClasslike, d: Documentable): Api.Member =
    when {
      d is DFunction -> function(owner, d)
      d is DProperty && owner is DAnnotation && language(owner) == "kotlin" ->
        annotationElement(owner, d)
      d is DProperty -> property(owner, d)
      d is DEnumEntry -> enumEntry(owner, d)
      else -> error("Not a member: ${d.dri}")
    }

  private fun isJava(owner: DClasslike?, d: Documentable): Boolean =
    language(owner ?: d) == "java" || language(d) == "java"

  private fun function(owner: DClasslike?, f: DFunction): Api.Member {
    val java = isJava(owner, f)
    val ownerDocs = owner?.let { docs(it) }
    where = location(f)
    val docs = docs(f)
    val constructor = f.isConstructor
    val modifiers = memberModifiers(owner, f, java)
    val ownerName = owner?.dri?.classNames?.substringAfterLast('.').orEmpty()
    val annotationElement = java && owner is DAnnotation
    val signature = Tokens(java, identity)
    modifiers.forEach { signature.keyword(it).punct(" ") }
    if (java) {
      if (f.generics.isNotEmpty()) signature.typeParams(f.generics).punct(" ")
      if (!constructor) signature.type(f.type).punct(" ")
      signature.name(if (constructor) ownerName else f.name).punct("(")
      f.parameters.forEachIndexed { i, p ->
        if (i > 0) signature.punct(", ")
        signature.type(p.type, varargs = isVararg(p)).punct(" ").param(p.name ?: "arg$i")
      }
      signature.punct(")")
      if (annotationElement)
        defaultValue(f)?.let { signature.punct(" ").keyword("default").punct(" ").literal(it) }
      val checked = checkedExceptions(f)
      if (checked.isNotEmpty()) {
        signature.punct(" ").keyword("throws").punct(" ")
        checked.forEachIndexed { i, dri ->
          if (i > 0) signature.punct(", ")
          signature.typeName(dri)
        }
      }
    } else {
      signature.keyword(if (constructor) "constructor" else "fun")
      if (!constructor) {
        signature.punct(" ")
        if (f.generics.isNotEmpty()) signature.typeParams(f.generics).punct(" ")
        f.receiver?.let { signature.type(it.type).punct(".") }
        signature.name(f.name)
      }
      signature.punct("(")
      f.parameters.forEachIndexed { i, p ->
        if (i > 0) signature.punct(", ")
        parameter(signature, p)
      }
      signature.punct(")")
      if (!constructor && !isUnit(f.type)) signature.punct(": ").type(f.type)
      if (!constructor) signature.where(f.generics)
    }

    val primary = constructor && f.extra<PrimaryConstructorExtra>() != null
    val paramDocs =
      if (primary)
        ownerDocs?.properties.orEmpty() + ownerDocs?.params.orEmpty() + docs?.params.orEmpty()
      else docs?.params.orEmpty()
    // Parts an override's comment leaves out (a parameter, the return value) come from the member
    // it overrides, as
    // the doclet inherits them.
    val overridden = overridden(owner, f, java)?.second as? DFunction
    val overriddenDocs = overridden?.let { docs(it) }.also { where = location(f) }
    val params =
      f.parameters.mapIndexed { i, p ->
        val name = p.name ?: "arg$i"
        val doc =
          paramDocs[name]
            ?: overridden?.parameters?.getOrNull(i)?.name?.let { overriddenDocs?.params?.get(it) }
        Api.Param(
          name,
          Tokens(java, identity).type(p.type, varargs = isVararg(p)).build(),
          isVararg(p),
          if (java) null else defaultValue(p),
          nullness(p.type, java),
          annotations(p, java),
          doc.orEmpty(),
        )
      }
    val returns =
      if (constructor || isUnit(f.type) || f.type is Void) null
      else
        Api.Returns(
          Tokens(java, identity).type(f.type).build(),
          nullness(f.type, java),
          docs?.returns ?: overriddenDocs?.returns.orEmpty(),
        )
    val doc =
      docs?.doc
        ?: if (primary)
          ownerDocs?.constructor?.let { Doc(KDocs.summary(it), it, listOf(), listOf(), null) }
        else null
    return Api.Member(
      identity.id(f.dri),
      identity.anchor(f, java),
      when {
        constructor -> Api.MemberKind.CONSTRUCTOR
        annotationElement -> Api.MemberKind.ANNOTATION_ELEMENT
        java -> Api.MemberKind.METHOD
        else -> Api.MemberKind.FUNCTION
      },
      if (constructor) ownerName else f.name,
      modifiers,
      annotations(f, java),
      if (constructor) listOf() else typeParams(f.generics, docs, java),
      f.receiver?.let { receiver(it, docs, java) },
      signature.build(),
      params,
      returns,
      listOf(),
      null,
      throws(f, docs, if (primary) ownerDocs else null, java),
      null,
      if (annotationElement) defaultValue(f) else null,
      overrides(owner, f, java),
      implicit(owner, f),
      doc,
      deprecation(f, docs),
      docs?.since,
      source(f),
    )
  }

  /**
   * A Kotlin parameter: `vararg`, `crossinline` or `noinline`, its name and type, and its default.
   */
  private fun parameter(tokens: Tokens, p: DParameter) {
    for (modifier in listOf("vararg", "crossinline", "noinline")) if (modifier in p.extraModifiers)
      tokens.keyword(modifier).punct(" ")
    tokens.param(p.name ?: "_").punct(": ").type(p.type, varargs = isVararg(p))
    defaultValue(p)?.let { tokens.punct(" = ").literal(it) }
  }

  private fun receiver(p: DParameter, docs: Docs?, java: Boolean) =
    Api.Param(
      "this",
      Tokens(java, identity).type(p.type).build(),
      false,
      null,
      nullness(p.type, java),
      listOf(),
      docs?.receiver.orEmpty(),
    )

  private fun property(owner: DClasslike?, p: DProperty): Api.Member {
    val java = isJava(owner, p)
    val ownerDocs = owner?.let { docs(it) }
    where = location(p)
    val docs = docs(p)
    val modifiers = memberModifiers(owner, p, java)
    val signature = Tokens(java, identity)
    modifiers.forEach { signature.keyword(it).punct(" ") }
    if (java) {
      signature.type(p.type).punct(" ").name(p.name)
    } else {
      // Dokka drops a setter less visible than the documented visibilities. The docs show public
      // API
      // only, so a property whose setter is hidden reads as `val`: that is all a caller can do with
      // it, and Kotlin forbids a private setter on an open property, so no subclass can widen it.
      val settable = p.extra<IsVar>() != null && p.setter != null
      signature.keyword(if (settable) "var" else "val").punct(" ")
      if (p.generics.isNotEmpty()) signature.typeParams(p.generics).punct(" ")
      p.receiver?.let { signature.type(it.type).punct(".") }
      signature.name(p.name).punct(": ").type(p.type)
      signature.where(p.generics)
    }
    val doc =
      docs?.doc
        ?: (docs?.properties?.get(p.name) ?: ownerDocs?.properties?.get(p.name))?.let {
          Doc(KDocs.summary(it), it, listOf(), listOf(), null)
        }
    val constant =
      if ("const" in modifiers || (java && "static" in modifiers && "final" in modifiers))
        defaultValue(p)
      else null
    return Api.Member(
      identity.id(p.dri),
      identity.anchor(p, java),
      if (java) Api.MemberKind.FIELD else Api.MemberKind.PROPERTY,
      p.name,
      modifiers,
      annotations(p, java),
      typeParams(p.generics, docs, java),
      p.receiver?.let { receiver(it, docs, java) },
      signature.build(),
      listOf(),
      null,
      Tokens(java, identity).type(p.type).build(),
      nullness(p.type, java),
      listOf(),
      constant,
      null,
      overrides(owner, p, java),
      implicit(owner, p),
      doc,
      deprecation(p, docs),
      docs?.since,
      source(p),
    )
  }

  /**
   * A Kotlin annotation class's parameter: an element, as a Java annotation's method is, with its
   * default.
   */
  private fun annotationElement(owner: DAnnotation, p: DProperty): Api.Member {
    val ownerDocs = docs(owner)
    where = location(p)
    val docs = docs(p)
    val default =
      primaryConstructor(owner)?.parameters?.firstOrNull { it.name == p.name }?.let(::defaultValue)
    val signature =
      Tokens(false, identity).keyword("val").punct(" ").name(p.name).punct(": ").type(p.type)
    default?.let { signature.punct(" = ").literal(it) }
    val doc =
      docs?.doc
        ?: (docs?.properties?.get(p.name)
            ?: ownerDocs?.properties?.get(p.name)
            ?: ownerDocs?.params?.get(p.name))
          ?.let { Doc(KDocs.summary(it), it, listOf(), listOf(), null) }
    return Api.Member(
      identity.id(p.dri),
      identity.anchor(p, false),
      Api.MemberKind.ANNOTATION_ELEMENT,
      p.name,
      listOf(),
      annotations(p, false),
      listOf(),
      null,
      signature.build(),
      listOf(),
      null,
      Tokens(false, identity).type(p.type).build(),
      nullness(p.type, false),
      listOf(),
      null,
      default,
      null,
      false,
      doc,
      deprecation(p, docs),
      docs?.since,
      source(p),
    )
  }

  private fun enumEntry(owner: DClasslike, e: DEnumEntry): Api.Member {
    val java = isJava(owner, e)
    where = location(e)
    val docs = docs(e)
    return Api.Member(
      identity.id(e.dri),
      identity.anchor(e, java),
      Api.MemberKind.ENUM_CONSTANT,
      e.name,
      listOf(),
      annotations(e, java),
      listOf(),
      null,
      Tokens(java, identity).name(e.name).build(),
      listOf(),
      null,
      Tokens(java, identity).typeName(owner.dri).build(),
      null,
      listOf(),
      null,
      null,
      null,
      false,
      docs?.doc,
      deprecation(e, docs),
      docs?.since,
      source(e),
    )
  }

  private fun typeAlias(a: DTypeAlias): Api.TypeAlias {
    where = location(a)
    val docs = docs(a)
    val target = a.underlyingType.values.firstOrNull() ?: a.type
    val signature =
      Tokens(false, identity)
        .keyword("typealias")
        .punct(" ")
        .name(a.name)
        .typeParams(a.generics)
        .punct(" = ")
        .type(target)
    return Api.TypeAlias(
      identity.id(a.dri),
      a.name,
      typeParams(a.generics, docs, false),
      signature.build(),
      Tokens(false, identity).type(target).build(),
      docs?.doc,
      deprecation(a, docs),
      docs?.since,
      source(a),
    )
  }

  /**
   * Members the compiler declares: those Dokka marks obvious, and an enum's `entries`, `values()`
   * and `valueOf()`.
   */
  private fun implicit(owner: DClasslike?, d: Documentable): Boolean =
    d.extra<ObviousMember>() != null ||
      owner is DEnum &&
        when {
          d is DProperty -> d.name == "entries" && d.receiver == null
          d is DFunction ->
            (d.name == "values" && d.parameters.isEmpty()) ||
              (d.name == "valueOf" && d.parameters.size == 1)
          else -> false
        }

  /** The member [d] overrides in a supertype this model has, found by anchor, with its type. */
  private fun overridden(
    owner: DClasslike?,
    d: Documentable,
    java: Boolean,
  ): Pair<DClasslike, Documentable>? {
    if (owner == null || (java || "override" !in d.extraModifiers)) return null
    val anchor = identity.anchor(d, java)
    val seen = HashSet<String>()
    val queue = ArrayDeque(supertypes(owner).map { it.typeConstructor.dri })
    while (queue.isNotEmpty()) {
      val dri = queue.removeFirst()
      if (!seen.add(identity.id(dri))) continue
      val type = types[identity.id(dri)] ?: continue
      members(type)
        .firstOrNull {
          it !is DEnumEntry && identity.anchor(it, language(type) == "java") == anchor
        }
        ?.let {
          return type to it
        }
      queue += supertypes(type).map { it.typeConstructor.dri }
    }
    return null
  }

  private fun overrides(owner: DClasslike?, d: Documentable, java: Boolean): Api.Ref? =
    overridden(owner, d, java)?.let { (type, member) ->
      identity.ref(member.dri, "${type.dri.classNames}.${member.name}")
    } ?: externalOverride(owner, d, java)

  /**
   * For an override of a member declared outside this model (another module's interface), a link to
   * that member's page through the configured links: the first direct supertype that the links
   * reach. The documentation of a class file is out of Dokka's reach, so the page links to the
   * member instead of copying its description.
   */
  private fun externalOverride(owner: DClasslike?, d: Documentable, java: Boolean): Api.Ref? {
    if (owner == null || java || "override" !in d.extraModifiers) return null
    val callable = d.dri.callable ?: return null
    for (supertype in supertypes(owner).map { it.typeConstructor.dri }) {
      if (types.containsKey(identity.id(supertype))) continue
      val target = supertype.copy(callable = callable, extra = null)
      val url = identity.url(target) ?: continue
      // A Javadoc-laid-out page names its members by their Java anchors; a Dokka-laid-out link
      // already names the member's own page.
      val member =
        if ('#' in url || url.endsWith("/") || !url.endsWith(".html") || url.contains("/-")) url
        else "$url#${identity.anchor(d, java = true)}"
      return Api.Ref(identity.id(target), "${supertype.classNames}.${d.name}", member)
    }
    return null
  }

  // ---- Pieces.

  private fun isUnit(b: Bound): Boolean =
    b is GenericTypeConstructor && b.dri.packageName == "kotlin" && b.dri.classNames == "Unit"

  private fun checkedExceptions(f: DFunction): List<DRI> {
    f.extra<CheckedExceptions>()?.exceptions?.values?.firstOrNull()?.let {
      return it
    }
    // Kotlin's @Throws(A::class, B::class).
    val throws =
      f.directAnnotations.firstOrNull { Identity.qualified(it.dri) in THROWS } ?: return listOf()
    return throws.params.values.flatMap { v ->
      ((v as? ArrayValue)?.value ?: listOf(v)).mapNotNull { (it as? ClassValue)?.classDRI }
    }
  }

  /**
   * Documented exceptions (`@throws`, `@exception`) in source order, then declared ones nothing
   * documents.
   */
  private fun throws(f: DFunction, docs: Docs?, classDocs: Docs?, java: Boolean): List<Api.Thrown> {
    val declared = checkedExceptions(f)
    val declaredIds = declared.map(identity::id).toSet()
    val documented = classDocs?.throws.orEmpty() + docs?.throws.orEmpty()
    return documented.map { (dri, blocks) ->
      Api.Thrown(
        Tokens(java, identity).typeName(dri).build(),
        identity.id(dri) in declaredIds,
        blocks,
      )
    } +
      declared
        .filter { c -> documented.none { identity.id(it.first) == identity.id(c) } }
        .map { Api.Thrown(Tokens(java, identity).typeName(it).build(), true, listOf()) }
  }

  private fun nullness(b: Bound, java: Boolean): Api.Nullness? =
    when {
      b is Nullable -> Api.Nullness.NULLABLE
      b is DefinitelyNonNullable -> Api.Nullness.NON_NULL
      java -> null
      b is TypeParameter ||
        b is Dynamic ||
        b is UnresolvedBound ||
        b is PrimitiveJavaType ||
        b is Void -> null
      else -> Api.Nullness.NON_NULL
    }

  private fun defaultValue(d: Documentable): String? =
    d.extra<DefaultValue>()?.expression?.values?.firstOrNull()?.let(::expression)

  /** A default value or constant as Kotlin writes it. */
  private fun expression(e: Expression): String =
    when (e) {
      is StringConstant -> quote(e.value)
      is IntegerConstant -> e.value.toString()
      is DoubleConstant -> e.value.toString()
      is FloatConstant -> e.value.toString() + "f"
      is BooleanConstant -> e.value.toString()
      is ComplexExpression -> e.value
      else -> e.toString()
    }

  private val kotlinOrder =
    listOf(
      "protected",
      "abstract",
      "open",
      "sealed",
      "const",
      "external",
      "override",
      "lateinit",
      "tailrec",
      "suspend",
      "inner",
      "fun",
      "companion",
      "inline",
      "value",
      "infix",
      "operator",
      "data",
    )
  private val javaOrder =
    listOf(
      "public",
      "protected",
      "abstract",
      "static",
      "final",
      "transient",
      "volatile",
      "synchronized",
      "native",
      "strictfp",
    )

  private fun typeModifiers(type: DClasslike, java: Boolean): List<String> {
    val out = mutableSetOf<String>()
    val extras = type.extraModifiers
    visibilityModifier(type, java)?.let { out += it }
    if (java) {
      if (type.modality is JavaModifier.Abstract && type !is DInterface) out += "abstract"
      if (type.modality is JavaModifier.Final && type !is DEnum) out += "final"
      if ("static" in extras) out += "static"
      return javaOrder.filter { it in out }
    }
    when (type.modality) {
      is KotlinModifier.Abstract -> if (type !is DInterface) out += "abstract"
      is KotlinModifier.Open -> out += "open"
      is KotlinModifier.Sealed -> out += "sealed"
      else -> {}
    }
    if (type is DObject && isCompanion(type)) out += "companion"
    out += extras.filter { it in setOf("data", "value", "inner", "fun", "external") }
    return kotlinOrder.filter { it in out }
  }

  private fun visibilityModifier(d: Documentable, java: Boolean): String? =
    when (d.declaredVisibility) {
      is JavaVisibility.Public -> if (java) "public" else null
      is KotlinVisibility.Protected,
      is JavaVisibility.Protected -> "protected"
      else -> null
    }

  private fun memberModifiers(owner: DClasslike?, d: Documentable, java: Boolean): List<String> {
    val out = mutableSetOf<String>()
    val extras = d.extraModifiers
    val inInterface = owner is DInterface
    visibilityModifier(d, java)?.let { out += it }
    if (java) {
      if (d.modality is JavaModifier.Abstract && !inInterface) out += "abstract"
      if (d.modality is JavaModifier.Final && !(d is DFunction && d.isConstructor)) out += "final"
      out += extras.filter { it in javaOrder }
      return javaOrder.filter { it in out }
    }
    val override = "override" in extras
    when (d.modality) {
      is KotlinModifier.Abstract -> if (!inInterface) out += "abstract"
      is KotlinModifier.Open ->
        if (!override && !inInterface && owner != null && !(d is DFunction && d.isConstructor))
          out += "open"
      else -> {}
    }
    out += extras.filter { it in kotlinOrder }
    return kotlinOrder.filter { it in out }
  }

  /**
   * Java-visible annotations: `@MustBeDocumented` ones and the JVM annotations that change what a
   * Java caller sees.
   */
  private fun annotations(d: Documentable, java: Boolean): List<List<Token>> {
    // Dokka marks a Java static member with a synthetic @JvmStatic; a Java declaration shows only
    // its own.
    val direct =
      d.directAnnotations.filter {
        if (java) it.mustBeDocumented && !Identity.qualified(it.dri).startsWith("kotlin.jvm.")
        else it.mustBeDocumented || Identity.qualified(it.dri) in JVM_ANNOTATIONS
      }
    // A top-level declaration's file may name its Java class: @file:JvmName("Shapes").
    val file =
      if (java || d.dri.classNames != null) listOf()
      else d.fileAnnotations.filter { Identity.qualified(it.dri) == "kotlin.jvm.JvmName" }
    return file.map { annotation(it, java, "file:") } + direct.map { annotation(it, java, "") }
  }

  private fun annotation(a: Annotations.Annotation, java: Boolean, target: String): List<Token> {
    val tokens = Tokens(java, identity).annotation(a.dri, target)
    if (a.params.isNotEmpty()) {
      tokens.punct("(")
      val single = a.params.size == 1 && (!java || a.params.keys.single() == "value")
      a.params.entries.forEachIndexed { i, (k, v) ->
        if (i > 0) tokens.punct(", ")
        if (single && !java && v is ArrayValue) {
          // Kotlin's single array argument is almost always a vararg: @Throws(A::class, B::class).
          v.value.forEachIndexed { j, e ->
            if (j > 0) tokens.punct(", ")
            annotationValue(tokens, e, false)
          }
          return@forEachIndexed
        }
        if (!single) tokens.param(k).punct(" = ")
        annotationValue(tokens, v, java)
      }
      tokens.punct(")")
    }
    return tokens.build()
  }

  private fun annotationValue(tokens: Tokens, v: AnnotationParameterValue, java: Boolean) {
    when (v) {
      is StringValue -> tokens.literal(quote(v.value))
      is EnumValue -> tokens.literal(v.enumName)
      is ClassValue ->
        tokens.typeName(v.classDRI, v.className).punct(if (java) "." else "::").keyword("class")
      is ArrayValue -> {
        tokens.punct(if (java) "{" else "[")
        v.value.forEachIndexed { i, e ->
          if (i > 0) tokens.punct(", ")
          annotationValue(tokens, e, java)
        }
        tokens.punct(if (java) "}" else "]")
      }
      is AnnotationValue -> {
        tokens.typeName(v.annotation.dri).punct("(")
        v.annotation.params.entries.forEachIndexed { i, (k, e) ->
          if (i > 0) tokens.punct(", ")
          tokens.param(k).punct(" = ")
          annotationValue(tokens, e, java)
        }
        tokens.punct(")")
      }
      is LiteralValue -> tokens.literal(v.text())
    }
  }

  /**
   * `@Deprecated` (Kotlin's or Java's) and the `@deprecated` tag: the tag's text, Kotlin's message,
   * its `ReplaceWith` and a level above `WARNING`. `ERROR` and `HIDDEN` also set `forRemoval`: they
   * are the last steps before removal.
   */
  private fun deprecation(d: Documentable, docs: Docs?): Api.Deprecation? {
    val annotations = d.directAnnotations
    val kotlin = annotations.firstOrNull { Identity.qualified(it.dri) == "kotlin.Deprecated" }
    val java = annotations.firstOrNull { Identity.qualified(it.dri) == "java.lang.Deprecated" }
    if (kotlin == null && java == null && docs?.deprecated == null) return null
    val blocks = mutableListOf<Doc.Block>()
    docs?.deprecated?.let { blocks += it }
    var level: String? = null
    if (kotlin != null) {
      (kotlin.params["message"] as? StringValue)
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?.let { blocks += Doc.Paragraph(listOf(Doc.Text(it))) }
      val replace = (kotlin.params["replaceWith"] as? AnnotationValue)?.annotation?.params
      val expression = (replace?.get("expression") as? StringValue)?.value
      if (!expression.isNullOrBlank()) {
        val imports =
          (replace["imports"] as? ArrayValue)?.value.orEmpty().mapNotNull {
            (it as? StringValue)?.value
          }
        val inlines = mutableListOf<Doc.Inline>(Doc.Text("Replace with "), Doc.Code(expression))
        if (imports.isNotEmpty()) {
          inlines += Doc.Text(", importing ")
          imports.forEachIndexed { i, import ->
            if (i > 0) inlines += Doc.Text(", ")
            inlines += Doc.Code(import)
          }
        }
        inlines += Doc.Text(".")
        blocks += Doc.Paragraph(inlines)
      }
      level = (kotlin.params["level"] as? EnumValue)?.enumName?.substringAfterLast('.')
      when (level) {
        "ERROR" ->
          blocks +=
            Doc.Paragraph(
              listOf(
                Doc.Text("Using it is a compile error (level "),
                Doc.Code("ERROR"),
                Doc.Text(")."),
              ),
            )
        "HIDDEN" ->
          blocks +=
            Doc.Paragraph(
              listOf(
                Doc.Text("Hidden from Kotlin callers (level "),
                Doc.Code("HIDDEN"),
                Doc.Text("); compiled code still links to it."),
              ),
            )
      }
    }
    val since = (java?.params?.get("since") as? StringValue)?.value
    val forRemoval =
      (java?.params?.get("forRemoval") as? BooleanValue)?.value == true ||
        level == "ERROR" ||
        level == "HIDDEN"
    return Api.Deprecation(blocks, since, forRemoval)
  }

  /**
   * Type parameters; a Kotlin one's name carries its variance and `reified` (`out T`, `reified T`).
   */
  private fun typeParams(
    generics: List<DTypeParameter>,
    docs: Docs?,
    java: Boolean,
  ): List<Api.TypeParam> = generics.map { g ->
    val prefix =
      if (java) ""
      else
        listOfNotNull(
            when (g.variantTypeParameter) {
              is Covariance<*> -> "out"
              is Contravariance<*> -> "in"
              else -> null
            },
            "reified".takeIf { it in g.extraModifiers },
          )
          .joinToString("") { "$it " }
    Api.TypeParam(
      prefix + g.name,
      Tokens.bounds(g).map { Tokens(java, identity).type(it).build() },
      docs?.params?.get(g.name).orEmpty(),
    )
  }

  private fun source(d: Documentable): Api.Source? {
    val path = d.sourcePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
    val root = sourceRoots.firstOrNull { path.startsWith(it) } ?: return null
    val line = d.line ?: return null
    return Api.Source(root.relativize(path).toString().replace('\\', '/'), line)
  }

  /** Where a problem is: `path:line: name`, or the name alone when Dokka gives no source. */
  private fun location(d: Documentable): String {
    val name =
      listOfNotNull(
          d.dri.packageName?.takeIf { it.isNotEmpty() },
          d.dri.classNames,
          d.dri.callable?.name,
        )
        .joinToString(".")
    val source = source(d)
    return if (source != null) "${source.path()}:${source.line()}: $name"
    else name.ifEmpty { identity.id(d.dri) }
  }

  /**
   * Members of one page with their anchors: where two would share one (overloads that differ only
   * in type arguments, kept apart on the JVM by `@JvmName`), both take the form with type
   * arguments.
   */
  private fun <D : Documentable> disambiguate(
    declarations: List<D>,
    java: Boolean,
    build: (D) -> Api.Member,
  ): List<Api.Member> {
    val members = declarations.map(build)
    val shared = members.groupingBy { it.anchor() }.eachCount().filterValues { it > 1 }.keys
    if (shared.isEmpty()) return members
    return members.mapIndexed { i, m ->
      if (m.anchor() !in shared) m
      else
        Api.Member(
          m.id(),
          identity.anchor(declarations[i], java, typeArguments = true),
          m.kind(),
          m.name(),
          m.modifiers(),
          m.annotations(),
          m.typeParams(),
          m.receiver(),
          m.signature(),
          m.params(),
          m.returns(),
          m.type(),
          m.nullness(),
          m.exceptions(),
          m.constant(),
          m.defaultValue(),
          m.overrides(),
          m.implicit(),
          m.doc(),
          m.deprecated(),
          m.since(),
          m.source(),
        )
    }
  }

  private fun uniqueAnchors(page: String, anchors: List<String>) {
    for ((anchor, count) in anchors.groupingBy { it }.eachCount()) {
      if (count > 1)
        errors +=
          "$page: $count declarations share the anchor $anchor; the model cannot tell them apart"
    }
  }

  private fun uniqueIds(packages: List<Api.Package>) {
    val ids = mutableListOf<String>()
    for (p in packages) {
      ids += p.id()
      for (t in p.types()) {
        ids += t.id()
        t.members().forEach { ids += it.id() }
      }
      p.functions().forEach { ids += it.id() }
      p.properties().forEach { ids += it.id() }
      p.typeAliases().forEach { ids += it.id() }
    }
    for ((id, count) in ids.groupingBy { it }.eachCount()) if (count > 1)
      errors += "$count declarations share the id $id"
  }

  // ---- Documentation.

  /** A declaration's documentation with its tags split out. */
  class Docs(
    val doc: Doc?,
    val params: Map<String, List<Doc.Block>>,
    val properties: Map<String, List<Doc.Block>>,
    val returns: List<Doc.Block>?,
    val receiver: List<Doc.Block>?,
    val throws: List<Pair<DRI, List<Doc.Block>>>,
    val constructor: List<Doc.Block>?,
    val deprecated: List<Doc.Block>?,
    val since: String?,
  )

  private val docsRead = java.util.IdentityHashMap<Documentable, Docs?>()

  /**
   * [d]'s documentation, read once (a type's is read again for its constructors and properties).
   */
  private fun docs(d: Documentable): Docs? {
    if (docsRead.containsKey(d)) return docsRead[d]
    return readDocs(d).also { docsRead[d] = it }
  }

  private fun readDocs(d: Documentable): Docs? {
    val node = d.documentation.values.firstOrNull() ?: return null
    val body = mutableListOf<Doc.Block>()
    val notes = mutableListOf<Doc.Note>()
    val see = mutableListOf<List<Doc.Inline>>()
    val params = LinkedHashMap<String, List<Doc.Block>>()
    val properties = LinkedHashMap<String, List<Doc.Block>>()
    var returns: List<Doc.Block>? = null
    var receiver: List<Doc.Block>? = null
    val throws = mutableListOf<Pair<DRI, List<Doc.Block>>>()
    var constructor: List<Doc.Block>? = null
    var deprecated: List<Doc.Block>? = null
    var since: String? = null
    for (tag in node.children) {
      when (tag) {
        is Description ->
          kdocs.description(tag.root).let { (b, n) ->
            body += b
            notes += n
          }
        is Param -> params[tag.name] = kdocs.blocks(listOf(tag.root))
        is Property -> properties[tag.name] = kdocs.blocks(listOf(tag.root))
        is Return -> returns = kdocs.blocks(listOf(tag.root))
        is Receiver -> receiver = kdocs.blocks(listOf(tag.root))
        is Throws -> {
          val address = tag.exceptionAddress
          if (address == null)
            errors += "$where: cannot resolve the exception ${tag.name} in @throws"
          else throws += address to kdocs.blocks(listOf(tag.root))
        }
        is Constructor -> constructor = kdocs.blocks(listOf(tag.root))
        is Deprecated -> deprecated = kdocs.blocks(listOf(tag.root))
        is Since -> since = KDocs.plain(listOf(tag.root)).trim()
        is See -> {
          val address = tag.address
          if (address == null) {
            errors += "$where: cannot resolve the reference ${tag.name} in @see"
          } else {
            val text = kdocs.inlines(listOf(tag.root))
            see +=
              listOf<Doc.Inline>(
                Doc.Link(identity.ref(address), null, true, listOf(Doc.Text(tag.name))),
              ) + if (text.isEmpty()) listOf() else listOf(Doc.Text(": ")) + text
          }
        }
        is Sample -> {
          val code = samples(d, tag.name)
          if (code == null)
            errors +=
              "$where: @sample ${tag.name} names no function Dokka can find; put its file in the source set's samples"
          else body += Doc.CodeBlock("kotlin", code)
        }
        is CustomTagWrapper ->
          when (tag.name) {
            "apiNote" -> notes += Doc.Note(Doc.NoteKind.API_NOTE, kdocs.blocks(listOf(tag.root)))
            "implSpec" -> notes += Doc.Note(Doc.NoteKind.IMPL_SPEC, kdocs.blocks(listOf(tag.root)))
            "implNote" -> notes += Doc.Note(Doc.NoteKind.IMPL_NOTE, kdocs.blocks(listOf(tag.root)))
            else -> errors += "$where: the API model cannot represent the tag @${tag.name}"
          }
        is Author -> warnings += "$where: @author is not part of the API model; left out"
        is Version -> warnings += "$where: @version is not part of the API model; left out"
        is Suppress -> {}
      }
    }
    val empty = body.isEmpty() && notes.isEmpty() && see.isEmpty()
    val doc = if (empty) null else Doc(KDocs.summary(body), body, notes, see, null)
    return Docs(doc, params, properties, returns, receiver, throws, constructor, deprecated, since)
  }

  /** A package's description from its package-info.java, when Dokka's model has none. */
  private fun packageInfo(pkg: String): Docs? {
    val relative = pkg.replace('.', '/') + "/package-info.java"
    val file =
      sourceRoots.map { it.resolve(relative) }.firstOrNull { it.toFile().isFile } ?: return null
    val parsed =
      PackageInfo(::resolveName) { errors += "$relative: $it" }.parse(file) ?: return null
    return Docs(parsed.first, mapOf(), mapOf(), null, null, listOf(), null, null, parsed.second)
  }

  /**
   * A `{@link}` target in package-info.java, by qualified name: a package or a type in this model,
   * or one on the class path (another module's), which links through the external links or shows as
   * code.
   */
  private fun resolveName(name: String): Api.Ref? {
    if (module.packages.any { it.packageName == name }) return Api.Ref(name, null, null)
    types.values
      .firstOrNull { Identity.qualified(it.dri) == name }
      ?.let {
        return identity.ref(it.dri)
      }
    classPath.type(name)?.let {
      return identity.ref(it)
    }
    if (classPath.isPackage(name)) return Api.Ref(name, null, identity.url(DRI(name, null)))
    return null
  }

  private companion object {
    val IMPLICIT_SUPERTYPES =
      setOf(
        "kotlin.Any",
        "java.lang.Object",
        "kotlin.Enum",
        "java.lang.Enum",
        "java.lang.Record",
        "kotlin.Annotation",
        "java.lang.annotation.Annotation",
      )
    val THROWS = setOf("kotlin.Throws", "kotlin.jvm.Throws")
    val JVM_ANNOTATIONS =
      setOf(
        "kotlin.jvm.JvmName",
        "kotlin.jvm.JvmStatic",
        "kotlin.jvm.JvmOverloads",
        "kotlin.jvm.JvmField",
        "kotlin.jvm.JvmInline",
        "kotlin.jvm.JvmRecord",
        "kotlin.jvm.JvmSynthetic",
      ) + THROWS

    fun quote(s: String): String = buildString {
      append('"')
      for (c in s) {
        when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          '$' -> append("\\$")
          else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
      }
      append('"')
    }
  }
}
