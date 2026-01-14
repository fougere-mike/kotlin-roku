package com.example.roku.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class RokuExtension @Inject constructor(project: Project) {
    // App metadata
    abstract val appName: Property<String>
    abstract val appId: Property<String>
    abstract val appVersion: Property<String>

    // BRS compilation options
    abstract val minRokuOS: Property<String>
    abstract val debugMode: Property<Boolean>

    // Roku project structure
    abstract val manifestFile: RegularFileProperty
    abstract val imagesDir: DirectoryProperty
    abstract val componentsDir: DirectoryProperty
    abstract val fontsDir: DirectoryProperty
    abstract val assetsDir: DirectoryProperty

    // Device deployment
    abstract val deviceIP: Property<String>
    abstract val devicePassword: Property<String>

    // BrighterScript integration
    abstract val brighterScriptEnabled: Property<Boolean>
    abstract val brighterScriptStagingDir: DirectoryProperty
    abstract val brighterScriptCommand: Property<String>
    abstract val brighterScriptSourceDir: DirectoryProperty

    init {
        appName.convention(project.name)
        appVersion.convention("1.0.0")
        minRokuOS.convention("9.4")
        debugMode.convention(false)
        manifestFile.convention(project.layout.projectDirectory.file("manifest"))
        imagesDir.convention(project.layout.projectDirectory.dir("images"))
        componentsDir.convention(project.layout.projectDirectory.dir("components"))
        fontsDir.convention(project.layout.projectDirectory.dir("fonts"))
        assetsDir.convention(project.layout.projectDirectory.dir("assets"))

        // BrighterScript defaults
        brighterScriptEnabled.convention(false)
        brighterScriptStagingDir.convention(project.layout.projectDirectory.dir("out"))
        brighterScriptCommand.convention("npx bsc")
        brighterScriptSourceDir.convention(project.layout.projectDirectory.dir("src"))
    }
}
