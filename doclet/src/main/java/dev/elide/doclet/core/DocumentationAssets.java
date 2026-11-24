package dev.elide.doclet.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Static assets shared by documentation generators. */
public final class DocumentationAssets {
  private DocumentationAssets() {}

  /** Writes the bundled stylesheet into an existing directory and returns its path. */
  public static Path writeStylesheet(Path directory) throws IOException {
    Path stylesheet = directory.resolve("elide.css");
    try (var css = DocumentationAssets.class.getResourceAsStream("/dev/elide/doclet/elide.css")) {
      if (css == null) {
        throw new IOException("Missing documentation resource elide.css");
      }
      Files.copy(css, stylesheet, StandardCopyOption.REPLACE_EXISTING);
    }
    return stylesheet;
  }
}
