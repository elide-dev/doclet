/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import dev.elide.apidocs.model.Api;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out declaration signatures as the source formatter would: line breaks and indentation
 * written into their punctuation tokens, every other character unchanged. A signature the formatter
 * keeps on one line stays on one line.
 *
 * <p>All of a batch's declarations go into one synthetic source file ({@link Stub}), which the
 * formatter lays out in one run; each declaration's lines are then read back and mapped onto its
 * tokens ({@link Alignment}). A declaration keeps its one-line form, with a warning that names it,
 * when the formatter changes anything but whitespace in it, when its markers are lost, or when the
 * formatter fails on it (a failing run is retried without the declaration its error names, or split
 * in halves, so one bad declaration does not cost the rest). With no formatter, every signature is
 * returned unchanged and that is reported once, as information.
 *
 * <pre>{@code
 * var layout = SignatureLayout.elide(null, log);   // elide on PATH
 * List<List<Api.Token>> signatures = layout.layout(Language.KOTLIN, List.of(
 *         Declaration.member(id, Api.MemberKind.FUNCTION, Api.TypeKind.OBJECT, tokens)));
 * }</pre>
 */
public final class SignatureLayout {
  /** Where messages go. */
  public interface Log {
    /**
     * Information, such as a summary or that no formatter is available.
     *
     * @param message the message
     */
    void info(String message);

    /**
     * A declaration that keeps its one-line form, and why.
     *
     * @param message the message
     */
    void warning(String message);
  }

  /**
   * What the layout did so far, over every call.
   *
   * @param declarations the declarations passed to the formatter
   * @param multiline those now laid out over several lines
   * @param fallbacks those that kept their one-line form after a problem
   * @param formatterRuns how many times the formatter ran
   * @param millis the time spent, in milliseconds
   */
  public record Stats(
      int declarations, int multiline, int fallbacks, int formatterRuns, long millis) {}

  private final SourceFormatter formatter;
  private final Log log;
  private boolean reportedMissing;
  private int declarations;
  private int multiline;
  private int fallbacks;
  private int runs;
  private long nanos;

  /**
   * A layout that uses {@code formatter}.
   *
   * @param formatter the formatter, or {@code null} to return every signature unchanged
   * @param log where information and warnings go
   */
  public SignatureLayout(SourceFormatter formatter, Log log) {
    this.formatter = formatter;
    this.log = log;
  }

  /**
   * A layout through Elide's formatters ({@link ElideFormatter}).
   *
   * @param command the Elide executable (a path, or a name on {@code PATH}); {@code null} or empty
   *     for {@code elide} on {@code PATH}; {@code none} turns layout off
   * @param log where information and warnings go
   * @return the layout; without its formatter when Elide is not found
   */
  public static SignatureLayout elide(String command, Log log) {
    if ("none".equals(command)) {
      return new SignatureLayout(null, log);
    }
    var formatter = ElideFormatter.locate(command, System.getenv());
    var layout = new SignatureLayout(formatter, log);
    if (formatter == null) {
      layout.reportedMissing = true;
      log.info(
          "Signatures stay on one line: no source formatter ("
              + (command == null || command.isBlank()
                  ? ElideFormatter.DEFAULT_COMMAND + " on PATH"
                  : command)
              + ") was found");
    }
    return layout;
  }

  /**
   * Lays out each declaration's signature.
   *
   * @param language the declarations' language
   * @param batch the declarations
   * @return each declaration's signature, laid out, in the same order; a signature that could not
   *     be laid out is returned as given
   */
  public List<List<Api.Token>> layout(Language language, List<Declaration> batch) {
    List<List<Api.Token>> result = new ArrayList<>();
    for (var declaration : batch) {
      result.add(declaration.tokens());
    }
    if (formatter == null) {
      if (!reportedMissing) {
        reportedMissing = true;
        log.info("Signatures stay on one line: no source formatter is configured");
      }
      return result;
    }
    long start = System.nanoTime();
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      var declaration = batch.get(i);
      if (declaration.tokens().isEmpty()
          || (language == Language.JAVA && declaration.kind() == Declaration.Kind.TYPE_ALIAS)) {
        continue;
      }
      indices.add(i);
    }
    declarations += indices.size();
    var blocks = format(language, batch, indices);
    for (int i : indices) {
      var declaration = batch.get(i);
      var block = blocks.get(i);
      if (block == null) {
        continue;
      }
      try {
        var laidOut =
            Alignment.apply(
                declaration.tokens(),
                block,
                Stub.body(language, declaration),
                language == Language.KOTLIN);
        result.set(i, laidOut);
        if (Alignment.multiline(laidOut)) {
          multiline++;
        }
      } catch (Alignment.Mismatch e) {
        fallback(declaration, e.getMessage());
      }
    }
    nanos += System.nanoTime() - start;
    return result;
  }

  /** What the layout did so far. */
  public Stats stats() {
    return new Stats(declarations, multiline, fallbacks, runs, nanos / 1_000_000);
  }

  /** Whether a formatter is in use. */
  public boolean enabled() {
    return formatter != null;
  }

  /**
   * Formats the declarations at {@code indices}: their laid-out text by index, without those that
   * failed.
   */
  private Map<Integer, String> format(
      Language language, List<Declaration> batch, List<Integer> indices) {
    Map<Integer, String> blocks = new HashMap<>();
    if (indices.isEmpty()) {
      return blocks;
    }
    var stub = Stub.build(language, batch, indices);
    String formatted;
    try {
      runs++;
      formatted = formatter.format(language, stub.source());
    } catch (SourceFormatter.FormatterException e) {
      if (indices.size() == 1) {
        fallback(batch.get(indices.get(0)), e.getMessage());
        return blocks;
      }
      for (int line : e.lines()) {
        var culprit = stub.declarationAt(line);
        if (culprit != null) {
          fallback(batch.get(culprit), e.getMessage());
          List<Integer> rest = new ArrayList<>(indices);
          rest.remove(culprit);
          return format(language, batch, rest);
        }
      }
      int half = indices.size() / 2;
      blocks.putAll(format(language, batch, indices.subList(0, half)));
      blocks.putAll(format(language, batch, indices.subList(half, indices.size())));
      return blocks;
    }
    var read = Stub.read(formatted);
    for (int i : indices) {
      var block = read.get(i);
      if (block == null) {
        fallback(batch.get(i), "its markers are missing from the formatter's output");
      } else {
        blocks.put(i, block);
      }
    }
    return blocks;
  }

  private void fallback(Declaration declaration, String why) {
    fallbacks++;
    log.warning("Signature of " + declaration.label() + " kept on one line: " + why);
  }
}
