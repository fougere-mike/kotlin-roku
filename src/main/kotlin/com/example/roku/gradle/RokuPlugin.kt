package com.example.roku.gradle

import com.example.roku.gradle.tasks.PackageRokuTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.targets.brs.KotlinBrsCompile

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

        // Create a configuration to resolve the BRS compiler JAR
        val brsCompilerConfig = project.configurations.create("brsCompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS compiler dependency
        project.dependencies.add(
            "brsCompiler",
            "org.jetbrains.kotlin:kotlin-compiler-brs:${project.findProperty("kotlin.version") ?: "2.1.255-SNAPSHOT"}"
        )

        // Configure the BRS compile task with the compiler JAR
        project.afterEvaluate {
            project.tasks.withType(KotlinBrsCompile::class.java).configureEach {
                val compilerJarFile = brsCompilerConfig.singleFile
                compilerJar.set(compilerJarFile)
            }
        }

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

            compiledBrs.set(project.layout.buildDirectory.dir("brs/brs/main/source"))
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
