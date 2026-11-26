# Runtime Kit

Plan a Java application for the JVM or a native executable.

Runtime Kit separates the configuration of an application from the tool that prepares it. An immutable [`BuildPlan`](api/example.runtime.BuildPlan) holds the entry point and compiler arguments. A [`Planner.Local`](api/example.runtime.Planner$Local) validates the plan for the chosen [`Target`](api/example.runtime.Target).

Start with the preparation guide for a runnable example, or explore the package reference for constructors, extension points, and error handling.
