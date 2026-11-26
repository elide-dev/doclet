package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.render.DocumentationSite;
import dev.elide.apidocs.render.RenderEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private Report.Severities severities = Report.Severities.STRICT;

  /** {@code -link} and {@code -linkoffline}: each site's URL and where its package list is. */
  private final List<String[]> links = new ArrayList<>();

  private final Map<String, Comments.CustomTag> customTags = new LinkedHashMap<>();

  /** Standard doclet options accepted without effect, in the order given. */
  private final Set<String> ignored = new LinkedHashSet<>();

  /**
   * Standard doclet options without an equivalent here, by argument count: accepted, so an existing
   * javadoc configuration runs unchanged, with a warning that they have no effect.
   */
  private static final Map<String, Integer> IGNORED =
      Map.ofEntries(
          Map.entry("-use", 0),
          Map.entry("-author", 0),
          Map.entry("-version", 0),
          Map.entry("-splitindex", 0),
          Map.entry("-nonavbar", 0),
          Map.entry("-nodeprecated", 0),
          Map.entry("-nodeprecatedlist", 0),
          Map.entry("-nohelp", 0),
          Map.entry("-noindex", 0),
          Map.entry("-nosince", 0),
          Map.entry("-notree", 0),
          Map.entry("-nocomment", 0),
          Map.entry("-linksource", 0),
          Map.entry("-keywords", 0),
          Map.entry("-serialwarn", 0),
          Map.entry("-docfilessubdirs", 0),
          Map.entry("-html5", 0),
          Map.entry("--allow-script-in-comments", 0),
          Map.entry("--no-platform-links", 0),
          Map.entry("--no-frames", 0),
          Map.entry("-header", 1),
          Map.entry("-footer", 1),
          Map.entry("-top", 1),
          Map.entry("-bottom", 1),
          Map.entry("-overview", 1),
          Map.entry("-stylesheetfile", 1),
          Map.entry("--main-stylesheet", 1),
          Map.entry("--add-stylesheet", 1),
          Map.entry("--add-script", 1),
          Map.entry("-charset", 1),
          Map.entry("-docencoding", 1),
          Map.entry("-helpfile", 1),
          Map.entry("-sourcetab", 1),
          Map.entry("-noqualifier", 1),
          Map.entry("-excludedocfilessubdir", 1),
          Map.entry("-Xdocrootparent", 1),
          Map.entry("--override-methods", 1),
          Map.entry("--since", 1),
          Map.entry("--since-label", 1),
          Map.entry("--link-modularity-mismatch", 1),
          Map.entry("--link-platform-properties", 1),
          Map.entry("-taglet", 1),
          Map.entry("-tagletpath", 1),
          Map.entry("-group", 2));

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
    options.add(
        new DocletOption(
            "--problems",
            "How to report comments the model cannot represent: error (default) or warning,"
                + " optionally per category, e.g. warning or error,inheritdoc=warning",
            value -> severities = Report.Severities.parse(value, severities)));
    options.add(
        new ArgsOption(
            "-link",
            "Link to another Javadoc site, reading its element-list",
            1,
            "<url>",
            args -> links.add(new String[] {args.get(0), args.get(0)})));
    options.add(
        new ArgsOption(
            "-linkoffline",
            "Link to another Javadoc site, reading its element-list from a local directory or URL",
            2,
            "<url> <element-list location>",
            args -> links.add(new String[] {args.get(0), args.get(1)})));
    options.add(
        new ArgsOption(
            "-tag",
            "A custom block tag, shown under its header",
            1,
            "<name>:<locations>:<header>",
            args -> {
              var tag = Comments.CustomTag.parse(args.get(0));
              if (tag != null) {
                customTags.put(tag.name(), tag);
              }
            }));
    IGNORED.forEach((name, count) ->
        options.add(
            new ArgsOption(
                name,
                "Accepted for compatibility with the standard doclet; has no effect",
                count,
                count == 0 ? "" : count == 1 ? "<value>" : "<value> <value>",
                args -> ignored.add(name))));
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
    var report = new Report(reporter, severities);
    for (var name : ignored) {
      reporter.print(
          Diagnostic.Kind.WARNING,
          name
              + " is a standard doclet option without an equivalent here; ignored"
              + (name.equals("-header") || name.equals("-bottom") || name.equals("-footer")
                  ? " (set footer or brand in the --theme file instead)"
                  : ""));
    }
    List<Api.External> externals = new ArrayList<>();
    for (var link : links) {
      try {
        externals.addAll(ExternalLinks.read(link[0], link[1]));
      } catch (IOException | IllegalArgumentException e) {
        reporter.print(
            Diagnostic.Kind.WARNING,
            "Cannot read the package list for "
                + link[0]
                + "; not linking to it: "
                + e.getMessage());
      }
    }
    var linker = new Linker(environment.getElementUtils(), environment.getTypeUtils(), externals);
    Api.Model model =
        new Extractor(environment, linker, report, customTags)
            .extract(moduleName, moduleVersion, List.copyOf(externals));
    if (report.downgraded() > 0) {
      report.note(
          report.downgraded()
              + " documentation problem(s) reported as warnings (--problems); the affected"
              + " parts are simplified or left out");
    }
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

  /** An option with any number of arguments. */
  private record ArgsOption(
      String name, String description, int count, String parameters, Consumer<List<String>> accept)
      implements Option {
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
      return parameters;
    }

    public boolean process(String option, List<String> arguments) {
      try {
        accept.accept(arguments);
        return true;
      } catch (IllegalArgumentException invalid) {
        return false;
      }
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
