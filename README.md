# Elide Doclet

Generate standard Javadoc HTML with Elide's bundled stylesheet on JDK 17 or later. The Elide workspace builds three artifacts together. The [doclet](doclet/) owns the implementation and resources. The [Gradle plugin](gradle-plugin/) configures Javadoc tasks, and the [Maven plugin](maven-plugin/) delegates to the Apache Maven Javadoc Plugin. Both declare the same version of the core as a dependency.

The core exposes `DocumentationAssets.writeStylesheet(Path)` for renderers that need the shared stylesheet. Only `ElideDoclet` depends on the JDK doclet API.

## Gradle

For a released version, add the GitHub Packages repository to the top-level `pluginManagement` block in `settings.gradle.kts`. Use a GitHub username and a token with read access to packages.

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/elide-dev/doclet")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        gradlePluginPortal()
    }
}
```

Apply the plugin in `build.gradle.kts` and run `./gradlew javadoc`. The plugin configures existing and future Javadoc tasks, including their doclet path.

```kotlin
plugins {
    java
    id("dev.elide.doclet") version "0.1.0"
}
```

## Maven

Add the repository and plugin to the consuming POM, then run `mvn elide-doclet:javadoc`. To generate docs during `mvn package`, add an execution for the `javadoc` goal. Its default phase is `prepare-package`.

```xml
<pluginRepositories>
  <pluginRepository>
    <id>elide-doclet</id>
    <url>https://maven.pkg.github.com/elide-dev/doclet</url>
  </pluginRepository>
</pluginRepositories>
<build>
  <plugins>
    <plugin>
      <groupId>dev.elide</groupId>
      <artifactId>elide-doclet-maven-plugin</artifactId>
      <version>0.1.0</version>
    </plugin>
  </plugins>
</build>
```

Configure a `<server>` with the matching ID in your Maven settings:

```xml
<server>
  <id>elide-doclet</id>
  <username>${env.GITHUB_ACTOR}</username>
  <password>${env.GITHUB_TOKEN}</password>
</server>
```

## Build and verify

```sh
mise trust
mise install
mise exec -- hk install
elide build
(cd doclet && elide test --coverage --reporter=junit)
scripts/smoke-test
elide publish --dry-run
scripts/smoke-test-plugins
```

The first smoke test loads the core JAR with Javadoc. The second runs [one example API](tests/consumer/) through each build tool, checks its Javadoc text, and compares the generated stylesheet with the bundled CSS. Run either consumer alone with `scripts/smoke-test-plugins gradle` or `scripts/smoke-test-plugins maven`. Each run uses fresh build outputs and the staged plugins. Maven caches third-party dependencies under `.dev/consumer-m2`. CI runs both on JDK 17. The pre-commit hook formats staged Java and Kotlin files. Run `hk check --all` or `hk fix --all` with mise activated.

To try the plugins before a release, run `elide publish` from the workspace root. Maven uses the local repository automatically. In Gradle, add `mavenLocal()` to `pluginManagement.repositories`. The standalone core remains available as `dev.elide:elide-doclet:<version>` for direct use with `javadoc -doclet dev.elide.doclet.ElideDoclet -docletpath <jar>`.

## Releases

Release Please updates the workspace members together from Conventional Commits on `main`. Enable Actions pull-request creation and GitHub Packages publication for the repository. On release, CI attaches the tested JARs and POMs to the GitHub release and deploys those same files to GitHub Packages. Maven Central and the Gradle Plugin Portal are future publication targets.
