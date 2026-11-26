/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import java.util.List;

/**
 * A source formatter: one whole source file in, the same file laid out. {@link ElideFormatter} runs
 * the real ones; tests pass a fake.
 */
public interface SourceFormatter {
  /**
   * Formats one source file.
   *
   * @param language the file's language
   * @param source the file's text
   * @return the formatted text
   * @throws FormatterException if the formatter fails, for example on a parse error
   */
  String format(Language language, String source) throws FormatterException;

  /**
   * Names the formatter in messages.
   *
   * @return a short description, such as the command it runs
   */
  String describe();

  /** A formatter run that failed. */
  final class FormatterException extends Exception {
    private static final long serialVersionUID = 1L;

    /** The input lines (1-based) the formatter's errors name, if any. */
    private final transient List<Integer> lines;

    /**
     * A failure.
     *
     * @param message what failed, with the formatter's own error
     * @param lines the input lines its errors name, 1-based; empty when unknown
     */
    public FormatterException(String message, List<Integer> lines) {
      super(message);
      this.lines = List.copyOf(lines);
    }

    /**
     * The input lines the errors name.
     *
     * @return 1-based line numbers, in the order reported; empty when unknown
     */
    public List<Integer> lines() {
      return lines;
    }
  }
}
