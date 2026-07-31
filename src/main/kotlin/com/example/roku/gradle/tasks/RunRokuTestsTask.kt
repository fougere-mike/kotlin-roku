package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Gradle task that runs Kotlin tests on a Roku device and parses the JSON results.
 *
 * Connects to the Roku debug console (port 8085) and captures test output between
 * [KOTLINTEST_START] and [KOTLINTEST_END] markers. The console buffer replays stale
 * output from previous runs, so all markers are ignored until a fresh
 * ===KOTLINTEST_SENTINEL_<ts>=== line proves the stream belongs to THIS run
 * (see [RokuTestStreamParser]). Crashes (BrightScript Micro Debugger, premature app
 * exit) fail the task with a backtrace instead of hanging until timeout.
 *
 * Output:
 * - JSON results file
 * - JUnit XML report for CI integration
 * - Real-time console logging of pass/fail
 */
@UntrackedTask(because = "Test execution depends on device state")
abstract class RunRokuTestsTask : DefaultTask() {

    @get:Input
    abstract val deviceIp: Property<String>

    @get:Input
    @get:Optional
    abstract val testTimeout: Property<Long>

    @get:OutputFile
    abstract val resultsJson: RegularFileProperty

    @get:OutputFile
    abstract val resultsXml: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val ignoreFailures: Property<Boolean>

    init {
        testTimeout.convention(300_000L)  // 5 minutes default
        ignoreFailures.convention(false)
    }

