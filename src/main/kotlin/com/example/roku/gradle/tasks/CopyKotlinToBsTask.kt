package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File

/**
 * Copies Kotlin-compiled BrightScript files to the BrighterScript source directory.
 *
 * This enables BrighterScript (bsc) to see and type-check calls to Kotlin-generated functions.
 * By running this task before compileBrighterScript, BSC will have visibility into all
 * Kotlin exports and won't report "undefined function" errors.
 *
 * Files are copied to `source/kotlin/` subdirectory (not a separate kotlin-generated/ folder)
 * to ensure BSC includes them in the main source scope.
 *
 * The output directory should be:
 * 1. Included in bsconfig.json files pattern (usually already covered by "source/**/*")
 * 2. Added to .gitignore (it's a build artifact)
 */
@CacheableTask
abstract class CopyKotlinToBsTask : DefaultTask() {

    /**
     * Directory containing Kotlin-compiled .brs source files.
     * Typically: build/brs/brs/main/source/
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinBrsSource: DirectoryProperty

    /**
     * Kotlin stdlib .brs runtime files.
     * These are extracted from the kotlin-stdlib-brs runtime JAR.
     * Required because Kotlin-generated code references stdlib functions.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val stdlibBrsFiles: ConfigurableFileCollection

    /**
     * Output directory within BrighterScript source tree.
     * Typically: src/source/kotlin/
     *
     * This directory will be cleaned and recreated on each run.
     */
    @get:OutputDirectory
    abstract val brighterScriptKotlinDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val source = kotlinBrsSource.get().asFile
        val dest = brighterScriptKotlinDir.get().asFile

        // Clean destination first to remove stale files from previous builds
        if (dest.exists()) {
            logger.lifecycle("Cleaning existing source/kotlin directory: ${dest.absolutePath}")
            dest.deleteRecursively()
        }
        dest.mkdirs()

        var fileCount = 0

        // Step 1: Copy stdlib runtime files first (Kotlin-generated code depends on these)
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
            var emptyCount = 0
            stdlibFiles.forEach { brsFile ->
                // Skip empty files - they cause BrightScript compilation errors
                if (brsFile.length() == 0L) {
                    emptyCount++
                    return@forEach
                }

                val destFile = File(dest, brsFile.name)
                brsFile.copyTo(destFile, overwrite = true)
                stdlibCount++
            }
            logger.lifecycle("Copied $stdlibCount stdlib .brs files (skipped $emptyCount empty)")
            fileCount += stdlibCount
        }

        // Step 2: Copy Kotlin-compiled user code
        if (source.exists()) {
            source.walkTopDown()
                .filter { it.isFile && it.extension == "brs" }
                .forEach { file ->
                    val relativePath = file.relativeTo(source)
                    val destFile = dest.resolve(relativePath.path)
                    destFile.parentFile.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                    fileCount++
                    logger.lifecycle("  Copied user code: $relativePath")
                }
        } else {
            logger.warn("Kotlin BRS source directory does not exist: ${source.absolutePath}")
            logger.warn("  This may indicate the Kotlin compilation produced no output.")
        }

        logger.lifecycle("Copied $fileCount total .brs files to ${dest.absolutePath}")
        logger.lifecycle("  BrighterScript can now see these functions for type-checking.")
    }
}
