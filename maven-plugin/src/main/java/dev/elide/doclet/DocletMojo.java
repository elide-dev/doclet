package dev.elide.doclet;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

import java.io.File;
import java.net.URISyntaxException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/** Runs Maven's Javadoc goal with the doclet from this plugin's class path. */
public final class DocletMojo extends AbstractMojo {
  private MavenProject project;
  private MavenSession session;
  private BuildPluginManager pluginManager;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      File jar =
          new File(ElideDoclet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      executeMojo(
          plugin(
              groupId("org.apache.maven.plugins"),
              artifactId("maven-javadoc-plugin"),
              version("3.11.2")),
          goal("javadoc"),
          configuration(
              element("doclet", ElideDoclet.class.getName()),
              element("docletPath", jar.getAbsolutePath()),
              element("useStandardDocletOptions", "true")),
          executionEnvironment(project, session, pluginManager));
    } catch (URISyntaxException e) {
      throw new MojoExecutionException("Cannot locate the Elide doclet", e);
    }
  }
}
