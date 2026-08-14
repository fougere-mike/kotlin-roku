package com.example.roku.gradle.tasks

import java.io.File

/**
 * Pure validation logic for SceneGraph component `<script>` include completeness.
 *
 * A SceneGraph component loads ONLY the .brs files its XML lists. A call to a function
 * that lives in an unlisted file (include hole) or that exists nowhere (bad emission)
 * is a hard crash on device. This validator compares ground truth (a definition scan of
 * every packaged .brs) against the bare global calls AND bare function-value references
 * made by each component's listed scripts.
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
 * - A call whose name matches a per-body LOCAL (parameter, assignment target, loop/dim
 *   target — the same flow-insensitive collection [extractBareRefs] uses) is excluded:
 *   in BrightScript a local shadows any global in call position, so `f(...)` with a
 *   local `f` is a dynamic function-pointer call that cannot be resolved statically.
 *   The corpus instance is the stdlib's scope-binding dispatch
 *   (`binding(captures, completion)` — a function-pointer parameter). Flip side, as
 *   with references: a genuine global call in a body that ALSO assigns that name
 *   locally is suppressed — acceptable, since mangled globals are never assigned.
 *   Occurrences outside any function body keep the plain scan (no locals in scope).
 * - Anonymous functions (`function(x)`) are excluded via the keyword allowlist.
 *
 * Bare function-value references ([extractBareRefs]): a global function name used OUTSIDE
 * call position — e.g. the compiler-emitted `this.equals = Any_equals_AnyN_k_` method-default
 * assignments in every root-class `_create` — crashes identically at call time if its
 * defining file is unlisted, but is invisible to the call scan above. Reference detection
 * heuristics and limits:
 * - Lexicon-gated: a bare identifier counts as a reference only if it matches an indexed
 *   definition name (classified UNDEFINED/NOT_INCLUDED as usual) or looks compiler-mangled
 *   (`_k_` suffix or `__kotlin_` prefix) — the latter is what makes UNDEFINED detectable
 *   for wrong-name emissions. A non-mangled reference to a name defined nowhere is
 *   indistinguishable from an ordinary variable read and is NOT reported.
 * - Per-body local exclusion (flow-insensitive): parameter names (named and anonymous
 *   functions), assignment targets in statement position (line start, after `:`, or after
 *   single-line-if `then`/`else`), and `for`/`for each`/`dim` targets
 *   are locals for the whole enclosing body. Reads of a local that shadows a global
 *   function name are therefore never flagged as references (the generated corpus hits
 *   this: lambda parameters named `init` vs SceneGraph `sub init()` definitions). The
 *   flip side: a genuine reference to a global whose name is also assigned locally in the
 *   same body is suppressed — acceptable, since mangled globals are never assigned.
 * - Definition lines are excluded; each scan body runs from one definition line to the
 *   next (BrightScript has no nested named functions, so `end function`/`end sub` of
 *   anonymous functions cannot truncate a body). Code outside any function/sub is not
 *   scanned (illegal in BrightScript anyway). Multi-line signatures are not supported
 *   (the compiler emits single-line signatures).
 * - Receiver/index positions (`x.member`, `x[i]`) and dotted accesses (`obj.name`) are
 *   excluded — a global function value cannot be dotted or indexed by name in BrightScript
 *   without first being assigned to a variable.
 * - Call-position occurrences use the same follower rule as [extractCalls] (`\s*(`), so
 *   every occurrence is either a call or a reference, never both — a name seen both ways
 *   in one component still yields a single finding.
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

    // Reference-scan regexes ([extractBareRefs]). DEFINITION_LINE additionally captures the
    // rest of the signature line (parameter list) for local-name collection.
    private val DEFINITION_LINE = Regex("(?im)^[ \t]*(?:function|sub)\\s+[a-z_][a-z0-9_]*\\s*\\(([^\n]*)")
    private val ANONYMOUS_FUNCTION = Regex("(?i)(?<![.\\w])(?:function|sub)[ \t]*\\(([^\n)]*)")
    private val PARAMETER_NAME = Regex("(?i)[(,][ \t]*([a-z_][a-z0-9_]*)")
    // Anchors cover every statement position an assignment can start at: line start, after
    // ':', and after single-line-if `then`/`else` (whole words — after `then`/`else` the next
    // token is a statement, so `ident =` there is always an assignment, never a comparison).
    private val ASSIGNMENT_TARGET =
        Regex("(?im)(?:^|:|(?<![.\\w])(?:then|else)[ \t]+)[ \t]*([a-z_][a-z0-9_]*)[ \t]*(?:[-+*/\\\\]|<<|>>)?=")
    private val LOOP_OR_DIM_TARGET = Regex("(?i)(?<![.\\w])(?:for[ \t]+each[ \t]+|for[ \t]+|dim[ \t]+)([a-z_][a-z0-9_]*)")
    private val BARE_IDENTIFIER = Regex("(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)")

    /**
     * Compiler-mangled global function name shape: `_k_`-suffixed mangled Kotlin declarations
     * (incl. the `Any_*_k_` method defaults) or `__kotlin_*` runtime helpers. These shapes make
     * accidental collision with hand-written locals implausible, which is what lets a bare
     * reference to one of them be reported UNDEFINED when it matches no definition anywhere.
     */
    private fun isCompilerMangled(name: String): Boolean =
        name.endsWith("_k_", ignoreCase = true) || name.startsWith("__kotlin_", ignoreCase = true)

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
        /**
         * True when the name was seen ONLY as a bare function-value reference (never in call
         * position) in this component's listed scripts — the `this.equals = Any_equals_AnyN_k_`
         * emission class. Crashes at call time just like a bad call; the marker exists so the
         * report says how the name was used.
         */
        val referenceOnly: Boolean = false,
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

    /** Per-component usage evidence for one name, merged across call and reference sightings. */
    private class Usage(val displayName: String, val definedIn: Set<String> = emptySet()) {
        val callers = mutableSetOf<String>()
        var seenAsCall = false
    }

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

    /**
     * Bare global calls in already-stripped text; original case preserved for reporting.
     *
     * A call occurrence inside a function body whose per-body locals contain the callee
     * name is excluded (function-pointer invocation — the local shadows any global in
     * call position; see the class KDoc). Occurrences outside any body are kept as-is.
     */
    fun extractCalls(strippedText: String): Set<String> {
        val bodies = scanBodies(strippedText)
        val calls = mutableSetOf<String>()
        for (match in BARE_CALL.findAll(strippedText)) {
            val name = match.groupValues[1]
            val body = bodies.firstOrNull { match.range.first in it.range }
            if (body != null && name.lowercase() in body.localNames) continue
            calls.add(name)
        }
        return calls
    }

    /** One function/sub body: its text range in the stripped file + its per-body local names. */
    private class BodyScan(val range: IntRange, val localNames: Set<String>)

    /**
     * Segments already-stripped text into definition bodies and collects each body's
     * flow-insensitive local names (parameters of the definition and of anonymous
     * functions within it, assignment targets, `for`/`for each`/`dim` targets).
     * Shared by [extractCalls] (call-position shadowing) and [extractBareRefs].
     */
    private fun scanBodies(strippedText: String): List<BodyScan> {
        val definitionLines = DEFINITION_LINE.findAll(strippedText).toList()
        return definitionLines.mapIndexed { index, definition ->
            val bodyStart = definition.range.last + 1
            val bodyEnd = definitionLines.getOrNull(index + 1)?.range?.first ?: strippedText.length
            val body = strippedText.substring(bodyStart, bodyEnd)

            val localNames = mutableSetOf<String>()
            fun addParameters(parameterList: String) = PARAMETER_NAME.findAll("($parameterList")
                .forEach { localNames.add(it.groupValues[1].lowercase()) }
            addParameters(definition.groupValues[1])
            ANONYMOUS_FUNCTION.findAll(body).forEach { addParameters(it.groupValues[1]) }
            ASSIGNMENT_TARGET.findAll(body).forEach { localNames.add(it.groupValues[1].lowercase()) }
            LOOP_OR_DIM_TARGET.findAll(body).forEach { localNames.add(it.groupValues[1].lowercase()) }

            BodyScan(bodyStart until bodyEnd, localNames)
        }
    }

    /**
     * Bare function-value references in already-stripped text; original case preserved.
     *
     * A reference is a bare identifier outside call position that either matches a name in
     * [knownNames] (the lowercased definition index) or looks compiler-mangled. Definition
     * lines, per-body locals, and receiver/index positions are excluded — see the class KDoc
     * for the full heuristics and limits.
     */
    fun extractBareRefs(strippedText: String, knownNames: Set<String>): Set<String> {
        val refs = mutableSetOf<String>()
        // Bodies run to the next definition line: anonymous `end function`s can't truncate
        // them, and the trailing `end function`/`end sub` keywords never match the lexicon.
        for (bodyScan in scanBodies(strippedText)) {
            val body = strippedText.substring(bodyScan.range.first, bodyScan.range.last + 1)
            for (match in BARE_IDENTIFIER.findAll(body)) {
                val name = match.groupValues[1]
                if (name.lowercase() in bodyScan.localNames) continue
                if (name.lowercase() !in knownNames && !isCompilerMangled(name)) continue
                val afterName = match.range.last + 1
                if (afterName < body.length && (body[afterName] == '.' || body[afterName] == '[')) continue
                var next = afterName
                while (next < body.length && body[next].isWhitespace()) next++
                if (next < body.length && body[next] == '(') continue // call position: extractCalls owns it
                refs.add(name)
            }
        }
        return refs
    }

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
        val refsCache = mutableMapOf<File, Set<String>>()
        fun refs(f: File): Set<String> = refsCache.getOrPut(f) { extractBareRefs(stripped(f), definitions.keys) }

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

            // callName(lower) -> usage evidence merged across calls and bare references, so a
            // name seen both ways yields one finding per component, not two.
            val undefined = mutableMapOf<String, Usage>()
            val notIncluded = mutableMapOf<String, Usage>()
            fun record(name: String, file: File, asCall: Boolean) {
                val key = name.lowercase()
                if (key in builtins) return
                val definedIn = definitions[key]
                val usage = when {
                    definedIn == null -> undefined.getOrPut(key) { Usage(name) }
                    definedIn.none { it.lowercase() in allowedNames } ->
                        notIncluded.getOrPut(key) { Usage(name, definedIn.toSet()) }
                    else -> return
                }
                usage.callers.add(file.name)
                if (asCall) usage.seenAsCall = true
            }
            for (file in listedFiles) {
                calls(file).forEach { record(it, file, asCall = true) }
                refs(file).forEach { record(it, file, asCall = false) }
            }
            undefined.values.sortedBy { it.displayName.lowercase() }.forEach { u ->
                findings.add(
                    Finding(
                        component.name, u.displayName, u.callers.sorted(), Finding.Reason.UNDEFINED,
                        referenceOnly = !u.seenAsCall,
                    )
                )
            }
            notIncluded.values.sortedBy { it.displayName.lowercase() }.forEach { u ->
                findings.add(
                    Finding(
                        component.name, u.displayName, u.callers.sorted(), Finding.Reason.NOT_INCLUDED,
                        u.definedIn.sorted(), referenceOnly = !u.seenAsCall,
                    )
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
                val usedFrom = if (f.referenceOnly) "referenced (as a function value) from" else "called from"
                when (f.reason) {
                    Finding.Reason.UNDEFINED -> appendLine(
                        "  UNDEFINED: ${f.call} — $usedFrom ${f.callers.joinToString()}; " +
                            "not defined in any packaged .brs file"
                    )
                    Finding.Reason.NOT_INCLUDED -> appendLine(
                        "  MISSING INCLUDE: ${f.call} — $usedFrom ${f.callers.joinToString()}; " +
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
