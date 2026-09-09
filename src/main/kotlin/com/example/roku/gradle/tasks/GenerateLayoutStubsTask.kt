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
 * Gradle task that generates stub .kt files for Layout classes AND one typed layout
 * builder per SceneGraph component (spec 2026-09-04-component-lifecycle §6).
 *
 * **Layout stubs.** The task parses Kotlin source files looking for @SGLayout annotations
 * in companion objects, extracts node IDs from the sceneLayout { } DSL - including embedded
 * custom components declared via component("Type", id = "...") and via the generated typed
 * builders (`badge(id = "...")`) - and generates stub files that provide IDE support (code
 * completion, navigation).
 *
 * The stubs are generated as top-level classes named `ClassName_Layout` and use the same
 * lazy caching pattern as the compiled BrightScript output for consistency.
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
 *     private var _incrementButton: RoSGNode? = null
 *     val incrementButton: RoSGNode
 *         get() {
 *             if (_incrementButton == null) {
 *                 _incrementButton = top.findNode("incrementButton")
 *                     ?: error("Layout node 'incrementButton' not found in component")
 *             }
 *             return _incrementButton!!
 *         }
 *     // ... similar for other nodes
 * }
 * ```
 *
 * This matches the BrightScript output which uses lazy initialization with caching:
 * ```brightscript
 * function MainScreen_Layout_get_incrementButton(this as Object) as Object
 *     if this._incrementButton = invalid then
 *         this._incrementButton = this._top.findNode("incrementButton")
 *         if this._incrementButton = invalid then
 *             throw "Layout node 'incrementButton' not found in component"
 *         end if
 *     end if
 *     return this._incrementButton
 * end function
 * ```
 *
 * **Typed builders.** A first pass over every source file collects component class
 * declarations with a CONSTRAINED grammar (see [extractComponentInfos]) and writes one
 * `ComponentBuilders_<package>.kt` per package, holding one
 * `@SGComponentBuilder("<Class>") fun LayoutBuilder.<lowerCamel>(id, <inputs>, <standard
 * attrs>, init)` per eligible component. The body delegates to `component(...)` — the
 * compiler's extractor reads the CALL site (by parameter name), never the body, so the
 * non-constant `attr(name, param)` inside is fine. The generated dir is a brsMain srcDir,
 * so the builders reach both the main compile and the test klib.
 *
 * Example: `class Badge(@SGStringField val label: String) : GroupComponent()` yields
 * ```kotlin
 * @SGComponentBuilder("Badge")
 * fun LayoutBuilder.badge(id: String, label: String, translation: Vector2D? = null, ..., init: ComponentBuilder.() -> Unit = {}) {
 *     component("Badge", id = id, translation = translation, ...) {
 *         attr("label", label)
 *         init()
 *     }
 * }
 * ```
 * so a parent layout can write `badge(id = "hostBadge", label = "NEW")`. The builder's
 * lowerCamel name beside the PascalCase class is legal: @SGComponentBuilder functions are
 * exempt from the compiler's BRS_NAME_CASE_CLASH (R33 — their mangled BRS global never
 * collides with the class's globals).
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
        description = "Generate Layout class stubs and typed component builders for IDE support"
    }

    /**
     * A component class eligible for a typed builder: [inputs] are the primary-constructor
     * inputs as (name, Kotlin type) pairs, in declaration order. Nested on the task (not its
     * companion) so callers address it as `GenerateLayoutStubsTask.ComponentInfo`.
     */
    internal data class ComponentInfo(
        val packageName: String,
        val className: String,
        val inputs: List<Pair<String, String>>
    ) {
        /** The builder's function name: the class name with its first character lowercased. */
        val builderName: String get() = className.replaceFirstChar { it.lowercase() }
    }

    /** A component that gets NO builder, with the reason. [isWarning] = the plugin could not parse it. */
    internal data class SkippedComponent(val className: String, val reason: String, val isWarning: Boolean)

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

        logger.lifecycle("Found ${allKotlinFiles.size} Kotlin files to scan for @SGLayout and components")

        val contents = allKotlinFiles.map { it.readText() }

        // ---- Pass 1: component declarations → typed builders, one file per package ----
        // Base-class resolution is MODULE-wide (a component may extend a base declared in
        // another file), so the known-component set is computed over every file first.
        val knownComponents = collectComponentClassNames(contents)
        val skipped = mutableListOf<SkippedComponent>()
        val infosByPackage = linkedMapOf<String, MutableList<ComponentInfo>>()
        for (content in contents) {
            for (info in extractComponentInfos(content, knownComponents, skipped)) {
                infosByPackage.getOrPut(info.packageName) { mutableListOf() }.add(info)
            }
        }
        for (skip in skipped) {
            val message = "  No typed builder for component ${skip.className}: ${skip.reason}"
            if (skip.isWarning) logger.warn(message) else logger.info(message)
        }
        var buildersGenerated = 0
        for ((packageName, infos) in infosByPackage) {
            writeBuildersFile(outputDir, packageName, infos)
            buildersGenerated += infos.size
            logger.lifecycle("  Generated ${infos.size} component builder(s) for package '$packageName'")
        }
        val allBuilderNames = infosByPackage.values.flatten().map { it.builderName }.toSet()

        // ---- Pass 2: @SGLayout → _Layout stubs (builder call ids included) ----
        contents.forEach { content ->
            val layoutInfo = extractLayoutInfo(content, allBuilderNames)
            if (layoutInfo != null && layoutInfo.nodeIds.isNotEmpty()) {
                writeStubFile(outputDir, layoutInfo)
                stubsGenerated++
                logger.lifecycle("  Generated stub for ${layoutInfo.className}_Layout")
            }
        }

        if (buildersGenerated > 0) {
            logger.lifecycle("Generated $buildersGenerated component builders to ${outputDir.absolutePath}")
        } else {
            logger.lifecycle("No eligible component classes found - no builders generated")
        }
        if (stubsGenerated > 0) {
            logger.lifecycle("Generated $stubsGenerated Layout stub files to ${outputDir.absolutePath}")
        } else {
            logger.lifecycle("No @SGLayout classes found - no stubs generated")
        }
    }

    companion object {
        private val builderMethods = setOf(
            "group", "layoutGroup", "label", "poster", "rectangle",
            "button", "buttonGroup", "textEditBox", "keyboard"
        )

        // \b keeps method names from matching as suffixes of longer identifiers
        // (e.g. relabel( must not count as label().

        // Pattern for id = "value" (named argument). component(...) declares an
        // embedded custom component and takes id the same way.
        private val namedArgPattern = Regex(
            """\b(${(builderMethods + "component").joinToString("|")})\s*\([^)]*id\s*=\s*"([^"]+)""""
        )

        // Pattern for first positional string argument
        // Only use if we didn't already get an id= match for this call
        private val positionalPattern = Regex(
            """\b(${builderMethods.joinToString("|")})\s*\(\s*"([^"]+)""""
        )

        // component("ComponentType", "id", ...) - the FIRST positional string is the
        // component type; the id is the SECOND, so it needs its own pattern.
        private val componentPositionalPattern = Regex(
            """\bcomponent\s*\(\s*"[^"]+"\s*,\s*"([^"]+)""""
        )

        /**
         * Information extracted from a source file with @SGLayout.
         */
        internal data class LayoutInfo(
            val packageName: String,
            val className: String,
            val nodeIds: List<String>
        )

        /**
         * Extract layout info from Kotlin source content.
         * Returns null if no @SGLayout annotation found.
         *
         * [builderNames] are the generated typed-builder names of the module (see
         * [extractComponentInfos]); their `id = "..."` call sites yield accessors too.
         */
        internal fun extractLayoutInfo(content: String, builderNames: Set<String> = emptySet()): LayoutInfo? {
            // Quick check for @SGLayout annotation
            if (!content.contains("@SGLayout")) return null

            // Extract package name
            val packageMatch = Regex("""package\s+([\w.]+)""").find(content)
            val packageName = packageMatch?.groupValues?.get(1) ?: ""

            // Extract class name - find the first non-companion class
            val classMatch = Regex("""class\s+(\w+)""").find(content)
            val className = classMatch?.groupValues?.get(1) ?: return null

            // Extract node IDs from DSL calls
            val nodeIds = extractNodeIds(content, builderNames)

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
         * - component("ShelfView", id = "shelf_view") / component("ShelfView", "shelf_view")
         */
        internal fun extractNodeIds(content: String): List<String> {
            val nodeIds = mutableListOf<String>()

            namedArgPattern.findAll(content).forEach {
                nodeIds.add(it.groupValues[2])
            }

            positionalPattern.findAll(content).forEach {
                val nodeId = it.groupValues[2]
                if (nodeId !in nodeIds) {
                    nodeIds.add(nodeId)
                }
            }

            componentPositionalPattern.findAll(content).forEach {
                val nodeId = it.groupValues[1]
                if (nodeId !in nodeIds) {
                    nodeIds.add(nodeId)
                }
            }

            return nodeIds.distinct()
        }

        /**
         * Node-id extraction that ALSO matches the generated typed-builder names, in both
         * call shapes — `badge(id = "hostBadge", …)` and `badge("hostBadge", …)` (a builder's
         * first parameter is always `id`).
         */
        internal fun extractNodeIds(content: String, builderNames: Set<String>): List<String> {
            val nodeIds = extractNodeIds(content).toMutableList()
            if (builderNames.isEmpty()) return nodeIds
            val alternatives = builderNames.sorted().joinToString("|") { Regex.escape(it) }
            val builderNamedPattern = Regex("""\b(?:$alternatives)\s*\([^)]*\bid\s*=\s*"([^"]+)"""")
            val builderPositionalPattern = Regex("""\b(?:$alternatives)\s*\(\s*"([^"]+)"""")
            for (pattern in listOf(builderNamedPattern, builderPositionalPattern)) {
                pattern.findAll(content).forEach {
                    val id = it.groupValues[1]
                    if (id !in nodeIds) nodeIds.add(id)
                }
            }
            return nodeIds
        }

        // ------------------------------------------------------------------
        // Typed component builders (spec §6)
        // ------------------------------------------------------------------

        /** The stdlib render bases a component may extend directly (spec §6). */
        private val renderBases = setOf("GroupComponent", "SceneComponent", "LayoutComponent", "RectangleComponent")

        /** Kotlin types an input may have to be expressible as an XML attribute constant. */
        private val builderInputTypes = setOf("String", "Int", "Float", "Double", "Boolean")

        /** Modifiers that make a class non-instantiable or not a component shape at all. */
        private val nonInstantiableModifiers = setOf("abstract", "sealed", "data", "enum", "annotation", "inner", "value", "private")

        /**
         * The optional standard attributes every builder accepts after its inputs — the
         * `component(...)` parameters between `id` and `init`, in that order.
         */
        internal val standardAttributes: List<Pair<String, String>> = listOf(
            "translation" to "Vector2D?",
            "rotation" to "Float?",
            "scale" to "Vector2D?",
            "scaleRotateCenter" to "Vector2D?",
            "opacity" to "Float?",
            "visible" to "Boolean?",
            "inheritParentOpacity" to "Boolean?",
            "inheritParentTransform" to "Boolean?",
            "clippingRect" to "Vector4D?",
            "renderGroup" to "Boolean?",
            "focusable" to "Boolean?",
            "renderPass" to "Int?",
        )

        /** Builder parameter names an input may not reuse. */
        private val reservedParameterNames = setOf("id", "init") + standardAttributes.map { it.first }

        /** Kotlin hard keywords — a class named `Object` would otherwise yield `fun LayoutBuilder.object`. */
        private val kotlinHardKeywords = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )

        // `[modifiers] class Name[(params)] : Base(` — params may nest one level of parens
        // (annotation arguments such as @SGIntegerField(alwaysNotify = true)).
        private val classHeaderPattern = Regex(
            """((?:\b(?:public|internal|private|protected|open|abstract|final|data|enum|sealed|annotation|inner|value)\s+)*)class\s+(\w+)\s*(?:\(([^()]*(?:\([^()]*\)[^()]*)*)\))?\s*:\s*(\w+)\s*\("""
        )

        // One constructor parameter: `@SG<Kind>Field[(args)] val|var name: Type[ = default]`.
        // @BrsField is the legacy spelling the compiler's constructor-input predicate also
        // accepts. The default (group 4) is matched only so it can be ignored — see
        // [extractComponentInfos]; anchoring it here keeps an `=` inside the annotation's
        // arguments (`@BrsField(type = "boolean")`) from being mistaken for one.
        private val inputParamPattern = Regex(
            """@(SG\w+Field|BrsField)(?:\([^)]*\))?\s+va[lr]\s+(\w+)\s*:\s*([\w.]+\??)(\s*=.*)?""",
            RegexOption.DOT_MATCHES_ALL
        )

        private val packagePattern = Regex("""package\s+([\w.]+)""")

        /**
         * Removes `//` and `/* */` comments while leaving string literals (escaped and raw)
         * intact, so a commented-out `class X : GroupComponent()` never yields a builder and a
         * `"https://…"` default inside a header never truncates it.
         */
        internal fun stripComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            val n = source.length
            while (i < n) {
                val c = source[i]
                when {
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        val stop = if (end < 0) n else end + 3
                        out.append(source, i, stop); i = stop
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < n && source[j] != '"') { if (source[j] == '\\') j++; j++ }
                        val stop = minOf(j + 1, n)
                        out.append(source, i, stop); i = stop
                    }
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i)
                        i = if (end < 0) n else end
                    }
                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2)
                        i = if (end < 0) n else end + 2
                        out.append(' ')
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        private data class ClassHeader(val modifiers: Set<String>, val className: String, val params: String, val base: String)

        private fun classHeaders(content: String): List<ClassHeader> =
            classHeaderPattern.findAll(stripComments(content)).map { m ->
                ClassHeader(
                    modifiers = m.groupValues[1].split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet(),
                    className = m.groupValues[2],
                    params = m.groupValues[3],
                    base = m.groupValues[4],
                )
            }.toList()

        /**
         * Every class in [contents] that is a component: it extends a stdlib render base or,
         * transitively, another component declared in ANY of the given sources (fixpoint —
         * base chains may span files). Abstract bases are included (their subclasses are
         * components); builder ELIGIBILITY is decided separately in [extractComponentInfos].
         */
        internal fun collectComponentClassNames(contents: Iterable<String>): Set<String> {
            val headers = contents.flatMap { classHeaders(it) }
            val known = mutableSetOf<String>()
            var changed = true
            while (changed) {
                changed = false
                for (h in headers) {
                    if (h.className !in known && (h.base in renderBases || h.base in known)) {
                        known.add(h.className)
                        changed = true
                    }
                }
            }
            return known
        }

        /**
         * Constrained grammar for component declarations: `class X(<@SG…Field val a: T, …>) : Base()`
         * where Base is a stdlib render base or another component in the same source set
         * ([externalComponents] carries the module-wide set; a single source is also resolved
         * on its own). A class is ELIGIBLE for a builder when every constructor parameter is
         * an @SG-annotated val of String/Int/Float/Double/Boolean (node/AA inputs cannot be
         * XML constants — construct those in code), none of them reuses a builder parameter
         * name (`id`, `init`, the standard attributes), the class is instantiable (not
         * abstract/sealed/data/…), and its lowerCamel name collides neither with a built-in
         * DSL method nor with a Kotlin hard keyword. A parameter's Kotlin default value is
         * ignored: the compiler treats EVERY annotated constructor property as a required
         * input (the ready marker needs all of them), so the builder parameter is required too.
         *
         * The grammar rejects rather than guesses. Every rejected component is reported via
         * [skipped]; unparseable shapes are warnings, designed-in ineligibility (node inputs,
         * abstract bases) is informational. The compiler's extractor is the source of truth:
         * a wrong builder is a compile error at its call site, never a silent XML.
         */
        internal fun extractComponentInfos(
            content: String,
            externalComponents: Set<String> = emptySet(),
            skipped: MutableList<SkippedComponent>? = null,
        ): List<ComponentInfo> {
            val packageName = packagePattern.find(content)?.groupValues?.get(1) ?: ""
            val knownComponents = externalComponents + collectComponentClassNames(listOf(content))
            val result = mutableListOf<ComponentInfo>()
            for (header in classHeaders(content)) {
                val className = header.className
                if (className !in knownComponents) continue
                fun skip(reason: String, isWarning: Boolean) { skipped?.add(SkippedComponent(className, reason, isWarning)) }

                val modifier = header.modifiers.firstOrNull { it in nonInstantiableModifiers }
                if (modifier != null) {
                    skip("'$modifier' classes are not instantiable as layout children", isWarning = false)
                    continue
                }

                val params = header.params.trim()   // comments already stripped by classHeaders()
                val inputs = mutableListOf<Pair<String, String>>()
                var problem: SkippedComponent? = null
                if (params.isNotEmpty()) {
                    val pieces = params.split(Regex(""",(?![^(]*\))""")).map { it.trim() }.filter { it.isNotEmpty() }
                    for (piece in pieces) {
                        val im = inputParamPattern.matchEntire(piece)
                        if (im == null) {
                            problem = SkippedComponent(className, "constructor parameter '$piece' is not an `@SG<Kind>Field val name: Type` input (spec §6 grammar)", isWarning = true)
                            break
                        }
                        val name = im.groupValues[2]
                        val type = im.groupValues[3]
                        if (type !in builderInputTypes) {
                            problem = SkippedComponent(className, "input '$name: $type' cannot be an XML attribute constant — construct this component in code", isWarning = false)
                            break
                        }
                        if (name in reservedParameterNames) {
                            problem = SkippedComponent(className, "input '$name' reuses a builder parameter name (id, init, or a standard attribute)", isWarning = true)
                            break
                        }
                        inputs.add(name to type)
                    }
                }
                if (problem != null) {
                    skipped?.add(problem)
                    continue
                }
                val info = ComponentInfo(packageName, className, inputs)
                if (info.builderName in builderMethods || info.builderName == "component") {
                    skip("builder name '${info.builderName}' collides with a built-in layout DSL method", isWarning = true)
                    continue
                }
                if (info.builderName in kotlinHardKeywords) {
                    skip("builder name '${info.builderName}' is a Kotlin keyword", isWarning = true)
                    continue
                }
                result.add(info)
            }
            return result
        }

        /** Renders one `@SGComponentBuilder` extension function for [info]. */
        internal fun renderBuilder(info: ComponentInfo): String {
            val inputParams = info.inputs.joinToString("") { (name, type) -> ", $name: $type" }
            val standardParams = standardAttributes.joinToString("") { (name, type) -> ", $name: $type = null" }
            val delegatedAttrs = standardAttributes.joinToString("") { (name, _) -> ", $name = $name" }
            val attrs = info.inputs.joinToString("") { (name, _) -> "        attr(\"$name\", $name)\n" }
            return buildString {
                append("@SGComponentBuilder(\"").append(info.className).append("\")\n")
                append("fun LayoutBuilder.").append(info.builderName)
                append("(id: String").append(inputParams).append(standardParams)
                append(", init: ComponentBuilder.() -> Unit = {}) {\n")
                append("    component(\"").append(info.className).append("\", id = id").append(delegatedAttrs).append(") {\n")
                append(attrs)
                append("        init()\n")
                append("    }\n")
                append("}\n")
            }
        }

        /**
         * The builders file for [packageName]. The compiler names every output `.brs` after the
         * source file alone (`<name>Kt.brs`, no package prefix), so the file name must be unique
         * ACROSS packages — a plain `ComponentBuilders.kt` in two packages would collide in
         * `pkg:/source/`.
         */
        internal fun buildersFileName(packageName: String): String =
            if (packageName.isEmpty()) "ComponentBuilders.kt" else "ComponentBuilders_${packageName.replace('.', '_')}.kt"

        /** The complete text of a builders file. */
        internal fun renderBuildersFile(packageName: String, infos: List<ComponentInfo>): String = buildString {
            appendLine("// AUTO-GENERATED - DO NOT EDIT")
            appendLine("// Generated by GenerateLayoutStubsTask: one typed layout builder per SceneGraph")
            appendLine("// component in this package (spec 2026-09-04-component-lifecycle §6).")
            appendLine()
            if (packageName.isNotEmpty()) {
                appendLine("package $packageName")
                appendLine()
            }
            appendLine("import kotlin.brs.scenegraph.ComponentBuilder")
            appendLine("import kotlin.brs.scenegraph.LayoutBuilder")
            appendLine("import kotlin.brs.scenegraph.SGComponentBuilder")
            appendLine("import kotlin.brs.scenegraph.Vector2D")
            appendLine("import kotlin.brs.scenegraph.Vector4D")
            for (info in infos) {
                appendLine()
                append(renderBuilder(info))
            }
        }
    }

    private fun writeBuildersFile(outputDir: File, packageName: String, infos: List<ComponentInfo>) {
        val packagePath = packageName.replace('.', '/')
        val fileName = buildersFileName(packageName)
        val outputFile = if (packagePath.isNotEmpty()) File(outputDir, "$packagePath/$fileName") else File(outputDir, fileName)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(renderBuildersFile(packageName, infos))
    }

    /**
     * Write a stub file for the given layout info.
     *
     * The stub uses the same lazy caching pattern as the compiled BrightScript output:
     * - Private nullable backing field initialized to null
     * - Getter that checks if backing field is null, calls findNode() if so
     * - Throws descriptive error if node not found
     * - Returns cached value on subsequent accesses
     */
    private fun writeStubFile(outputDir: File, info: LayoutInfo) {
        val content = buildString {
            appendLine("// AUTO-GENERATED - DO NOT EDIT")
            appendLine("// Generated by GenerateLayoutStubsTask for IDE support.")
            appendLine("// This code matches the BrightScript output generated by the compiler.")
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
            appendLine(" *")
            appendLine(" * Node lookups are lazily cached - findNode() is only called once per node,")
            appendLine(" * on first access. Subsequent accesses return the cached reference.")
            appendLine(" */")
            appendLine("class ${info.className}_Layout(private val top: RoSGNode) {")

            // Generate backing fields
            for (nodeId in info.nodeIds) {
                appendLine("    private var _$nodeId: RoSGNode? = null")
            }
            appendLine()

            // Generate properties with lazy caching getters
            for (nodeId in info.nodeIds) {
                appendLine("    val $nodeId: RoSGNode")
                appendLine("        get() {")
                appendLine("            if (_$nodeId == null) {")
                appendLine("                _$nodeId = top.findNode(\"$nodeId\")")
                appendLine("                    ?: error(\"Layout node '$nodeId' not found in component\")")
                appendLine("            }")
                appendLine("            return _$nodeId!!")
                appendLine("        }")
                appendLine()
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
