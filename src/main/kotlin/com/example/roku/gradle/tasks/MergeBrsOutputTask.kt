package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File

/**
 * Merges Kotlin-compiled BrightScript output with BrighterScript staging output.
 *
 * This task enables hybrid builds where:
 * - BrighterScript compiles existing .bs/.brs files to a staging directory
 * - Kotlin compiles .kt files to BrightScript
 * - This task merges both outputs into a single directory for packaging
 *
 * Kotlin output takes precedence over BrighterScript output for files with the same name,
 * enabling incremental migration from BrighterScript to Kotlin.
 */
@CacheableTask
abstract class MergeBrsOutputTask : DefaultTask() {

    /**
     * Directory containing Kotlin-compiled .brs source files.
     * Typically: build/brs/brs/main/source/
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinBrsSource: DirectoryProperty

    /**
     * Directory containing Kotlin-compiled component .brs files.
     * Typically: build/brs/brs/main/components/
     *
     * Note: Using @Internal instead of @InputDirectory because the directory
     * may not exist if there are no Kotlin SceneGraph components.
     */
    @get:Internal
    abstract val kotlinBrsComponents: DirectoryProperty

    /**
     * Directory containing BrighterScript staging output.
     * Typically: out/tablo-fast/ or similar
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val brighterScriptStaging: DirectoryProperty

    /**
     * Output directory for merged content.
     * Typically: build/merged-staging/
     */
    @get:OutputDirectory
    abstract val mergedOutput: DirectoryProperty

