package example.runtime;

/** Indicates that a {@link BuildPlan} cannot be prepared. */
public final class PlanException extends Exception {
  /**
   * Creates a failure with an explanation suitable for the build log.
   *
   * @param reason the rejected setting
   */
  public PlanException(String reason) {
    super(reason);
  }
}
