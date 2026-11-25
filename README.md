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

Besides the HTML pages, a site has a Markdown twin of every page, `llms.txt` and `llms-full.txt` for AI agents, and `api.json`, the model. Leave any of them out with `--no-markdown`, `--no-llms`, or `--no-model`; the pages then link only to the files the site has. In Gradle, add them to the Javadoc task with `options.addBooleanOption("-no-markdown", true)`.

Every page holds only its own path through the navigation tree: its package, that package’s types, and the current type’s nested types. The whole tree is loaded once from `assets/nav.js`, which works from `file://` too, so a page’s size follows its own content rather than the size of the library.

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
scripts/build-samples && scripts/check-size .dev/pages/signal
elide publish --dry-run
scripts/smoke-test-plugins
```

The standalone test loads the published JAR through Javadoc. `scripts/check-size` holds every sample theme’s site to a size budget: each page’s navigator, shell, and inline SVG, the HTML per page, and the site’s text files. The plugin tests build [one example API](tests/consumer/) with each tool, verify its model, HTML, Markdown, and search assets, then render it again with a custom theme. Run one consumer with `scripts/smoke-test-plugins gradle` or `scripts/smoke-test-plugins maven`.

The shared model and `DocumentationSite.write` have no Javadoc dependencies. Another frontend can produce an `Api.Model` and use the same renderer. The renderer distribution is checked in so building the doclet or using it offline requires no frontend dependency installation. To refresh it from a components checkout, run `scripts/update-renderer /path/to/components`.

To try the plugins before a release, run `elide publish` from the workspace root. Maven uses the local repository automatically. In Gradle, add `mavenLocal()` to `pluginManagement.repositories`. The standalone core remains available as `dev.elide:elide-doclet:<version>` for direct use with `javadoc -doclet dev.elide.doclet.ElideDoclet -docletpath <jar>`.

## Releases

Release Please updates the workspace members together from Conventional Commits on `main`. Enable Actions pull-request creation and GitHub Packages publication for the repository. On release, CI attaches the tested JARs and POMs to the GitHub release and deploys those same files to GitHub Packages. Maven Central and the Gradle Plugin Portal are future publication targets.

## Theme gallery

[Browse the sample gallery](https://elide-dev.github.io/doclet/) or edit the [sample themes](samples/themes/). Run `scripts/build-samples` and open `.dev/pages/index.html` to compare the module overview, package, guides, and Java declarations across all seven themes. The gallery links to each rendered reference and its complete JSON configuration. The GraalVM sample uses artwork from the [official brand resources](https://www.graalvm.org/graalvm-brand-guidelines/). The Micronaut and Google samples embed the [Micronaut wordmark](https://micronaut.io/micronaut-assets/logos/micronaut-horizontal-black.svg) and [Google wordmark](https://www.gstatic.com/images/branding/googlelogo/svg/googlelogo_clr_74x24px.svg).

For an interactive preview, serve the generated folder with `python3 -m http.server 8787 --bind 127.0.0.1 --directory .dev/pages` and open `http://127.0.0.1:8787/preview.html`. Rerun `scripts/build-samples` after editing a theme and reload the preview.
