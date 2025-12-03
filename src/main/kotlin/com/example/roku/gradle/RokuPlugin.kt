package com.example.roku.gradle

import com.example.roku.gradle.tasks.InstallRokuTask
import com.example.roku.gradle.tasks.PackageRokuTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.targets.brs.KotlinBrsCompile
import java.util.Properties

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

        // Create a configuration for the BRS stdlib (compile-time klib)
        val brsStdlibConfig = project.configurations.create("kotlinBrsStdlib") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS stdlib dependency
        val kotlinVersion = project.findProperty("kotlin.version") ?: "2.1.255-SNAPSHOT"
        project.dependencies.add(
            "kotlinBrsStdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-brs:$kotlinVersion"
        )

        // Create a configuration for the BRS stdlib runtime files (.brs files for packaging)
        val brsRuntimeConfig = project.configurations.create("kotlinBrsRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS stdlib runtime JAR dependency (contains .brs files)
        project.dependencies.add(
            "kotlinBrsRuntime",
            "org.jetbrains.kotlin:kotlin-stdlib-brs:$kotlinVersion:brs-runtime"
        )

        // Configure the BRS compile task with the compiler JAR and stdlib
        project.afterEvaluate {
            project.tasks.withType(KotlinBrsCompile::class.java).configureEach {
                val compilerJarFile = brsCompilerConfig.singleFile
                compilerJar.set(compilerJarFile)

                // Add stdlib to libraries
                libraries.from(brsStdlibConfig)
            }
        }

        // Register tasks
        registerTasks(project, rokuExtension, brsRuntimeConfig)
    }

    private fun registerTasks(
        project: Project,
        extension: RokuExtension,
        brsRuntimeConfig: org.gradle.api.artifacts.Configuration
    ) {
        // Load device config from local.properties / environment variables
        val localProps = loadLocalProperties(project)
        val deviceIp = project.provider {
            localProps["roku.deviceIp"] as? String
                ?: System.getenv("ROKU_DEVICE_IP")
                ?: ""
        }
        val devicePassword = project.provider {
            localProps["roku.devicePassword"] as? String
                ?: System.getenv("ROKU_PASSWORD")
                ?: ""
        }

        // Set conventions on extension so users can access them if needed
        extension.deviceIP.convention(deviceIp)
        extension.devicePassword.convention(devicePassword)

        // Package task: creates Roku .zip
        val packageTask = project.tasks.register("packageRoku", PackageRokuTask::class.java).apply {
            configure {
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

                // Include stdlib .brs runtime files from the resolved JAR
                stdlibBrs.from(
                    project.provider {
                        val runtimeJar = brsRuntimeConfig.resolve().firstOrNull()
                        if (runtimeJar != null && runtimeJar.exists()) {
                            project.zipTree(runtimeJar)
                        } else {
                            project.files()
                        }
                    }
                )
            }
        }

        // Install task: deploys .zip to Roku device
        project.tasks.register("installRoku", InstallRokuTask::class.java).configure {
            group = "roku"
            description = "Install Roku app to device"

            dependsOn(packageTask)

            zipFile.set(packageTask.flatMap { it.outputZip })
            this.deviceIp.set(extension.deviceIP)
            this.devicePassword.set(extension.devicePassword)
        }
    }

    private fun loadLocalProperties(project: Project): Properties {
        val props = Properties()
        val localPropsFile = project.rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { props.load(it) }
        }
        return props
    }
}
