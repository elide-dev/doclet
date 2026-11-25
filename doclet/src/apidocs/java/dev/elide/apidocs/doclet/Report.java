/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import com.sun.source.util.DocTreePath;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Reporter;

/**
 * Diagnostics through javadoc's reporter, counting errors so the run fails when any was reported.
 *
 * <p>A <em>problem</em> is a comment the model cannot represent faithfully. Each belongs to a
 * {@link Problem} category, and {@link Severities} decides whether it is an error (the default: no
 * output) or a warning (the comment's part is left out or simplified, and the run goes on).
 */
final class Report {
  /** What kind of comment problem a diagnostic is; its name is the {@code --problems} key. */
  enum Problem {
    /** {@code {@inheritDoc}} with nothing to inherit. */
    INHERIT_DOC("inheritdoc"),
    /** A block tag the model has no place for. */
    BLOCK_TAG("block-tag"),
    /** An inline tag the model has no node for. */
    INLINE_TAG("inline-tag"),
    /** An external {@code {@snippet}}. */
    SNIPPET("snippet"),
    /** A reference that does not resolve, or to a declaration the model cannot link to. */
    REFERENCE("reference"),
    /** HTML the model cannot represent: an unknown entity, markup in an attribute. */
    HTML("html"),
    /** A URL with a scheme other than http, https or mailto. */
    URL("url"),
    /** A {@code {@value}} without a constant, or with a format. */
    VALUE("value"),
    /** A {@code @param} that names no parameter. */
    PARAM("param"),
    /** A comment javadoc could not parse. */
    MALFORMED("malformed"),
    /** Any other construct the model cannot represent. */
    UNSUPPORTED("unsupported");

    final String key;

    Problem(String key) {
      this.key = key;
    }

    static Problem of(String key) {
      for (var problem : values()) {
        if (problem.key.equals(key)) {
          return problem;
        }
      }
      throw new IllegalArgumentException(
          "Unknown problem category "
              + key
              + "; expected one of "
              + Arrays.stream(values()).map(p -> p.key).collect(Collectors.joining(", ")));
    }
  }

  /**
   * How each problem category is reported: {@code --problems} takes a comma-separated list of
   * {@code error} or {@code warning}, each optionally prefixed with {@code <category>=}. A bare
   * severity sets the default; later entries win.
   */
  record Severities(boolean warnByDefault, Set<Problem> warn, Set<Problem> error) {
    static final Severities STRICT = new Severities(false, Set.of(), Set.of());

    static Severities parse(String spec, Severities base) {
      boolean warnByDefault = base.warnByDefault();
      var warn = EnumSet.noneOf(Problem.class);
      var error = EnumSet.noneOf(Problem.class);
      warn.addAll(base.warn());
      error.addAll(base.error());
      for (var entry : spec.split(",")) {
        var item = entry.strip();
        if (item.isEmpty()) {
          continue;
        }
        int eq = item.indexOf('=');
        var warning = severity(eq < 0 ? item : item.substring(eq + 1).strip());
        if (eq < 0) {
          warnByDefault = warning;
          warn.clear();
          error.clear();
        } else {
          var problem = Problem.of(item.substring(0, eq).strip());
          (warning ? warn : error).add(problem);
          (warning ? error : warn).remove(problem);
        }
      }
      return new Severities(warnByDefault, warn, error);
    }

    private static boolean severity(String name) {
      return switch (name) {
        case "error" -> false;
        case "warning" -> true;
        default ->
            throw new IllegalArgumentException(
                "Unknown problem severity " + name + "; expected error or warning");
      };
    }

    boolean warns(Problem problem) {
      return warn.contains(problem) || (warnByDefault && !error.contains(problem));
    }
  }

  private final Reporter reporter;
  private final Severities severities;
  private int errors;
  private int downgraded;
  private final Set<Object> warned = new HashSet<>();

  Report(Reporter reporter) {
    this(reporter, Severities.STRICT);
  }

  Report(Reporter reporter, Severities severities) {
    this.reporter = reporter;
    this.severities = severities;
  }

  /** Reports a comment problem at {@code path} as {@link Severities} says. */
  void problem(Problem problem, DocTreePath path, String message) {
    var tagged = message + " [" + problem.key + "]";
    if (severities.warns(problem)) {
      // A comment's first sentence and body are converted separately; report each problem once,
      // as javac does for errors at the same position.
      if (path != null && !warned.add(List.of(path.getLeaf(), tagged))) {
        return;
      }
      downgraded++;
      warning(path, tagged);
    } else {
      error(path, tagged);
    }
  }

  /** Reports a comment problem on {@code element} as {@link Severities} says. */
  void problem(Problem problem, Element element, String message) {
    var tagged = message + " [" + problem.key + "]";
    if (severities.warns(problem)) {
      downgraded++;
      print(Diagnostic.Kind.WARNING, element, tagged);
    } else {
      error(element, tagged);
    }
  }

  /** How many problems were reported as warnings. */
  int downgraded() {
    return downgraded;
  }

  void error(Element element, String message) {
    errors++;
    print(Diagnostic.Kind.ERROR, element, message);
  }

  void error(DocTreePath path, String message) {
    errors++;
    if (path == null) {
      reporter.print(Diagnostic.Kind.ERROR, message);
    } else {
      reporter.print(Diagnostic.Kind.ERROR, path, message);
    }
  }

  void warning(DocTreePath path, String message) {
    if (path == null) {
      reporter.print(Diagnostic.Kind.WARNING, message);
    } else {
      reporter.print(Diagnostic.Kind.WARNING, path, message);
    }
  }

  void note(String message) {
    reporter.print(Diagnostic.Kind.NOTE, message);
  }

  private void print(Diagnostic.Kind kind, Element element, String message) {
    if (element == null) {
      reporter.print(kind, message);
    } else {
      reporter.print(kind, element, message);
    }
  }

  boolean ok() {
    return errors == 0;
  }

  int errors() {
    return errors;
  }
}
