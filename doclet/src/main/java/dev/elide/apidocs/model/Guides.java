package dev.elide.apidocs.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/** Imports authored Markdown into a module and adds reciprocal type references. */
public final class Guides {
  private Guides() {}

  /** A Markdown frontend; the resolver returns null for ordinary hyperlinks. */
  @FunctionalInterface
  public interface MarkdownParser {
    List<Doc.Block> parse(String markdown, Function<String, Api.Ref> resolveLink);
  }

  /** Reads guides into the model. Unknown API links are reported against their source file. */
  public static Api.Model read(Api.Model model, Path directory, MarkdownParser parser)
      throws IOException {
    if (!Files.isDirectory(directory))
      throw new IOException("Missing guides directory: " + directory);
    var owners = new HashMap<String, String>();
    for (var pkg : model.packages())
      for (var type : pkg.types()) {
        owners.put(type.id(), type.id());
        for (var member : type.members()) owners.put(member.id(), type.id());
      }
    var guides = new ArrayList<Api.Guide>();
    if (model.module().guides() != null) guides.addAll(model.module().guides());
    var slugs = new HashSet<String>();
    for (var guide : guides) slugs.add(guide.slug());
    var related = new HashMap<String, List<List<Doc.Inline>>>();
    var module = model.module();
    var overview = module.doc();
    try (var files = Files.walk(directory)) {
      for (var file :
          files
              .filter(f ->
                  Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && f.toString().endsWith(".md"))
              .sorted()
              .toList()) {
        var relative =
            directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        var slug = relative.substring(0, relative.length() - 3);
        if (!slug.matches("[a-z0-9-]+(/[a-z0-9-]+)*"))
          throw new IOException("Invalid guide slug: " + relative);
        var lines = Files.readAllLines(file);
        if (lines.isEmpty()
            || !lines.get(0).startsWith("# ")
            || lines.get(0).substring(2).isBlank())
          throw new IOException(file + ": expected a # Title on the first line");
        String title = lines.get(0).substring(2).strip();
        String id = "guide:" + slug;
        var referenced = new LinkedHashSet<String>();
        List<Doc.Block> body;
        try {
          body =
              parser.parse(
                  String.join("\n", lines.subList(1, lines.size())),
                  href -> {
                    if (!href.startsWith("api/")) return null;
                    String key = href.substring(4);
                    if (!owners.containsKey(key))
                      throw new IllegalArgumentException("Unknown API reference " + key);
                    referenced.add(owners.get(key));
                    return new Api.Ref(key, null, null);
                  });
        } catch (IllegalArgumentException ex) {
          throw new IOException(file + ": " + ex.getMessage(), ex);
        }
        var summary =
            body.isEmpty() || !(body.get(0) instanceof Doc.Paragraph p)
                ? List.<Doc.Inline>of()
                : p.children();
        if (slug.equals("index")) {
          overview =
              new Doc(
                  summary,
                  body,
                  overview == null ? null : overview.notes(),
                  overview == null ? null : overview.see(),
                  null);
        } else {
          if (!slugs.add(slug)) throw new IOException(file + ": duplicate guide slug " + slug);
          var remainder = summary.isEmpty() ? body : body.subList(1, body.size());
          guides.add(new Api.Guide(id, slug, title, summary, remainder));
          for (var owner : referenced)
            related
                .computeIfAbsent(owner, k -> new ArrayList<>())
                .add(
                    List.of(
                        new Doc.Link(
                            new Api.Ref(id, null, null),
                            null,
                            false,
                            List.of(new Doc.Text(title)))));
        }
      }
    }
    var packages = new ArrayList<Api.Package>();
    for (var pkg : model.packages()) {
      var types = new ArrayList<Api.Type>();
      for (var type : pkg.types()) {
        if (related.containsKey(type.id())) {
          var doc = type.doc();
          if (doc == null) doc = new Doc(null, null, null, null, null);
          var see = new ArrayList<List<Doc.Inline>>();
          if (doc.see() != null) see.addAll(doc.see());
          see.addAll(related.get(type.id()));
          type =
              type.withDoc(
                  new Doc(doc.summary(), doc.body(), doc.notes(), see, doc.inheritedFrom()));
        }
        types.add(type);
      }
      packages.add(
          new Api.Package(
              pkg.id(),
              pkg.name(),
              pkg.doc(),
              pkg.since(),
              pkg.deprecated(),
              pkg.nullMarked(),
              types,
              pkg.functions(),
              pkg.properties(),
              pkg.typeAliases()));
    }
    return new Api.Model(
        model.schema(),
        new Api.Module(
            module.name(),
            module.version(),
            module.javaModule(),
            module.languages(),
            overview,
            guides),
        model.externals(),
        packages);
  }
}
