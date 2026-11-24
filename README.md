# Elide Doclet

Build a Javadoc doclet with Elide and run it with any JDK 17+ Javadoc tool. The starter inherits the standard doclet's HTML output and options, and adds a stylesheet from the JAR. Java sources and classpath resources live under `src/main/java` and `src/main/resources`.

## Build and verify

Install the pinned development tools with mise, then enable hk for this checkout:

```sh
mise trust
mise install
mise exec -- hk install
```

The pre-commit hook formats staged Java, Kotlin and Kotlin script files with Elide and stages the fixes. Run `mise exec -- hk check --all` for a read-only check or `mise exec -- hk fix --all` to format the repository. CI uses the same Elide version and Google-style Kotlin formatting.

With mise activated and a JDK on PATH, run:

```sh
elide build
scripts/smoke-test
```

The smoke test documents a sample class and verifies the generated page references the packaged CSS. To document your own sources:

```sh
javadoc -d build/docs -doclet dev.elide.doclet.ElideDoclet \
  -docletpath .dev/artifacts/jar/elide-doclet/elide-doclet.jar src/example/Example.java
```

## Consume a GitHub release

Download the versioned JAR and save it as `tools/elide-doclet.jar` in the consuming project. Consumers only need their existing Java toolchain.

### Maven

Configure the Maven Javadoc Plugin in `build.plugins`, then run `mvn javadoc:javadoc`.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-javadoc-plugin</artifactId>
  <version>3.11.2</version>
  <configuration>
    <doclet>dev.elide.doclet.ElideDoclet</doclet>
    <docletPath>${project.basedir}/tools/elide-doclet.jar</docletPath>
    <useStandardDocletOptions>true</useStandardDocletOptions>
  </configuration>
</plugin>
```

### Gradle

In a Java project's `build.gradle.kts`, configure the existing Javadoc task and run `./gradlew javadoc`.

```kotlin
tasks.javadoc {
    options.doclet = "dev.elide.doclet.ElideDoclet"
    options.docletpath = listOf(rootProject.file("tools/elide-doclet.jar"))
}
```

## Releases and Maven coordinates

Use Conventional Commits on `main`. Release Please maintains the changelog and version in `elide.pkl` through release PRs. Allow GitHub Actions to create pull requests in repository settings. Merging a release PR creates a tagged GitHub release with the tested JAR and its generated POM.

Run `elide publish --dry-run` to stage the Maven layout under `.dev/staging/publish/maven`, or `elide publish` to install it in your local Maven repository. The coordinates are `dev.elide:elide-doclet:<version>`. Maven Central publication is deferred until the namespace, license, signing, sources and Javadoc artifacts are configured.
