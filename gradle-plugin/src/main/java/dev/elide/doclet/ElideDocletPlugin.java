package dev.elide.doclet;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.javadoc.Javadoc;

/** Applies the shared doclet to existing and future Javadoc tasks. */
public final class ElideDocletPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    File jar;
    try {
      jar = new File(ElideDoclet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot locate the Elide doclet", e);
    }
    project
        .getTasks()
        .withType(Javadoc.class)
        .configureEach(task -> {
          task.getOptions().setDoclet(ElideDoclet.class.getName());
          task.getOptions().setDocletpath(List.of(jar));
          task.getInputs().file(jar);
        });
  }
}
