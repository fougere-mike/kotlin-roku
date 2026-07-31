package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Assembles the source/ payload for the Roku TEST package.
 *
 * Stages into [outputDir]:
 * - ALL compiled test sources (build/brs/brs/test/source) — the test app's entry point lives here
 * - compiled MAIN sources (build/brs/brs/main/source) EXCLUDING any file that declares the app
 *   entry point (sub/function main). Main classes must be staged because component XML
 *   <script> imports reference them — a missing import is a hard load failure on device.
 *
 * Fails loudly when there are no compiled test sources: packaging the demo app as a "test app"
 * (the old silent fallback) produced green-looking runs that never executed a single test.
 */
abstract class StageRokuTestSourceTask : DefaultTask() {

    /** Compiled test sources. Declared as InputFiles (not InputDirectory) so a missing dir is diagnosed by us, not Gradle. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mainSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val staged = stageInto(
            testDir = testSourceDir.get().asFile,
            mainDir = mainSourceDir.get().asFile,
            out = outputDir.get().asFile,
            log = { logger.info(it) },
        )
        logger.lifecycle("Staged Roku test sources: $staged files -> ${outputDir.get().asFile.absolutePath}")
    }

    companion object {
        /** Matches a BrightScript app entry point declaration: `sub main(` / `function Main(`. */
        val MAIN_ENTRY_REGEX = Regex("(?im)^\\s*(sub|function)\\s+main\\s*\\(")

        /**
         * Pure staging logic (unit-tested separately from the Gradle machinery).
         * Returns the number of staged files.
         */
        internal fun stageInto(testDir: File, mainDir: File, out: File, log: (String) -> Unit): Int {
            val testFiles = testDir.takeIf { it.isDirectory }
                ?.walkTopDown()?.filter { it.isFile }?.toList()
                .orEmpty()

            if (testFiles.isEmpty()) {
                throw GradleException(
                    "No compiled test sources. Add Kotlin test sources under src/brsTest/kotlin " +
                        "with a fun main() that calls runTests {} — see " +
                        "roku-test-app/src/brsTest/kotlin/tests/TestMain.kt."
                )
            }

            out.deleteRecursively()
            out.mkdirs()

            // Test sources first: on a name collision the test file wins.
            for (file in testFiles) {
                copyPreservingPath(file, testDir, out)
            }

            if (mainDir.isDirectory) {
                for (file in mainDir.walkTopDown().filter { it.isFile }) {
                    if (file.extension == "brs" && MAIN_ENTRY_REGEX.containsMatchIn(file.readText())) {
                        log("stageRokuTestSource: excluding app entry point ${file.name}")
                        continue
                    }
                    val target = File(out, file.relativeTo(mainDir).path)
                    if (target.exists()) continue
                    copyPreservingPath(file, mainDir, out)
                }
            }

            return out.walkTopDown().count { it.isFile }
        }

        private fun copyPreservingPath(file: File, baseDir: File, out: File) {
            val target = File(out, file.relativeTo(baseDir).path)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }
}