    @TaskAction
    fun runTests() {
        val ip = deviceIp.get()
        val timeoutMs = testTimeout.get()

        if (ip.isBlank()) {
            throw GradleException(
                "Device IP not configured. Set roku.deviceIp in local.properties or ROKU_DEVICE_IP environment variable."
            )
        }

        logger.lifecycle("Running tests on Roku device at $ip...")
        logger.lifecycle("Timeout: ${timeoutMs / 1000} seconds")
        logger.lifecycle("")

        val results = mutableListOf<TestEvent>()
        val parser = RokuTestStreamParser(
            taskStartMillis = System.currentTimeMillis(),
            log = { logger.lifecycle(it) },
        )
        parser.onEvent = { event ->
            results.add(event)
            logTestEvent(event)
        }

        var testCompleted = false
        var crash: RokuTestStreamParser.Outcome.Crashed? = null
        val startTime = System.currentTimeMillis()

        try {
            Socket(ip, 8085).use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                while (!testCompleted && crash == null) {
                    val line = reader.readLine()
                    if (line == null) {
                        // Stream closed: a pending exit-grace/crash-capture is a crash verdict.
                        val outcome = parser.onStreamEnd(System.currentTimeMillis())
                        if (outcome is RokuTestStreamParser.Outcome.Crashed) crash = outcome
                        break
                    }

                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        throw GradleException("Test execution timed out after ${timeoutMs / 1000} seconds")
                    }

                    when (val outcome = parser.onLine(line, System.currentTimeMillis())) {
                        is RokuTestStreamParser.Outcome.Completed -> testCompleted = true
                        is RokuTestStreamParser.Outcome.Crashed -> crash = outcome
                        is RokuTestStreamParser.Outcome.InProgress -> Unit
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            val outcome = parser.onStreamEnd(System.currentTimeMillis())
            if (outcome is RokuTestStreamParser.Outcome.Crashed) {
                crash = outcome
            } else {
                throw GradleException("Connection to Roku device timed out. Device may be unresponsive.")
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to connect to Roku device: ${e.message}")
        }

        // Write results (even on crash - partial results help diagnosis)
        writeJsonResults(results)
        writeJUnitXml(results)

        crash?.let { throw GradleException(buildCrashMessage(it)) }

        if (!testCompleted) {
            throw GradleException(
                "Test run did not complete: no fresh [KOTLINTEST_END] marker received before the stream ended. " +
                    "The app may have crashed before startRun() or the build deployed a stale package."
            )
        }

        // Report summary
        val summary = calculateSummary(results)
        logger.lifecycle("")
        logger.lifecycle("Test Results:")
        logger.lifecycle("  Passed:  ${summary.passed}")
        logger.lifecycle("  Failed:  ${summary.failed}")
        logger.lifecycle("  Ignored: ${summary.ignored}")
        logger.lifecycle("  Total:   ${summary.total}")

        // Check for failures
        if (summary.failed > 0 && !ignoreFailures.get()) {
            throw GradleException("${summary.failed} test(s) failed")
        }

        if (summary.failed == 0) {
            logger.lifecycle("")
            logger.lifecycle("All ${summary.passed} test(s) passed!")
        }
    }

    private fun buildCrashMessage(crash: RokuTestStreamParser.Outcome.Crashed): String {
        return buildString {
            appendLine("APP CRASHED DURING TESTS")
            appendLine("Reason: ${crash.reason}")
            crash.lastTestStarted?.let { appendLine("Crash occurred during: $it") }
            if (crash.details.isNotEmpty()) {
                appendLine("Crash details:")
                appendLine("─".repeat(60))
                crash.details.forEach { appendLine(it) }
                append("─".repeat(60))
            }
        }
    }

    private fun logTestEvent(event: TestEvent) {
        when (event.type) {
            "suite_start" -> {
                logger.lifecycle("")
                logger.lifecycle("Suite: ${event.suite}")
            }
            "test_pass" -> {
                logger.lifecycle("  ✓ ${event.test} (${event.durationMs}ms)")
            }
            "test_fail" -> {
                logger.error("  ✗ ${event.test}: ${event.message}")
            }
            "test_error" -> {
                logger.error("  ✗ ${event.test}: ${event.error} - ${event.message}")
            }
            "test_ignored" -> {
                logger.warn("  ○ ${event.test} (ignored)")
            }
            "suite_end" -> {
                logger.lifecycle("  Suite completed: ${event.passed} passed, ${event.failed} failed")
            }
            "run_complete" -> {
                // Handled by summary logging
            }
        }
    }

    private fun writeJsonResults(results: List<TestEvent>) {
        val file = resultsJson.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(results.joinToString("\n") { it.toJson() })
        logger.lifecycle("JSON results written to: ${file.absolutePath}")
    }

    private fun writeJUnitXml(results: List<TestEvent>) {
        val file = resultsXml.get().asFile
        file.parentFile?.mkdirs()

        val summary = calculateSummary(results)
        val testCases = buildTestCases(results)

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<testsuite name="RokuTests" tests="${summary.total}" failures="${summary.failed}" skipped="${summary.ignored}" time="${summary.durationMs / 1000.0}">""")
            for (testCase in testCases) {
                appendLine(testCase)
            }
            appendLine("</testsuite>")
        }

        file.writeText(xml)
        logger.lifecycle("JUnit XML report written to: ${file.absolutePath}")
    }

    private fun buildTestCases(results: List<TestEvent>): List<String> {
        val testCases = mutableListOf<String>()
        var currentSuite = ""

        for (event in results) {
            when (event.type) {
                "suite_start" -> currentSuite = event.suite
                "test_pass" -> {
                    testCases.add(
                        """  <testcase classname="$currentSuite" name="${escapeXml(event.test)}" time="${event.durationMs / 1000.0}"/>"""
                    )
                }
                "test_fail" -> {
                    testCases.add(buildString {
                        appendLine("""  <testcase classname="$currentSuite" name="${escapeXml(event.test)}" time="${event.durationMs / 1000.0}">""")
                        appendLine("""    <failure message="${escapeXml(event.message)}">${escapeXml(event.message)}</failure>""")
                        append("  </testcase>")
                    })
                }
                "test_error" -> {
                    testCases.add(buildString {
                        appendLine("""  <testcase classname="$currentSuite" name="${escapeXml(event.test)}" time="${event.durationMs / 1000.0}">""")
                        appendLine("""    <error type="${escapeXml(event.error)}" message="${escapeXml(event.message)}">${escapeXml(event.message)}</error>""")
                        append("  </testcase>")
                    })
                }
                "test_ignored" -> {
                    testCases.add(buildString {
                        appendLine("""  <testcase classname="$currentSuite" name="${escapeXml(event.test)}">""")
                        appendLine("""    <skipped message="${escapeXml(event.reason)}"/>""")
                        append("  </testcase>")
                    })
                }
            }
        }

        return testCases
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun calculateSummary(results: List<TestEvent>): TestSummary {
        val runComplete = results.find { it.type == "run_complete" }
        if (runComplete != null) {
            return TestSummary(
                passed = runComplete.passed,
                failed = runComplete.failed,
                ignored = runComplete.ignored,
                total = runComplete.totalTests,
                durationMs = runComplete.durationMs
            )
        }

        // Fallback: calculate from individual events
        var passed = 0
        var failed = 0
        var ignored = 0
        var durationMs = 0

        for (event in results) {
            when (event.type) {
                "test_pass" -> {
                    passed++
                    durationMs += event.durationMs
                }
                "test_fail", "test_error" -> {
                    failed++
                    durationMs += event.durationMs
                }
                "test_ignored" -> ignored++
            }
        }

        return TestSummary(passed, failed, ignored, passed + failed + ignored, durationMs)
    }

    data class TestSummary(
        val passed: Int,
        val failed: Int,
        val ignored: Int,
        val total: Int,
        val durationMs: Int
    )
}
