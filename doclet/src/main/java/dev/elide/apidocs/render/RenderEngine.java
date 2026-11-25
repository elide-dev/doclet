/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.render;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the {@code @elide/apidocs} renderer over a model and writes the site. The doclet and the
 * Dokka plugin use only this interface, so how the renderer runs can change without touching them.
 */
public interface RenderEngine {
  /**
   * Renders {@code model} with {@code theme} into {@code out}.
   *
   * @param model the {@code apidocs/1} model file ({@code api.json})
   * @param theme the theme file, or {@code null} for the neutral default
   * @param out the output directory; created when missing
   * @param sourceRef the value of {@code {ref}} in the theme's source-link template, or {@code
   *     null} for the theme's own
   * @return what was written
   * @throws IOException if the renderer fails or the files cannot be written
   */
  Result render(Path model, Path theme, Path out, String sourceRef) throws IOException;

  /**
   * Renders {@code model} with {@code theme} into {@code out}, writing only the optional files that
   * {@code outputs} names.
   *
   * @param model the {@code apidocs/1} model file ({@code api.json})
   * @param theme the theme file, or {@code null} for the neutral default
   * @param out the output directory; created when missing
   * @param sourceRef the value of {@code {ref}} in the theme's source-link template, or {@code
   *     null} for the theme's own
   * @param outputs which optional files to write
   * @return what was written
   * @throws IOException if the renderer fails or the files cannot be written
   */
  default Result render(Path model, Path theme, Path out, String sourceRef, Outputs outputs)
      throws IOException {
    if (!outputs.equals(Outputs.ALL)) {
      throw new UnsupportedOperationException("This renderer writes every optional file");
    }
    return render(model, theme, out, sourceRef);
  }

  /**
   * The optional files of a site, besides its HTML pages and their assets. Pages link only to the
   * ones the site has.
   *
   * @param markdown a Markdown twin of every page
   * @param llms {@code llms.txt} and {@code llms-full.txt}
   * @param model {@code api.json}, the model
   */
  record Outputs(boolean markdown, boolean llms, boolean model) {
    /** Every optional file: the default. */
    public static final Outputs ALL = new Outputs(true, true, true);
  }

  /**
   * What a render wrote.
   *
   * @param files the number of files
   * @param bytes the bytes of text written (copied assets are not counted)
   * @param renderMillis the time {@code render()} took inside the runtime, without start-up or
   *     writing
   */
  record Result(int files, long bytes, long renderMillis) {}
}
