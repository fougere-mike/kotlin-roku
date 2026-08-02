package com.example.roku.gradle.tasks

import com.example.roku.gradle.tasks.ComponentIncludeValidator.ComponentScripts
import com.example.roku.gradle.tasks.ComponentIncludeValidator.Finding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComponentIncludeValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun brs(name: String, content: String): File {
        val f = File(tmp.root, name)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    // ---- stripStringsAndComments ----

    @Test
    fun `strips string literals including apostrophes inside them`() {
        val stripped = ComponentIncludeValidator.stripStringsAndComments(
            "x = \"don't call fake(\" + realCall(1)"
        )
        assertFalse(stripped.contains("fake("))
        assertTrue(stripped.contains("realCall(1)"))
    }

    @Test
    fun `strips tick comments but keeps code before them`() {
        val stripped = ComponentIncludeValidator.stripStringsAndComments(
            "y = keepMe(2) ' but dropMe(3) is commented out"
        )
        assertTrue(stripped.contains("keepMe(2)"))
        assertFalse(stripped.contains("dropMe"))
    }

    @Test
    fun `strips REM comments at line start and after colon but not identifiers containing rem`() {
        val stripped = ComponentIncludeValidator.stripStringsAndComments(
            "REM whole line dropOne(1)\n" +
                "x = 1 : rem dropTwo(2)\n" +
                "y = remainder(3)\n"
        )
        assertFalse(stripped.contains("dropOne"))
        assertFalse(stripped.contains("dropTwo"))
        assertTrue(stripped.contains("remainder(3)"))
    }

    @Test
    fun `comment markers inside strings do not eat code`() {
        val stripped = ComponentIncludeValidator.stripStringsAndComments(
            "s = \"it's a quote\" : keepMe(1)"
        )
        assertTrue(stripped.contains("keepMe(1)"))
    }

    // ---- extractDefinitions ----

    @Test
    fun `finds function and sub definitions case-insensitively`() {
        val defs = ComponentIncludeValidator.extractDefinitions(
            "function Alpha_init(x as Integer)\nend function\n" +
                "  SUB beta_run()\n  end sub\n"
        )
        assertEquals(setOf("alpha_init", "beta_run"), defs)
    }

    @Test
    fun `does not match end function or mid-line function keyword`() {
        val defs = ComponentIncludeValidator.extractDefinitions(
            "function real()\nend function\nx = function(y)\n  return y\nend function\n"
        )
        assertEquals(setOf("real"), defs)
    }

    // ---- extractCalls ----

    @Test
    fun `finds bare calls but ignores dotted member calls`() {
        val calls = ComponentIncludeValidator.extractCalls(
            "a = globalCall(1)\nb = obj.methodCall(2)\nc = m.top.deepCall(3)\n"
        )
        assertTrue(calls.contains("globalCall"))
        assertFalse(calls.contains("methodCall"))
        assertFalse(calls.contains("deepCall"))
    }

    @Test
    fun `anonymous function keyword is extracted but filtered by builtins allowlist`() {
        val calls = ComponentIncludeValidator.extractCalls("f = function(x)\n return x\nend function\n")
        assertTrue(calls.all { it.lowercase() in ComponentIncludeValidator.DEFAULT_BUILTINS })
    }

    @Test
    fun `indexed invocation does not produce a false call for the index variable`() {
        val calls = ComponentIncludeValidator.extractCalls("v = arr[i]\nw = other(j)\n")
        assertFalse(calls.contains("i"))
        assertTrue(calls.contains("other"))
    }

    // ---- parseScriptUris ----

    @Test
    fun `parses script uris and ignores commented-out script tags`() {
        val uris = ComponentIncludeValidator.parseScriptUris(
            """
            <component name="X" extends="Group">
                <script type="text/brightscript" uri="pkg:/source/AKt.brs" />
                <!-- <script type="text/brightscript" uri="pkg:/source/DisabledKt.brs" /> -->
                <script type="text/brightscript" uri="pkg:/components/X/XKt.brs" />
            </component>
            """.trimIndent()
        )
        assertEquals(listOf("pkg:/source/AKt.brs", "pkg:/components/X/XKt.brs"), uris)
    }

    // ---- validate ----

    private fun component(name: String, vararg uris: String) =
        ComponentScripts(name, "components/$name/$name.xml", uris.toList())

    @Test
    fun `component whose scripts cover all calls produces no findings`() {
        val helper = brs("source/HelperKt.brs", "function doHelp(x)\n return x\nend function\n")
        val widget = brs(
            "components/WidgetKt.brs",
            "sub Widget_init()\n  y = doHelp(1)\n  print y\nend sub\n"
        )
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(helper),
            componentFiles = listOf(widget),
            components = listOf(
                component("Widget", "pkg:/source/HelperKt.brs", "pkg:/components/Widget/WidgetKt.brs")
            ),
        )
        assertEquals(emptyList<Finding>(), result.findings)
        assertEquals(1, result.componentCount)
    }

    @Test
    fun `call into staged but unlisted file is reported as missing include naming both files`() {
        val helper = brs("source/HelperKt.brs", "function doHelp(x)\n return x\nend function\n")
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\n  y = doHelp(1)\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(helper),
            componentFiles = listOf(widget),
            components = listOf(component("Widget", "pkg:/components/Widget/WidgetKt.brs")),
        )
        assertEquals(1, result.findings.size)
        val finding = result.findings.single()
        assertEquals(Finding.Reason.NOT_INCLUDED, finding.reason)
        assertEquals("doHelp", finding.call)
        assertEquals(listOf("WidgetKt.brs"), finding.callers)
        assertEquals(listOf("HelperKt.brs"), finding.definedIn)
    }

    @Test
    fun `call defined nowhere is reported as undefined`() {
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\n  runIOWorker_Str_k_(\"x\", {})\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = emptyList(),
            componentFiles = listOf(widget),
            components = listOf(component("Widget", "pkg:/components/Widget/WidgetKt.brs")),
        )
        val finding = result.findings.single()
        assertEquals(Finding.Reason.UNDEFINED, finding.reason)
        assertEquals("runIOWorker_Str_k_", finding.call)
        assertEquals(listOf("WidgetKt.brs"), finding.callers)
    }

    @Test
    fun `include hole in a listed stdlib file is caught, not just in the component's own file`() {
        // Mirrors the task-7 device crash: a listed file (DefaultDispatcherKt) calls its
        // superclass ctor defined in an UNLISTED file (CoroutineDispatcherKt).
        val dispatcher = brs(
            "source/CoroutineDispatcherKt.brs",
            "function CoroutineDispatcher_init(this)\n return this\nend function\n"
        )
        val defaultDispatcher = brs(
            "source/DefaultDispatcherKt.brs",
            "function DefaultDispatcher_create()\n  this = {}\n  CoroutineDispatcher_init(this)\n  return this\nend function\n"
        )
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\n  d = DefaultDispatcher_create()\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(dispatcher, defaultDispatcher),
            componentFiles = listOf(widget),
            components = listOf(
                component("Widget", "pkg:/source/DefaultDispatcherKt.brs", "pkg:/components/Widget/WidgetKt.brs")
            ),
        )
        val finding = result.findings.single()
        assertEquals(Finding.Reason.NOT_INCLUDED, finding.reason)
        assertEquals("CoroutineDispatcher_init", finding.call)
        assertEquals(listOf("DefaultDispatcherKt.brs"), finding.callers)
        assertEquals(listOf("CoroutineDispatcherKt.brs"), finding.definedIn)
    }

    @Test
    fun `script tag referencing a file missing from the package is reported`() {
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = emptyList(),
            componentFiles = listOf(widget),
            components = listOf(
                component("Widget", "pkg:/source/GhostKt.brs", "pkg:/components/Widget/WidgetKt.brs")
            ),
        )
        val finding = result.findings.single()
        assertEquals(Finding.Reason.SCRIPT_MISSING, finding.reason)
        assertEquals("pkg:/source/GhostKt.brs", finding.call)
    }

    @Test
    fun `builtins and extra allowlisted names are never flagged`() {
        val widget = brs(
            "components/WidgetKt.brs",
            "sub Widget_init()\n  o = CreateObject(\"roArray\")\n  n = Len(\"x\")\n  MyProjectGlobal(1)\nend sub\n"
        )
        val result = ComponentIncludeValidator.validate(
            sourceFiles = emptyList(),
            componentFiles = listOf(widget),
            components = listOf(component("Widget", "pkg:/components/Widget/WidgetKt.brs")),
            builtins = ComponentIncludeValidator.DEFAULT_BUILTINS + "myprojectglobal",
        )
        assertEquals(emptyList<Finding>(), result.findings)
    }

    @Test
    fun `matching is case-insensitive across calls, definitions, and script uris`() {
        val helper = brs("source/HelperKt.brs", "function DoHelp(x)\n return x\nend function\n")
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\n  y = DOHELP(1)\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(helper),
            componentFiles = listOf(widget),
            components = listOf(
                // uri case differs from the actual file name, as in real compiler-generated XML
                component("Widget", "pkg:/source/helperKt.brs", "pkg:/components/Widget/WidgetKt.brs")
            ),
        )
        assertEquals(emptyList<Finding>(), result.findings)
    }

    @Test
    fun `calls inside strings and comments are not flagged`() {
        val widget = brs(
            "components/WidgetKt.brs",
            "sub Widget_init()\n  s = \"looksLikeCall(\"\n  ' commentedCall(1)\nend sub\n"
        )
        val result = ComponentIncludeValidator.validate(
            sourceFiles = emptyList(),
            componentFiles = listOf(widget),
            components = listOf(component("Widget", "pkg:/components/Widget/WidgetKt.brs")),
        )
        assertEquals(emptyList<Finding>(), result.findings)
    }

    @Test
    fun `renderReport names component caller file call and defining file`() {
        val helper = brs("source/HelperKt.brs", "function doHelp(x)\n return x\nend function\n")
        val widget = brs("components/WidgetKt.brs", "sub Widget_init()\n  doHelp(1)\n  ghost(2)\nend sub\n")
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(helper),
            componentFiles = listOf(widget),
            components = listOf(component("Widget", "pkg:/components/Widget/WidgetKt.brs")),
        )
        val report = ComponentIncludeValidator.renderReport(result)
        assertTrue(report.contains("Component 'Widget'"))
        assertTrue(report.contains("UNDEFINED: ghost"))
        assertTrue(report.contains("MISSING INCLUDE: doHelp"))
        assertTrue(report.contains("WidgetKt.brs"))
        assertTrue(report.contains("HelperKt.brs"))
    }

    @Test
    fun `empty component list yields zero component count for the task-level guard`() {
        val result = ComponentIncludeValidator.validate(
            sourceFiles = listOf(brs("source/AKt.brs", "function a()\nend function\n")),
            componentFiles = emptyList(),
            components = emptyList(),
        )
        assertEquals(0, result.componentCount)
        assertEquals(emptyList<Finding>(), result.findings)
    }
}
