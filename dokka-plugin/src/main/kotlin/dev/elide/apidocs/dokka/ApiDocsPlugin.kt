/*
 * Copyright (c) 2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

package dev.elide.apidocs.dokka

import dev.elide.apidocs.format.ModelLayout
import dev.elide.apidocs.format.SignatureLayout
import dev.elide.apidocs.model.Api
import dev.elide.apidocs.model.Json
import dev.elide.apidocs.render.DocumentationSite
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.dokka.CoreExtensions
import org.jetbrains.dokka.DelicateDokkaApi
import org.jetbrains.dokka.analysis.kotlin.KotlinAnalysisPlugin
import org.jetbrains.dokka.analysis.kotlin.sample.SampleAnalysisEnvironment
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.model.DModule
import org.jetbrains.dokka.model.Documentable
import org.jetbrains.dokka.pages.ModulePageNode
import org.jetbrains.dokka.pages.PageNode
import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.DokkaPlugin
import org.jetbrains.dokka.plugability.DokkaPluginApiPreview
import org.jetbrains.dokka.plugability.Extension
import org.jetbrains.dokka.plugability.PluginApiPreviewAcknowledgement
import org.jetbrains.dokka.renderers.Renderer

/**
 * A Dokka plugin that writes a module's API reference from the `apidocs/1` model, with the
 * `@elide/apidocs` renderer this jar carries, in place of Dokka's HTML renderer: static HTML and
 * Markdown pages, `llms.txt` indexes and the model itself (`api.json`), in Dokka's output
 * directory. The `modelOnly` option writes `api.json` alone.
 *
 * Put the jar on Dokka's `pluginsClasspath` beside `dokka-base` and the Kotlin analysis plugin, and
 * pass [Options] as this class's `pluginsConfiguration` entry (JSON). Rendering needs a JavaScript
 * runtime, Bun or Node, and nothing else.
 */
public class ApiDocsPlugin : DokkaPlugin() {
  private val dokkaBase by lazy { plugin<DokkaBase>() }

  /** The renderer, in place of Dokka's HTML renderer. */
  public val apiDocsRenderer: Extension<Renderer, *, *> by extending {
    CoreExtensions.renderer providing ::ApiDocsRenderer override dokkaBase.htmlRenderer
  }

  @DokkaPluginApiPreview
  override fun pluginApiPreviewAcknowledgement(): PluginApiPreviewAcknowledgement =
    PluginApiPreviewAcknowledgement
}

/**
 * Builds the model from the merged, filtered module (after Dokka's own transformers: `@suppress`,
 * suppressed files, documented visibilities) and renders it. Anything the model cannot represent
 * fails the run, naming the declaration.
 */
public class ApiDocsRenderer(private val context: DokkaContext) : Renderer {
  override fun render(root: RootPageNode) {
    val module =
      modulePage(root)?.documentables?.singleOrNull() as? DModule
        ?: error(
          "The apidocs plugin found no module page in Dokka's pages (got ${root::class.simpleName})",
        )
    val options =
      Options.parse(
        context.configuration.pluginsConfiguration
          .firstOrNull { it.fqPluginName == ApiDocsPlugin::class.qualifiedName }
          ?.values,
      )
    val sourceRoots =
      context.configuration.sourceSets
        .flatMap { it.sourceRoots }
        .map { it.toPath().toAbsolutePath().normalize() }
    val samples = Samples(context)
    val model =
      try {
        val classPath = ClassPath(context.configuration.sourceSets.flatMap { it.classpath })
        val builder = ModelBuilder(module, options, sourceRoots, samples::resolve, classPath)
        val built = builder.build()
        for (warning in builder.warnings) context.logger.warn("apidocs: $warning")
        if (builder.errors.isNotEmpty()) {
          for (error in builder.errors) context.logger.error("apidocs: $error")
          throw IllegalStateException(
            "${builder.errors.size} documentation error(s); no API reference written:\n" +
              builder.errors.joinToString("\n") { "  $it" },
          )
        }
        layOut(built, options)
      } finally {
        samples.close()
      }
    val output = context.configuration.outputDir.toPath()
    Files.createDirectories(output)
    if (options.modelOnly) {
      Files.writeString(
        output.resolve("api.json"),
        Json.write(model, options.pretty),
        StandardCharsets.UTF_8,
      )
      return
    }
    DocumentationSite.write(
      model,
      output,
      options.theme?.let { Path.of(it) },
      options.jsRuntime,
      options.sourceRef,
    )
  }

  /**
   * The model with every signature laid out by the source formatter, in one run per language
   * ([ModelLayout]); a signature it cannot lay out keeps its one-line form, with a warning.
   */
  private fun layOut(model: Api.Model, options: Options): Api.Model {
    val layout =
      SignatureLayout.elide(
        options.signatureFormatter,
        object : SignatureLayout.Log {
          override fun info(message: String) = context.logger.info("apidocs: $message")

          override fun warning(message: String) = context.logger.warn("apidocs: $message")
        },
      )
    if (!layout.enabled()) return model
    val laidOut = ModelLayout.apply(model, layout)
    val stats = layout.stats()
    context.logger.info(
      "apidocs: laid out ${stats.declarations()} signatures in ${stats.millis()} ms " +
        "(${stats.formatterRuns()} formatter run(s)): ${stats.multiline()} over several lines, " +
        "${stats.fallbacks()} kept on one line after a problem",
    )
    return laidOut
  }

  private fun modulePage(page: PageNode): ModulePageNode? =
    page as? ModulePageNode ?: page.children.firstNotNullOfOrNull { modulePage(it) }
}

/**
 * `@sample` bodies, from the Kotlin analysis plugin's sample environment (the source set's
 * `samples` roots), opened on the first sample a comment names: imports, a blank line, then the
 * body.
 */
private class Samples(private val context: DokkaContext) : AutoCloseable {
  private var environment: SampleAnalysisEnvironment? = null

  @OptIn(DelicateDokkaApi::class)
  fun resolve(d: Documentable, name: String): String? {
    val sourceSet = d.sourceSets.firstOrNull() ?: return null
    val env =
      environment
        ?: run {
          val analysis = context.plugin(KotlinAnalysisPlugin::class) ?: return null
          context.single(analysis.sampleAnalysisEnvironmentCreator).create().also {
            environment = it
          }
        }
    val sample = env.resolveSample(sourceSet, name) ?: return null
    val imports =
      sample.imports.joinToString("\n") { if (it.startsWith("import ")) it else "import $it" }
    return (if (imports.isEmpty()) "" else "$imports\n\n") + KDocs.dedent(sample.body)
  }

  override fun close() {
    environment?.close()
  }
}
