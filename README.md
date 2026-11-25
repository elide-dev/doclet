# Elide Doclet

Generate a static API reference for a Java library through Javadoc, Gradle, or Maven. Every build extracts the library’s public API into `api.json` and renders HTML and Markdown from that model. Open `index.html` directly or publish the output directory. Pages, styles, search, and navigation work offline.

Run Javadoc with **JDK 25**. The build also needs **Node.js or Bun** on PATH to execute the renderer bundled in the JAR. Readers only need a browser. The documentation JDK can be newer than the library’s Java target.

## Standalone Javadoc

```sh
javadoc -doclet dev.elide.doclet.ElideDoclet -docletpath tools/elide-doclet.jar \
  -d build/docs --module-name catalogue --module-version 1.0 \
  -sourcepath src/main/java -subpackages com.example
```

The name and version are optional. A named Java module supplies the default title. Gradle and Maven supply the project’s name and version.

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

## Branding

The default theme uses the library name. Supply a JSON theme to change the header, light and dark colors, logos, navigation, footer, and source links. For example, save this as `docs/theme.json`:

```json
{
  "productName": "Field Guide SDK",
  "brand": "Field Guide",
  "tokens": {
    "light": { "accent": "#345b84" },
    "dark": { "accent": "#99c4ed" }
  },
  "footer": "Field Guide developer documentation"
}
```

| Entry point | Theme configuration |
| --- | --- |
| Javadoc | Add `--theme docs/theme.json` to the command |
| Gradle | Set `elideDoclet.theme=docs/theme.json` in `gradle.properties` |
| Maven | Add `<configuration><theme>${project.basedir}/docs/theme.json</theme></configuration>` to the plugin |

For a logo, set `logo.light` and `logo.dark` to SVG markup and `logo.alt` to its accessible name. Set `baseUrl` to generate canonical URLs and a sitemap. The renderer accepts the complete [theme definition](https://github.com/elide-dev/components/blob/d9acdfe98d033b32c77982392bc2a22b8f38451f/packages/apidocs/src/theme.ts).

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

The standalone test loads the published JAR through Javadoc. The plugin tests build [one example API](tests/consumer/) with each tool, verify its model, HTML, Markdown, and search assets, then render it again with a custom theme. Run one consumer with `scripts/smoke-test-plugins gradle` or `scripts/smoke-test-plugins maven`.

The shared model and `DocumentationSite.write` have no Javadoc dependencies. Another frontend can produce an `Api.Model` and use the same renderer. The renderer distribution is checked in so building the doclet or using it offline requires no frontend dependency installation. To refresh it from a components checkout, run `scripts/update-renderer /path/to/components`.

To try the plugins before a release, run `elide publish` from the workspace root. Maven uses the local repository automatically. In Gradle, add `mavenLocal()` to `pluginManagement.repositories`. The standalone core remains available as `dev.elide:elide-doclet:<version>` for direct use with `javadoc -doclet dev.elide.doclet.ElideDoclet -docletpath <jar>`.

## Releases

Release Please updates the workspace members together from Conventional Commits on `main`. Enable Actions pull-request creation and GitHub Packages publication for the repository. On release, CI attaches the tested JARs and POMs to the GitHub release and deploys those same files to GitHub Packages. Maven Central and the Gradle Plugin Portal are future publication targets.

## Theme gallery

[Browse the sample gallery](https://animated-adventure-jkjlqo3.pages.github.io/) or edit the [sample themes](samples/themes/). Run `scripts/build-samples` and open `.dev/pages/index.html` to compare the same Java record across all three themes. The gallery links to each rendered reference and its complete JSON configuration. The GraalVM sample uses artwork from the [official brand resources](https://www.graalvm.org/graalvm-brand-guidelines/).
