/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.render;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The renderer the tools carry as resources under {@value #PREFIX}: {@code runner.mjs}, {@code
 * render.js} and {@code assets/}. A JavaScript runtime needs them as files, so they are copied out
 * to a directory first.
 */
public final class RendererResources {
  /** Where the renderer lives on the class path. */
  public static final String PREFIX = "dev/elide/apidocs/renderer/";

  private RendererResources() {}

  /**
   * Copies the renderer out of the class path that loaded {@code anchor} (a jar or a directory).
   *
   * @param anchor a class from the jar that carries the renderer
   * @param target an empty directory to copy into
   * @return {@code target}, now holding {@code runner.mjs}, {@code render.js} and {@code assets/}
   * @throws IllegalStateException when the class path carries no renderer
   */
  public static Path extract(Class<?> anchor, Path target) throws IOException {
    URL marker = anchor.getClassLoader().getResource(PREFIX + "render.js");
    if (marker == null) {
      throw new IllegalStateException(
          "This build carries no API reference renderer ("
              + PREFIX
              + "render.js is missing); build "
              + "@elide/apidocs (bun run build in packages/apidocs) before building the tool");
    }
    try {
      switch (marker.getProtocol()) {
        case "jar" -> {
          var spec = marker.getPath();
          var jar = Path.of(java.net.URI.create(spec.substring(0, spec.indexOf("!/"))));
          try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
              var entry = entries.nextElement();
              if (entry.isDirectory() || !entry.getName().startsWith(PREFIX)) {
                continue;
              }
              var file = inside(target, entry.getName().substring(PREFIX.length()));
              Files.createDirectories(file.getParent());
              try (var in = zip.getInputStream(entry)) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
              }
            }
          }
        }
        case "file" -> {
          var root = Path.of(marker.toURI()).getParent();
          try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .forEach(source -> {
                  try {
                    var file = inside(target, root.relativize(source).toString());
                    Files.createDirectories(file.getParent());
                    Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
          }
        }
        default -> throw new IllegalStateException("Cannot read the renderer from " + marker);
      }
    } catch (URISyntaxException e) {
      throw new IOException("Cannot locate the renderer at " + marker, e);
    }
    return target;
  }

  private static Path inside(Path root, String relative) throws IOException {
    var file = root.resolve(relative).normalize();
    if (!file.startsWith(root)) {
      throw new IOException("Renderer resource outside its directory: " + relative);
    }
    return file;
  }
}
