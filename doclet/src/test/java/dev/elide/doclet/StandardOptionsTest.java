package dev.elide.doclet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardOptionsTest {
  private static final String ELEMENT_LIST =
      "module:java.base\njava.lang\njava.util\nmodule:java.logging\njava.util.logging\n";

  private static final Map<String, String> SOURCES =
      Map.of(
          "shelf/Shelf.java",
          """
          package shelf;

          /**
           * A shelf, logged with {@link java.util.logging.Logger}; see {@link java.util}.
           *
           * @todo Sort the books.
           * @internal Not shown.
           */
          public interface Shelf {
            /**
             * The shelf's name.
             *
             * @return the name
             */
            String name();
          }
          """);

  @Test
  void linkOfflineLinksExternalTypes(@TempDir Path directory) throws Exception {
    var lists = Files.createDirectories(directory.resolve("jdk"));
    Files.writeString(lists.resolve("element-list"), ELEMENT_LIST);
    var result =
        Javadoc.run(
            directory,
            SOURCES,
            List.of(
                "-linkoffline",
                "https://docs.example/api/",
                lists.toString(),
                "-tag",
                "todo:a:To do:",
                "-tag",
                "internal:X"));
    assertTrue(result.ok(), result::toString);
    var model = result.model();
    assertTrue(
        model.contains("\"url\":\"https://docs.example/api/java.base/java/lang/String.html\""),
        model);
    assertTrue(
        model.contains(
            "\"url\":\"https://docs.example/api/java.logging/java/util/logging/Logger.html\""),
        model);
    assertTrue(
        model.contains(
            "\"url\":\"https://docs.example/api/java.base/java/util/package-summary.html\""),
        model);
    assertTrue(model.contains("{\"prefix\":\"java.lang.\""), model);
    var page = result.page("shelf/Shelf.html");
    assertTrue(
        page.contains("https://docs.example/api/java.base/java/lang/String.html"), "String link");
    assertTrue(model.contains("\"text\":\"To do:\""), model);
    assertTrue(model.contains("Sort the books."), model);
    assertFalse(model.contains("Not shown."), model);
  }

  @Test
  void linkReadsTheRemoteElementList(@TempDir Path directory) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/element-list",
        exchange -> {
          var body = ELEMENT_LIST.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
      var result =
          Javadoc.run(
              directory,
              SOURCES,
              List.of("-link", url, "-tag", "todo:a:To do:", "-tag", "internal:X"));
      assertTrue(result.ok(), result::toString);
      assertTrue(result.model().contains("\"url\":\"" + url + "java.base/java/lang/String.html\""));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unreadableLinkIsAWarning(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            SOURCES,
            List.of(
                "-linkoffline",
                "https://docs.example/api/",
                directory.resolve("missing").toString(),
                "-tag",
                "todo:a:To do:",
                "-tag",
                "internal:X"));
    assertTrue(result.ok(), result::toString);
    assertTrue(
        result.warnings().stream().anyMatch(w -> w.contains("Cannot read the package list")),
        result::toString);
  }

  @Test
  void customTagsWithoutTagOptionAreProblems(@TempDir Path directory) throws Exception {
    var result = Javadoc.run(directory, SOURCES, List.of());
    assertFalse(result.ok());
    assertEquals(2, result.errors().size(), result::toString);
  }

  @Test
  void standardDocletOptionsAreAcceptedWithAWarning(@TempDir Path directory) throws Exception {
    var result =
        Javadoc.run(
            directory,
            SOURCES,
            List.of(
                "-header",
                "<b>SDK</b>",
                "-bottom",
                "Copyright",
                "-use",
                "-author",
                "-version",
                "-splitindex",
                "-nonavbar",
                "-nodeprecatedlist",
                "-linksource",
                "-stylesheetfile",
                "style.css",
                "-group",
                "Core",
                "shelf*",
                "-tag",
                "todo:a:To do:",
                "-tag",
                "internal:X",
                "-tag",
                "param"));
    assertTrue(result.ok(), result::toString);
    assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("-header ")));
    assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("-group ")));
    assertEquals(11, result.warnings().size(), result::toString);
  }
}
