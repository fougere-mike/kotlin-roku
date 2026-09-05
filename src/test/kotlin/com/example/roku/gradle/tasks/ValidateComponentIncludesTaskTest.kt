package com.example.roku.gradle.tasks

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Task-level tests for the strict/warning gate and the anti-vacuous-pass guards.
 * The scanner logic itself is covered by [ComponentIncludeValidatorTest].
 */
class ValidateComponentIncludesTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(relPath: String, content: String): File {
        val f = File(tmp.root, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    /** Enough definitions to clear MIN_PLAUSIBLE_DEFINITIONS so guard tests stay orthogonal. */
    private fun fillerDefinitions(): String =
        (1..ComponentIncludeValidator.MIN_PLAUSIBLE_DEFINITIONS + 20)
            .joinToString("\n") { "function filler_$it()\nend function" }

    /**
     * A synthetic package with a planted include hole: Widget's XML lists only its own
     * .brs, which calls doHelp() defined in the staged-but-unlisted source/HelperKt.brs.
     */
    private fun makeTask(mode: String, plantHole: Boolean = true, components: Boolean = true): ValidateComponentIncludesTask {
        write("staged/FillerKt.brs", fillerDefinitions())
        write("staged/HelperKt.brs", "function doHelp(x)\n return x\nend function\n")
        if (components) {
            val call = if (plantHole) "  y = doHelp(1)\n" else ""
            write("compiled/WidgetKt.brs", "sub Widget_init()\n$call" + "end sub\n")
            write(
                "compiled/components/Widget/Widget.xml",
                "<component name=\"Widget\" extends=\"Group\">\n" +
                    "  <script type=\"text/brightscript\" uri=\"pkg:/components/Widget/WidgetKt.brs\" />\n" +
                    "</component>\n"
            )
        }
        write("processed/.keep", "")

        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks
            .register("validateComponentIncludes", ValidateComponentIncludesTask::class.java)
            .get()
        task.processedXmlDir.set(File(tmp.root, "processed"))
        task.compiledComponentsDir.set(File(tmp.root, "compiled"))
        task.stagedSourceDir.set(File(tmp.root, "staged"))
        task.mode.set(mode)
        task.extraBuiltins.set(emptySet())
        task.reportFile.set(File(tmp.root, "report.txt"))
        return task
    }

    private fun runExpectingFailure(task: ValidateComponentIncludesTask): GradleException? =
        try {
            task.validate()
            null
        } catch (e: GradleException) {
            e
        }

    @Test
    fun `strict mode fails on planted include hole naming file and call`() {
        val e = runExpectingFailure(makeTask("strict"))
        assertNotNull("strict mode must fail on an include hole", e)
        assertTrue(e!!.message!!.contains("doHelp"))
        assertTrue(e.message!!.contains("WidgetKt.brs"))
        assertTrue(e.message!!.contains("HelperKt.brs"))
    }

    @Test
    fun `warning mode does not fail on planted include hole but writes the finding to the report`() {
        val task = makeTask("warning")
        task.validate()
        val report = File(tmp.root, "report.txt").readText()
        assertTrue(report.contains("MISSING INCLUDE: doHelp"))
        assertTrue(report.contains("WidgetKt.brs"))
    }

    @Test
    fun `clean package passes in strict mode`() {
        val task = makeTask("strict", plantHole = false)
        task.validate()
        val report = File(tmp.root, "report.txt").readText()
        assertTrue(report.contains("0 finding(s)"))
    }

    @Test
    fun `implausibly small definition index fails even in warning mode`() {
        val task = makeTask("warning")
        // Sabotage the inputs: point the staged source at an empty dir so the definition
        // index collapses. A silent pass here would be exactly the vacuous-green trap.
        task.stagedSourceDir.set(tmp.newFolder("empty"))
        val e = runExpectingFailure(task)
        assertNotNull("tiny definition index must fail in warning mode too", e)
        assertTrue(e!!.message!!.contains("implausibly small"))
    }

    @Test
    fun `zero components fails in strict mode`() {
        val e = runExpectingFailure(makeTask("strict", components = false))
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("ZERO packaged component XMLs"))
    }

    @Test
    fun `zero components only warns in warning mode`() {
        val task = makeTask("warning", components = false)
        assertNull(runExpectingFailure(task))
    }

