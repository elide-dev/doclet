/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.render;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Renders with an external JavaScript runtime (Bun or Node): it starts {@code runner.mjs}, which
 * runs the classic {@code render.js} bundle over the model and writes the site. Input and output go
 * through files, so output size is not limited by a pipe. Nothing is downloaded: the runner, the
 * bundle and the assets come from the renderer directory.
 *
 * <p>The runtime is found in this order: an explicit path (the tools' {@code --js-runtime} option),
 * the {@value #ENV} environment variable, then {@code bun} and {@code node} on {@code PATH}.
 */
public final class ExternalRuntime implements RenderEngine {
  /** The environment variable naming the runtime when no option does. */
  public static final String ENV = "APIDOCS_JS_RUNTIME";

  private static final Pattern RESULT =
      Pattern.compile("\\{\"files\":(\\d+),\"bytes\":(\\d+),\"renderMs\":(\\d+)\\}");

  private final Path runtime;
  private final Path renderer;
  private final long timeoutSeconds;

  /**
   * A renderer run by {@code runtime}.
   *
   * @param runtime the Bun or Node executable
   * @param renderer the directory holding {@code runner.mjs}, {@code render.js} and {@code assets/}
   * @param timeoutSeconds how long a render may take
   */
  public ExternalRuntime(Path runtime, Path renderer, long timeoutSeconds) {
    this.runtime = runtime;
    this.renderer = renderer;
    this.timeoutSeconds = timeoutSeconds;
    for (var required : List.of("runner.mjs", "render.js", "assets")) {
      if (!Files.exists(renderer.resolve(required))) {
        throw new IllegalArgumentException(
            "The renderer directory " + renderer + " has no " + required);
      }
    }
  }

  /** The runtime this engine starts. */
  public Path runtime() {
    return runtime;
  }

  @Override
  public Result render(Path model, Path theme, Path out, String sourceRef) throws IOException {
    return render(model, theme, out, sourceRef, Outputs.ALL);
  }

  @Override
  public Result render(Path model, Path theme, Path out, String sourceRef, Outputs outputs)
      throws IOException {
    List<String> command =
        new ArrayList<>(
            List.of(
                runtime.toString(),
                renderer.resolve("runner.mjs").toString(),
                "--bundle",
                renderer.resolve("render.js").toString(),
                "--assets",
                renderer.resolve("assets").toString(),
                "--model",
                model.toString(),
                "--out",
                out.toString()));
    if (theme != null) {
      command.add("--theme");
      command.add(theme.toString());
    }
    if (sourceRef != null) {
      command.add("--source-ref");
      command.add(sourceRef);
    }
    if (!outputs.markdown()) command.add("--no-markdown");
    if (!outputs.llms()) command.add("--no-llms");
    if (!outputs.model()) command.add("--no-model");
    var stdout = Files.createTempFile("apidocs-runner", ".out");
    var stderr = Files.createTempFile("apidocs-runner", ".err");
    try {
      var process =
          new ProcessBuilder(command)
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      boolean finished;
      try {
        finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while rendering", e);
      }
      if (!finished) {
        process.destroyForcibly();
        throw new IOException(
            "The renderer took longer than " + timeoutSeconds + " s (" + runtime + ")");
      }
      var errors = Files.readString(stderr, StandardCharsets.UTF_8).strip();
      if (process.exitValue() != 0) {
        throw new IOException(
            "The renderer failed (" + runtime + ", exit " + process.exitValue() + "): " + errors);
      }
      var result = RESULT.matcher(Files.readString(stdout, StandardCharsets.UTF_8));
      if (!result.find()) {
        throw new IOException("The renderer printed no result (" + runtime + "): " + errors);
      }
      return new Result(
          Integer.parseInt(result.group(1)),
          Long.parseLong(result.group(2)),
          Long.parseLong(result.group(3)));
    } finally {
      Files.deleteIfExists(stdout);
      Files.deleteIfExists(stderr);
    }
  }

  /**
   * Finds the runtime: {@code explicit}, then {@value #ENV}, then {@code bun} and {@code node} on
   * {@code PATH}.
   *
   * @param explicit the {@code --js-runtime} option's value, or {@code null}
   * @param env the environment
   * @return the executable
   * @throws IllegalStateException when none is found, naming the option that fixes it
   */
  public static Path locate(String explicit, Map<String, String> env) {
    if (explicit != null && !explicit.isBlank()) {
      return executable(Path.of(explicit), "--js-runtime " + explicit);
    }
    var fromEnv = env.get(ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return executable(Path.of(fromEnv), ENV + "=" + fromEnv);
    }
    var path = env.getOrDefault("PATH", "");
    for (var name : List.of("bun", "node")) {
      for (var dir : path.split(Pattern.quote(File.pathSeparator))) {
        if (dir.isEmpty()) {
          continue;
        }
        for (var candidate : List.of(name, name + ".exe")) {
          var file = Path.of(dir, candidate);
          if (Files.isRegularFile(file) && Files.isExecutable(file)) {
            return file;
          }
        }
      }
    }
    throw new IllegalStateException(
        "No JavaScript runtime to render the API reference: pass --js-runtime <path to bun or"
            + " node>, set "
            + ENV
            + ", or put bun or node on PATH");
  }

  private static Path executable(Path file, String source) {
    if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
      throw new IllegalStateException(
          "The JavaScript runtime from " + source + " is not an executable file");
    }
    return file;
  }
}
