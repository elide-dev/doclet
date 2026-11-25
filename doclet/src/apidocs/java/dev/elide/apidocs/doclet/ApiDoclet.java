package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.render.DocumentationSite;
import dev.elide.apidocs.render.RenderEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/** Extracts the public Java API and renders its documentation site. */
public class ApiDoclet implements Doclet {
  private Path output = Path.of(".");
  private Path theme;
  private String runtime;
  private String sourceRef;
  private boolean writeMarkdown = true;
  private boolean writeLlms = true;
  private boolean writeModel = true;

  @Override
  public String getName() {
    return "Elide API Docs";
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latest();
  }

  private Reporter reporter;
  private String moduleName;
  private String moduleVersion;
  private String title;

  @Override
  public void init(Locale locale, Reporter reporter) {
    this.reporter = reporter;
  }

  @Override
  public Set<? extends Option> getSupportedOptions() {
    var options = new HashSet<Option>();
    options.add(
        new DocletOption("-notimestamp", "Generate timestamp-free documentation", 0, value -> {}));
    options.add(new DocletOption("-windowtitle", "Default library title", value -> title = value));
    options.add(new DocletOption("-doctitle", "Default library title", value -> title = value));
    // Elide's Javadoc launcher passes this option to suppress the JDK stylesheet notices.
    options.add(
        new DocletOption(
            "--legal-notices",
            "JDK stylesheet notices (none)",
            value -> {
              if (!value.equals("none"))
                throw new IllegalArgumentException("Only none is supported");
            }));
    options.add(
        new DocletOption("-d", "Documentation output directory", value -> output = Path.of(value)));
    options.add(
        new DocletOption("--theme", "Renderer theme JSON file", value -> theme = Path.of(value)));
    options.add(new DocletOption("--js-runtime", "Path to Node or Bun", value -> runtime = value));
    options.add(
        new DocletOption("--source-ref", "Revision for source links", value -> sourceRef = value));
    options.add(
        new DocletOption(
            "--no-markdown", "Omit the pages' Markdown twins", 0, v -> writeMarkdown = false));
    options.add(
        new DocletOption(
            "--no-llms", "Omit llms.txt and llms-full.txt", 0, v -> writeLlms = false));
    options.add(new DocletOption("--no-model", "Omit api.json", 0, v -> writeModel = false));
    options.add(
        new DocletOption(
            "--module-name", "Library name for api.json", value -> moduleName = value));
    options.add(
        new DocletOption(
            "--module-version", "Library version for api.json", value -> moduleVersion = value));
    return options;
  }

  @Override
  public boolean run(DocletEnvironment environment) {
    if (moduleName == null) {
      moduleName =
          ElementFilter.modulesIn(environment.getIncludedElements()).stream()
              .filter(module -> !module.isUnnamed())
              .map(module -> module.getQualifiedName().toString())
              .findFirst()
              .orElse(title == null ? "API Reference" : title);
    }
    var report = new Report(reporter);
    var linker = new Linker(environment.getElementUtils(), environment.getTypeUtils(), List.of());
    Api.Model model =
        new Extractor(environment, linker, report).extract(moduleName, moduleVersion, List.of());
    if (!report.ok()) return false;
    try {
      DocumentationSite.write(
          model,
          output,
          theme,
          runtime,
          sourceRef,
          new RenderEngine.Outputs(writeMarkdown, writeLlms, writeModel));
      return true;
    } catch (IOException | IllegalStateException e) {
      reporter.print(
          Diagnostic.Kind.ERROR, "Cannot generate the documentation site: " + e.getMessage());
      return false;
    }
  }

  private record DocletOption(String name, String description, int count, Consumer<String> accept)
      implements Option {
    DocletOption(String name, String description, Consumer<String> accept) {
      this(name, description, 1, accept);
    }

    public int getArgumentCount() {
      return count;
    }

    public String getDescription() {
      return description;
    }

    public Kind getKind() {
      return Kind.STANDARD;
    }

    public List<String> getNames() {
      return List.of(name);
    }

    public String getParameters() {
      return count == 0 ? "" : "<value>";
    }

    public boolean process(String option, List<String> arguments) {
      if (count == 0) {
        accept.accept("");
        return true;
      }
      if (arguments.getFirst().isBlank()) return false;
      try {
        accept.accept(arguments.getFirst());
        return true;
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
  }
}
