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
    var source =
        Files.writeString(
            directory.resolve("Book.java"), "/** Catalogue entry. */ public interface Book {}");
    var docs = directory.resolve("docs");
    var tool = ToolProvider.getSystemDocumentationTool();
    try (var files = tool.getStandardFileManager(null, null, null)) {
      var task =
          tool.getTask(
              null,
              files,
              null,
              ElideDoclet.class,
              List.of("-quiet", "-d", docs.toString()),
              files.getJavaFileObjects(source));
      assertTrue(task.call());
    }
    var page = Files.readString(docs.resolve("Book.html"));
    assertTrue(page.contains("Catalogue entry."));
    assertTrue(page.contains("elide.css"));
  }
}
