package dev.elide.apidocs.doclet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.elide.apidocs.model.Api;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalLinksTest {
  @Test
  void modularElementList() {
    assertEquals(
        List.of(
            new Api.External("java.io.", "https://docs/api/java.base/"),
            new Api.External("java.lang.", "https://docs/api/java.base/"),
            new Api.External("java.util.logging.", "https://docs/api/java.logging/")),
        ExternalLinks.parse(
            "https://docs/api/",
            "module:java.base\njava.io\njava.lang\n\nmodule:java.logging\r\njava.util.logging\n"));
  }

  @Test
  void packageList() {
    assertEquals(
        List.of(
            new Api.External("org.example.", "../lib/"),
            new Api.External("org.example.util.", "../lib/")),
        ExternalLinks.parse("../lib/", "org.example\norg.example.util\nunnamed package\n"));
  }

  @Test
  void modulePrefixedPackageList() {
    assertEquals(
        List.of(new Api.External("java.awt.", "http://docs/api/java.desktop/")),
        ExternalLinks.parse("http://docs/api/", "java.desktop/java.awt\n"));
  }
}
