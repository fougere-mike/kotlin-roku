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
 * [KOTLINTEST_START] and [KOTLINTEST_END] markers.
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
        var inTestOutput = false
        var testCompleted = false
        val startTime = System.currentTimeMillis()

        try {
            Socket(ip, 8085).use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                while (!testCompleted) {
                    val line = reader.readLine() ?: break

                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        throw GradleException("Test execution timed out after ${timeoutMs / 1000} seconds")
                    }

                    when {
                        line.contains("[KOTLINTEST_START]") -> {
                            inTestOutput = true
                            logger.lifecycle("Test run started")
                            logger.lifecycle("─".repeat(60))
                        }
                        line.contains("[KOTLINTEST_END]") -> {
                            inTestOutput = false
                            testCompleted = true
                            logger.lifecycle("─".repeat(60))
                            logger.lifecycle("Test run completed")
                        }
                        inTestOutput && line.trim().startsWith("{") -> {
                            val event = parseTestEvent(line.trim())
                            if (event != null) {
                                results.add(event)
                                logTestEvent(event)
                            }
                        }
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw GradleException("Connection to Roku device timed out. Device may be unresponsive.")
        } catch (e: Exception) {
            throw GradleException("Failed to connect to Roku device: ${e.message}")
        }

        // Write results
        writeJsonResults(results)
        writeJUnitXml(results)

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

    private fun parseTestEvent(json: String): TestEvent? {
        return try {
            // Simple JSON parsing without external dependencies
            val type = extractJsonString(json, "type") ?: return null
            TestEvent(
                type = type,
                suite = extractJsonString(json, "suite") ?: "",
                test = extractJsonString(json, "test") ?: "",
                message = extractJsonString(json, "message") ?: "",
                error = extractJsonString(json, "error") ?: "",
                expected = extractJsonString(json, "expected") ?: "",
                actual = extractJsonString(json, "actual") ?: "",
                reason = extractJsonString(json, "reason") ?: "",
                durationMs = extractJsonInt(json, "duration_ms") ?: 0,
                timestamp = extractJsonLong(json, "timestamp") ?: 0L,
                passed = extractJsonInt(json, "passed") ?: 0,
                failed = extractJsonInt(json, "failed") ?: 0,
                ignored = extractJsonInt(json, "ignored") ?: 0,
                totalTests = extractJsonInt(json, "total_tests") ?: 0,
                totalSuites = extractJsonInt(json, "total_suites") ?: 0
            )
        } catch (e: Exception) {
            logger.debug("Failed to parse test event: $json - ${e.message}")
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
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

    data class TestEvent(
        val type: String,
        val suite: String = "",
        val test: String = "",
        val message: String = "",
        val error: String = "",
        val expected: String = "",
        val actual: String = "",
        val reason: String = "",
        val durationMs: Int = 0,
        val timestamp: Long = 0L,
        val passed: Int = 0,
        val failed: Int = 0,
        val ignored: Int = 0,
        val totalTests: Int = 0,
        val totalSuites: Int = 0
    ) {
        fun toJson(): String {
            val parts = mutableListOf<String>()
            parts.add(""""type":"$type"""")
            if (suite.isNotEmpty()) parts.add(""""suite":"$suite"""")
            if (test.isNotEmpty()) parts.add(""""test":"$test"""")
            if (message.isNotEmpty()) parts.add(""""message":"${escapeJsonString(message)}"""")
            if (error.isNotEmpty()) parts.add(""""error":"$error"""")
            if (expected.isNotEmpty()) parts.add(""""expected":"${escapeJsonString(expected)}"""")
            if (actual.isNotEmpty()) parts.add(""""actual":"${escapeJsonString(actual)}"""")
            if (reason.isNotEmpty()) parts.add(""""reason":"$reason"""")
            if (durationMs > 0) parts.add(""""duration_ms":$durationMs""")
            if (timestamp > 0) parts.add(""""timestamp":$timestamp""")
            if (passed > 0) parts.add(""""passed":$passed""")
            if (failed > 0) parts.add(""""failed":$failed""")
            if (ignored > 0) parts.add(""""ignored":$ignored""")
            if (totalTests > 0) parts.add(""""total_tests":$totalTests""")
            if (totalSuites > 0) parts.add(""""total_suites":$totalSuites""")
            return "{${parts.joinToString(",")}}"
        }

        private fun escapeJsonString(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }

    data class TestSummary(
        val passed: Int,
        val failed: Int,
        val ignored: Int,
        val total: Int,
        val durationMs: Int
    )
}
