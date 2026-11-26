/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.doclet;

import dev.elide.apidocs.model.Api;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The standard doclet's {@code -link} and {@code -linkoffline}: another Javadoc site's {@code
 * element-list} (or, for sites older than JDK 10, {@code package-list}) as {@link Api.External}s,
 * one per package.
 *
 * <p>An {@code element-list} that names modules ({@code module:java.base}) belongs to a modular
 * site, whose pages sit below a directory per module; each package links below its module's. A
 * {@code module/package} line (mx's JDK {@code package-list}) says the same for one package.
 */
final class ExternalLinks {
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private ExternalLinks() {}

  /**
   * The externals for the Javadoc site at {@code url}, reading its package list from {@code
   * location}: a directory or URL holding {@code element-list} or {@code package-list}; {@code url}
   * itself for {@code -link}.
   */
  static List<Api.External> read(String url, String location) throws IOException {
    var base = url.endsWith("/") ? url : url + "/";
    var dir = location.endsWith("/") || location.endsWith("\\") ? location : location + "/";
    String text = fetch(dir, "element-list");
    if (text == null) {
      text = fetch(dir, "package-list");
    }
    if (text == null) {
      throw new IOException("No element-list or package-list at " + location);
    }
    return parse(base, text);
  }

  /**
   * The externals an {@code element-list} or {@code package-list} describes, below {@code base}.
   */
  static List<Api.External> parse(String base, String list) {
    List<Api.External> externals = new ArrayList<>();
    String module = null;
    for (var raw : list.split("\\R")) {
      var line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("module:")) {
        module = line.substring("module:".length()).strip();
        continue;
      }
      if (line.equals("unnamed package")) {
        continue;
      }
      var slash = line.indexOf('/');
      if (slash > 0) {
        // module/package, as in mx's JDK package-list.
        externals.add(
            new Api.External(
                line.substring(slash + 1) + ".", base + line.substring(0, slash) + "/"));
      } else {
        externals.add(new Api.External(line + ".", module == null ? base : base + module + "/"));
      }
    }
    return externals;
  }

  /** {@code name} below {@code dir} (a URL or a local directory), or {@code null} if absent. */
  private static String fetch(String dir, String name) throws IOException {
    URI uri = null;
    try {
      var parsed = new URI(dir.replace('\\', '/'));
      if (parsed.getScheme() != null && parsed.getScheme().length() > 1) {
        uri = parsed.resolve(name);
      }
    } catch (URISyntaxException notAUri) {
      // A local path with characters a URI does not allow.
    }
    if (uri == null) {
      var file = Path.of(dir).resolve(name);
      return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }
    return switch (uri.getScheme().toLowerCase(java.util.Locale.ROOT)) {
      case "http", "https" -> http(uri);
      default -> {
        try (InputStream in = uri.toURL().openStream()) {
          yield new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException absent) {
          yield null;
        }
      }
    };
  }

  private static String http(URI uri) throws IOException {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404 || response.statusCode() == 410) {
        return null;
      }
      if (response.statusCode() / 100 != 2) {
        throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted fetching " + uri, e);
    }
  }
}
