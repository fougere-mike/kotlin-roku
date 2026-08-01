package com.example.roku.gradle.tasks

/**
 * A single JSON event emitted by the on-device test adapter (JsonTestAdapter).
 */
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

/**
 * State machine that interprets the Roku debug-console stream (telnet port 8085) for one test run.
 *
 * The Roku console buffer replays output from PREVIOUS runs, including stale
 * [KOTLINTEST_START]/[KOTLINTEST_END] markers. This parser therefore ignores ALL markers
 * until it sees a FRESH sentinel line `===KOTLINTEST_SENTINEL_<ts>===` whose timestamp is
 * within [sentinelFreshnessMs] of task start (JsonTestAdapter prints it at run start, floods
 * the buffer, then prints it again). BrightScript may print the timestamp in scientific
 * notation (e.g. 1.767901e+12), so it is parsed as a double.
 *
 * After a fresh sentinel arms the parser:
 * - `BrightScript Micro Debugger.` => crash: capture the following backtrace lines
 *   (up to [crashCaptureMaxLines] or [crashCaptureGraceMs]) and report [Outcome.Crashed].
 * - `[bs.ndk.proc.exit] ... EXIT_USER_NAV` without a subsequent [KOTLINTEST_END] within
 *   [exitGraceMs] => the app exited mid-run; report [Outcome.Crashed].
 *
 * Pure logic: callers supply `nowMs` on every call, so the machine is deterministic and
 * unit-testable. Mirrors Kotlin/libraries/stdlib/brs/test/run-tests.sh:229-520.
 */
