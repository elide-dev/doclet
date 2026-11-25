/**
 * Plans Java applications for a local JVM or native executable.
 *
 * <h2>Preparing a build</h2>
 *
 * <p>Start with {@link example.runtime.BuildPlan#defaults(String)}, select a {@link
 * example.runtime.Target}, and pass the plan to {@link example.runtime.Planner.Local}. Preparation
 * validates configuration before a compiler is invoked.
 *
 * <h2>Extending a build</h2>
 *
 * <p>Implement {@link example.runtime.BuildStep} to transform a plan into another artifact. Report
 * invalid input with {@link example.runtime.PlanException}.
 *
 * @since 1.0
 */
package example.runtime;