    /**
     * Kotlin stdlib .brs runtime files.
     * These are extracted from the kotlin-stdlib-brs runtime JAR.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val stdlibBrsFiles: ConfigurableFileCollection

    @TaskAction
    fun merge() {
        val output = mergedOutput.get().asFile
        val bsStaging = brighterScriptStaging.get().asFile
        val ktSource = kotlinBrsSource.get().asFile

        // Clean output directory
        if (output.exists()) {
            output.deleteRecursively()
        }
        output.mkdirs()

        // Step 1: Copy BrighterScript staging as base layer
        if (bsStaging.exists()) {
            logger.lifecycle("Copying BrighterScript staging from: ${bsStaging.absolutePath}")
            bsStaging.copyRecursively(output)
            logger.lifecycle("  Copied ${countFiles(output)} files")
        } else {
            logger.warn("BrighterScript staging directory does not exist: ${bsStaging.absolutePath}")
        }

        // Step 2: Overlay Kotlin source files into source/
        val sourceDir = File(output, "source")
        if (!sourceDir.exists()) {
            sourceDir.mkdirs()
        }

        // Check if hybrid mode already copied Kotlin files via BSC
        // In hybrid mode, copyKotlinToBrighterScript puts files in source/kotlin/
        // BSC then compiles them, so they're already in the output
        val kotlinSubdir = File(sourceDir, "kotlin")
        val hybridModeActive = kotlinSubdir.exists() &&
            kotlinSubdir.listFiles()?.any { it.extension == "brs" } == true

        if (hybridModeActive) {
            logger.lifecycle("Skipping Kotlin source overlay - already processed by BSC (hybrid mode)")
        } else if (ktSource.exists()) {
            var overlayCount = 0
            ktSource.walkTopDown()
                .filter { it.isFile && it.extension == "brs" }
                .forEach { file ->
                    val relativePath = file.relativeTo(ktSource).path
                    val destFile = File(sourceDir, relativePath)
                    destFile.parentFile.mkdirs()

                    val isOverwrite = destFile.exists()
                    file.copyTo(destFile, overwrite = true)
                    overlayCount++

                    if (isOverwrite) {
                        logger.lifecycle("  Overlaid Kotlin source (replaced BS): ${file.name}")
                    } else {
                        logger.lifecycle("  Added Kotlin source: ${file.name}")
                    }
                }
            logger.lifecycle("Merged $overlayCount Kotlin source files")
        }

        // Step 3: Overlay Kotlin component files alongside their XML counterparts
        if (kotlinBrsComponents.isPresent) {
            val ktComponents = kotlinBrsComponents.get().asFile
            if (ktComponents.exists()) {
                val componentsDir = File(output, "components")

                // Build map of componentName -> directory path for XML files
                val xmlDirMap = mutableMapOf<String, File>()
                if (componentsDir.exists()) {
                    componentsDir.walkTopDown()
                        .filter { it.isFile && it.extension == "xml" }
                        .forEach { xmlFile ->
                            val baseName = xmlFile.nameWithoutExtension
                            xmlDirMap[baseName] = xmlFile.parentFile
                        }
                }

                var componentCount = 0
                ktComponents.walkTopDown()
                    .filter { it.isFile && it.extension == "brs" }
                    .forEach { brsFile ->
                        val baseName = brsFile.nameWithoutExtension
                        val targetDir = xmlDirMap[baseName]

                        if (targetDir != null) {
                            // Place alongside the matching XML file
                            val destFile = File(targetDir, brsFile.name)
                            val isOverwrite = destFile.exists()
                            brsFile.copyTo(destFile, overwrite = true)
                            componentCount++

                            if (isOverwrite) {
                                logger.lifecycle("  Overlaid Kotlin component (replaced BS): ${brsFile.name} -> ${targetDir.relativeTo(output)}")
                            } else {
                                logger.lifecycle("  Added Kotlin component: ${brsFile.name} -> ${targetDir.relativeTo(output)}")
                            }
                        } else {
                            // No matching XML found - this might be a new Kotlin-only component
                            // Place in components root or a kotlin subdirectory
                            val destFile = File(componentsDir, brsFile.name)
                            destFile.parentFile.mkdirs()
                            brsFile.copyTo(destFile, overwrite = true)
                            componentCount++
                            logger.lifecycle("  Added Kotlin component (no XML match): ${brsFile.name}")
                        }
                    }
                logger.lifecycle("Merged $componentCount Kotlin component files")
            }
        }

        // Step 4: Copy Kotlin stdlib runtime .brs files into source/
        // Skip this step if stdlib files already exist in a subdirectory (e.g., source/kotlin/)
        // This happens in hybrid mode where copyKotlinToBrighterScript already added them
        // Reuse hybridModeActive check from Step 2
        if (hybridModeActive) {
            logger.lifecycle("Skipping stdlib copy - already present in source/kotlin/ (hybrid mode)")
        } else {
            val stdlibFiles = stdlibBrsFiles.files.flatMap { file ->
                if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile && it.extension == "brs" }.toList()
                } else if (file.extension == "brs") {
                    listOf(file)
                } else {
                    emptyList()
                }
            }

            if (stdlibFiles.isNotEmpty()) {
                var stdlibCount = 0
                var skippedCount = 0
                var emptyCount = 0
                stdlibFiles.forEach { brsFile ->
                    // Skip empty files - they cause BrightScript compilation errors
                    if (brsFile.length() == 0L) {
                        emptyCount++
                        logger.debug("  Skipped empty stdlib file: ${brsFile.name}")
                        return@forEach
                    }

                    val destFile = File(sourceDir, brsFile.name)
                    // Case-insensitive check for existing files
                    val existingFile = sourceDir.listFiles()?.find {
                        it.name.equals(brsFile.name, ignoreCase = true)
                    }
                    if (existingFile != null) {
                        skippedCount++
                        logger.lifecycle("  Skipped stdlib file (already exists): ${brsFile.name}")
                    } else {
                        brsFile.copyTo(destFile)
                        stdlibCount++
                    }
                }
                logger.lifecycle("Added $stdlibCount Kotlin stdlib runtime files (skipped $skippedCount existing, $emptyCount empty)")
            }
        }

        logger.lifecycle("Merge complete: ${output.absolutePath}")
        logger.lifecycle("  Total files: ${countFiles(output)}")
    }

    private fun countFiles(dir: File): Int {
        return dir.walkTopDown().filter { it.isFile }.count()
    }
}
