package example.runtime;

import java.util.List;

/**
 * Describes how to build a Java application as a native executable.
 *
 * <p>A plan pairs an entry point with compiler arguments. For example:
 *
 * <pre>{@code
 * var plan = new BuildPlan("example.Main", List.of("-O2"));
 * }</pre>
 *
 * @param mainClass the fully qualified entry point
 * @param arguments compiler arguments in invocation order
 * @since 1.0
 */
public record BuildPlan(String mainClass, List<String> arguments) {
  /** Creates a plan with a stable copy of the arguments. */
  public BuildPlan {
    arguments = List.copyOf(arguments);
  }

  /**
   * Returns a plan for the entry point using default compiler settings.
   *
   * @param mainClass the fully qualified entry point
   * @return a plan with an empty argument list
   */
  public static BuildPlan defaults(String mainClass) {
    return new BuildPlan(mainClass, List.of());
  }
}
