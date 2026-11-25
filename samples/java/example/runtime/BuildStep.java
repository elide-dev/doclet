package example.runtime;

/**
 * A named phase in the preparation of a {@link BuildPlan}.
 *
 * @param <T> the artifact produced by the step
 */
@FunctionalInterface
public interface BuildStep<T> {
  /**
   * Prepares an artifact without executing the application.
   *
   * @param plan the input configuration
   * @return the prepared artifact
   * @throws PlanException if the configuration cannot be prepared
   */
  T prepare(BuildPlan plan) throws PlanException;
}
