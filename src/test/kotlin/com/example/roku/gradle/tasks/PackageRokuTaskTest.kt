package com.example.roku.gradle.tasks

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Task-level tests for PackageRokuTask's duplicate-entry guard: same-named files from
 * different origins must fail loudly naming BOTH origin paths, never die with an opaque
 * ZipException or silently shadow each other on device.
 */
class PackageRokuTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(relPath: String, content: String): File {
        val f = File(tmp.root, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    private fun makeTask(): PackageRokuTask {
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks.register("packageTest", PackageRokuTask::class.java).get()
        task.compiledBrs.set(File(tmp.root, "compiled"))
        task.outputZip.set(File(tmp.root, "out/app.zip"))
        return task
    }

    @Test
    fun `packages compiled brs files into source without incident`() {
        write("compiled/MainKt.brs", "sub main()\nend sub\n")
        write("compiled/nested/UtilKt.brs", "function util()\nend function\n")
        val task = makeTask()

        task.packageApp()

        ZipFile(File(tmp.root, "out/app.zip")).use { zip ->
            assertNotNull(zip.getEntry("source/MainKt.brs"))
            assertNotNull(zip.getEntry("source/nested/UtilKt.brs"))
        }
    }

    @Test
    fun `same-named brs from two origins fails naming both absolute paths`() {
        val fromCompiled = write("compiled/Config.brs", "' compiled version\n")
        val fromRuntimeJar = write("runtimeJar/Config.brs", "' runtime jar version\n")
        val task = makeTask()
        task.stdlibBrs.from(fromRuntimeJar)

        val e = try {
            task.packageApp()
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull("duplicate source/ entry must fail the build", e)
        assertTrue(e!!.message!!.contains("source/Config.brs"))
        assertTrue("must name the first origin", e.message!!.contains(fromCompiled.absolutePath))
        assertTrue("must name the second origin", e.message!!.contains(fromRuntimeJar.absolutePath))
    }

    @Test
    fun `collision detection is case-insensitive like Roku package paths`() {
        write("compiled/CONFIG.brs", "' compiled version\n")
        val fromRuntimeJar = write("runtimeJar/config.brs", "' runtime jar version\n")
        val task = makeTask()
        task.stdlibBrs.from(fromRuntimeJar)

        val e = try {
            task.packageApp()
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull("case-differing duplicate must still fail", e)
        assertTrue(e!!.message!!.contains(fromRuntimeJar.absolutePath))
    }
}
