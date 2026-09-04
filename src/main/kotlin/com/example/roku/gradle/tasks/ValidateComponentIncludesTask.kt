package com.example.roku.gradle.tasks

import com.example.roku.gradle.tasks.ComponentIncludeValidator.ComponentScripts
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Build-time completeness gate for SceneGraph component `<script>` includes.
 *
 * Runs before packaging and cross-checks every packaged component XML against a
 * definition scan of ALL staged .brs files (see [ComponentIncludeValidator]).
 * Definition scan is ground truth — function-manifest.json is NOT consulted
 * (it has known gaps, e.g. enum *_initEntries entries are missing).
 *
 * Modes (rokuValidation.includeMode):
 * - "warning" (default): every finding is logged as a build warning; the build proceeds.
 * - "strict": findings fail the build.
 *
 * Infrastructure guards (never vacuously green):
 * - An implausibly small definition index fails the build in BOTH modes — it means the
 *   validator was handed an empty/broken file set, not that the app is clean.
 * - Zero packaged components fails in strict mode and warns loudly in warning mode.
 */
abstract class ValidateComponentIncludesTask : DefaultTask() {

    /** Processed user-authored component XMLs (output of processComponentXml). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val processedXmlDir: DirectoryProperty

    /**
     * Compiled components output (build/brs/brs/main/components): flat component .brs
     * files plus compiler-generated XMLs under components/&lt;Name&gt;/.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledComponentsDir: DirectoryProperty

    /** The package's staged source/ payload (compiled main sources, or staged test sources). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagedSourceDir: DirectoryProperty

    /** Runtime .brs files added to source/ from jars (stdlib runtime, kotlin.test runtime). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeBrs: ConfigurableFileCollection

    /** "warning" or "strict". */
    @get:Input
    abstract val mode: Property<String>

    /** Extra allowlisted global names (merged with the built-in BrightScript allowlist). */
    @get:Input
    abstract val extraBuiltins: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun validate() {
        val modeValue = mode.get().lowercase()
        if (modeValue !in setOf("warning", "strict")) {
            throw GradleException(
                "rokuValidation.includeMode must be 'warning' or 'strict' (got '${mode.get()}')"
            )
        }

        val sourceFiles = buildList {
            stagedSourceDir.asFile.orNull?.takeIf { it.isDirectory }?.walkTopDown()
                ?.filter { it.isFile && it.extension == "brs" }?.forEach { add(it) }
            runtimeBrs.files.filter { it.isFile && it.extension == "brs" }.forEach { add(it) }
        }

        val compiledDir = compiledComponentsDir.asFile.orNull?.takeIf { it.isDirectory }
        val componentFiles = compiledDir?.walkTopDown()
            ?.filter { it.isFile && it.extension == "brs" }?.toList().orEmpty()

        // Packaged component XMLs: processed user XMLs win; compiler-generated XMLs
        // (<compiled>/<Name>/<Name>.xml — the flat single-compilation layout) fill in
        // the rest — mirrors PackageRokuTask.
        val components = mutableListOf<ComponentScripts>()
        val userXmlNames = mutableSetOf<String>()
        processedXmlDir.asFile.orNull?.takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && it.extension == "xml" }
            ?.forEach { xml ->
                userXmlNames.add(xml.nameWithoutExtension.lowercase())
                components.add(
                    ComponentScripts(
                        xml.nameWithoutExtension,
                        xml.path,
                        ComponentIncludeValidator.parseScriptUris(xml.readText()),
                    )
                )
            }
        compiledDir?.walkTopDown()
            ?.filter { it.isFile && it.extension == "xml" }
            ?.filter { it.nameWithoutExtension.lowercase() !in userXmlNames }
            ?.forEach { xml ->
                components.add(
                    ComponentScripts(
                        xml.nameWithoutExtension,
                        xml.path,
                        ComponentIncludeValidator.parseScriptUris(xml.readText()),
                    )
                )
            }

        val builtins = ComponentIncludeValidator.DEFAULT_BUILTINS +
            extraBuiltins.get().map { it.lowercase() }
        val result = ComponentIncludeValidator.validate(sourceFiles, componentFiles, components, builtins)

        val report = ComponentIncludeValidator.renderReport(result)
        reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(report)

        if (result.definitionCount < ComponentIncludeValidator.MIN_PLAUSIBLE_DEFINITIONS) {
            throw GradleException(
                "$name: definition index is implausibly small (${result.definitionCount} definitions " +
                    "across ${sourceFiles.size + componentFiles.size} .brs files; expected at least " +
                    "${ComponentIncludeValidator.MIN_PLAUSIBLE_DEFINITIONS}). The validator was handed an " +
                    "empty or broken file set — this is an infrastructure failure, not a clean result."
            )
        }
        if (result.componentCount == 0) {
            val msg = "$name: found ZERO packaged component XMLs — nothing was validated. " +
                "If this project genuinely has no SceneGraph components this is harmless; " +
                "otherwise the validator's inputs are miswired."
            if (modeValue == "strict") throw GradleException(msg)
            logger.warn(msg)
        }

        logger.lifecycle(
            "$name: ${result.componentCount} component(s), ${result.definitionCount} definition(s) " +
                "indexed, ${result.findings.size} finding(s). Report: ${reportFile.get().asFile}"
        )

        if (result.findings.isEmpty()) return

        if (modeValue == "strict") {
            throw GradleException(
                "$name found ${result.findings.size} component include problem(s):\n\n$report"
            )
        }
        report.lineSequence().filter { it.isNotBlank() }.forEach { logger.warn("$name: $it") }
    }
}
