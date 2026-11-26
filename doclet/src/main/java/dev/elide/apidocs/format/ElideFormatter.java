/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.format;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The source formatters through {@code elide format}, which picks the formatter by the file's
 * extension and passes the arguments after {@code --} to it: google-java-format in its AOSP style
 * for Java ({@code elide format -q -- --aosp --skip-reflowing-long-strings Stub.java}: 4-space
 * indentation), and ktfmt in its Google style for Kotlin ({@code elide format -q -- --google-style
 * Stub.kt}). Both wrap at 100 columns, their fixed limit; neither takes a width option. Long string
 * literals are never reflowed, so a literal keeps its text.
 */
public final class ElideFormatter implements SourceFormatter {
  /** The arguments after the command for Java; the file's path follows them. */
  public static final List<String> JAVA_ARGUMENTS =
      List.of("format", "-q", "--", "--aosp", "--skip-reflowing-long-strings");

  /** The arguments after the command for Kotlin; the file's path follows them. */
  public static final List<String> KOTLIN_ARGUMENTS =
      List.of("format", "-q", "--", "--google-style");

  /** What {@link #locate} looks for on {@code PATH} when no command is given. */
  public static final String DEFAULT_COMMAND = "elide";

  private static final Pattern ERROR_LINE = Pattern.compile(":(\\d+):\\d+: error");
  private static final long TIMEOUT_SECONDS = 600;

  private final List<String> command;

  /**
   * A formatter that runs {@code command} followed by the language's arguments.
   *
   * @param command the Elide executable and any arguments before the subcommand
   */
  public ElideFormatter(List<String> command) {
    if (command.isEmpty()) {
      throw new IllegalArgumentException("The formatter command is empty");
    }
    this.command = List.copyOf(command);
  }

  /**
   * Finds Elide: {@code executable} when given (a path, or a name looked up on {@code PATH}), else
   * {@value #DEFAULT_COMMAND} on {@code PATH}.
   *
   * @param executable the Elide executable, or {@code null} or empty for the default
   * @param env the environment whose {@code PATH} is searched
   * @return the formatter, or {@code null} when no executable is found
   */
  public static ElideFormatter locate(String executable, Map<String, String> env) {
    var name = executable == null || executable.isBlank() ? DEFAULT_COMMAND : executable;
    if (name.contains("/") || name.contains(File.separator)) {
      var path = Path.of(name);
      return Files.isRegularFile(path) && Files.isExecutable(path)
          ? new ElideFormatter(List.of(path.toString()))
          : null;
    }
    var path = env.get("PATH");
    if (path == null) {
      return null;
    }
    for (var dir : path.split(File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      var candidate = Path.of(dir, name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return new ElideFormatter(List.of(candidate.toString()));
      }
    }
    return null;
  }

  @Override
  public String describe() {
    return String.join(" ", command);
  }

  @Override
  public String format(Language language, String source) throws FormatterException {
    Path work = null;
    try {
      work = Files.createTempDirectory("apidocs-format");
      var out = work.resolve("out.txt");
      var err = work.resolve("err.txt");
      List<String> argv = new ArrayList<>(command);
      var builder =
          new ProcessBuilder(argv).redirectOutput(out.toFile()).redirectError(err.toFile());
      Path file;
      // elide format rewrites the file in place.
      argv.addAll(language == Language.JAVA ? JAVA_ARGUMENTS : KOTLIN_ARGUMENTS);
      file =
          Files.writeString(
              work.resolve(language == Language.JAVA ? "Stub.java" : "Stub.kt"),
              source,
              StandardCharsets.UTF_8);
      argv.add(file.toString());
      builder.command(argv);
      var process = builder.start();
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new FormatterException(
            describe() + " did not finish in " + TIMEOUT_SECONDS + " s", List.of());
      }
      var errors = Files.readString(err, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        List<Integer> lines = new ArrayList<>();
        var matcher = ERROR_LINE.matcher(errors);
        while (matcher.find()) {
          lines.add(Integer.parseInt(matcher.group(1)));
        }
        throw new FormatterException(
            describe()
                + " "
                + argv.get(command.size())
                + " failed (exit "
                + process.exitValue()
                + "): "
                + firstLines(errors),
            lines);
      }
      var formatted = Files.readString(file, StandardCharsets.UTF_8);
      if (formatted.isBlank() && !source.isBlank()) {
        throw new FormatterException(
            describe() + " wrote nothing: " + firstLines(errors), List.of());
      }
      return formatted;
    } catch (IOException e) {
      throw new FormatterException("Cannot run " + describe() + ": " + e.getMessage(), List.of());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FormatterException("Interrupted while running " + describe(), List.of());
    } finally {
      if (work != null) {
        delete(work);
      }
    }
  }

  private static String firstLines(String text) {
    var lines = text.strip().lines().limit(4).toList();
    return lines.isEmpty() ? "(no output)" : String.join(" | ", lines);
  }

  private static void delete(Path directory) {
    try (var walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException e) {
      // A temporary directory left behind is harmless.
    }
  }
}
