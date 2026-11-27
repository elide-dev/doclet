# Prepare a Kotlin greeting for a Java runtime

Use the Java planner to select an entry point and the Kotlin greeter to produce the application's message.

## Choose an entry point

Create a plan with [BuildPlan.defaults](api/java/example.runtime.BuildPlan#defaults(java.lang.String)).
The plan records the entry-point class and its compiler arguments.

## Produce a greeting

Construct a [Greeter](api/kotlin/example.Greeter) with the recipient's name and call its greeting method.

```kotlin
val message = Greeter("world").greet()
```
