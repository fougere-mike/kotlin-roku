package com.example.roku.gradle

import com.example.roku.gradle.tasks.PackageRokuTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper

class RokuPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Apply multiplatform plugin
        project.plugins.apply(KotlinMultiplatformPluginWrapper::class.java)

        // Create Roku extension
        val rokuExtension = project.extensions.create(
            "roku",
            RokuExtension::class.java,
            project
        )

        // Configure BRS target immediately after plugin apply
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

        // Apply BRS target
        kotlin.brs()

        // Register tasks
        registerTasks(project, rokuExtension)
    }

    private fun registerTasks(project: Project, extension: RokuExtension) {
        // Package task: creates Roku .zip
        project.tasks.register("packageRoku", PackageRokuTask::class.java).configure {
            group = "roku"
            description = "Package Roku app as .zip"

            // Depend on the BRS compile task (uses default naming convention)
            dependsOn("compileKotlinBrs")

            compiledBrs.set(project.layout.buildDirectory.dir("brs/brs/main"))
            manifest.set(extension.manifestFile)
            images.from(extension.imagesDir)
            outputZip.set(
                extension.appName.flatMap { appName ->
                    project.layout.buildDirectory.file("roku/$appName.zip")
                }
            )
        }
    }
}
