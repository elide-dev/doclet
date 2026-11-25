package dev.elide.doclet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElideDocletTest {
  @Test
  void generatesStyledApiPage(@TempDir Path directory) throws Exception {
    var docs = generate(directory, List.of());
    var page = Files.readString(docs.resolve("default-package/Book.html"));
    assertTrue(page.contains("Catalogue entry."));
    assertTrue(page.contains("assets/apidocs.css"));
    assertTrue(
        Files.readString(docs.resolve("default-package/Book.md")).contains("Catalogue entry."));
  }

  @Test
  void rendersBrandedSite(@TempDir Path directory) throws Exception {
    var theme =
        Files.writeString(
            directory.resolve("theme.json"),
            """
            {"productName":"Catalogue SDK","tokens":{"light":{"accent":"#315723"}}}
            """);
    var docs =
        generate(directory, List.of("--module-name", "catalogue", "--theme", theme.toString()));
    assertTrue(Files.readString(docs.resolve("index.html")).contains("Catalogue SDK"));
    assertTrue(Files.readString(docs.resolve("assets/theme.css")).contains("#315723"));
    var model = Files.readString(docs.resolve("api.json"));
    assertTrue(model.contains("\"schema\":\"apidocs/1\""));
    assertTrue(model.contains("\"name\":\"catalogue\""));
    assertTrue(model.contains("Catalogue entry."));
  }

  private Path generate(Path directory, List<String> extraOptions) throws Exception {
    var source =
        Files.writeString(
            directory.resolve("Book.java"), "/** Catalogue entry. */ public interface Book {}");
    var docs = directory.resolve("docs");
    var options = new java.util.ArrayList<>(List.of("-quiet", "-d", docs.toString()));
    options.addAll(extraOptions);
    var tool = ToolProvider.getSystemDocumentationTool();
    try (var files = tool.getStandardFileManager(null, null, null)) {
      var task =
          tool.getTask(
              null, files, null, ElideDoclet.class, options, files.getJavaFileObjects(source));
      assertTrue(task.call());
    }
    return docs;
  }
}
