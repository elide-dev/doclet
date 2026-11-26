package dev.elide.doclet;

import dev.elide.apidocs.model.Api;
import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;

/** Applies the shared doclet to existing and future Javadoc tasks. */
public final class ElideDocletPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    File jar;
    try {
      jar = new File(Api.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot locate the Elide doclet", e);
    }
    project
        .getTasks()
        .withType(Javadoc.class)
        .configureEach(task -> {
          var options = (StandardJavadocDocletOptions) task.getOptions();
          options.setDoclet("dev.elide.doclet.ElideDoclet");
          options.addStringOption("-module-name", project.getName());
          if (!"unspecified".equals(project.getVersion().toString())) {
            options.addStringOption("-module-version", project.getVersion().toString());
          }
          var theme = project.getProviders().gradleProperty("elideDoclet.theme");
          if (theme.isPresent()) {
            var file = project.file(theme.get());
            options.addStringOption("-theme", file.getAbsolutePath());
            task.getInputs().file(file);
          }
          var guides = project.getProviders().gradleProperty("elideDoclet.guides");
          if (guides.isPresent()) {
            var dir = project.file(guides.get());
            options.addStringOption("-guides", dir.getAbsolutePath());
            task.getInputs().dir(dir);
          }
          var formatter = project.getProviders().gradleProperty("elideDoclet.signatureFormatter");
          if (formatter.isPresent()) {
            String value = formatter.get();
            if (value.contains("/") || value.contains(File.separator)) {
              var executable = project.file(value);
              task.getInputs().file(executable);
              value = executable.getAbsolutePath();
            }
            options.addStringOption("-signature-formatter", value);
          }
          var problems = project.getProviders().gradleProperty("elideDoclet.problems");
          if (problems.isPresent()) {
            options.addStringOption("-problems", problems.get());
          }
          task.getOptions().setDocletpath(List.of(jar));
          task.getInputs().file(jar);
        });
  }
}
