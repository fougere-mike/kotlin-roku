/*
 * Copyright 2024 Nuvyyo Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File

/**
 * Gradle task that generates stub .kt files for Layout classes.
 *
 * This task parses Kotlin source files looking for @SGLayout annotations in companion objects,
 * extracts node IDs from the sceneLayout { } DSL, and generates stub files that provide
 * IDE support (code completion, navigation) without being compiled.
 *
 * The stubs are generated as top-level classes named `ClassName_Layout` to match the
 * FIR-generated classes in the compiler.
 *
 * Example:
 * For a source file containing:
 * ```kotlin
 * class MainScreen {
 *     companion object {
 *         @SGLayout
 *         fun defineLayout() = sceneLayout {
 *             button(id = "incrementButton")
 *             label(id = "counterLabel")
 *         }
 *     }
 * }
 * ```
 *
 * Generates:
 * ```kotlin
 * class MainScreen_Layout(private val top: RoSGNode) {
 *     val incrementButton: RoSGNode by lazy {
 *         top.findNode("incrementButton") ?: error("Node 'incrementButton' not found in MainScreen")
 *     }
 *     val counterLabel: RoSGNode by lazy {
 *         top.findNode("counterLabel") ?: error("Node 'counterLabel' not found in MainScreen")
 *     }
 * }
 * ```
 */
@CacheableTask
abstract class GenerateLayoutStubsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val stubOutputDir: DirectoryProperty

    init {
        group = "roku"
        description = "Generate Layout class stubs for IDE support"
    }

    @TaskAction
    fun generateStubs() {
        val outputDir = stubOutputDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        var stubsGenerated = 0

        // Recursively collect all .kt files from directories
        val allKotlinFiles = mutableListOf<File>()
        sourceFiles.files.forEach { fileOrDir ->
            if (fileOrDir.isDirectory) {
                fileOrDir.walkTopDown().filter { it.extension == "kt" }.forEach { allKotlinFiles.add(it) }
            } else if (fileOrDir.extension == "kt" && fileOrDir.exists()) {
                allKotlinFiles.add(fileOrDir)
            }
        }

        logger.lifecycle("Found ${allKotlinFiles.size} Kotlin files to scan for @SGLayout")

        allKotlinFiles.forEach { file ->
            val content = file.readText()

            val layoutInfo = extractLayoutInfo(content)
            if (layoutInfo != null && layoutInfo.nodeIds.isNotEmpty()) {
                writeStubFile(outputDir, layoutInfo)
                stubsGenerated++
                logger.lifecycle("  Generated stub for ${layoutInfo.className}_Layout")
            }
        }

        if (stubsGenerated > 0) {
            logger.lifecycle("Generated $stubsGenerated Layout stub files to ${outputDir.absolutePath}")
        } else {
            logger.lifecycle("No @SGLayout classes found - no stubs generated")
        }
    }

    /**
     * Information extracted from a source file with @SGLayout.
     */
    private data class LayoutInfo(
        val packageName: String,
        val className: String,
        val nodeIds: List<String>
    )

    /**
     * Extract layout info from Kotlin source content.
     * Returns null if no @SGLayout annotation found.
     */
    private fun extractLayoutInfo(content: String): LayoutInfo? {
        // Quick check for @SGLayout annotation
        if (!content.contains("@SGLayout")) return null

        // Extract package name
        val packageMatch = Regex("""package\s+([\w.]+)""").find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // Extract class name - find the first non-companion class
        val classMatch = Regex("""class\s+(\w+)""").find(content)
        val className = classMatch?.groupValues?.get(1) ?: return null

        // Extract node IDs from DSL calls
        val nodeIds = extractNodeIds(content)

        return if (nodeIds.isNotEmpty()) {
            LayoutInfo(packageName, className, nodeIds)
        } else null
    }

    /**
     * Extract node IDs from sceneLayout DSL calls.
     *
     * Looks for patterns like:
     * - button(id = "xyz")
     * - label(id = "abc")
     * - layoutGroup(id = "group1") { ... }
     */
    private fun extractNodeIds(content: String): List<String> {
        val nodeIds = mutableListOf<String>()
        val builderMethods = setOf(
            "group", "layoutGroup", "label", "poster", "rectangle",
            "button", "buttonGroup", "textEditBox", "keyboard"
        )

        // Pattern for id = "value" (named argument)
        val namedArgPattern = Regex(
            """(${builderMethods.joinToString("|")})\s*\([^)]*id\s*=\s*"([^"]+)""""
        )
        namedArgPattern.findAll(content).forEach {
            nodeIds.add(it.groupValues[2])
        }

        // Pattern for first positional string argument
        // Only use if we didn't already get an id= match for this call
        val positionalPattern = Regex(
            """(${builderMethods.joinToString("|")})\s*\(\s*"([^"]+)""""
        )
        positionalPattern.findAll(content).forEach {
            val nodeId = it.groupValues[2]
            if (nodeId !in nodeIds) {
                nodeIds.add(nodeId)
            }
        }

        return nodeIds.distinct()
    }

    /**
     * Write a stub file for the given layout info.
     *
     * The stub uses getter-only properties that call findNode() directly.
     * The FIR-generated class (from SceneGraphLayoutGenerator) uses caching,
     * but the stub is simpler since it's only for IDE resolution, not runtime.
     */
    private fun writeStubFile(outputDir: File, info: LayoutInfo) {
        val content = buildString {
            appendLine("// AUTO-GENERATED - DO NOT EDIT")
            appendLine("// Stub for IDE support. Real implementation is compiler-generated.")
            appendLine()
            if (info.packageName.isNotEmpty()) {
                appendLine("package ${info.packageName}")
                appendLine()
            }
            appendLine("import kotlin.brs.roku.RoSGNode")
            appendLine()
            appendLine("/**")
            appendLine(" * Layout accessor for ${info.className}.")
            appendLine(" * Provides type-safe access to SceneGraph nodes defined in @SGLayout.")
            appendLine(" * The compiler generates an optimized implementation with caching.")
            appendLine(" */")
            appendLine("class ${info.className}_Layout(private val top: RoSGNode) {")
            for (nodeId in info.nodeIds) {
                // Use a simple getter - the FIR-generated class will have caching
                appendLine("    val $nodeId: RoSGNode")
                appendLine("        get() = top.findNode(\"$nodeId\")!!")
            }
            appendLine("}")
        }

        val packagePath = info.packageName.replace('.', '/')
        val outputFile = if (packagePath.isNotEmpty()) {
            File(outputDir, "$packagePath/${info.className}_Layout.kt")
        } else {
            File(outputDir, "${info.className}_Layout.kt")
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(content)
    }
}
