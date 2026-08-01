package com.example.roku.gradle.tasks

import com.example.roku.gradle.tasks.RokuTestStreamParser.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuTestStreamParserTest {

    private val taskStart = 1_767_901_000_000L
    private val logLines = mutableListOf<String>()

    private fun parser() = RokuTestStreamParser(
        taskStartMillis = taskStart,
        log = { logLines.add(it) },
    )

    private fun freshSentinel(offsetMs: Long = 5_000L) =
        "===KOTLINTEST_SENTINEL_${taskStart + offsetMs}==="

    private val exitLine =
        "07-28 12:00:01.000 [bs.ndk.proc.exit] pid=1234 status=0 EXIT_USER_NAV"

    @Test
    fun `markers before any sentinel are ignored`() {
        val p = parser()
        assertEquals(Outcome.InProgress, p.onLine("[KOTLINTEST_START]", taskStart))
        assertEquals(Outcome.InProgress, p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart))
        assertEquals(Outcome.InProgress, p.onLine("[KOTLINTEST_END]", taskStart))
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `crash markers before any sentinel are ignored`() {
        val p = parser()
        assertEquals(Outcome.InProgress, p.onLine("BrightScript Micro Debugger.", taskStart))
        assertEquals(Outcome.InProgress, p.onLine(exitLine, taskStart))
        // A fresh run afterwards still completes normally
        p.onLine(freshSentinel(), taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
    }

    @Test
    fun `stale sentinel does not arm and is logged`() {
        val p = parser()
        p.onLine("===KOTLINTEST_SENTINEL_${taskStart - 600_000}===", taskStart)
        assertEquals(Outcome.InProgress, p.onLine("[KOTLINTEST_END]", taskStart))
        assertTrue(logLines.any { it.contains("stale sentinel") })
    }

    @Test
    fun `stale sentinel then fresh sentinel arms`() {
        val p = parser()
        p.onLine("===KOTLINTEST_SENTINEL_${taskStart - 600_000}===", taskStart)
        p.onLine(freshSentinel(), taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
    }

    @Test
    fun `fresh sentinel then run completes with events collected`() {
        val p = parser()
        val seen = mutableListOf<TestEvent>()
        p.onEvent = { seen.add(it) }

        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"StringSuite","test":"testConcat","duration_ms":12}""", taskStart)
        val outcome = p.onLine("[KOTLINTEST_END]", taskStart)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(1, seen.size)
        assertEquals("test_pass", seen[0].type)
        assertEquals(12, seen[0].durationMs)
    }

    @Test
    fun `scientific notation sentinel timestamp is parsed`() {
        // BrightScript prints large doubles in scientific notation (run-tests.sh:471-478).
        // 1.767901e+12 == 1_767_901_000_000 == taskStart exactly.
        val p = parser()
        p.onLine("===KOTLINTEST_SENTINEL_1.767901e+12===", taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
    }

    @Test
    fun `future-skewed sentinel is fresh`() {
        // Sci-notation truncation can round the device timestamp past task start;
        // like run-tests.sh, only sentinels OLDER than the window are stale.
        val p = parser()
        p.onLine(freshSentinel(offsetMs = 300_000L), taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
    }

    @Test
    fun `sentinel with unparseable timestamp does not arm`() {
        val p = parser()
        p.onLine("===KOTLINTEST_SENTINEL_..e++===", taskStart)
        assertEquals(Outcome.InProgress, p.onLine("[KOTLINTEST_END]", taskStart))
    }

    @Test
    fun `debugger entry captures backtrace and fails`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_start","suite":"ListSuite","test":"testAdd"}""", taskStart)

        assertEquals(Outcome.InProgress, p.onLine("BrightScript Micro Debugger.", taskStart))
        assertEquals(Outcome.InProgress, p.onLine("Divide by Zero. (runtime error &h14) in pkg:/source/main.brs(42)", taskStart))
        assertEquals(Outcome.InProgress, p.onLine("Backtrace:", taskStart))
        val outcome = p.onLine("Brightscript Debugger> ", taskStart)

        assertTrue(outcome is Outcome.Crashed)
        outcome as Outcome.Crashed
        assertTrue(outcome.reason.contains("Micro Debugger"))
        assertEquals("ListSuite > testAdd", outcome.lastTestStarted)
        assertTrue(outcome.details.any { it.contains("Divide by Zero") })
    }

    @Test
    fun `debugger capture stops at line limit`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("BrightScript Micro Debugger.", taskStart)

        var outcome: Outcome = Outcome.InProgress
        for (i in 1..100) {
            outcome = p.onLine("backtrace line $i", taskStart)
            if (outcome !is Outcome.InProgress) break
        }
        assertTrue(outcome is Outcome.Crashed)
        // 60 lines total: the debugger marker line + 59 captured backtrace lines
        assertEquals(60, (outcome as Outcome.Crashed).details.size)
    }

    @Test
    fun `debugger capture finishes on stream end`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("BrightScript Micro Debugger.", taskStart)
        p.onLine("Backtrace:", taskStart)

        val outcome = p.onStreamEnd(taskStart)
        assertTrue(outcome is Outcome.Crashed)
        assertEquals(2, (outcome as Outcome.Crashed).details.size)
    }

    @Test
    fun `exit followed by end marker within grace completes`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine(exitLine, taskStart)
        val outcome = p.onLine("[KOTLINTEST_END]", taskStart + 1_000)
        assertEquals(Outcome.Completed, outcome)
    }

    @Test
    fun `exit without end marker fails after grace deadline`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_start","suite":"CoroutineSuite","test":"testLaunch"}""", taskStart)
        p.onLine(exitLine, taskStart)

        assertEquals(Outcome.InProgress, p.onLine("some other log line", taskStart + 1_000))
        val outcome = p.onLine("another log line", taskStart + 3_000)

        assertTrue(outcome is Outcome.Crashed)
        outcome as Outcome.Crashed
        assertTrue(outcome.reason.contains("exited during tests"))
        assertEquals("CoroutineSuite > testLaunch", outcome.lastTestStarted)
    }

    @Test
    fun `exit then stream end fails`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine(exitLine, taskStart)
        val outcome = p.onStreamEnd(taskStart + 500)
        assertTrue(outcome is Outcome.Crashed)
        assertTrue((outcome as Outcome.Crashed).reason.contains("exited during tests"))
    }

    @Test
    fun `stream end in normal states is not a crash`() {
        assertEquals(Outcome.InProgress, parser().onStreamEnd(taskStart))
        val armed = parser()
        armed.onLine(freshSentinel(), taskStart)
        assertEquals(Outcome.InProgress, armed.onStreamEnd(taskStart))
    }

    @Test
    fun `json lines outside START and END are not collected`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart)
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `replayed previous run is discarded when a newer fresh sentinel arrives`() {
        // Back-to-back runs <120s apart: the console buffer replays the previous run's
        // sentinel (still within the freshness window) and its START/events. The newer
        // run's sentinel must reset the window so only the new run's events count.
        val p = parser()
        p.onLine(freshSentinel(offsetMs = -60_000L), taskStart)  // previous run, 60s old but "fresh"
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"OldSuite","test":"oldTest"}""", taskStart)
        assertEquals(1, p.events.size)

        p.onLine(freshSentinel(offsetMs = 1_000L), taskStart)     // current run's sentinel
        assertTrue(p.events.isEmpty())
        assertTrue(logLines.any { it.contains("discarding 1 replayed event") })

        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"NewSuite","test":"newTest"}""", taskStart)
        val outcome = p.onLine("[KOTLINTEST_END]", taskStart)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(1, p.events.size)
        assertEquals("NewSuite", p.events[0].suite)
        assertEquals("newTest", p.events[0].test)
    }

    @Test
    fun `re-arm resets inTestOutput - json after newer sentinel needs a new START`() {
        val p = parser()
        p.onLine(freshSentinel(offsetMs = -90_000L), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"OldSuite","test":"oldTest"}""", taskStart)
        p.onLine(freshSentinel(offsetMs = 2_000L), taskStart)
        assertTrue(p.events.isEmpty())
        p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart)
        assertTrue("not in test output until new START", p.events.isEmpty())
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
        assertEquals(1, p.events.size)
    }

    @Test
    fun `newer fresh sentinel during EXIT_GRACE re-arms instead of failing`() {
        // A replayed exit line from the previous run puts the parser in EXIT_GRACE;
        // the current run's sentinel proves a new run started - re-arm, don't fail.
        val p = parser()
        p.onLine(freshSentinel(offsetMs = -60_000L), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine(exitLine, taskStart)

        // Even past the 2s grace deadline the sentinel wins over the crash verdict
        assertEquals(Outcome.InProgress, p.onLine(freshSentinel(offsetMs = 1_000L), taskStart + 5_000))

        p.onLine("[KOTLINTEST_START]", taskStart + 5_000)
        p.onLine("""{"type":"test_pass","suite":"NewSuite","test":"newTest"}""", taskStart + 5_000)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart + 5_000))
        assertEquals(1, p.events.size)
        // Exit state was reset: stream end afterwards is not a crash
        assertEquals(Outcome.InProgress, p.onStreamEnd(taskStart + 6_000))
    }

    @Test
    fun `newer fresh sentinel during crash capture discards the stale crash`() {
        // Previous run crashed; its replayed debugger output starts a crash capture.
        // The current run's sentinel arriving within the capture window re-arms.
        val p = parser()
        p.onLine(freshSentinel(offsetMs = -60_000L), taskStart)
        p.onLine("BrightScript Micro Debugger.", taskStart)
        p.onLine("Divide by Zero. (runtime error &h14)", taskStart)

        assertEquals(Outcome.InProgress, p.onLine(freshSentinel(offsetMs = 1_000L), taskStart))

        p.onLine("[KOTLINTEST_START]", taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
        // Crash state was reset: stream end afterwards is not a crash
        assertEquals(Outcome.InProgress, p.onStreamEnd(taskStart))
    }

    @Test
    fun `adapter second sentinel print after flood is a harmless re-arm`() {
        // JsonTestAdapter prints the sentinel, floods 100 lines, prints the SAME sentinel
        // again, THEN emits START. The second print resets before any events exist.
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        for (i in 0 until 100) p.onLine("[KOTLINTEST_BUFFER_FLUSH:123:$i]", taskStart)
        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
        assertEquals(1, p.events.size)
    }

    @Test
    fun `stale sentinel while armed does not reset the current run`() {
        val p = parser()
        p.onLine(freshSentinel(), taskStart)
        p.onLine("[KOTLINTEST_START]", taskStart)
        p.onLine("""{"type":"test_pass","suite":"S","test":"t"}""", taskStart)
        p.onLine("===KOTLINTEST_SENTINEL_${taskStart - 600_000}===", taskStart)
        assertEquals(1, p.events.size)
        assertEquals(Outcome.Completed, p.onLine("[KOTLINTEST_END]", taskStart))
    }
}
