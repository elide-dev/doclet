package example.runtime;

/** Selects the runtime that will consume a {@link BuildPlan}. */
public enum Target {
  /** Use the installed Java virtual machine. */
  JVM,
  /** Compile an operating-system executable. */
  NATIVE
}
