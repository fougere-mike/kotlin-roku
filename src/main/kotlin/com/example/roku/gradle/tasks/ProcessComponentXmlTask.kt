package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File

/**
 * Processes SceneGraph component XML files to inject script imports.
 *
 * For each XML file, this task:
 * 1. Finds the corresponding compiled .brs file (if any)
 * 2. Analyzes the .brs to determine ALL dependencies:
 *    - Stdlib files (e.g., ArrayList.brs, coreRuntime.brs)
 *    - User code from main source (e.g., User.brs, ApiClient.brs)
 *    - Other component files
 * 3. Injects <script> tags for all required .brs files
 * 4. Writes processed XML to the output directory
 */
@CacheableTask
abstract class ProcessComponentXmlTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceXmlDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val compiledComponentsDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val compiledMainSource: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stdlibBrsFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputXmlDir: DirectoryProperty

    /**
     * Result of dependency analysis for a component.
     */
    data class DependencyResult(
        val stdlibFiles: Set<String>,      // e.g., {"ArrayList.brs", "coreRuntime.brs"}
        val mainSourceFiles: Set<String>,  // e.g., {"User.brs", "ApiClient.brs"}
        val componentFiles: Set<String>    // e.g., {"OtherComponent.brs"}
    )

    @TaskAction
    fun processXml() {
        val sourceDir = sourceXmlDir.get().asFile
        val outputDir = outputXmlDir.get().asFile
        val compiledDir = if (compiledComponentsDir.isPresent) compiledComponentsDir.get().asFile else null
        val mainSourceDir = if (compiledMainSource.isPresent) compiledMainSource.get().asFile else null

        // Build sets of available files for matching
        val stdlibFileNames = stdlibBrsFiles.files
            .filter { it.exists() && it.extension == "brs" }
            .map { it.name }
            .toSet()

        val mainSourceFileNames = mainSourceDir?.walkTopDown()
            ?.filter { it.isFile && it.extension == "brs" }
            ?.map { it.name }
            ?.toSet() ?: emptySet()

        val componentFileNames = compiledDir?.walkTopDown()
            ?.filter { it.isFile && it.extension == "brs" }
            ?.map { it.relativeTo(compiledDir).path }
            ?.toSet() ?: emptySet()

        // Process each XML file
        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { xmlFile ->
                val relativePath = xmlFile.relativeTo(sourceDir)
                val outputFile = File(outputDir, relativePath.path)

                // Find corresponding .brs file
                val brsRelativePath = relativePath.path.replace(".xml", ".brs")
                val componentBrsFile = compiledDir?.let { File(it, brsRelativePath) }
                val hasComponentBrs = componentBrsFile?.exists() == true

                // Analyze all dependencies
                val dependencies = if (hasComponentBrs && componentBrsFile != null) {
                    analyzeDependencies(
                        componentBrsFile,
                        stdlibFileNames,
                        mainSourceFileNames,
                        componentFileNames,
                        brsRelativePath
                    )
                } else {
                    DependencyResult(emptySet(), emptySet(), emptySet())
                }

                // Read and process the XML
                val xmlContent = xmlFile.readText()
                val processedXml = injectScriptTags(
                    xmlContent,
                    relativePath.path,
                    hasComponentBrs,
                    dependencies
                )

                // Write to output
                outputFile.parentFile.mkdirs()
                outputFile.writeText(processedXml)

                val totalDeps = dependencies.stdlibFiles.size + dependencies.mainSourceFiles.size + dependencies.componentFiles.size
                if (hasComponentBrs || totalDeps > 0) {
                    logger.lifecycle("Processed ${relativePath.path}: " +
                        "${if (hasComponentBrs) 1 else 0} component, " +
                        "${dependencies.mainSourceFiles.size} main source, " +
                        "${dependencies.stdlibFiles.size} stdlib, " +
                        "${dependencies.componentFiles.size} other components")
                }
            }
    }

    /**
     * Analyzes a compiled BRS file to find all dependencies.
     * Looks for function calls that match available .brs files.
     */
    private fun analyzeDependencies(
        brsFile: File,
        stdlibFileNames: Set<String>,
        mainSourceFileNames: Set<String>,
        componentFileNames: Set<String>,
        currentComponentPath: String
    ): DependencyResult {
        val brsContent = brsFile.readText()

        // Extract all potential module/class names from function calls
        // Pattern: ModuleName_functionName() or ClassName_methodName()
        val functionCallPattern = Regex("\\b(\\w+)_\\w+\\s*\\(")
        val calledModules = functionCallPattern.findAll(brsContent)
            .map { it.groupValues[1] }
            .toSet()

        // Match against stdlib files
        val requiredStdlib = mutableSetOf<String>()
        for (stdlibFile in stdlibFileNames) {
            val baseName = stdlibFile.removeSuffix(".brs")
            if (calledModules.contains(baseName)) {
                requiredStdlib.add(stdlibFile)
            }
        }

        // Always include core runtime files for any component with code
        val coreRuntimeFiles = stdlibFileNames.filter { name ->
            name.equals("coreRuntime.brs", ignoreCase = true) ||
            name.equals("intrinsics.brs", ignoreCase = true) ||
            name.equals("primitives.brs", ignoreCase = true) ||
            name.equals("Kotlin.brs", ignoreCase = true)
        }
        requiredStdlib.addAll(coreRuntimeFiles)

        // Match against main source files
        val requiredMainSource = mutableSetOf<String>()
        for (sourceFile in mainSourceFileNames) {
            val baseName = sourceFile.removeSuffix(".brs")
            if (calledModules.contains(baseName)) {
                requiredMainSource.add(sourceFile)
            }
        }

        // Match against other component files (excluding self)
        val requiredComponents = mutableSetOf<String>()
        for (componentFile in componentFileNames) {
            if (componentFile == currentComponentPath) continue
            val baseName = File(componentFile).nameWithoutExtension
            if (calledModules.contains(baseName)) {
                requiredComponents.add(componentFile)
            }
        }

        return DependencyResult(requiredStdlib, requiredMainSource, requiredComponents)
    }

    /**
     * Injects <script> tags into XML content.
     * Inserts tags right after the opening <component> tag.
     */
    private fun injectScriptTags(
        xmlContent: String,
        xmlRelativePath: String,
        hasComponentBrs: Boolean,
        dependencies: DependencyResult
    ): String {
        val totalDeps = dependencies.stdlibFiles.size + dependencies.mainSourceFiles.size + dependencies.componentFiles.size
        if (!hasComponentBrs && totalDeps == 0) {
            return xmlContent
        }

        // Build the script tags to inject
        val scriptTags = buildString {
            // Component's own BRS file first
            if (hasComponentBrs) {
                val brsPath = xmlRelativePath.replace(".xml", ".brs")
                appendLine("  <script type=\"text/brightscript\" uri=\"pkg:/components/$brsPath\"/>")
            }

            // Main source files (user code)
            for (sourceFile in dependencies.mainSourceFiles.sorted()) {
                appendLine("  <script type=\"text/brightscript\" uri=\"pkg:/source/$sourceFile\"/>")
            }

            // Other component files
            for (componentFile in dependencies.componentFiles.sorted()) {
                appendLine("  <script type=\"text/brightscript\" uri=\"pkg:/components/$componentFile\"/>")
            }

            // Stdlib files
            for (stdlibFile in dependencies.stdlibFiles.sorted()) {
                appendLine("  <script type=\"text/brightscript\" uri=\"pkg:/source/$stdlibFile\"/>")
            }
        }

        // Find the position to inject (after <component ...>)
        val componentTagPattern = Regex("<component[^>]*>", RegexOption.IGNORE_CASE)
        val match = componentTagPattern.find(xmlContent)

        return if (match != null) {
            val insertPosition = match.range.last + 1
            buildString {
                append(xmlContent.substring(0, insertPosition))
                appendLine()
                append(scriptTags)
                append(xmlContent.substring(insertPosition))
            }
        } else {
            // No <component> tag found, return unchanged
            logger.warn("No <component> tag found in $xmlRelativePath, skipping script injection")
            xmlContent
        }
    }
}
