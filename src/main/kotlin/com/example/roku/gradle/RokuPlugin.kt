package com.example.roku.gradle

import com.example.roku.gradle.tasks.CompileBrighterScriptTask
import com.example.roku.gradle.tasks.CopyKotlinToBsTask
import com.example.roku.gradle.tasks.DeleteRokuTask
import com.example.roku.gradle.tasks.DeviceLogTask
import com.example.roku.gradle.tasks.InstallRokuTask
import com.example.roku.gradle.tasks.MergeBrsOutputTask
import com.example.roku.gradle.tasks.PackageRokuTask
import com.example.roku.gradle.tasks.ProcessComponentXmlTask
import com.example.roku.gradle.tasks.RunRokuTestsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryCoordinates
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinClasspath
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinResolvedBinaryDependency
import org.jetbrains.kotlin.gradle.idea.tcs.extras.KlibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeDistribution
import org.jetbrains.kotlin.gradle.idea.tcs.extras.isNativeStdlib
import org.jetbrains.kotlin.gradle.idea.tcs.extras.klibExtra
import org.jetbrains.kotlin.gradle.idea.tcs.extras.sourcesClasspath
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.ide.IdeDependencyResolver
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport
import org.jetbrains.kotlin.gradle.targets.brs.KotlinBrsCompile
import org.jetbrains.kotlin.gradle.targets.brs.KotlinBrsIrTarget
import java.util.Properties
import java.util.zip.ZipFile

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

        // Create the components compilation for SceneGraph component Kotlin files
        val brsTarget = kotlinExt.targets.getByName("brs") as KotlinBrsIrTarget
        val componentsCompilation = brsTarget.compilations.create("components")

        // Configure brsComponents source set to use components/ directory
        val brsComponentsSourceSet = componentsCompilation.defaultSourceSet
        brsComponentsSourceSet.kotlin.srcDir(rokuExtension.componentsDir)

        // Make brsComponents depend on brsMain for symbol visibility
        val brsMainSourceSet = kotlinExt.sourceSets.getByName("brsMain")
        brsComponentsSourceSet.dependsOn(brsMainSourceSet)

        // Create a configuration to resolve the BRS compiler JAR
        val brsCompilerConfig = project.configurations.create("brsCompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        // Add the BRS compiler dependency
        val kotlinVersion = project.findProperty("kotlin.version") ?: "2.2.255-SNAPSHOT"
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
        registerIdeImport(project, brsStdlibConfig, kotlinVersion.toString())

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

        // Configure all BRS compile tasks with the compiler JAR and stdlib
        project.tasks.withType(KotlinBrsCompile::class.java).configureEach {
            compilerJar.fileProvider(project.provider { brsCompilerConfig.singleFile })
            libraries.from(brsStdlibConfig)

            // Additional configuration for the components compile task
            if (name == "compileComponentsKotlinBrs") {
                // Components depend on main compilation output
                dependsOn("compileKotlinBrs")
                // Add main compiled output as library so components can reference main classes
                libraries.from(project.layout.buildDirectory.dir("brs/brs/main/source"))
                // Output to same location as before for packaging compatibility
                outputDirectory.set(project.layout.buildDirectory.dir("brs/brs/main/components"))
            }
        }

        // Register tasks
        registerTasks(project, rokuExtension, rokuTestExtension, brsRuntimeConfig, brsCompilerConfig, brsStdlibConfig)

        // Register hybrid build tasks if BrighterScript is enabled
        project.afterEvaluate {
            if (rokuExtension.brighterScriptEnabled.get()) {
                registerHybridBuildTasks(project, rokuExtension, brsRuntimeConfig)
            }
        }
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

        // Process component XML task: injects script imports
        val processComponentXmlTask = project.tasks.register("processComponentXml", ProcessComponentXmlTask::class.java).apply {
            configure {
                group = "roku"
                description = "Process component XML files to inject script imports"

                // Must run after both compile tasks
                dependsOn("compileKotlinBrs")
                dependsOn("compileComponentsKotlinBrs")

                sourceXmlDir.set(extension.componentsDir)
                compiledComponentsDir.set(compiledComponentsDirProvider)
                compiledMainSource.set(compiledMainSourceDir)
                // Compiler-generated XML with interface sections (for SceneGraph fields)
                compilerGeneratedXmlDir.set(project.layout.buildDirectory.dir("brs/brs/main/components/components"))
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
                dependsOn("compileComponentsKotlinBrs")
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

        // Install task: deploys .zip to Roku device and launches it
        project.tasks.register("installRoku", InstallRokuTask::class.java).configure {
            group = "roku"
            description = "Install Roku app to device and launch it"

            dependsOn(packageTask)

            zipFile.set(packageTask.flatMap { it.outputZip })
            this.deviceIp.set(extension.deviceIP)
            this.devicePassword.set(extension.devicePassword)
            this.launchAfterInstall.convention(true)
        }

        // Delete task: removes app from Roku device
        project.tasks.register("deleteRoku", DeleteRokuTask::class.java).configure {
            group = "roku"
            description = "Delete app from Roku device"

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
                dependsOn("compileComponentsKotlinBrs")
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

        // Install tests task: deploys test app to Roku device and launches it
        val installTestsTask = project.tasks.register("installRokuTests", InstallRokuTask::class.java).apply {
            configure {
                group = "roku test"
                description = "Install Roku test app to device and launch it"

                dependsOn(packageTestsTask)

                zipFile.set(packageTestsTask.flatMap { it.outputZip })
                this.deviceIp.set(extension.deviceIP)
                this.devicePassword.set(extension.devicePassword)
                this.launchAfterInstall.convention(true)
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

    /**
     * Registers tasks for hybrid BrighterScript + Kotlin builds.
     *
     * When brighterScriptEnabled is true, this sets up:
     * 1. copyKotlinToBrighterScript - Copies Kotlin output to BSC source directory
     * 2. compileBrighterScript - Runs bsc to compile BrighterScript sources (after Kotlin)
     * 3. mergeBrsOutput - Merges Kotlin and BrighterScript outputs
     * 4. buildHybrid - Convenience task for full hybrid build
     *
     * The task graph is SEQUENTIAL to allow BSC to see Kotlin-generated functions:
     *   compileKotlinBrs
     *           ↓
     *   compileComponentsKotlinBrs
     *           ↓
     *   copyKotlinToBrighterScript (copies .brs files to src/kotlin-generated/)
     *           ↓
     *   compileBrighterScript (now sees Kotlin functions - no lint errors!)
     *           ↓
     *   mergeBrsOutput
     *           ↓
     *   packageHybridRoku
     *
     * This eliminates the need for 'bs:disable-line' comments when calling Kotlin from BSC.
     */
    private fun registerHybridBuildTasks(
        project: Project,
        extension: RokuExtension,
        brsRuntimeConfig: org.gradle.api.artifacts.Configuration
    ) {
        project.logger.lifecycle("[RokuPlugin] BrighterScript integration enabled (sequential build mode)")

        // Stdlib BRS files provider - needed so BSC can see stdlib functions
        val stdlibBrsFilesProvider = project.provider {
            val runtimeJar = brsRuntimeConfig.resolve().firstOrNull()
            if (runtimeJar != null && runtimeJar.exists()) {
                project.zipTree(runtimeJar)
            } else {
                project.files()
            }
        }

        // Task 1: Copy Kotlin-compiled .brs files to BrighterScript source directory
        // This runs BEFORE BSC so it can see Kotlin-generated functions
        val copyKotlinTask = project.tasks.register("copyKotlinToBrighterScript", CopyKotlinToBsTask::class.java).apply {
            configure {
                group = "roku"
                description = "Copy Kotlin-compiled BRS files to BrighterScript source directory"

                // Wait for Kotlin compilation to finish
                dependsOn("compileKotlinBrs")
                dependsOn("compileComponentsKotlinBrs")

                kotlinBrsSource.set(project.layout.buildDirectory.dir("brs/brs/main/source"))
                // Copy to source/kotlin/ subdirectory to ensure BSC includes them in the main source scope
                // (BSC scopes files by directory - kotlin-generated/ would be a separate scope)
                brighterScriptKotlinDir.set(extension.brighterScriptSourceDir.map {
                    it.dir("source/kotlin")
                })
                // Include stdlib runtime files so BSC can resolve stdlib function calls
                stdlibBrsFiles.from(stdlibBrsFilesProvider)
            }
        }

        // Task 2: Compile BrighterScript sources - NOW runs AFTER Kotlin copy
        val compileBsTask = project.tasks.register("compileBrighterScript", CompileBrighterScriptTask::class.java).apply {
            configure {
                group = "roku"
                description = "Compile BrighterScript sources using bsc"

                // Sequential: wait for Kotlin files to be copied
                dependsOn(copyKotlinTask)

                // Look for bsconfig.json in project directory
                val bsConfig = project.file("bsconfig.json")
                if (bsConfig.exists()) {
                    bsConfigFile.set(bsConfig)
                }

                sourceDir.set(extension.brighterScriptSourceDir)
                stagingDir.set(extension.brighterScriptStagingDir)
                bscCommand.set(extension.brighterScriptCommand)
                workingDir.set(project.layout.projectDirectory)
            }
        }

        // Task 3: Merge Kotlin and BrighterScript outputs
        val mergeTask = project.tasks.register("mergeBrsOutput", MergeBrsOutputTask::class.java).apply {
            configure {
                group = "roku"
                description = "Merge Kotlin and BrighterScript compiled outputs"

                // Only depends on BSC now since BSC already depends on Kotlin via copyKotlinTask
                dependsOn(compileBsTask)

                kotlinBrsSource.set(project.layout.buildDirectory.dir("brs/brs/main/source"))
                kotlinBrsComponents.set(project.layout.buildDirectory.dir("brs/brs/main/components"))
                brighterScriptStaging.set(extension.brighterScriptStagingDir)
                mergedOutput.set(project.layout.buildDirectory.dir("merged-staging"))
            }
        }

        // Configure merge task with stdlib files (reuse provider from above)
        mergeTask.configure {
            stdlibBrsFiles.from(stdlibBrsFilesProvider)
        }

        // Task 3: Reconfigure packageRoku to use merged output when in hybrid mode
        // We create a new packageHybridRoku task instead of modifying the existing one
        val packageHybridTask = project.tasks.register("packageHybridRoku", PackageRokuTask::class.java).apply {
            configure {
                group = "roku"
                description = "Package hybrid Roku app (Kotlin + BrighterScript) as .zip"

                dependsOn(mergeTask)

                // Use merged output directories
                val mergedDir = project.layout.buildDirectory.dir("merged-staging")
                compiledBrs.set(mergedDir.map { it.dir("source") })

                // Manifest from merged staging (BrighterScript may have processed it)
                manifest.set(mergedDir.map { it.file("manifest") })

                // Components from merged staging - include ALL component files (XML, BRS, and sourcemaps)
                components.from(mergeTask.map { task ->
                    project.fileTree(task.mergedOutput.get().dir("components")) { include("**/*") }
                })
                componentsBaseDir.set(mergedDir.map { it.dir("components") })

                // Images from merged staging
                images.from(mergedDir.map { dir ->
                    project.fileTree(dir.dir("images"))
                })
                imagesBaseDir.set(mergedDir.map { it.dir("images") })

                // Fonts from merged staging
                fonts.from(mergedDir.map { dir ->
                    project.fileTree(dir.dir("fonts"))
                })
                fontsBaseDir.set(mergedDir.map { it.dir("fonts") })

                // Assets from merged staging (if exists)
                // Note: We don't set assetsBaseDir for hybrid builds since the
                // assets may not exist in BrighterScript projects. The assets
                // files collection handles non-existence gracefully.
                assets.from(mergedDir.map { dir ->
                    val assetsDir = dir.dir("assets").asFile
                    if (assetsDir.exists()) project.fileTree(assetsDir) else project.files()
                })

                outputZip.set(
                    extension.appName.flatMap { appName ->
                        project.layout.buildDirectory.file("roku/$appName.zip")
                    }
                )

                // Add other standard Roku directories from merged staging
                // These include data/, locale/, and any other directories
                additionalDirsBase = mergedDir.get().asFile

                // data/ directory
                val dataFiles = project.objects.fileCollection()
                dataFiles.from(mergedDir.map { dir ->
                    val dataDir = dir.dir("data").asFile
                    if (dataDir.exists()) project.fileTree(dataDir) else project.files()
                })
                additionalDirs.add(dataFiles to "data")

                // locale/ directory
                val localeFiles = project.objects.fileCollection()
                localeFiles.from(mergedDir.map { dir ->
                    val localeDir = dir.dir("locale").asFile
                    if (localeDir.exists()) project.fileTree(localeDir) else project.files()
                })
                additionalDirs.add(localeFiles to "locale")
            }
        }

        // Task 4: Install hybrid app
        val installHybridTask = project.tasks.register("installHybridRoku", InstallRokuTask::class.java).apply {
            configure {
                group = "roku"
                description = "Install hybrid Roku app to device and launch it"

                dependsOn(packageHybridTask)

                zipFile.set(packageHybridTask.flatMap { it.outputZip })
                deviceIp.set(extension.deviceIP)
                devicePassword.set(extension.devicePassword)
                launchAfterInstall.convention(true)
            }
        }

        // Task 5: Convenience task for full hybrid build cycle
        project.tasks.register("buildHybrid").configure {
            group = "roku"
            description = "Full hybrid build: compile both BrighterScript and Kotlin, merge, and package"
            dependsOn(packageHybridTask)
        }

        // Task 6: Clean source/kotlin/ directory on Gradle clean
        project.tasks.findByName("clean")?.doLast {
            val kotlinGenDir = extension.brighterScriptSourceDir.get().dir("source/kotlin").asFile
            if (kotlinGenDir.exists()) {
                kotlinGenDir.deleteRecursively()
                project.logger.lifecycle("[RokuPlugin] Cleaned source/kotlin directory: ${kotlinGenDir.absolutePath}")
            }
        }

        project.logger.lifecycle("[RokuPlugin] Registered hybrid build tasks: copyKotlinToBrighterScript, compileBrighterScript, mergeBrsOutput, packageHybridRoku, installHybridRoku, buildHybrid")
        project.logger.lifecycle("[RokuPlugin] Kotlin files will be copied to source/kotlin/ for BSC visibility")
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
    private fun registerIdeImport(project: Project, brsStdlibConfig: Configuration, kotlinVersion: String) {
        val ideImport = IdeMultiplatformImport.instance(project)

        println("[RokuPlugin] Registering BRS IDE import resolver")

        // Register a dependency resolver for BRS source sets
        // We identify BRS source sets by their naming convention (brsMain, brsTest, etc.)
        ideImport.registerDependencyResolver(
            resolver = BrsStdlibIdeDependencyResolver(brsStdlibConfig, kotlinVersion),
            constraint = IdeMultiplatformImport.SourceSetConstraint { sourceSet ->
                val matches = sourceSet.name.startsWith("brs") || sourceSet.name.contains("Brs")
                println("[RokuPlugin] Checking constraint for sourceSet: ${sourceSet.name}, matches: $matches")
                matches
            },
            phase = IdeMultiplatformImport.DependencyResolutionPhase.BinaryDependencyResolution,
            priority = IdeMultiplatformImport.Priority.high  // Use high priority to run before other resolvers
        )
    }
}

/**
 * IDE dependency resolver for BRS source sets.
 * Returns the BRS stdlib klib as an IDE dependency so IntelliJ can resolve
 * symbols from kotlin-stdlib-brs during indexing.
 *
 * This implementation is modeled after IdeNativeStdlibDependencyResolver to ensure
 * proper klib metadata is provided for IDE navigation.
 */
@OptIn(ExternalKotlinTargetApi::class)
internal class BrsStdlibIdeDependencyResolver(
    private val stdlibConfig: Configuration,
    private val kotlinVersion: String
) : IdeDependencyResolver {
    override fun resolve(sourceSet: KotlinSourceSet): Set<IdeaKotlinDependency> {
        println("[BrsStdlibIdeDependencyResolver] Resolving dependencies for sourceSet: ${sourceSet.name}")
        val stdlibFiles = stdlibConfig.resolve()
        println("[BrsStdlibIdeDependencyResolver] Resolved ${stdlibFiles.size} files: ${stdlibFiles.map { it.name }}")
        if (stdlibFiles.isEmpty()) return emptySet()

        // Find the klib file
        val stdlibFile = stdlibFiles.firstOrNull { it.extension == "klib" } ?: stdlibFiles.firstOrNull() ?: return emptySet()
        println("[BrsStdlibIdeDependencyResolver] Using stdlib file: ${stdlibFile.absolutePath}")

        // Try to find sources jar in the same directory
        val sourcesJar = findSourcesJar(stdlibFile)
        if (sourcesJar != null) {
            println("[BrsStdlibIdeDependencyResolver] Found sources jar: ${sourcesJar.absolutePath}")
        }

        // Try to read klib metadata directly from the klib file
        val klibExtraData = try {
            readKlibMetadata(stdlibFile)
        } catch (error: Throwable) {
            println("[BrsStdlibIdeDependencyResolver] Failed to read klib metadata: ${error.message}")
            // Fallback to minimal metadata
            KlibExtra(
                builtInsPlatform = null,
                uniqueName = "kotlin-stdlib-brs",
                shortName = "stdlib",
                packageFqName = null,
                nativeTargets = null,
                commonizerNativeTargets = null,
                commonizerTarget = null,
                isInterop = false
            )
        }

        return setOf(
            IdeaKotlinResolvedBinaryDependency(
                binaryType = IdeaKotlinBinaryDependency.KOTLIN_COMPILE_BINARY_TYPE,
                classpath = IdeaKotlinClasspath(stdlibFile),
                coordinates = brsStdlibCoordinates()
            ).apply {
                // Set the klib extra metadata for proper IDE indexing
                this.klibExtra = klibExtraData

                // Mark as native distribution/stdlib for IDE compatibility
                // This helps IntelliJ's Kotlin plugin recognize and index the klib
                this.isNativeDistribution = true
                this.isNativeStdlib = true

                // Add sources for IDE navigation
                if (sourcesJar != null) {
                    this.sourcesClasspath += sourcesJar
                }
            }
        )
    }

    /**
     * Find the sources jar for the given klib file.
     * Looks for a file with "-sources.jar" suffix in the same directory.
     */
    private fun findSourcesJar(klibFile: java.io.File): java.io.File? {
        val parentDir = klibFile.parentFile ?: return null
        val baseName = klibFile.nameWithoutExtension

        // Look for sources jar with the same base name
        val sourcesJar = java.io.File(parentDir, "$baseName-sources.jar")
        return if (sourcesJar.exists()) sourcesJar else null
    }

    /**
     * Read klib metadata directly from the klib file.
     * A klib is a zip file containing a manifest with library properties.
     */
    private fun readKlibMetadata(klibFile: java.io.File): KlibExtra {
        val properties = java.util.Properties()

        ZipFile(klibFile).use { zip ->
            // The manifest is at default/manifest in the klib
            val manifestEntry = zip.getEntry("default/manifest")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { input ->
                    properties.load(input)
                }
            }
        }

        val uniqueName = properties.getProperty("unique_name") ?: "kotlin-stdlib-brs"
        val shortName = properties.getProperty("short_name")
        val builtInsPlatform = properties.getProperty("builtins_platform")

        println("[BrsStdlibIdeDependencyResolver] Read klib metadata: uniqueName=$uniqueName, shortName=$shortName, builtInsPlatform=$builtInsPlatform")

        return KlibExtra(
            builtInsPlatform = builtInsPlatform,
            uniqueName = uniqueName,
            shortName = shortName,
            packageFqName = null,
            nativeTargets = null,
            commonizerNativeTargets = null,
            commonizerTarget = null,
            isInterop = false
        )
    }

    /**
     * Provide coordinates for the BRS stdlib dependency.
     */
    private fun brsStdlibCoordinates(): IdeaKotlinBinaryCoordinates {
        return IdeaKotlinBinaryCoordinates(
            group = "org.jetbrains.kotlin",
            module = "kotlin-stdlib-brs",
            version = kotlinVersion
        )
    }
}
