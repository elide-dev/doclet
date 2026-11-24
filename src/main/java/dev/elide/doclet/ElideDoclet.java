package dev.elide.doclet;

import java.io.IOException;
import java.io.InputStream;
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
    try (InputStream css = ElideDoclet.class.getResourceAsStream("elide.css")) {
      if (css == null) {
        throw new IOException("Missing doclet resource elide.css");
      }
      Path directory = Files.createTempDirectory("elide-doclet-");
      Path stylesheet = directory.resolve("elide.css");
      try {
        Files.copy(css, stylesheet);
        var option =
            getSupportedOptions().stream()
                .filter(item -> item.getNames().contains("--add-stylesheet"))
                .findFirst()
                .orElseThrow();
        return option.process("--add-stylesheet", List.of(stylesheet.toString()))
            && super.run(environment);
      } finally {
        Files.deleteIfExists(stylesheet);
        Files.delete(directory);
      }
    } catch (IOException exception) {
      reporter.print(Diagnostic.Kind.ERROR, exception.toString());
      return false;
    }
  }
}
