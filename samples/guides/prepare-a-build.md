# Prepare your first build

Create an immutable plan, select a runtime, and handle validation errors.

## Choose an entry point

Use [`BuildPlan.defaults(String)`](api/example.runtime.BuildPlan#defaults(java.lang.String)) for a plan with no extra compiler flags. The entry point is the fully qualified class name.

```java
var plan = BuildPlan.defaults("example.Main");
```

## Select the runtime

Construct a [`Planner.Local`](api/example.runtime.Planner$Local) for the JVM. For fine-grained progress reporting, pass [`Planner.Options`](api/example.runtime.Planner$Options) to the overloaded constructor.

```java
var planner = new Planner.Local(Target.JVM);
```

## Validate the plan

Preparation returns the validated plan. A blank entry point raises [`PlanException`](api/example.runtime.PlanException) before execution.

```java
try {
  var ready = planner.prepare(plan);
  System.out.println(ready.mainClass());
} catch (PlanException rejected) {
  System.err.println(rejected.getMessage());
}
```
