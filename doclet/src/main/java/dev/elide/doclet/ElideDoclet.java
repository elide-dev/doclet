package dev.elide.doclet;

import dev.elide.doclet.core.DocumentationAssets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import jdk.javadoc.doclet.StandardDoclet;

/** Generates standard API documentation with the stylesheet bundled in this JAR. */
public final class ElideDoclet extends StandardDoclet {
  private Reporter reporter;

  @Override
  public void init(Locale locale, Reporter reporter) {
    super.init(locale, reporter);
    this.reporter = reporter;
  }

  @Override
  public boolean run(DocletEnvironment environment) {
    try {
      Path directory = Files.createTempDirectory("elide-doclet-");
      try {
        Path stylesheet = DocumentationAssets.writeStylesheet(directory);
        var option =
            getSupportedOptions().stream()
                .filter(item -> item.getNames().contains("--add-stylesheet"))
                .findFirst()
                .orElseThrow();
        return option.process("--add-stylesheet", List.of(stylesheet.toString()))
            && super.run(environment);
      } finally {
        Files.deleteIfExists(directory.resolve("elide.css"));
        Files.delete(directory);
      }
    } catch (IOException exception) {
      reporter.print(Diagnostic.Kind.ERROR, exception.toString());
      return false;
    }
  }
}
