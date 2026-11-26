/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import org.jetbrains.dokka.model.AdditionalModifiers
import org.jetbrains.dokka.model.Annotations
import org.jetbrains.dokka.model.Bound
import org.jetbrains.dokka.model.Contravariance
import org.jetbrains.dokka.model.Covariance
import org.jetbrains.dokka.model.DParameter
import org.jetbrains.dokka.model.Documentable
import org.jetbrains.dokka.model.Invariance
import org.jetbrains.dokka.model.Modifier
import org.jetbrains.dokka.model.Projection
import org.jetbrains.dokka.model.Visibility
import org.jetbrains.dokka.model.WithAbstraction
import org.jetbrains.dokka.model.WithSources
import org.jetbrains.dokka.model.WithVisibility
import org.jetbrains.dokka.model.properties.WithExtraProperties

// Small accessors over Dokka's model. A JVM module has one source set, so a per-source-set map
// has one value.

/** The documentable's extra properties of type [T], if it carries extras. */
internal inline fun <reified T : Any> Documentable.extras(): List<T> =
  (this as? WithExtraProperties<*>)?.extra?.allOfType<T>().orEmpty()

internal inline fun <reified T : Any> Documentable.extra(): T? = extras<T>().firstOrNull()

internal val Documentable.declaredVisibility: Visibility?
  get() = (this as? WithVisibility)?.visibility?.values?.firstOrNull()

internal val Documentable.modality: Modifier?
  get() = (this as? WithAbstraction)?.modifier?.values?.firstOrNull()

/**
 * Modifiers beyond visibility and modality, as Dokka names them (`suspend`, `override`, `static`,
 * ...).
 */
internal val Documentable.extraModifiers: Set<String>
  get() =
    extra<AdditionalModifiers>()?.content?.values?.firstOrNull().orEmpty().map { it.name }.toSet()

/** The annotations written on the declaration itself (not its file, getter or setter). */
internal val Documentable.directAnnotations: List<Annotations.Annotation>
  get() =
    extra<Annotations>()?.directAnnotations?.values?.firstOrNull().orEmpty().filter {
      it.scope == Annotations.AnnotationScope.DIRECT
    }

/** File annotations Dokka attaches to a top-level declaration (`@file:JvmName`). */
internal val Documentable.fileAnnotations: List<Annotations.Annotation>
  get() =
    extra<Annotations>()?.fileLevelAnnotations?.values?.firstOrNull().orEmpty().filter {
      it.scope == Annotations.AnnotationScope.FILE
    }

internal val Documentable.sourcePath: String?
  get() = (this as? WithSources)?.sources?.values?.firstOrNull()?.path

internal val Documentable.line: Int?
  get() = (this as? WithSources)?.sources?.values?.firstOrNull()?.computeLineNumber()

internal fun isVararg(p: DParameter): Boolean = "vararg" in p.extraModifiers

/** A projection's type: `T` of `out T`, `in T` or `T`; `null` for a star. */
internal fun projected(p: Projection): Bound? =
  when (p) {
    is Covariance<*> -> p.inner
    is Contravariance<*> -> p.inner
    is Invariance<*> -> p.inner
    is Bound -> p
    else -> null
  }