    @Test
    fun `unknown mode fails with a clear message`() {
        val e = runExpectingFailure(makeTask("lenient"))
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("includeMode"))
    }

    @Test
    fun `extra builtins suppress a finding`() {
        val task = makeTask("strict")
        task.extraBuiltins.set(setOf("doHelp"))
        task.validate()
        val report = File(tmp.root, "report.txt").readText()
        assertEquals(false, report.contains("doHelp"))
    }

    @Test
    fun `compiler-generated xml at the flat compiled path is discovered`() {
        // One compilation: compiler XML lives at <compiled>/<Name>/<Name>.xml, no extra
        // components/ level. A clean flat package must pass strict mode (not "zero components").
        write("staged/FillerKt.brs", fillerDefinitions())
        write("compiled/Widget/WidgetKt.brs", "sub Widget_init()\nend sub\n")
        write(
            "compiled/Widget/Widget.xml",
            "<component name=\"Widget\" extends=\"Group\">\n" +
                "  <script type=\"text/brightscript\" uri=\"pkg:/components/Widget/WidgetKt.brs\" />\n" +
                "</component>\n"
        )
        write("processed/.keep", "")
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks
            .register("validateComponentIncludesFlat", ValidateComponentIncludesTask::class.java)
            .get()
        task.processedXmlDir.set(File(tmp.root, "processed"))
        task.compiledComponentsDir.set(File(tmp.root, "compiled"))
        task.stagedSourceDir.set(File(tmp.root, "staged"))
        task.mode.set("strict")
        task.extraBuiltins.set(emptySet())
        task.reportFile.set(File(tmp.root, "report.txt"))

        task.validate()

        assertTrue(File(tmp.root, "report.txt").readText().contains("0 finding(s)"))
    }

    /**
     * Base component B (extends Group) defines B_describe_k_ in its own script; leaf L extends B
     * and lists only its own script, whose body is [leafBody]. Mirrors the compiler's super.f()
     * emission: a static call into the base component's global, in scope through SceneGraph's
     * extends chain without the base's script in the leaf XML.
     */
    private fun makeExtendsChainTask(leafBody: String): ValidateComponentIncludesTask {
        write("staged/FillerKt.brs", fillerDefinitions())
        write("compiled/B/b.brs", "function B_describe_k_() as String\n  return \"base\"\nend function\n")
        write(
            "compiled/B/B.xml",
            "<component name=\"B\" extends=\"Group\">\n" +
                "  <script type=\"text/brightscript\" uri=\"pkg:/components/B/b.brs\" />\n" +
                "</component>\n"
        )
        write("compiled/L/l.brs", "function L_describe_k_() as String\n$leafBody" + "end function\n")
        write(
            "compiled/L/L.xml",
            "<component name=\"L\" extends=\"B\">\n" +
                "  <script type=\"text/brightscript\" uri=\"pkg:/components/L/l.brs\" />\n" +
                "</component>\n"
        )
        write("processed/.keep", "")
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks
            .register("validateComponentIncludesExtends", ValidateComponentIncludesTask::class.java)
            .get()
        task.processedXmlDir.set(File(tmp.root, "processed"))
        task.compiledComponentsDir.set(File(tmp.root, "compiled"))
        task.stagedSourceDir.set(File(tmp.root, "staged"))
        task.mode.set("strict")
        task.extraBuiltins.set(emptySet())
        task.reportFile.set(File(tmp.root, "report.txt"))
        return task
    }

    @Test
    fun `leaf calling a base component global through the extends chain is not a finding`() {
        val task = makeExtendsChainTask("  return B_describe_k_() + \"+leaf\"\n")

        task.validate()

        assertTrue(File(tmp.root, "report.txt").readText().contains("0 finding(s)"))
    }

    @Test
    fun `extends chain does not excuse a call defined nowhere`() {
        val task = makeExtendsChainTask("  return Zzz_k_()\n")

        val e = runExpectingFailure(task)

        assertNotNull("strict mode must still fail on an undefined call from an extending leaf", e)
        val report = File(tmp.root, "report.txt").readText()
        assertTrue(report.contains("1 finding(s)"))
        assertTrue(report.contains("UNDEFINED: Zzz_k_"))
        assertTrue(report.contains("l.brs"))
    }
}
