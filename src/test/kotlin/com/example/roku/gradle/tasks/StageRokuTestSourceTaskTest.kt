package com.example.roku.gradle.tasks

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StageRokuTestSourceTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): File = tmp.newFolder(name)

    private fun File.write(relPath: String, content: String): File {
        val f = File(this, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `missing test dir fails loudly`() {
        val e = try {
            StageRokuTestSourceTask.stageInto(File(tmp.root, "nope"), dir("main"), dir("out")) {}
            null
        } catch (e: GradleException) {
            e
        }
        assertTrue(e != null && e.message!!.contains("No compiled test sources"))
        assertTrue(e!!.message!!.contains("src/brsTest/kotlin"))
    }

    @Test
    fun `empty test dir fails loudly`() {
        val e = try {
            StageRokuTestSourceTask.stageInto(dir("test"), dir("main"), dir("out")) {}
            null
        } catch (e: GradleException) {
            e
        }
        assertTrue(e != null && e.message!!.contains("No compiled test sources"))
    }

    @Test
    fun `stages test sources plus main sources without app entry point`() {
        val test = dir("test")
        test.write("TestMain.brs", "sub RunKotlinTests()\nend sub\n")
        val main = dir("main")
        main.write("main.brs", "sub main()\n  screen = CreateObject(\"roSGScreen\")\nend sub\n")
        main.write("MyClass.brs", "function MyClass_init()\nend function\n")
        main.write("nested/Helper.brs", "function Helper()\nend function\n")
        val out = dir("out")

        val staged = StageRokuTestSourceTask.stageInto(test, main, out) {}

        assertEquals(3, staged)
        assertTrue(File(out, "TestMain.brs").exists())
        assertTrue(File(out, "MyClass.brs").exists())
        assertTrue(File(out, "nested/Helper.brs").exists())
        assertFalse("app entry point must be excluded", File(out, "main.brs").exists())
    }

    @Test
    fun `test file wins name collision with main file`() {
        val test = dir("test")
        test.write("Config.brs", "' test version\n")
        val main = dir("main")
        main.write("Config.brs", "' main version\n")
        val out = dir("out")

        StageRokuTestSourceTask.stageInto(test, main, out) {}

        assertEquals("' test version\n", File(out, "Config.brs").readText())
    }

    @Test
    fun `entry point detection is case-insensitive and multiline`() {
        val test = dir("test")
        test.write("TestMain.brs", "sub RunKotlinTests()\nend sub\n")
        val main = dir("main")
        main.write("app.brs", "' header comment\n  Function MAIN ()\n  End Function\n")
        val out = dir("out")

        StageRokuTestSourceTask.stageInto(test, main, out) {}

        assertFalse(File(out, "app.brs").exists())
    }

    @Test
    fun `non-brs main files are staged without content check`() {
        val test = dir("test")
        test.write("TestMain.brs", "sub RunKotlinTests()\nend sub\n")
        val main = dir("main")
        main.write("main.brs.map", "sub main( <- looks like an entry point but is a sourcemap")
        val out = dir("out")

        StageRokuTestSourceTask.stageInto(test, main, out) {}

        assertTrue(File(out, "main.brs.map").exists())
    }

    @Test
    fun `functions merely named mainSomething are not excluded`() {
        val test = dir("test")
        test.write("TestMain.brs", "sub RunKotlinTests()\nend sub\n")
        val main = dir("main")
        main.write("MainScreen.brs", "function MainScreen_init()\nend function\n")
        val out = dir("out")

        StageRokuTestSourceTask.stageInto(test, main, out) {}

        assertTrue(File(out, "MainScreen.brs").exists())
    }
}
