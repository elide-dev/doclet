/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

/** The source language of a batch of declarations. */
public enum Language {
  /** Java, laid out by google-java-format in its AOSP style. */
  JAVA,
  /** Kotlin, laid out by ktfmt's Google style. */
  KOTLIN
}