internal class RokuTestStreamParser(
    private val taskStartMillis: Long,
    private val log: (String) -> Unit = {},
    private val sentinelFreshnessMs: Long = 120_000L,
    private val exitGraceMs: Long = 2_000L,
    private val crashCaptureMaxLines: Int = 60,
    private val crashCaptureGraceMs: Long = 2_000L,
) {
    sealed interface Outcome {
        object InProgress : Outcome
        object Completed : Outcome
        data class Crashed(
            val reason: String,
            val details: List<String>,
            val lastTestStarted: String?,
        ) : Outcome
    }

    private enum class Phase { AWAITING_SENTINEL, ARMED, EXIT_GRACE, CAPTURING_CRASH, DONE }

    val events = mutableListOf<TestEvent>()
    var onEvent: (TestEvent) -> Unit = {}

    private var phase = Phase.AWAITING_SENTINEL
    private var inTestOutput = false
    private var exitDeadlineMs = 0L
    private var exitLine: String? = null
    private var crashDeadlineMs = 0L
    private val crashLines = mutableListOf<String>()

    fun onLine(line: String, nowMs: Long): Outcome {
        // A fresh sentinel in ANY active state starts (or restarts) the trusted window - the
        // streaming equivalent of the shell runner taking the LAST sentinel (run-tests.sh:466).
        // Back-to-back runs <120s apart replay the previous run's still-fresh sentinel from the
        // console buffer; arming only once would consume that replayed run's markers as current.
        if (phase != Phase.DONE && handleSentinel(line)) {
            return Outcome.InProgress
        }
        return when (phase) {
            Phase.AWAITING_SENTINEL -> Outcome.InProgress
            Phase.ARMED -> onArmedLine(line, nowMs)
            Phase.EXIT_GRACE -> onExitGraceLine(line, nowMs)
            Phase.CAPTURING_CRASH -> onCrashCaptureLine(line, nowMs)
            Phase.DONE -> Outcome.InProgress
        }
    }

    /**
     * Called when no more lines are available (stream closed or read timed out).
     * Turns a pending exit-grace or crash-capture state into its final crash verdict.
     */
    fun onStreamEnd(nowMs: Long): Outcome {
        return when (phase) {
            Phase.CAPTURING_CRASH -> {
                phase = Phase.DONE
                debuggerCrash()
            }
            Phase.EXIT_GRACE -> {
                phase = Phase.DONE
                appExitedCrash()
            }
            else -> Outcome.InProgress
        }
    }

    /**
     * Returns true when the line is a FRESH sentinel, after (re)arming on it. A fresh sentinel
     * seen while already armed means a newer run started (or the adapter's own second print
     * after the buffer flood, where the reset is a harmless no-op): everything collected so far
     * was replayed from the console buffer and must be discarded.
     */
    private fun handleSentinel(line: String): Boolean {
        val match = SENTINEL_REGEX.find(line) ?: return false
        val sentinelMs = match.groupValues[1].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?.toLong()
        if (sentinelMs == null) {
            log("Ignoring sentinel with unparseable timestamp: ${match.value}")
            return false
        }
        // Signed diff, mirroring run-tests.sh: only sentinels OLDER than the window are stale.
        val ageMs = taskStartMillis - sentinelMs
        if (ageMs > sentinelFreshnessMs) {
            log("Ignoring stale sentinel from a previous run (${ageMs / 1000}s before task start): ${match.value}")
            return false
        }
        if (phase != Phase.AWAITING_SENTINEL && events.isNotEmpty()) {
            log("Newer fresh sentinel found - discarding ${events.size} replayed event(s) from a previous run")
        }
        events.clear()
        inTestOutput = false
        exitLine = null
        exitDeadlineMs = 0L
        crashLines.clear()
        crashDeadlineMs = 0L
        phase = Phase.ARMED
        log("Fresh sentinel found (${ageMs / 1000}s before task start) - now trusting test markers")
        return true
    }

    private fun onArmedLine(line: String, nowMs: Long): Outcome {
        when {
            line.contains(DEBUGGER_MARKER) -> {
                crashLines.add(line)
                crashDeadlineMs = nowMs + crashCaptureGraceMs
                phase = Phase.CAPTURING_CRASH
            }
            EXIT_REGEX.containsMatchIn(line) -> {
                exitLine = line
                exitDeadlineMs = nowMs + exitGraceMs
                phase = Phase.EXIT_GRACE
                log("App exit detected - waiting ${exitGraceMs}ms for [KOTLINTEST_END]...")
            }
            line.contains(START_MARKER) -> {
                inTestOutput = true
                log("Test run started")
                log("─".repeat(60))
            }
            line.contains(END_MARKER) -> {
                phase = Phase.DONE
                log("─".repeat(60))
                log("Test run completed")
                return Outcome.Completed
            }
            inTestOutput && line.trim().startsWith("{") -> {
                parseTestEvent(line.trim())?.let { event ->
                    events.add(event)
                    onEvent(event)
                }
            }
        }
        return Outcome.InProgress
    }

    private fun onExitGraceLine(line: String, nowMs: Long): Outcome {
        // The END marker may still be buffered behind the exit line; accept it whenever it shows up.
        if (line.contains(END_MARKER)) {
            phase = Phase.DONE
            log("Test run completed (end marker arrived after app exit)")
            return Outcome.Completed
        }
        if (line.contains(DEBUGGER_MARKER)) {
            crashLines.add(line)
            crashDeadlineMs = nowMs + crashCaptureGraceMs
            phase = Phase.CAPTURING_CRASH
            return Outcome.InProgress
        }
        if (nowMs > exitDeadlineMs) {
            phase = Phase.DONE
            return appExitedCrash()
        }
        return Outcome.InProgress
    }

    private fun onCrashCaptureLine(line: String, nowMs: Long): Outcome {
        crashLines.add(line)
        val backtraceComplete = line.startsWith(DEBUGGER_PROMPT)
        if (backtraceComplete || crashLines.size >= crashCaptureMaxLines || nowMs > crashDeadlineMs) {
            phase = Phase.DONE
            return debuggerCrash()
        }
        return Outcome.InProgress
    }

    private fun debuggerCrash() = Outcome.Crashed(
        reason = "BrightScript Micro Debugger entered - the app crashed during the test run",
        details = crashLines.toList(),
        lastTestStarted = lastTestStarted(),
    )

    private fun appExitedCrash() = Outcome.Crashed(
        reason = "App exited during tests (EXIT_USER_NAV without [KOTLINTEST_END])",
        details = listOfNotNull(exitLine),
        lastTestStarted = lastTestStarted(),
    )

    private fun lastTestStarted(): String? =
        events.lastOrNull { it.type == "test_start" }?.let { "${it.suite} > ${it.test}" }

    private fun parseTestEvent(json: String): TestEvent? {
        return try {
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

    companion object {
        // Timestamp may be scientific notation (BrightScript double formatting), e.g. 1.767901e+12
        private val SENTINEL_REGEX = Regex("===KOTLINTEST_SENTINEL_([0-9.eE+-]+)===")
        private val EXIT_REGEX = Regex("""\[bs\.ndk\.proc\.exit\].*EXIT_USER_NAV""")
        private const val DEBUGGER_MARKER = "BrightScript Micro Debugger."
        private const val DEBUGGER_PROMPT = "Brightscript Debugger>"
        private const val START_MARKER = "[KOTLINTEST_START]"
        private const val END_MARKER = "[KOTLINTEST_END]"
    }
}
