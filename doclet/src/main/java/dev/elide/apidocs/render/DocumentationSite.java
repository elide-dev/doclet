package dev.elide.apidocs.render;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Generates a portable HTML and Markdown site from the shared API model. */
public final class DocumentationSite {
  private DocumentationSite() {}

  public static void write(
      Api.Model model, Path output, Path theme, String runtime, String sourceRef)
      throws IOException {
    var executable = ExternalRuntime.locate(runtime, System.getenv());
    var work = Files.createTempDirectory("elide-apidocs-");
    try {
      var renderer =
          RendererResources.extract(
              DocumentationSite.class, Files.createDirectory(work.resolve("renderer")));
      var input = Files.writeString(work.resolve("api.json"), Json.write(model, false));
      new ExternalRuntime(executable, renderer, 1800).render(input, theme, output, sourceRef);
    } finally {
      try (var files = Files.walk(work)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }
}
