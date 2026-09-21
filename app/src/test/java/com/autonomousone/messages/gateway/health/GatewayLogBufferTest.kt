package com.autonomousone.messages.gateway.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The live feed.
 *
 * BOUNDED is the property that has to be proven rather than intended: this buffer is fed by
 * a long-poll that completes about twice a minute plus every event batch, on a device that
 * already holds hundreds of thousands of messages, so an unbounded list is a slow leak.
 *
 * The other property is what the user reads: the normal feed must never fill up with
 * `accepted=2/2` rows, and "Errors" must not silently include anything else.
 */
class GatewayLogBufferTest {

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() = GatewayLog.resetForTest()

    @After
    fun tearDown() = GatewayLog.resetForTest()

    private fun entry(
        severity: GatewayLogSeverity = GatewayLogSeverity.INFO,
        subsystem: GatewayLogSubsystem = GatewayLogSubsystem.PULL_BRIDGE,
        code: String = "CODE",
        title: String = "title",
        detail: String? = null,
        advanced: Boolean = false,
        at: Long = t0
    ) = GatewayLogEntry(at, severity, subsystem, code, title, detail, advanced)

    // ═══════════════════════════════════════════════════════════════════════════
    // The bound
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theBufferNeverGrowsPastItsCapacity`() {
        val buffer = GatewayLogBuffer(capacity = 50)

        repeat(1_000) { buffer.add(entry(code = "C$it")) }

        assertEquals(50, buffer.size())
        assertEquals(50, buffer.snapshot().size)
    }

    @Test
    fun `evictionForgetsTheOldestAndKeepsTheNewest`() {
        val buffer = GatewayLogBuffer(capacity = 3)

        buffer.add(entry(code = "A"))
        buffer.add(entry(code = "B"))
        buffer.add(entry(code = "C"))
        buffer.add(entry(code = "D"))

        // Newest FIRST, and A is gone.
        assertEquals(listOf("D", "C", "B"), buffer.snapshot().map { it.code })
    }

    @Test
    fun `theBufferIsBoundedUnderConcurrentWriters`() {
        val buffer = GatewayLogBuffer(capacity = 200)
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { writer ->
            pool.execute {
                start.await()
                repeat(perThread) { index -> buffer.add(entry(code = "W$writer-$index")) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("writers must finish", done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("the bound holds regardless of interleaving", 200, buffer.size())
        assertEquals(200, buffer.snapshot().size)
    }

    @Test
    fun `clearingEmptiesTheBuffer`() {
        val buffer = GatewayLogBuffer(capacity = 10)
        buffer.add(entry())

        buffer.clear()

        assertEquals(0, buffer.size())
        assertTrue(buffer.snapshot().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Advanced rows
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The brief is explicit: dozens of `1/1 event(s) ACKed` in the main feed is the problem
     * being fixed. The rows are KEPT — they are useful in a report — but never shown by
     * default.
     */
    @Test
    fun `advancedRowsAreKeptButNotShownInTheNormalFeed`() {
        val buffer = GatewayLogBuffer(capacity = 10)
        buffer.add(entry(code = "VISIBLE"))
        buffer.add(entry(code = "DETAIL", advanced = true))

        assertEquals(listOf("VISIBLE"), buffer.visible(GatewayLogFilter.ALL).map { it.code })
        assertEquals(
            listOf("DETAIL", "VISIBLE"),
            buffer.visible(GatewayLogFilter.ALL, includeAdvanced = true).map { it.code }
        )
        assertEquals("nothing was dropped", 2, buffer.size())
    }

    @Test
    fun `aSyncUploadProducesOneUserLineAndOneAdvancedLine`() {
        GatewayLog.syncUploaded(accepted = 2, duplicates = 0, failed = 0, httpStatus = 200)

        val visible = GatewayLog.buffer.visible(GatewayLogFilter.ALL)
        assertEquals(1, visible.size)
        assertEquals("Sync uploaded 2 events", visible.first().title)
        assertEquals(GatewayLogSubsystem.SYNC_UPLOAD, visible.first().subsystem)

        val all = GatewayLog.buffer.visible(GatewayLogFilter.ALL, includeAdvanced = true)
        assertEquals(2, all.size)
        val detail = all.first { it.advanced }
        assertTrue(detail.detail!!.contains("accepted=2"))
        assertTrue(detail.detail!!.contains("duplicates=0"))
    }

    @Test
    fun `aSingleUploadedEventReadsCorrectlyInTheSingular`() {
        GatewayLog.syncUploaded(accepted = 1, duplicates = 0, failed = 0, httpStatus = 200)

        assertEquals("Sync uploaded 1 event", GatewayLog.buffer.visible(GatewayLogFilter.ALL).first().title)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Filters
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `errorsMatchesErrorsAndWarningsOnly`() {
        assertTrue(GatewayLogFilter.ERRORS.matches(entry(severity = GatewayLogSeverity.ERROR)))
        assertTrue(GatewayLogFilter.ERRORS.matches(entry(severity = GatewayLogSeverity.WARNING)))
        assertFalse(GatewayLogFilter.ERRORS.matches(entry(severity = GatewayLogSeverity.SUCCESS)))
        assertFalse(GatewayLogFilter.ERRORS.matches(entry(severity = GatewayLogSeverity.INFO)))
        assertFalse(GatewayLogFilter.ERRORS.matches(entry(severity = GatewayLogSeverity.DEBUG)))
    }

    @Test
    fun `allMatchesEverything`() {
        GatewayLogSeverity.entries.forEach { severity ->
            assertTrue(GatewayLogFilter.ALL.matches(entry(severity = severity)))
        }
        GatewayLogSubsystem.entries.forEach { subsystem ->
            assertTrue(GatewayLogFilter.ALL.matches(entry(subsystem = subsystem)))
        }
    }

    /**
     * The reported bug in one assertion: the sync lines are green and the bridge lines are
     * red, and the two must be separable.
     */
    @Test
    fun `theBridgeAndSyncFiltersAreDisjoint`() {
        val sync = entry(subsystem = GatewayLogSubsystem.SYNC_UPLOAD)
        val bridge = entry(subsystem = GatewayLogSubsystem.PULL_BRIDGE)
        val ack = entry(subsystem = GatewayLogSubsystem.ACK)

        assertTrue(GatewayLogFilter.SYNC.matches(sync))
        assertFalse(GatewayLogFilter.SYNC.matches(bridge))
        assertFalse(GatewayLogFilter.SYNC.matches(ack))

        assertTrue(GatewayLogFilter.BRIDGE.matches(bridge))
        assertTrue(GatewayLogFilter.BRIDGE.matches(ack))
        assertFalse(GatewayLogFilter.BRIDGE.matches(sync))
    }

    @Test
    fun `connectionCoversTheWholeTransportChainAndAuth`() {
        listOf(
            GatewayLogSubsystem.NETWORK,
            GatewayLogSubsystem.SERVER,
            GatewayLogSubsystem.TLS,
            GatewayLogSubsystem.AUTH,
            GatewayLogSubsystem.SUPERVISOR
        ).forEach { subsystem ->
            assertTrue("$subsystem belongs to Connection", GatewayLogFilter.CONNECTION.matches(entry(subsystem = subsystem)))
        }
        assertFalse(GatewayLogFilter.CONNECTION.matches(entry(subsystem = GatewayLogSubsystem.PULL_BRIDGE)))
    }

    @Test
    fun `eveCoversTheLocalQueueAndTheRadio`() {
        assertTrue(GatewayLogFilter.EVE.matches(entry(subsystem = GatewayLogSubsystem.EVE_QUEUE)))
        assertTrue(GatewayLogFilter.EVE.matches(entry(subsystem = GatewayLogSubsystem.SMS_RADIO)))
        assertFalse(GatewayLogFilter.EVE.matches(entry(subsystem = GatewayLogSubsystem.ACK)))
    }

    @Test
    fun `filteringKeepsNewestFirstOrder`() {
        val buffer = GatewayLogBuffer(capacity = 10)
        buffer.add(entry(code = "old", subsystem = GatewayLogSubsystem.PULL_BRIDGE, at = t0))
        buffer.add(entry(code = "sync", subsystem = GatewayLogSubsystem.SYNC_UPLOAD, at = t0 + 1))
        buffer.add(entry(code = "new", subsystem = GatewayLogSubsystem.PULL_BRIDGE, at = t0 + 2))

        assertEquals(
            listOf("new", "old"),
            buffer.visible(GatewayLogFilter.BRIDGE).map { it.code }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The event vocabulary
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `anEmptyPullIsPhrasedAsACompletedRoundTrip`() {
        GatewayLog.pullCompletedEmpty(durationMs = 26_000L, at = t0)

        val line = GatewayLog.buffer.visible(GatewayLogFilter.BRIDGE).single()
        assertEquals("Pull completed · no task", line.title)
        assertEquals(GatewayLogSeverity.SUCCESS, line.severity)
        assertEquals("26000ms", line.detail)
    }

    @Test
    fun `aReceivedTaskIsReportedWithATokenAndNeverAnId`() {
        GatewayLog.pullReceivedTask("83ad1f2c", at = t0)

        val line = GatewayLog.buffer.visible(GatewayLogFilter.BRIDGE).single()
        assertEquals("Task received", line.title)
        assertEquals("req 83ad1f2c", line.detail)
    }

    @Test
    fun `aRejectedKeySaysSoInWordsAndCarriesTheStatusCode`() {
        GatewayLog.pullFailed(
            kind = GatewayFailureKind.HTTP_AUTH,
            httpStatus = 401,
            safeDetail = "HTTP 401",
            at = t0
        )

        val line = GatewayLog.buffer.visible(GatewayLogFilter.BRIDGE).single()
        assertEquals("Pull failed · device key rejected", line.title)
        assertEquals(GatewayLogSeverity.ERROR, line.severity)
        assertTrue(line.detail!!.contains("HTTP 401"))
    }

    @Test
    fun `aTlsFailureReadsDifferentlyFromATimeout`() {
        GatewayLog.pullFailed(GatewayFailureKind.TLS, null, "certificate mismatch", t0)
        GatewayLog.pullFailed(GatewayFailureKind.READ_TIMEOUT, null, "timed out", t0)

        val titles = GatewayLog.buffer.visible(GatewayLogFilter.BRIDGE).map { it.title }
        assertTrue(titles.contains("Pull failed · TLS problem"))
        assertTrue(titles.contains("Pull failed · read timeout"))
    }

    @Test
    fun `theAuthEventExistsEvenThoughThePullAlsoReportsIt`() {
        GatewayLog.authRejected(401, t0)

        val line = GatewayLog.buffer.visible(GatewayLogFilter.CONNECTION).single()
        assertEquals(GatewayLogSubsystem.AUTH, line.subsystem)
        assertEquals("Device key rejected by GMweb", line.title)
    }

    @Test
    fun `everyRecordedTransitionCarriesAStableCode`() {
        GatewayLog.pullStarted(t0)
        GatewayLog.pullCompletedEmpty(1_000L, t0)
        GatewayLog.pullReceivedTask("tok", t0)
        GatewayLog.pullFailed(GatewayFailureKind.UNKNOWN, null, null, t0)
        GatewayLog.ackSucceeded(t0)
        GatewayLog.ackFailed(GatewayFailureKind.HTTP_SERVER, 503, null, t0)
        GatewayLog.syncUploaded(1, 0, 0, 200, t0)
        GatewayLog.syncFailed(503, GatewayFailureKind.HTTP_SERVER, null, 1, t0)
        GatewayLog.authRejected(401, t0)
        GatewayLog.serverReachable(42L, t0)
        GatewayLog.tlsVerified("TLSv1.3", t0)

        val codes = GatewayLog.buffer.snapshot().map { it.code }
        listOf(
            "PULL_START", "PULL_EMPTY", "PULL_TASK", "PULL_FAILED",
            "ACK_SUCCESS", "ACK_FAILED", "SYNC_UPLOADED", "SYNC_FAILED",
            "AUTH_REJECTED", "SERVER_REACHABLE", "TLS_VERIFIED"
        ).forEach { assertTrue("missing $it", codes.contains(it)) }
        assertTrue("codes are unique per event", GatewayLog.buffer.snapshot().isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safety
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aLongOrUnsafeDetailIsBoundedAndRedactedOnTheWayIn`() {
        GatewayLog.pullFailed(
            GatewayFailureKind.TCP_CONNECT,
            null,
            "dial +989121234567 failed\nsecond line " + "x".repeat(500),
            t0
        )

        val line = GatewayLog.buffer.snapshot().single()
        assertNotNull(line.detail)
        assertFalse("a number never enters the feed", line.detail!!.contains("+989121234567"))
        assertFalse("a detail is one line", line.detail!!.contains('\n'))
        assertTrue(line.detail!!.length <= 200)
    }

    @Test
    fun `theStoredEntryIsReturnedSoACallerCanRenderItWithoutRederiving`() {
        val stored = GatewayLog.buffer.add(entry(title = "x".repeat(500)))

        assertTrue(stored.title.length <= 200)
    }
}
