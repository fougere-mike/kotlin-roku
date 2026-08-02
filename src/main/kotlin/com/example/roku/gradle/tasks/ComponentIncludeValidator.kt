package com.example.roku.gradle.tasks

import java.io.File

/**
 * Pure validation logic for SceneGraph component `<script>` include completeness.
 *
 * A SceneGraph component loads ONLY the .brs files its XML lists. A call to a function
 * that lives in an unlisted file (include hole) or that exists nowhere (bad emission)
 * is a hard crash on device. This validator compares ground truth (a definition scan of
 * every packaged .brs) against the bare global calls made by each component's listed
 * scripts.
 *
 * Everything here is deliberately Gradle-free so it can be unit-tested directly;
 * [ValidateComponentIncludesTask] owns the Gradle wiring, logging, and mode enforcement.
 *
 * Grammar notes / known limitations (regex-based scanning, not a full BRS parser):
 * - String literals are stripped first (BrightScript strings have no backslash escapes;
 *   `""` inside a string reads as two adjacent literals, which strips just as well).
 * - `'` comments and leading/colon-prefixed `REM` comments are stripped after strings.
 * - A "call" is a bare identifier followed by `(`, not preceded by `.` or a word
 *   character. Dotted/member calls (`obj.foo(`) dispatch through the object and are
 *   intentionally ignored.
 * - A local variable holding a function value and invoked as `f()` is indistinguishable
 *   from a global call; if the name matches no definition anywhere it will be reported.
 *   The generated-code corpus does not use this pattern (invocation goes through
 *   `this.method()`), so in practice this does not produce noise.
 * - Anonymous functions (`function(x)`) are excluded via the keyword allowlist.
 */
object ComponentIncludeValidator {

    /**
     * Definition-index sizes below this are treated as an infrastructure failure, not a
     * clean pass: the stdlib runtime alone defines thousands of functions, so a tiny
     * index means the validator was handed a broken/empty file set and any "no findings"
     * result would be vacuous.
     */
    const val MIN_PLAUSIBLE_DEFINITIONS = 100

    /**
     * Bare global calls provided by BrightScript itself (global functions, print-zone
     * functions, plus keywords that pattern-match as `name(`). Never flagged.
     * Extensible per-project via `rokuValidation.extraBuiltins`.
     */
    val DEFAULT_BUILTINS: Set<String> = setOf(
        // Object / type
        "createobject", "type", "getinterface", "findmemberfunction", "box", "getglobalaa",
        // String
        "len", "left", "right", "mid", "instr", "ucase", "lcase", "str", "stri", "string",
        "stringi", "val", "strtoi", "chr", "asc", "substitute", "tr",
        // Math
        "abs", "atn", "cdbl", "cint", "cos", "csng", "exp", "fix", "int", "log", "rnd",
        "sgn", "sin", "sqr", "tan",
        // JSON
        "formatjson", "parsejson",
        // System
        "uptime", "wait", "sleep", "rebootsystem", "rungarbagecollector", "run", "eval",
        "getlastruncompileerror", "getlastrunruntimeerror",
        // File system
        "readasciifile", "writeasciifile", "copyfile", "movefile", "deletefile",
        "deletedirectory", "createdirectory", "listdir", "matchfiles", "formatdrive",
        // Print-zone functions
        "tab", "pos",
        // Keywords/literals that can precede '(' in source
        "function", "sub", "if", "then", "else", "elseif", "return", "print", "stop",
        "end", "while", "for", "each", "step", "exit", "goto", "dim", "rem", "and", "or",
        "not", "as", "to", "mod", "in", "next", "throw", "true", "false", "invalid", "m",
        "line_num", "interface",
    )

    private val STRING_LITERAL = Regex("\"[^\"\n]*\"")
    private val TICK_COMMENT = Regex("'[^\n]*")
    private val REM_COMMENT = Regex("(?im)(^[ \t]*|:[ \t]*)rem\\b[^\n]*")
    private val DEFINITION = Regex("(?im)^[ \t]*(?:function|sub)\\s+([a-z_][a-z0-9_]*)\\s*\\(")
    private val BARE_CALL = Regex("(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT_URI = Regex("<script\\b[^>]*\\buri\\s*=\\s*\"([^\"]+)\"")

    /** A packaged component XML and the script URIs it lists. */
    data class ComponentScripts(
        val name: String,
        val xmlDisplayPath: String,
        val scriptUris: List<String>,
    )

    data class Finding(
        val component: String,
        val call: String,
        /** Listed files whose code makes the call (empty for SCRIPT_MISSING, where [call] is the URI). */
        val callers: List<String>,
        val reason: Reason,
        /** For NOT_INCLUDED: the packaged files that do define the call. */
        val definedIn: List<String> = emptyList(),
    ) {
        enum class Reason {
            /** The called function is defined in NO packaged .brs file (bad emission / wrong mangled name). */
            UNDEFINED,

            /** The called function is defined only in files absent from this component's `<script>` list. */
            NOT_INCLUDED,

            /** A `<script>` tag references a file that is not in the package at all. */
            SCRIPT_MISSING,
        }
    }

    data class Result(
        val findings: List<Finding>,
        val componentCount: Int,
        val definitionCount: Int,
    )

    /** Strips string literals, then `'` comments, then REM comments. */
    fun stripStringsAndComments(text: String): String {
        var t = STRING_LITERAL.replace(text, "\"\"")
        t = TICK_COMMENT.replace(t, "")
        t = REM_COMMENT.replace(t) { m -> m.groupValues[1] }
        return t
    }

