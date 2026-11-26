/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import java.io.File
import java.util.zip.ZipFile
import org.jetbrains.dokka.links.DRI

/**
 * The documented sources' class path, to resolve a name the model does not have: a `{@link}` in a
 * `package-info.java` comment may name a type or package of another module, which Dokka's model
 * never sees. A name that resolves nowhere is a problem, as it is for the doclet.
 */
internal class ClassPath(roots: List<File>) {
  private val entries: Set<String> by lazy {
    val out = HashSet<String>()
    for (root in roots) {
      when {
        root.isDirectory ->
          root
            .walkTopDown()
            .filter { it.isFile }
            .forEach { out += it.relativeTo(root).invariantSeparatorsPath }
        root.isFile && root.name.endsWith(".jar") ->
          ZipFile(root).use { zip ->
            for (entry in zip.entries()) out += entry.name.removeSuffix("/")
          }
      }
    }
    // Every directory of a class file is a package.
    out +
      out
        .filter { it.endsWith(".class") }
        .flatMap { path -> path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }
  }

  /**
   * The DRI of the type [name] (qualified, nested types joined with dots) on the class path, or
   * `null`.
   */
  fun type(name: String): DRI? {
    val parts = name.split('.')
    for (i in parts.size - 1 downTo 1) {
      val pkg = parts.subList(0, i)
      val classes = parts.subList(i, parts.size)
      if (pkg.joinToString("/") + "/" + classes.joinToString("$") + ".class" in entries) {
        return DRI(pkg.joinToString("."), classes.joinToString("."))
      }
    }
    return null
  }

  fun isPackage(name: String): Boolean = name.replace('.', '/') in entries
}
