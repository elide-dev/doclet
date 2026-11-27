package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api;
import dev.elide.apidocs.model.Guides;
import dev.elide.apidocs.render.DocumentationSite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/** Renders a catalog's independent guide suite against its version's declaration index. */
public final class GuideSuite {
  private GuideSuite() {}

  public static void main(String[] args) throws Exception {
    var targets = new HashMap<String, String>();
    for (var line : Files.readAllLines(Path.of(args[4]))) {
      var fields = line.split("\t", 2);
      targets.put(fields[0], fields[1]);
    }
    var module = new Api.Module(args[0], args[1], null, List.of(), null);
    var model =
        Guides.read(
            new Api.Model(Api.SCHEMA, module, List.of(), List.of()),
            Path.of(args[2]),
            (markdown, ignored) ->
                Comments.guideBody(
                    markdown,
                    href -> {
                      if (!href.startsWith("api/")) return null;
                      var target = targets.get(href);
                      if (target == null)
                        throw new IllegalArgumentException("Unknown cross-module API link " + href);
                      return new Api.Ref(href, null, "../../" + target);
                    }));
    DocumentationSite.write(model, Path.of(args[3]), null, null, null);
  }
}