    /** Lowercased names of all `function`/`sub` definitions in already-stripped text. */
    fun extractDefinitions(strippedText: String): Set<String> =
        DEFINITION.findAll(strippedText).map { it.groupValues[1].lowercase() }.toSet()

    /** Bare global calls in already-stripped text; original case preserved for reporting. */
    fun extractCalls(strippedText: String): Set<String> =
        BARE_CALL.findAll(strippedText).map { it.groupValues[1] }.toSet()

    /** Script URIs listed by a component XML, in document order (XML comments ignored). */
    fun parseScriptUris(xmlContent: String): List<String> =
        SCRIPT_URI.findAll(XML_COMMENT.replace(xmlContent, "")).map { it.groupValues[1] }.toList()

    /**
     * Validates every component's `<script>` set against the package-wide definition index.
     *
     * @param sourceFiles .brs files staged into the package's source/ directory
     *   (stdlib runtime + kotlin.test runtime + compiled main/test sources)
     * @param componentFiles .brs files packaged under components/
     * @param components the packaged component XMLs with their script lists
     */
    fun validate(
        sourceFiles: Collection<File>,
        componentFiles: Collection<File>,
        components: List<ComponentScripts>,
        builtins: Set<String> = DEFAULT_BUILTINS,
    ): Result {
        val sourceByName = sourceFiles.groupBy { it.name.lowercase() }
        val componentsByName = componentFiles.groupBy { it.name.lowercase() }

        // Definition index over the whole package: lowercased function name -> defining file names.
        val definitions = mutableMapOf<String, MutableSet<String>>()
        val strippedCache = mutableMapOf<File, String>()
        fun stripped(f: File): String = strippedCache.getOrPut(f) { stripStringsAndComments(f.readText()) }

        var definitionCount = 0
        for (file in sourceFiles + componentFiles) {
            if (!file.name.endsWith(".brs", ignoreCase = true)) continue
            for (def in extractDefinitions(stripped(file))) {
                definitions.getOrPut(def) { mutableSetOf() }.add(file.name)
                definitionCount++
            }
        }

        val callsCache = mutableMapOf<File, Set<String>>()
        fun calls(f: File): Set<String> = callsCache.getOrPut(f) { extractCalls(stripped(f)) }

        val findings = mutableListOf<Finding>()
        for (component in components) {
            // Resolve each listed URI to packaged file(s); pkg:/source/ URIs resolve against the
            // source payload, everything else against packaged component files. Roku paths are
            // case-insensitive, so resolution and the allowed set are keyed on lowercased names.
            val listedFiles = linkedSetOf<File>()
            val allowedNames = mutableSetOf<String>()
            for (uri in component.scriptUris) {
                val fileName = uri.substringAfterLast('/').lowercase()
                if (!fileName.endsWith(".brs")) continue
                val resolved = if (uri.startsWith("pkg:/source/", ignoreCase = true)) {
                    sourceByName[fileName]
                } else {
                    componentsByName[fileName] ?: sourceByName[fileName]
                }
                if (resolved == null) {
                    findings.add(Finding(component.name, uri, emptyList(), Finding.Reason.SCRIPT_MISSING))
                } else {
                    listedFiles.addAll(resolved)
                    allowedNames.add(fileName)
                }
            }

            // callName(lower) -> caller file names / defining file names
            val undefined = mutableMapOf<String, Pair<String, MutableSet<String>>>()
            val notIncluded = mutableMapOf<String, Triple<String, MutableSet<String>, Set<String>>>()
            for (file in listedFiles) {
                for (call in calls(file)) {
                    val key = call.lowercase()
                    if (key in builtins) continue
                    val definedIn = definitions[key]
                    if (definedIn == null) {
                        undefined.getOrPut(key) { call to mutableSetOf() }.second.add(file.name)
                    } else if (definedIn.none { it.lowercase() in allowedNames }) {
                        notIncluded.getOrPut(key) { Triple(call, mutableSetOf(), definedIn.toSet()) }
                            .second.add(file.name)
                    }
                }
            }
            undefined.values.sortedBy { it.first.lowercase() }.forEach { (call, callers) ->
                findings.add(Finding(component.name, call, callers.sorted(), Finding.Reason.UNDEFINED))
            }
            notIncluded.values.sortedBy { it.first.lowercase() }.forEach { (call, callers, definedIn) ->
                findings.add(
                    Finding(component.name, call, callers.sorted(), Finding.Reason.NOT_INCLUDED, definedIn.sorted())
                )
            }
        }

        return Result(findings, components.size, definitionCount)
    }

    /** Human-readable report; one block per component with findings. */
    fun renderReport(result: Result): String = buildString {
        appendLine("Component include validation: ${result.componentCount} component(s), " +
            "${result.definitionCount} definition(s) indexed, ${result.findings.size} finding(s)")
        for ((component, componentFindings) in result.findings.groupBy { it.component }) {
            appendLine()
            appendLine("Component '$component':")
            for (f in componentFindings) {
                when (f.reason) {
                    Finding.Reason.UNDEFINED -> appendLine(
                        "  UNDEFINED: ${f.call} — called from ${f.callers.joinToString()}; " +
                            "not defined in any packaged .brs file"
                    )
                    Finding.Reason.NOT_INCLUDED -> appendLine(
                        "  MISSING INCLUDE: ${f.call} — called from ${f.callers.joinToString()}; " +
                            "defined in ${f.definedIn.joinToString()} which is not in this component's <script> list"
                    )
                    Finding.Reason.SCRIPT_MISSING -> appendLine(
                        "  MISSING SCRIPT: ${f.call} — listed in the component XML but no such file is packaged"
                    )
                }
            }
        }
    }
}
