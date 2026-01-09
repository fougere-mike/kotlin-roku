package com.example.roku.gradle

import com.example.roku.gradle.tasks.DeviceLogTask
import com.example.roku.gradle.tasks.InstallRokuTask
import com.example.roku.gradle.tasks.PackageRokuTask
import com.example.roku.gradle.tasks.ProcessComponentXmlTask
import com.example.roku.gradle.tasks.RunRokuTestsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.ide.IdeDependencyResolver
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport
import org.jetbrains.kotlin.gradle.dsl.KotlinBrsCompilerOptions
import org.jetbrains.kotlin.gradle.targets.brs.KotlinBrsCompile
import org.jetbrains.kotlin.tooling.core.mutableExtrasOf
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

        // Create Roku test extension
        val rokuTestExtension = project.extensions.create(
            "rokuTest",
            RokuTestExtension::class.java,
            project
        )

        // Configure BRS target immediately after plugin apply
        val kotlinExt = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

        // Apply BRS target
        kotlinExt.brs()

        // Create a configuration to resolve the BRS compiler JAR
        val brsCompilerConfig = project.configurations.create("brsCompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS compiler dependency
        val kotlinVersion = project.findProperty("kotlin.version") ?: "2.1.255-SNAPSHOT"
        project.dependencies.add(
            "brsCompiler",
            "org.jetbrains.kotlin:kotlin-compiler-brs:$kotlinVersion"
        )

        // Create a configuration for the BRS stdlib (compile-time klib)
        val brsStdlibConfig = project.configurations.create("kotlinBrsStdlib") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS stdlib dependency
        project.dependencies.add(
            "kotlinBrsStdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-brs:$kotlinVersion"
        )

        // Register IDE import for BRS source sets
        // This allows IntelliJ to properly index Kotlin files targeting BrightScript
        registerIdeImport(project, brsStdlibConfig)

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
        registerTasks(project, rokuExtension, rokuTestExtension, brsRuntimeConfig, brsCompilerConfig, brsStdlibConfig)
    }

    private fun registerTasks(
        project: Project,
        extension: RokuExtension,
        testExtension: RokuTestExtension,
        brsRuntimeConfig: org.gradle.api.artifacts.Configuration,
        brsCompilerConfig: org.gradle.api.artifacts.Configuration,
        brsStdlibConfig: org.gradle.api.artifacts.Configuration
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

        // Stdlib BRS files provider (used by multiple tasks)
        val stdlibBrsFilesProvider = project.provider {
            val runtimeJar = brsRuntimeConfig.resolve().firstOrNull()
            if (runtimeJar != null && runtimeJar.exists()) {
                project.zipTree(runtimeJar)
            } else {
                project.files()
            }
        }

        // Compiled components directory
        val compiledComponentsDirProvider = project.layout.buildDirectory.dir("brs/brs/main/components")

        // Compiled main source directory (for dependency tracking)
        val compiledMainSourceDir = project.layout.buildDirectory.dir("brs/brs/main/source")

        // Component compile task: compiles .kt files from components/ to components/*.brs
        // Create compiler options using reflection (the Default class is internal)
        val compilerOptionsClass = Class.forName("org.jetbrains.kotlin.gradle.dsl.KotlinBrsCompilerOptionsDefault")
        val componentCompilerOptions = project.objects.newInstance(compilerOptionsClass) as KotlinBrsCompilerOptions
        val compileComponentsTask = project.tasks.register(
            "compileKotlinBrsComponents",
            KotlinBrsCompile::class.java,
            componentCompilerOptions
        ).apply {
            configure {
                group = "roku"
                description = "Compile component Kotlin files to BrightScript"

                // Only compile .kt files from components directory
                sources.from(project.fileTree(extension.componentsDir) {
                    include("**/*.kt")
                })

                // Output to components directory structure
                outputDirectory.set(compiledComponentsDirProvider)

                // Must run after main compilation so it can reference main source classes
                dependsOn("compileKotlinBrs")

                // Configure compiler - use lazy providers to avoid afterEvaluate
                compilerJar.fileProvider(project.provider { brsCompilerConfig.singleFile })
                compilerClasspath.from(brsCompilerConfig)
                // Include stdlib + main compiled output as libraries
                libraries.from(brsStdlibConfig)
                libraries.from(compiledMainSourceDir)
            }
        }

        // Process component XML task: injects script imports
        val processComponentXmlTask = project.tasks.register("processComponentXml", ProcessComponentXmlTask::class.java).apply {
            configure {
                group = "roku"
                description = "Process component XML files to inject script imports"

                // Must run after both compile tasks
                dependsOn("compileKotlinBrs")
                dependsOn(compileComponentsTask)

                sourceXmlDir.set(extension.componentsDir)
                compiledComponentsDir.set(compiledComponentsDirProvider)
                compiledMainSource.set(compiledMainSourceDir)
                stdlibBrsFiles.from(stdlibBrsFilesProvider)
                outputXmlDir.set(project.layout.buildDirectory.dir("roku/processedComponents"))
            }
        }

        // Package task: creates Roku .zip
        val packageTask = project.tasks.register("packageRoku", PackageRokuTask::class.java).apply {
            configure {
                group = "roku"
                description = "Package Roku app as .zip"

                // Depend on all compile tasks and XML processing
                dependsOn("compileKotlinBrs")
                dependsOn(compileComponentsTask)
                dependsOn(processComponentXmlTask)

                compiledBrs.set(project.layout.buildDirectory.dir("brs/brs/main/source"))
                manifest.set(extension.manifestFile)

                // Configure images with directory structure preservation
                images.from(extension.imagesDir)
                imagesBaseDir.set(extension.imagesDir)

                // Configure components (processed XML files with script imports)
                components.from(processComponentXmlTask.map { task ->
                    project.fileTree(task.outputXmlDir) { include("**/*.xml") }
                })
                componentsBaseDir.set(processComponentXmlTask.flatMap { it.outputXmlDir })

                // Add compiled component .brs files (place alongside their XML files)
                compiledComponents.set(compiledComponentsDirProvider)
                processedXmlDir.set(processComponentXmlTask.flatMap { it.outputXmlDir })

                // Configure fonts
                fonts.from(extension.fontsDir)
                fontsBaseDir.set(extension.fontsDir)

                // Configure assets
                assets.from(extension.assetsDir)
                assetsBaseDir.set(extension.assetsDir)

                outputZip.set(
                    extension.appName.flatMap { appName ->
                        project.layout.buildDirectory.file("roku/$appName.zip")
                    }
                )

                // Include stdlib .brs runtime files from the resolved JAR
                stdlibBrs.from(stdlibBrsFilesProvider)
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

        // Device log task: streams debug output from device
        project.tasks.register("deviceLog", DeviceLogTask::class.java).configure {
            group = "roku"
            description = "Tail Roku device debug console logs"
            this.deviceIp.set(extension.deviceIP)
        }

        // ==================== Test Tasks ====================

        // Package tests task: creates Roku test app .zip
        val packageTestsTask = project.tasks.register("packageRokuTests", PackageRokuTask::class.java).apply {
            configure {
                group = "roku test"
                description = "Package Roku test app as .zip"

                // Depend on the test compile task if it exists, plus component compile and XML processing
                project.tasks.findByName("compileTestKotlinBrs")?.let {
                    dependsOn(it)
                } ?: dependsOn("compileKotlinBrs")
                dependsOn(compileComponentsTask)
                dependsOn(processComponentXmlTask)

                // Use test source output if available, otherwise main
                val testSourceDir = project.layout.buildDirectory.dir("brs/brs/test/source")
                val mainSourceDir = project.layout.buildDirectory.dir("brs/brs/main/source")
                compiledBrs.set(project.provider {
                    val testDir = testSourceDir.get().asFile
                    if (testDir.exists() && testDir.listFiles()?.isNotEmpty() == true) {
                        testSourceDir.get()
                    } else {
                        mainSourceDir.get()
                    }
                })

                manifest.set(extension.manifestFile)

                // Configure images with directory structure preservation
                images.from(extension.imagesDir)
                imagesBaseDir.set(extension.imagesDir)

                // Configure components (processed XML files with script imports)
                components.from(processComponentXmlTask.map { task ->
                    project.fileTree(task.outputXmlDir) { include("**/*.xml") }
                })
                componentsBaseDir.set(processComponentXmlTask.flatMap { it.outputXmlDir })

                // Add compiled component .brs files (place alongside their XML files)
                compiledComponents.set(compiledComponentsDirProvider)
                processedXmlDir.set(processComponentXmlTask.flatMap { it.outputXmlDir })

                // Configure fonts
                fonts.from(extension.fontsDir)
                fontsBaseDir.set(extension.fontsDir)

                // Configure assets
                assets.from(extension.assetsDir)
                assetsBaseDir.set(extension.assetsDir)

                outputZip.set(
                    extension.appName.flatMap { appName ->
                        project.layout.buildDirectory.file("roku/${appName}Tests.zip")
                    }
                )

                // Include stdlib .brs runtime files from the resolved JAR
                stdlibBrs.from(stdlibBrsFilesProvider)
            }
        }

        // Install tests task: deploys test app to Roku device
        val installTestsTask = project.tasks.register("installRokuTests", InstallRokuTask::class.java).apply {
            configure {
                group = "roku test"
                description = "Install Roku test app to device"

                dependsOn(packageTestsTask)

                zipFile.set(packageTestsTask.flatMap { it.outputZip })
                this.deviceIp.set(extension.deviceIP)
                this.devicePassword.set(extension.devicePassword)
            }
        }

        // Run tests task: connects to device and parses test output
        val runTestsTask = project.tasks.register("runRokuTests", RunRokuTestsTask::class.java).apply {
            configure {
                group = "roku test"
                description = "Run tests on Roku device and collect results"

                dependsOn(installTestsTask)

                this.deviceIp.set(extension.deviceIP)
                testTimeout.set(testExtension.timeout)
                ignoreFailures.set(testExtension.ignoreFailures)
                resultsJson.set(testExtension.reportsDir.file("results.json"))
                resultsXml.set(testExtension.reportsDir.file("results.xml"))
            }
        }

        // Convenience task: full test cycle
        project.tasks.register("rokuTest").configure {
            group = "roku test"
            description = "Build, install, and run all Roku tests"
            dependsOn(runTestsTask)
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

    /**
     * Registers IDE import handlers for BRS source sets.
     * This allows IntelliJ to properly index Kotlin files targeting BrightScript
     * by providing the BRS stdlib as a resolvable dependency during Gradle sync.
     */
    @OptIn(ExternalKotlinTargetApi::class)
    private fun registerIdeImport(project: Project, brsStdlibConfig: Configuration) {
        val ideImport = IdeMultiplatformImport.instance(project)

        // Register a dependency resolver for BRS source sets
        // We identify BRS source sets by their naming convention (brsMain, brsTest, etc.)
        ideImport.registerDependencyResolver(
            resolver = BrsStdlibIdeDependencyResolver(brsStdlibConfig),
            constraint = IdeMultiplatformImport.SourceSetConstraint { sourceSet ->
                sourceSet.name.startsWith("brs") || sourceSet.name.contains("Brs")
            },
            phase = IdeMultiplatformImport.DependencyResolutionPhase.BinaryDependencyResolution,
            priority = IdeMultiplatformImport.Priority.normal
        )
    }
}

/**
 * IDE dependency resolver for BRS source sets.
 * Returns the BRS stdlib klib as an IDE dependency so IntelliJ can resolve
 * symbols from kotlin-stdlib-brs during indexing.
 */
@OptIn(ExternalKotlinTargetApi::class)
internal class BrsStdlibIdeDependencyResolver(
    private val stdlibConfig: Configuration
) : IdeDependencyResolver {
    override fun resolve(sourceSet: KotlinSourceSet): Set<IdeaKotlinDependency> {
        val stdlibFiles = stdlibConfig.resolve()
        if (stdlibFiles.isEmpty()) return emptySet()

        return setOf(
            IdeaKotlinResolvedBinaryDependency(
                binaryType = IdeaKotlinBinaryDependency.KOTLIN_COMPILE_BINARY_TYPE,
                classpath = IdeaKotlinClasspath(stdlibFiles),
                coordinates = null,
                extras = mutableExtrasOf()
            )
        )
    }
}
