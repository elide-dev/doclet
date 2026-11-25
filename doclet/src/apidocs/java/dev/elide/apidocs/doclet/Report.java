/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import com.sun.source.util.DocTreePath;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Reporter;

/**
 * Diagnostics through javadoc's reporter, counting errors so the run fails when any was reported.
 */
final class Report {
  private final Reporter reporter;
  private int errors;

  Report(Reporter reporter) {
    this.reporter = reporter;
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
