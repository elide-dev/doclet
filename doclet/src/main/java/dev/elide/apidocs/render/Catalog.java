package dev.elide.apidocs.render;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds a versioned reference or serves an existing site with Markdown negotiation. */
public final class Catalog {
  private Catalog() {}

  public static void main(String[] args) throws Exception {
    var script = Files.createTempFile("elide-docs-catalog-", ".mjs");
    try {
      try (var input =
          Objects.requireNonNull(
              Catalog.class.getResourceAsStream("/dev/elide/apidocs/catalog/catalog.mjs"))) {
        Files.copy(input, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      var command =
          new ArrayList<>(
              List.of(ExternalRuntime.locate(null, System.getenv()).toString(), script.toString()));
      command.addAll(List.of(args));
      var process = new ProcessBuilder(command).inheritIO().start();
      var hook = new Thread(process::destroy);
      Runtime.getRuntime().addShutdownHook(hook);
      try {
        if (process.waitFor() != 0) throw new IllegalStateException("Catalog command failed");
      } finally {
        process.destroy();
        Runtime.getRuntime().removeShutdownHook(hook);
      }
    } finally {
      Files.deleteIfExists(script);
    }
  }
}
