package dev.elide.doclet;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Runs javadoc with the doclet over fixture sources and collects its diagnostics. */
final class Javadoc {
  private Javadoc() {}

  /** The outcome of one run. */
  record Result(boolean ok, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path docs) {
    List<String> messages(Diagnostic.Kind kind) {
      return diagnostics.stream()
          .filter(d -> d.getKind() == kind)
          .map(d -> d.getMessage(null))
          .toList();
    }

    List<String> errors() {
      return messages(Diagnostic.Kind.ERROR);
    }

    List<String> warnings() {
      return messages(Diagnostic.Kind.WARNING);
    }

    String model() throws IOException {
      return Files.readString(docs.resolve("api.json"));
    }

    String page(String path) throws IOException {
      return Files.readString(docs.resolve(path));
    }

    @Override
    public String toString() {
      return diagnostics.stream()
          .map(d -> d.getKind() + ": " + d.getMessage(null))
          .collect(Collectors.joining("\n"));
    }
  }

  /**
   * Writes {@code sources} (path below the source root to contents) under {@code directory} and
   * documents them with {@code options}.
   */
  static Result run(Path directory, Map<String, String> sources, List<String> options)
      throws IOException {
    var root = directory.resolve("src");
    List<Path> files = new ArrayList<>();
    for (var entry : sources.entrySet()) {
      var file = root.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      files.add(Files.writeString(file, entry.getValue()));
    }
    var docs = directory.resolve("docs");
    var arguments = new ArrayList<>(List.of("-quiet", "-d", docs.toString()));
    arguments.addAll(options);
    var tool = ToolProvider.getSystemDocumentationTool();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var manager = tool.getStandardFileManager(null, null, null)) {
      var task =
          tool.getTask(
              new StringWriter(),
              manager,
              diagnostics,
              ElideDoclet.class,
              arguments,
              manager.getJavaFileObjectsFromPaths(files));
      var ok = task.call();
      return new Result(ok, diagnostics.getDiagnostics(), docs);
    }
  }
}
