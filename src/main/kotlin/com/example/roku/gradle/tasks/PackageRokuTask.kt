package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
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
            if (compiledComponents.isPresent && processedXmlDir.isPresent) {
                val componentsDir = compiledComponents.get().asFile
                val xmlDir = processedXmlDir.get().asFile

                // Build map: baseName -> relative XML directory path
                val xmlDirMap = mutableMapOf<String, String>()
                xmlDir.walkTopDown()
                    .filter { it.isFile && it.extension == "xml" }
                    .forEach { xmlFile ->
                        val baseName = xmlFile.nameWithoutExtension
                        val relativeDir = xmlFile.parentFile.relativeTo(xmlDir).path
                        xmlDirMap[baseName] = relativeDir
                    }

                // Package each BRS file alongside its XML
                if (componentsDir.exists()) {
                    componentsDir.walkTopDown()
                        .filter { it.isFile && it.extension == "brs" }
                        .forEach { brsFile ->
                            val baseName = brsFile.nameWithoutExtension
                            val targetDir = xmlDirMap[baseName] ?: ""
                            val targetPath = if (targetDir.isEmpty()) {
                                "components/${brsFile.name}"
                            } else {
                                "components/$targetDir/${brsFile.name}"
                            }
                            addToZip(zip, brsFile, targetPath)
                        }
                }
            } else if (compiledComponents.isPresent) {
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
