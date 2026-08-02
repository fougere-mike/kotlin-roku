package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
 * Same-named files arriving from different origins FAIL the build, naming both
 * origin paths (mirroring PackageRokuTask's duplicate-entry guard — packageHybridRoku
 * zips from this single merged tree, so the zip-level guard can never fire for hybrid
 * packages and collisions must be caught here). Byte-identical duplicates are skipped
 * as benign. When migrating a file to Kotlin, delete the superseded BrighterScript
 * source instead of relying on overlay precedence.
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

        // Origin of every merged entry, keyed by case-insensitive relative path
        // (Roku package paths are case-insensitive). See stageEntry.
        val entryOrigins = mutableMapOf<String, File>()

        // Step 1: Copy BrighterScript staging as base layer
        if (bsStaging.exists()) {
            logger.lifecycle("Copying BrighterScript staging from: ${bsStaging.absolutePath}")
            bsStaging.copyRecursively(output)
            bsStaging.walkTopDown().filter { it.isFile }.forEach { file ->
                entryOrigins[file.relativeTo(bsStaging).path.lowercase()] = file
            }
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
                    val destFile = File(sourceDir, file.relativeTo(ktSource).path)
                    if (stageEntry(entryOrigins, output, file, destFile)) {
                        overlayCount++
                        logger.lifecycle("  Added Kotlin source: ${file.name}")
                    } else {
                        logger.lifecycle("  Skipped identical duplicate: ${file.name}")
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
                            if (stageEntry(entryOrigins, output, brsFile, destFile)) {
                                componentCount++
                                logger.lifecycle("  Added Kotlin component: ${brsFile.name} -> ${targetDir.relativeTo(output)}")
                            } else {
                                logger.lifecycle("  Skipped identical duplicate: ${brsFile.name}")
                            }
                        } else {
                            // No matching XML found - this might be a new Kotlin-only component
                            // Place in components root or a kotlin subdirectory
                            val destFile = File(componentsDir, brsFile.name)
                            if (stageEntry(entryOrigins, output, brsFile, destFile)) {
                                componentCount++
                                logger.lifecycle("  Added Kotlin component (no XML match): ${brsFile.name}")
                            } else {
                                logger.lifecycle("  Skipped identical duplicate: ${brsFile.name}")
                            }
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

                    // A same-named file already merged from another origin with DIFFERENT
                    // content fails here; the old skip-if-exists silently dropped a needed
                    // stdlib file whenever anything else claimed its name first.
                    if (stageEntry(entryOrigins, output, brsFile, File(sourceDir, brsFile.name))) {
                        stdlibCount++
                    } else {
                        skippedCount++
                        logger.lifecycle("  Skipped identical stdlib duplicate: ${brsFile.name}")
                    }
                }
                logger.lifecycle("Added $stdlibCount Kotlin stdlib runtime files (skipped $skippedCount identical, $emptyCount empty)")
            }
        }

        logger.lifecycle("Merge complete: ${output.absolutePath}")
        logger.lifecycle("  Total files: ${countFiles(output)}")
    }

    private fun countFiles(dir: File): Int {
        return dir.walkTopDown().filter { it.isFile }.count()
    }

    companion object {
        /**
         * Claims [dest]'s merged path for [origin] and copies the file, guarding against
         * cross-origin collisions (pure logic, unit-tested separately from the Gradle
         * machinery). [entryOrigins] maps each already-merged relative path (lowercased —
         * Roku package paths are case-insensitive) to the file it came from.
         *
         * Returns true when the file was copied, false when it was skipped as a
         * byte-identical duplicate of an already-merged file (benign — unlike a zip,
         * the merge tree tolerates the same content arriving twice).
         *
         * @throws GradleException when a DIFFERENT file already claimed the path,
         *   naming both origins (mirrors PackageRokuTask's duplicate-entry guard).
         */
        internal fun stageEntry(
            entryOrigins: MutableMap<String, File>,
            outputRoot: File,
            origin: File,
            dest: File,
        ): Boolean {
            val entryPath = dest.relativeTo(outputRoot).path
            val previous = entryOrigins.putIfAbsent(entryPath.lowercase(), origin)
            if (previous != null) {
                if (previous.readBytes().contentEquals(origin.readBytes())) {
                    return false
                }
                throw GradleException(
                    "Duplicate merged-staging entry '$entryPath' staged from two different origins:\n" +
                        "  - ${previous.absolutePath}\n" +
                        "  - ${origin.absolutePath}\n" +
                        "Both would land on the same case-insensitive path in the Roku package. " +
                        "Rename or remove one of them before packaging (when migrating a file to " +
                        "Kotlin, delete the superseded BrighterScript source)."
                )
            }
            dest.parentFile?.mkdirs()
            // overwrite=false: the origin map is the source of truth, so an on-disk hit the
            // map missed is a staging bug that must surface, not be papered over.
            origin.copyTo(dest, overwrite = false)
            return true
        }
    }
}
