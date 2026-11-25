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
      if (theme != null) {
        arguments.add("--theme");
        String path = theme.getAbsolutePath().replace(File.separatorChar, '/');
        arguments.add("\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        // Maven fingerprints the Javadoc argument file but does not track theme file contents.
        byte[] digest =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(theme.toPath()));
        arguments.add("# Theme SHA-256: " + HexFormat.of().formatHex(digest));
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
      throw new MojoExecutionException("Cannot read the documentation theme " + theme, e);
    } catch (URISyntaxException e) {
      throw new MojoExecutionException("Cannot locate the Elide doclet", e);
    }
  }
}
