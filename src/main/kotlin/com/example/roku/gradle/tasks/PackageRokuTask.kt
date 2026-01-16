package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CacheableTask
abstract class PackageRokuTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledBrs: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val manifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val images: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val imagesBaseDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val components: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val componentsBaseDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val fontsBaseDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val assetsBaseDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val compiledComponents: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val processedXmlDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val stdlibBrs: ConfigurableFileCollection

    /**
     * Additional directories to include (data/, locale/, etc.)
     * Each pair is (ConfigurableFileCollection, prefix)
     */
    @get:Internal
    val additionalDirs: MutableList<Pair<ConfigurableFileCollection, String>> = mutableListOf()

    /**
     * Base directory for additional dirs to compute relative paths
     */
    @get:Internal
    var additionalDirsBase: java.io.File? = null

    @get:OutputFile
    abstract val outputZip: RegularFileProperty

    @TaskAction
    fun packageApp() {
        val zipFile = outputZip.get().asFile
        zipFile.parentFile.mkdirs()

        ZipOutputStream(zipFile.outputStream()).use { zip ->
            // Add manifest if exists
            if (manifest.isPresent && manifest.get().asFile.exists()) {
                addToZip(zip, manifest.get().asFile, "manifest")
            }

            // Add compiled .brs files to source/
            val brsDir = compiledBrs.get().asFile
            if (brsDir.exists()) {
                brsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relativePath = file.relativeTo(brsDir).path
                    addToZip(zip, file, "source/$relativePath")
                }
            }

            // Add stdlib .brs runtime files to source/
            stdlibBrs.files.filter { it.exists() && it.extension == "brs" }.forEach { file ->
                addToZip(zip, file, "source/${file.name}")
            }

            // Add components/ (SceneGraph XML files) with directory structure
            addDirectoryToZip(zip, components, componentsBaseDir, "components")

            // Add compiled component .brs files - place alongside their XML files
            if (compiledComponents.isPresent) {
                val componentsDir = compiledComponents.get().asFile

                // Build map: baseName -> relative XML directory path
                // Include both processed user XMLs and compiler-generated XMLs
                val xmlDirMap = mutableMapOf<String, String>()

                // Add processed user XMLs (from processedXmlDir)
                if (processedXmlDir.isPresent) {
                    val xmlDir = processedXmlDir.get().asFile
                    xmlDir.walkTopDown()
                        .filter { it.isFile && it.extension == "xml" }
                        .forEach { xmlFile ->
                            val baseName = xmlFile.nameWithoutExtension
                            val relativeDir = xmlFile.parentFile.relativeTo(xmlDir).path
                            xmlDirMap[baseName] = relativeDir
                        }
                }

                // Add compiler-generated XMLs (from components/components/ subdirectory)
                // These are for @Component classes that don't have user-authored XML files
                val compilerXmlDir = File(componentsDir, "components")
                if (compilerXmlDir.exists()) {
                    compilerXmlDir.walkTopDown()
                        .filter { it.isFile && it.extension == "xml" }
                        .toList()
                        .forEach { xmlFile ->
                            val baseName = xmlFile.nameWithoutExtension
                            val relativeDir = xmlFile.parentFile.relativeTo(compilerXmlDir).path
                            // Don't override user XMLs - they take precedence
                            if (!xmlDirMap.containsKey(baseName)) {
                                xmlDirMap[baseName] = relativeDir
                                // Also add compiler-generated XML to package (user XMLs already added above)
                                val targetPath = "components/$relativeDir/${xmlFile.name}"
                                addToZip(zip, xmlFile, targetPath)
                            }
                        }
                }

                // Package each BRS file alongside its XML
                if (componentsDir.exists()) {
                    componentsDir.walkTopDown()
                        .filter { it.isFile && it.extension == "brs" }
                        .forEach { brsFile ->
                            // Strip "Kt" suffix to match component name (e.g., "ShelfViewKt" -> "ShelfView")
                            val baseName = brsFile.nameWithoutExtension.removeSuffix("Kt")

                            // Check for exact component match OR layout file for a component
                            val componentName = when {
                                xmlDirMap.containsKey(baseName) -> baseName
                                baseName.endsWith("_Layout") -> {
                                    // Check if this is a layout file for a known component
                                    val possibleComponent = baseName.removeSuffix("_Layout")
                                    if (xmlDirMap.containsKey(possibleComponent)) possibleComponent else null
                                }
                                else -> null
                            }

                            val targetDir = componentName?.let { xmlDirMap[it] } ?: ""
                            val targetPath = if (targetDir.isEmpty()) {
                                "components/${brsFile.name}"
                            } else {
                                "components/$targetDir/${brsFile.name}"
                            }
                            addToZip(zip, brsFile, targetPath)
                        }
                }
            } else if (false) { // Legacy fallback - no longer needed
                // Fallback: use original behavior if no XML dir provided
                val componentsDir = compiledComponents.get().asFile
                if (componentsDir.exists()) {
                    componentsDir.walkTopDown().filter { it.isFile && it.extension == "brs" }.forEach { file ->
                        val relativePath = file.relativeTo(componentsDir).path
                        addToZip(zip, file, "components/$relativePath")
                    }
                }
            }

            // Add images/ with directory structure
            addDirectoryToZip(zip, images, imagesBaseDir, "images")

            // Add fonts/ with directory structure
            addDirectoryToZip(zip, fonts, fontsBaseDir, "fonts")

            // Add assets/ with directory structure
            addDirectoryToZip(zip, assets, assetsBaseDir, "assets")

            // Add any additional directories (data/, locale/, etc.)
            additionalDirs.forEach { (files, prefix) ->
                val base = additionalDirsBase
                files.files.filter { it.exists() && it.isFile }.forEach { file ->
                    val relativePath = if (base != null && file.startsWith(base)) {
                        file.relativeTo(base).path
                    } else {
                        "$prefix/${file.name}"
                    }
                    addToZip(zip, file, relativePath)
                }
            }
        }

        logger.lifecycle("Created Roku package: ${zipFile.absolutePath}")
    }

    private fun addToZip(zip: ZipOutputStream, file: java.io.File, entryPath: String) {
        zip.putNextEntry(ZipEntry(entryPath))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addDirectoryToZip(
        zip: ZipOutputStream,
        files: ConfigurableFileCollection,
        baseDir: DirectoryProperty,
        targetPrefix: String
    ) {
        val base = if (baseDir.isPresent) baseDir.get().asFile else null
        files.files.filter { it.exists() && it.isFile }.forEach { file ->
            val relativePath = if (base != null && file.startsWith(base)) {
                file.relativeTo(base).path
            } else {
                file.name
            }
            addToZip(zip, file, "$targetPrefix/$relativePath")
        }
    }
}
