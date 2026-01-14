package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.ByteArrayOutputStream

/**
 * Compiles BrighterScript source files using the bsc compiler.
 *
 * This task wraps the BrighterScript compiler (bsc) to enable hybrid builds
 * where both BrighterScript and Kotlin sources are compiled and merged.
 *
 * The task tracks:
 * - bsconfig.json as input for configuration changes
 * - Source directory for .bs/.brs/.xml file changes
 * - Staging directory as output for up-to-date checking
 *
 * Note: This task is not cacheable because bsc manages its own caching
 * and the output directory is outside the build directory.
 */
abstract class CompileBrighterScriptTask : DefaultTask() {

    /**
     * The bsconfig.json file that configures the BrighterScript compiler.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val bsConfigFile: RegularFileProperty

    /**
     * The source directory containing BrighterScript files.
     * Typically: src/
     *
     * Note: Using @Internal because the source directory may contain
     * Kotlin source files that are also tracked by compileKotlinBrs,
     * and bsc handles its own change detection via bsconfig.json.
     */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /**
     * The staging directory where bsc outputs compiled files.
     * This is read from bsconfig.json's stagingDir property.
     *
     * Note: Using @Internal instead of @OutputDirectory because:
     * 1. The output directory is outside the build directory (typically out/)
     * 2. bsc manages its own incremental compilation
     * 3. Prevents Gradle from detecting false dependency conflicts
     */
    @get:Internal
    abstract val stagingDir: DirectoryProperty

    /**
     * The command to run the BrighterScript compiler.
     * Default: "npx bsc"
     */
    @get:Input
    abstract val bscCommand: Property<String>

    /**
     * Working directory for running the bsc command.
     * Default: project directory
     *
     * Note: Using @Internal because the working directory doesn't affect
     * the task's outputs and tracking it can cause false dependency conflicts.
     */
    @get:Internal
    abstract val workingDir: DirectoryProperty

    init {
        bscCommand.convention("npx bsc")
    }

    @TaskAction
    fun compile() {
        val workDir = workingDir.get().asFile
        val command = bscCommand.get()

        logger.lifecycle("Compiling BrighterScript sources...")
        logger.lifecycle("  Working directory: ${workDir.absolutePath}")
        logger.lifecycle("  Command: $command")

        // Split command into parts for ProcessBuilder
        val commandParts = command.split("\\s+".toRegex())

        val processBuilder = ProcessBuilder(commandParts)
            .directory(workDir)
            .redirectErrorStream(true)

        // Inherit environment variables (needed for npx/node)
        processBuilder.environment().putAll(System.getenv())

        val process = processBuilder.start()
        val output = ByteArrayOutputStream()

        // Stream output to both console and capture
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                logger.lifecycle("  [bsc] $line")
                output.write(line.toByteArray())
                output.write('\n'.code)
            }
        }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw org.gradle.api.GradleException(
                "BrighterScript compilation failed with exit code $exitCode\n${output.toString()}"
            )
        }

        val staging = stagingDir.get().asFile
        if (staging.exists()) {
            val fileCount = staging.walkTopDown().filter { it.isFile }.count()
            logger.lifecycle("BrighterScript compilation complete: $fileCount files in ${staging.absolutePath}")
        } else {
            logger.warn("BrighterScript staging directory was not created: ${staging.absolutePath}")
        }
    }
}
