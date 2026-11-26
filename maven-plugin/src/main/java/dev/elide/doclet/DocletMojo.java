package dev.elide.doclet;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

import dev.elide.apidocs.model.Api;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/** Runs Maven's Javadoc goal with the doclet from this plugin's class path. */
public final class DocletMojo extends AbstractMojo {
  private File theme;
  private File guides;
  private String signatureFormatter;
  private String problems;
  private MavenProject project;
  private MavenSession session;
  private BuildPluginManager pluginManager;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      File jar = new File(Api.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      var arguments =
          new ArrayList<>(
              List.of(
                  "--module-name",
                  project.getArtifactId(),
                  "--module-version",
                  project.getVersion()));
      if (problems != null && !problems.isBlank()) {
        arguments.add("--problems");
        arguments.add(problems.strip());
      }
      if (theme != null) {
        arguments.add("--theme");
        arguments.add(quoted(theme.getAbsolutePath()));
        // Maven fingerprints the Javadoc argument file but does not track theme file contents.
        byte[] digest =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(theme.toPath()));
        arguments.add("# Theme SHA-256: " + HexFormat.of().formatHex(digest));
      }
      if (guides != null) {
        arguments.add("--guides");
        arguments.add(quoted(guides.getAbsolutePath()));
        var digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(guides.toPath())) {
          for (var file :
              files
                  .filter(f ->
                      Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                          && f.toString().endsWith(".md"))
                  .sorted()
                  .toList()) {
            digest.update(
                guides
                    .toPath()
                    .relativize(file)
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
          }
        }
        arguments.add("# Guides SHA-256: " + HexFormat.of().formatHex(digest.digest()));
      }
      if (signatureFormatter != null && !signatureFormatter.isBlank()) {
        arguments.add("--signature-formatter");
        var value = signatureFormatter;
        if (value.contains("/") || value.contains(File.separator))
          value = project.getBasedir().toPath().resolve(value).toString();
        arguments.add(quoted(value));
      }
      var options =
          configuration(
              element("doclet", "dev.elide.doclet.ElideDoclet"),
              element("docletPath", jar.getAbsolutePath()),
              element("useStandardDocletOptions", "false"),
              element(
                  "additionalOptions",
                  arguments.stream()
                      .map(arg -> element("additionalOption", arg))
                      .toArray(Element[]::new)));
      executeMojo(
          plugin(
              groupId("org.apache.maven.plugins"),
              artifactId("maven-javadoc-plugin"),
              version("3.11.2")),
          goal("javadoc"),
          options,
          executionEnvironment(project, session, pluginManager));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new MojoExecutionException("Cannot read documentation inputs: " + e.getMessage(), e);
    } catch (URISyntaxException e) {
      throw new MojoExecutionException("Cannot locate the Elide doclet", e);
    }
  }

  private static String quoted(String value) {
    String path = value.replace(File.separatorChar, '/');
    return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
