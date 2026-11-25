package example.runtime;

/**
 * Validates a {@link BuildPlan} before handing it to a {@link BuildStep}. Select a {@link Target}
 * and call {@link #prepare(BuildPlan)} to check the entry point.
 *
 * @see Planner.Options
 * @since 1.0
 */
public abstract sealed class Planner implements BuildStep<BuildPlan> permits Planner.Local {
  /** Creates a planner. */
  protected Planner() {}

  /**
   * Identifies the runtime for prepared plans.
   *
   * @return the selected target
   */
  public abstract Target target();

  /**
   * Validates that the plan names an entry point.
   *
   * @param plan configuration to validate
   * @return the validated plan
   * @throws PlanException when the entry point is blank
   */
  @Override
  public BuildPlan prepare(BuildPlan plan) throws PlanException {
    if (plan.mainClass().isBlank()) throw new PlanException("Specify an entry point");
    return plan;
  }

  /**
   * Configuration for {@link Local}, including how to report progress.
   *
   * @param target the runtime
   * @param detail the amount of progress to report
   */
  public record Options(Target target, Detail detail) {
    /** Controls progress while preparing a plan. */
    public enum Detail {
      /** Report only the result. */
      QUIET,
      /** Include each preparation step. */
      VERBOSE
    }
  }

  /** Prepares builds against a locally installed toolchain. */
  @Experimental
  public static final class Local extends Planner {
    private final Options options;

    /**
     * Uses explicit planning options.
     *
     * @param options runtime and progress settings
     */
    public Local(Options options) {
      this.options = options;
    }

    /**
     * Uses the supplied target with quiet progress.
     *
     * @param target the runtime
     */
    public Local(Target target) {
      this(new Options(target, Options.Detail.QUIET));
    }

    @Override
    public Target target() {
      return options.target();
    }

    /**
     * Returns the configured runtime.
     *
     * @return the selected target
     * @deprecated Use {@link #target()}.
     */
    @Deprecated(since = "1.0")
    public Target runtime() {
      return target();
    }
  }
}
