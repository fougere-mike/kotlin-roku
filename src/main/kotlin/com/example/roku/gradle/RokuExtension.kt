package com.example.roku.gradle

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `roku { }` block — the ONE configuration surface of a Roku app module,
 * shaped like Android's `android { }`: app metadata, compilation options,
 * project layout, device deployment, and the nested `test { }` / `validation { }`
 * option groups.
 */
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

    /**
     * OPTIONAL directory of hand-written SceneGraph component XML files (default
     * `components/`). Kotlin components live in `src/brsMain/kotlin` like every other
     * class — the compiler generates their XML. Most projects never create this dir.
     */
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

    /** Device-test options: `roku { test { timeout.set(300_000L) } }`. */
    val test: RokuTestExtension = project.objects.newInstance(RokuTestExtension::class.java, project)

    /** Include-closure validation options: `roku { validation { includeMode.set("strict") } }`. */
    val validation: RokuValidationExtension = project.objects.newInstance(RokuValidationExtension::class.java)

    fun test(action: Action<in RokuTestExtension>) = action.execute(test)

    fun validation(action: Action<in RokuValidationExtension>) = action.execute(validation)

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
