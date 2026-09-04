/*
 * Copyright 2024 Nuvyyo Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Serializes a set of Kotlin source roots into a klib so OTHER compilations can
 * reference their declarations with static types. The plugin uses it to give the
 * brsTest driver the whole of brsMain — components included — so tests can hold
 * typed component handles (createComponent<T>() plus @SG*Field property access)
 * and call main-source classes.
 *
 * compileKotlinBrs produces .brs + component XML for packaging but no compile-time
 * metadata, and the BRS compiler's -libraries flag only loads real klibs
 * (KlibLoader). This task runs the same compiler over the same sources with
 * -Xproduce=library to emit that metadata. The klib never ships in a package: it
 * is a compile-time artifact only.
 */
@CacheableTask
abstract class CompileKlibTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** Source roots to serialize (checked-in source dirs + generated layout stubs). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** The BRS compiler fat jar. */
    @get:Classpath
    abstract val compilerClasspath: ConfigurableFileCollection

    /** Compile-time klib dependencies (the BRS stdlib). */
    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputFile
    abstract val outputKlib: RegularFileProperty

    @TaskAction
    fun compile() {
        val output = outputKlib.get().asFile
        output.parentFile?.mkdirs()

        val ktFiles = sourceFiles.files.flatMap { root ->
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            } else if (root.isFile && root.extension == "kt") {
                listOf(root)
            } else {
                emptyList()
            }
        }

        val args = mutableListOf<String>()
        args.addAll(ktFiles.map { it.absolutePath })
        args.add("-libraries")
        args.add(libraries.files.joinToString(File.pathSeparator) { it.absolutePath })
        args.add("-module-name")
        args.add(moduleName.get())
        args.add("-Xproduce=library")
        args.add("-output")
        args.add(output.absolutePath)

        logger.info("Serializing ${ktFiles.size} component source files to klib: $output")

        execOperations.javaexec {
            mainClass.set("org.jetbrains.kotlin.cli.brs.K2BrsCompiler")
            setClasspath(compilerClasspath)
            setArgs(args)
        }
    }
}
