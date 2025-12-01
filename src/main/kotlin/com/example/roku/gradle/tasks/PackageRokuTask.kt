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

            // Add images/
            images.files.filter { it.exists() }.forEach { file ->
                addToZip(zip, file, "images/${file.name}")
            }
        }

        logger.lifecycle("Created Roku package: ${zipFile.absolutePath}")
    }

    private fun addToZip(zip: ZipOutputStream, file: java.io.File, entryPath: String) {
        zip.putNextEntry(ZipEntry(entryPath))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
