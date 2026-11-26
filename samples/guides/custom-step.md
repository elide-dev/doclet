# Write a preparation step

Use the generic extension point to derive a launch command.

## Choose the output type

[`BuildStep<T>`](api/example.runtime.BuildStep) describes the result of preparation. A step can return a command line, a manifest, or another build plan.

```java
BuildStep<List<String>> launch = p -> List.of("java", p.mainClass());
```

## Apply the step

Pass a validated [`BuildPlan`](api/example.runtime.BuildPlan) to the step. The result contains the command and its argument as separate entries.

```java
var command = launch.prepare(BuildPlan.defaults("example.Main"));
```
