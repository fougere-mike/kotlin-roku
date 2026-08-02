package com.example.roku.gradle.tasks

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the merged-staging collision guard: hybrid packages are zipped from the single
 * merged tree, so PackageRokuTask's zip-level duplicate guard can never fire for them —
 * cross-origin collisions must fail HERE, naming both origins.
 */
class MergeBrsOutputTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(relPath: String, content: String): File {
        val f = File(tmp.root, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    // ---- stageEntry (pure logic) ----

    @Test
    fun `stageEntry copies a fresh file and records its origin`() {
        val origins = mutableMapOf<String, File>()
        val out = tmp.newFolder("out")
        val src = write("in/A.brs", "content")

        val copied = MergeBrsOutputTask.stageEntry(origins, out, src, File(out, "source/A.brs"))

        assertTrue(copied)
        assertEquals("content", File(out, "source/A.brs").readText())
        assertEquals(src, origins["source/a.brs"])
    }

    @Test
    fun `stageEntry skips a byte-identical duplicate without failing`() {
        val origins = mutableMapOf<String, File>()
        val out = tmp.newFolder("out")
        val first = write("in1/A.brs", "same content")
        val second = write("in2/A.brs", "same content")
        MergeBrsOutputTask.stageEntry(origins, out, first, File(out, "source/A.brs"))

        val copied = MergeBrsOutputTask.stageEntry(origins, out, second, File(out, "source/A.brs"))

        assertFalse(copied)
        assertEquals("first origin keeps the claim", first, origins["source/a.brs"])
    }

    @Test
    fun `stageEntry fails on different content naming both origin paths`() {
        val origins = mutableMapOf<String, File>()
        val out = tmp.newFolder("out")
        val first = write("in1/Config.brs", "' version one\n")
        val second = write("in2/Config.brs", "' version two\n")
        MergeBrsOutputTask.stageEntry(origins, out, first, File(out, "source/Config.brs"))

        val e = try {
            MergeBrsOutputTask.stageEntry(origins, out, second, File(out, "source/Config.brs"))
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull("cross-origin collision must fail", e)
        assertTrue(e!!.message!!.contains("source/Config.brs"))
        assertTrue("must name the first origin", e.message!!.contains(first.absolutePath))
        assertTrue("must name the second origin", e.message!!.contains(second.absolutePath))
    }

    @Test
    fun `stageEntry collision key is case-insensitive like Roku package paths`() {
        val origins = mutableMapOf<String, File>()
        val out = tmp.newFolder("out")
        val first = write("in1/CONFIG.brs", "' version one\n")
        val second = write("in2/config.brs", "' version two\n")
        MergeBrsOutputTask.stageEntry(origins, out, first, File(out, "source/CONFIG.brs"))

        val e = try {
            MergeBrsOutputTask.stageEntry(origins, out, second, File(out, "source/config.brs"))
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull(e)
    }

    // ---- merge() (task level) ----

    private fun makeTask(): MergeBrsOutputTask {
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks.register("mergeTest", MergeBrsOutputTask::class.java).get()
        task.brighterScriptStaging.set(File(tmp.root, "bsStaging"))
        task.kotlinBrsSource.set(File(tmp.root, "ktSource"))
        task.mergedOutput.set(File(tmp.root, "merged"))
        return task
    }

    @Test
    fun `kotlin source colliding with brighterscript staging fails naming both origins`() {
        val bsFile = write("bsStaging/source/Config.brs", "' BrighterScript-compiled version\n")
        val ktFile = write("ktSource/Config.brs", "' Kotlin-compiled version\n")
        val task = makeTask()

        val e = try {
            task.merge()
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull("silent last-writer-wins must be dead", e)
        assertTrue(e!!.message!!.contains(bsFile.absolutePath))
        assertTrue(e.message!!.contains(ktFile.absolutePath))
    }

    @Test
    fun `disjoint kotlin and brighterscript files merge cleanly`() {
        write("bsStaging/source/Legacy.brs", "' legacy\n")
        write("bsStaging/manifest", "title=App\n")
        write("ktSource/NewKt.brs", "' new\n")
        val task = makeTask()

        task.merge()

        assertEquals("' legacy\n", File(tmp.root, "merged/source/Legacy.brs").readText())
        assertEquals("' new\n", File(tmp.root, "merged/source/NewKt.brs").readText())
        assertEquals("title=App\n", File(tmp.root, "merged/manifest").readText())
    }

    @Test
    fun `byte-identical duplicate across origins is skipped not fatal`() {
        write("bsStaging/source/Shared.brs", "' identical everywhere\n")
        write("ktSource/Shared.brs", "' identical everywhere\n")
        val task = makeTask()

        assertNull(
            "identical duplicate must not fail the merge",
            try {
                task.merge()
                null
            } catch (e: GradleException) {
                e
            }
        )
        assertEquals("' identical everywhere\n", File(tmp.root, "merged/source/Shared.brs").readText())
    }

    @Test
    fun `stdlib file shadowed by a different same-named file fails instead of silent drop`() {
        write("bsStaging/source/ArrayListKt.brs", "' stale vendored copy\n")
        val stdlib = write("stdlibJar/ArrayListKt.brs", "' real stdlib runtime\n")
        val task = makeTask()
        task.stdlibBrsFiles.from(stdlib)

        val e = try {
            task.merge()
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull("the old skip-if-exists silently dropped the real stdlib file", e)
        assertTrue(e!!.message!!.contains(stdlib.absolutePath))
    }
}
