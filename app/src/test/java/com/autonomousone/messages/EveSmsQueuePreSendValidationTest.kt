package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Final pre-send stale-notification protection (GMweb metadata-aware tasks).
 *
 * These tests pin the CORRECTNESS BARRIER: a task pulled from GMweb with
 * meta.requiresValidation=true is validated inside [EveSmsQueue.drainOne]
 * immediately before the native sender is invoked, and a superseded or
 * unverifiable task never reaches that sender.
 *
 * The native sender is represented by the sender lambda — the same seam
 * GatewayServer uses to call SmsSender.sendForResult (the only path to
 * SmsManager). Asserting on it is asserting on "no physical SMS".
 */
class EveSmsQueuePreSendValidationTest {

    /** Store that round-trips, so "process death then reboot" is testable. */
    private class RoundTripStore : EveSmsQueue.Store {
        var records: List<EveSmsQueue.Record> = emptyList()
        var idem: Map<String, String> = emptyMap()
        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> = records to idem
        override fun save(records: List<EveSmsQueue.Record>, idempotency: Map<String, String>) {
            this.records = records.toList()
            this.idem = idempotency.toMap()
        }
    }

    private lateinit var store: RoundTripStore
    private val sent = mutableListOf<String>()
    private val order = mutableListOf<String>()
    private var nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = RoundTripStore()
        sent.clear()
        order.clear()
        nowMs = 1_700_000_000_000L
        EveSmsQueue.resetForTest(store)
        // Drain any persist queued by a previous test before this store is used.
        EveSmsQueue.awaitPersistence()
        EveSmsQueue.clock = { nowMs }
    }

    @After
    fun tearDown() {
        EveSmsQueue.stop()
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore())
    }

    private fun boot(
        validator: EveSmsQueue.FinalValidator? = null,
        sender: (EveSmsQueue.Record) -> Boolean = { record ->
            order.add("send")
            sent.add(record.text)
            true
        }
    ) {
        EveSmsQueue.bootstrap(store, sender, validator)
        // Stop the worker so the tests drive drainOne() deterministically.
        EveSmsQueue.stop()
    }

    private fun valid(): EveSmsQueue.FinalValidator =
        EveSmsQueue.FinalValidator { order.add("validate"); EveSmsQueue.ValidationDecision.Valid }

    private fun superseded(reason: String = "renewed"): EveSmsQueue.FinalValidator =
        EveSmsQueue.FinalValidator {
            order.add("validate")
            EveSmsQueue.ValidationDecision.Superseded(reason)
        }

    private fun unavailable(reason: String = "timeout"): EveSmsQueue.FinalValidator =
        EveSmsQueue.FinalValidator {
            order.add("validate")
            EveSmsQueue.ValidationDecision.Unavailable(reason)
        }

    private fun enqueueDepletion(
        gatewayRequestId: String,
        to: String = "+989120000001",
        notificationKind: String = "volume_ended",
        serviceKey: String = "eve:srv-1:client-1",
        requiresValidation: Boolean = true,
        startDeferred: Boolean = false
    ): EveSmsQueue.Record = EveSmsQueue.enqueue(
        to = to,
        text = "volume ended",
        priority = "critical",
        idempotencyKey = null,
        meta = EveSmsQueue.GatewayMeta(
            gatewayRequestId = gatewayRequestId,
            source = "eve",
            serviceKey = serviceKey,
            notificationKind = notificationKind,
            generation = 17,
            correlationId = "corr-" + gatewayRequestId,
            requiresValidation = requiresValidation,
            pulledAt = nowMs
        ),
        startDeferred = startDeferred
    ).record

    // ── 1. happy path ────────────────────────────────────────────────────────

    @Test
    fun validDepletionTaskIsValidatedThenSentExactlyOnce() {
        boot(valid())
        val rec = enqueueDepletion("gw-valid")

        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)
        assertNotNull(after)
        assertEquals(EveSmsQueue.Status.SENT, after!!.status)
        assertTrue(after.successful)
        assertEquals(1, sent.size)
        assertEquals("valid", after.validationResult)
        assertEquals(1, after.validationAttempts)
        // Exact native-submission timestamp recorded for the race window.
        assertTrue(after.nativeSubmitStartedAt > 0)
    }

    // ── 2. superseded between pull and send ──────────────────────────────────

    @Test
    fun supersededBeforeLocalSendNeverReachesTheSender() {
        boot(superseded("renewed"))
        val rec = enqueueDepletion("gw-superseded")

        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.SUPERSEDED, after.status)
        assertTrue(after.terminal)
        assertFalse(after.successful)
        assertFalse(after.deferred)
        assertEquals("superseded", after.outcome)
        assertEquals("renewed", after.supersededReason)
        assertTrue("physical send must never start", sent.isEmpty())
        assertEquals(0L, after.nativeSubmitStartedAt)
        assertFalse(after.submittedOnce)
    }

    @Test
    fun supersededRecordIsNeverRetried() {
        boot(superseded())
        val rec = enqueueDepletion("gw-superseded-terminal")
        EveSmsQueue.drainOne()

        nowMs += 10 * 60_000L // long past any backoff window
        assertEquals(0, EveSmsQueue.sweepDeferred())
        EveSmsQueue.drainOne()

        assertTrue(sent.isEmpty())
        assertEquals(EveSmsQueue.Status.SUPERSEDED, EveSmsQueue.status(rec.requestId)!!.status)
    }

    // ── 3. persisted across process death / reboot ───────────────────────────

    @Test
    fun persistedDepletionTaskIsRevalidatedAfterRestartAndNeverSent() {
        // 1-2: pull + persist (validation has not run yet).
        boot(valid())
        val rec = enqueueDepletion("gw-restart")
        EveSmsQueue.awaitPersistence()
        assertEquals(1, store.records.size)
        assertEquals(1, store.records.count { it.gatewayRequestId == "gw-restart" })

        // 3-4: process death + reboot happen; the customer renews meanwhile.
        EveSmsQueue.stop()
        sent.clear()
        order.clear()

        // 5-6: the worker resumes from persistent storage and validates the old request.
        boot(superseded("renewed"))
        EveSmsQueue.drainOne()

        // 7-8: GMweb says superseded -> the local sender is NOT called.
        assertTrue("native sender must never be invoked after restart", sent.isEmpty())
        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.SUPERSEDED, after.status)
        assertEquals("gw-restart", after.gatewayRequestId)
        assertEquals("renewed", after.supersededReason)
    }

    @Test
    fun gatewayMetadataSurvivesPersistenceRoundTrip() {
        boot(valid())
        val rec = enqueueDepletion("gw-persist", serviceKey = "eve:srv-9:client-9")
        EveSmsQueue.awaitPersistence()
        EveSmsQueue.stop()

        boot(valid())
        val restored = EveSmsQueue.status(rec.requestId)
        assertNotNull(restored)
        assertEquals("gw-persist", restored!!.gatewayRequestId)
        assertEquals("eve", restored.source)
        assertEquals("eve:srv-9:client-9", restored.serviceKey)
        assertEquals("volume_ended", restored.notificationKind)
        assertEquals(17, restored.generation)
        assertEquals("corr-gw-persist", restored.correlationId)
        assertTrue(restored.requiresValidation)
    }

    // ── 4-5. fail closed on validation failure ───────────────────────────────

    @Test
    fun validationTimeoutNeverSendsAndStaysRetryable() {
        boot(unavailable("timeout"))
        val rec = enqueueDepletion("gw-timeout")

        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.DEFERRED, after.status)
        assertFalse("deferred is NOT terminal", after.terminal)
        assertFalse(after.successful)
        assertFalse(after.superseded)
        assertFalse(after.submittedOnce)
        assertTrue("must not become SENT", sent.isEmpty())
        assertTrue("must carry a future retry deadline", after.deferredUntil > nowMs)
        assertEquals("deferred", after.outcome)
    }

    @Test
    fun validationServerErrorFailsClosed() {
        boot(unavailable("http_500"))
        val rec = enqueueDepletion("gw-500")

        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.DEFERRED, after.status)
        assertEquals("unavailable", after.validationResult)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun deferredRecordIsNotRequeuedBeforeItsBackoffElapses() {
        boot(unavailable("http_500"))
        val rec = enqueueDepletion("gw-backoff")
        EveSmsQueue.drainOne()
        val after = EveSmsQueue.status(rec.requestId)!!

        // Nothing due yet: an outage must not become a request storm.
        assertEquals(0, EveSmsQueue.sweepDeferred(nowMs))
        assertEquals(0, EveSmsQueue.sweepDeferred(after.deferredUntil - 1))
        assertEquals(1, EveSmsQueue.sweepDeferred(after.deferredUntil))
        assertEquals(EveSmsQueue.Status.QUEUED, EveSmsQueue.status(rec.requestId)!!.status)
    }

    // ── 6. idempotency on the GMweb gateway requestId ────────────────────────

    @Test
    fun sameGatewayRequestIdNeverProducesASecondSend() {
        boot(valid())
        val first = EveSmsQueue.enqueue(
            to = "+989120000001", text = "volume ended", priority = "critical",
            idempotencyKey = null,
            meta = EveSmsQueue.GatewayMeta(
                gatewayRequestId = "gw-dup", notificationKind = "volume_ended",
                requiresValidation = true
            )
        )
        val second = EveSmsQueue.enqueue(
            to = "+989120000001", text = "volume ended", priority = "critical",
            idempotencyKey = null,
            meta = EveSmsQueue.GatewayMeta(
                gatewayRequestId = "gw-dup", notificationKind = "volume_ended",
                requiresValidation = true
            )
        )

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.record.requestId, second.record.requestId)

        EveSmsQueue.drainOne()
        EveSmsQueue.drainOne()
        assertEquals(1, sent.size)
    }

    @Test
    fun duplicateGatewayRequestAfterRestartIsNotResent() {
        boot(valid())
        val rec = enqueueDepletion("gw-dup-restart")
        EveSmsQueue.drainOne()
        EveSmsQueue.awaitPersistence()
        assertEquals(1, sent.size)

        // Reboot: the same task is pulled again by a redelivering server.
        EveSmsQueue.stop()
        boot(valid())
        val again = EveSmsQueue.enqueue(
            to = "+989120000001", text = "volume ended", priority = "critical",
            idempotencyKey = null,
            meta = EveSmsQueue.GatewayMeta(
                gatewayRequestId = "gw-dup-restart", requiresValidation = true
            )
        )

        assertFalse(again.created)
        assertEquals(rec.requestId, again.record.requestId)
        EveSmsQueue.drainOne()
        assertEquals("terminal record must not re-send", 1, sent.size)
    }

    // ── 7-9. backward compatibility ──────────────────────────────────────────

    @Test
    fun renewConfirmationWithoutRequiresValidationKeepsExistingBehaviour() {
        var validationCalls = 0
        boot(EveSmsQueue.FinalValidator {
            validationCalls++
            EveSmsQueue.ValidationDecision.Superseded("renewed")
        })
        val rec = enqueueDepletion("gw-renew", notificationKind = "renew", requiresValidation = false)

        EveSmsQueue.drainOne()

        assertEquals(0, validationCalls)
        assertEquals(1, sent.size)
        assertEquals(EveSmsQueue.Status.SENT, EveSmsQueue.status(rec.requestId)!!.status)
    }

    @Test
    fun createdConfirmationWithoutRequiresValidationKeepsExistingBehaviour() {
        var validationCalls = 0
        boot(EveSmsQueue.FinalValidator {
            validationCalls++
            EveSmsQueue.ValidationDecision.Superseded("renewed")
        })
        val rec = enqueueDepletion("gw-created", notificationKind = "created", requiresValidation = false)

        EveSmsQueue.drainOne()

        assertEquals(0, validationCalls)
        assertEquals(1, sent.size)
        assertEquals(EveSmsQueue.Status.SENT, EveSmsQueue.status(rec.requestId)!!.status)
    }

    @Test
    fun legacyTaskWithoutMetaKeepsExistingBehaviour() {
        var validationCalls = 0
        boot(EveSmsQueue.FinalValidator {
            validationCalls++
            EveSmsQueue.ValidationDecision.Superseded("renewed")
        })
        // Exactly what an older GMweb instance posts: no meta, no gateway fields.
        val rec = EveSmsQueue.enqueue("+989120000001", "legacy", "announcement", "idem-legacy").record

        EveSmsQueue.drainOne()

        assertEquals(0, validationCalls)
        assertEquals(1, sent.size)
        assertEquals(EveSmsQueue.Status.SENT, EveSmsQueue.status(rec.requestId)!!.status)
        assertFalse(EveSmsQueue.status(rec.requestId)!!.requiresValidation)
    }

    // ── 11-12. outage then recovery ──────────────────────────────────────────

    @Test
    fun temporaryValidationOutageThenSuccessSendsOnce() {
        var calls = 0
        boot(EveSmsQueue.FinalValidator { rec ->
            calls++
            order.add("validate")
            // The gate is re-validated for the SAME record after the backoff.
            if (calls == 1) EveSmsQueue.ValidationDecision.Unavailable("timeout")
            else EveSmsQueue.ValidationDecision.Valid
        })
        val rec = enqueueDepletion("gw-recover")

        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.DEFERRED, EveSmsQueue.status(rec.requestId)!!.status)
        assertTrue(sent.isEmpty())

        nowMs = EveSmsQueue.status(rec.requestId)!!.deferredUntil
        assertEquals(1, EveSmsQueue.sweepDeferred(nowMs))
        EveSmsQueue.drainOne()

        assertEquals(EveSmsQueue.Status.SENT, EveSmsQueue.status(rec.requestId)!!.status)
        assertEquals(1, sent.size)
        assertEquals(2, calls)
    }

    @Test
    fun temporaryValidationOutageThenSupersededNeverSends() {
        var calls = 0
        boot(EveSmsQueue.FinalValidator {
            calls++
            order.add("validate")
            if (calls == 1) EveSmsQueue.ValidationDecision.Unavailable("http_500")
            else EveSmsQueue.ValidationDecision.Superseded("renewed")
        })
        val rec = enqueueDepletion("gw-outage-superseded")

        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.DEFERRED, EveSmsQueue.status(rec.requestId)!!.status)

        nowMs = EveSmsQueue.status(rec.requestId)!!.deferredUntil
        assertEquals(1, EveSmsQueue.sweepDeferred(nowMs))
        EveSmsQueue.drainOne()

        assertEquals(EveSmsQueue.Status.SUPERSEDED, EveSmsQueue.status(rec.requestId)!!.status)
        assertTrue("never sent after an outage", sent.isEmpty())
        assertEquals(2, calls)
    }

    // ── 13. service identity, not phone number ───────────────────────────────

    @Test
    fun supersedingOneServiceDoesNotSuppressAnotherOnTheSamePhone() {
        val phone = "+989120000001"
        boot(EveSmsQueue.FinalValidator { rec ->
            order.add("validate")
            if (rec.serviceKey == "eve:srv-a:client-a") {
                EveSmsQueue.ValidationDecision.Superseded("renewed")
            } else {
                EveSmsQueue.ValidationDecision.Valid
            }
        })
        val a = enqueueDepletion("gw-srv-a", to = phone, serviceKey = "eve:srv-a:client-a")
        val b = enqueueDepletion("gw-srv-b", to = phone, serviceKey = "eve:srv-b:client-b")

        EveSmsQueue.drainOne()
        EveSmsQueue.drainOne()

        assertEquals(EveSmsQueue.Status.SUPERSEDED, EveSmsQueue.status(a.requestId)!!.status)
        assertEquals(EveSmsQueue.Status.SENT, EveSmsQueue.status(b.requestId)!!.status)
        assertEquals("the other service must still be delivered", 1, sent.size)
        assertEquals(phone, EveSmsQueue.status(b.requestId)!!.to)
    }

    // ── 14. ordering: the gate runs before the sender ────────────────────────

    @Test
    fun finalValidatorExecutesBeforeTheNativeSender() {
        boot(valid())
        enqueueDepletion("gw-order")

        EveSmsQueue.drainOne()

        assertEquals(listOf("validate", "send"), order)
    }

    @Test
    fun gateRunsAtSendTimeNotAtEnqueueTime() {
        var calls = 0
        boot(EveSmsQueue.FinalValidator {
            calls++
            EveSmsQueue.ValidationDecision.Valid
        })

        enqueueDepletion("gw-late-gate")
        assertEquals("enqueue must not validate", 0, calls)
        assertEquals(0, sent.size)

        EveSmsQueue.drainOne()
        assertEquals(1, calls)
        assertEquals(1, sent.size)
    }

    // ── fail-closed when no validator is installed at all ────────────────────

    @Test
    fun metadataAwareTaskFailsClosedWhenNoValidatorIsInstalled() {
        boot(null)
        val rec = enqueueDepletion("gw-no-validator")

        EveSmsQueue.drainOne()

        assertTrue(sent.isEmpty())
        assertEquals(EveSmsQueue.Status.DEFERRED, EveSmsQueue.status(rec.requestId)!!.status)
    }

    @Test
    fun startDeferredParksTheRecordWithoutOfferingItToTheSender() {
        boot(valid())
        val rec = enqueueDepletion("gw-start-deferred", startDeferred = true)

        assertEquals(EveSmsQueue.Status.DEFERRED, rec.status)
        assertFalse(EveSmsQueue.drainOne())
        assertTrue(sent.isEmpty())
    }

    // ── observability events ─────────────────────────────────────────────────

    @Test
    fun supersededEmitsStructuredObservabilityEvents() {
        val events = mutableListOf<String>()
        boot(superseded("renewed"))
        EveSmsQueue.observer = EveSmsQueue.Observer { name, fields ->
            events.add(name)
            if (name == "VALIDATION_SUPERSEDED") {
                assertEquals("gw-observe", fields["gatewayRequestId"])
                assertEquals(17, fields["generation"])
                assertEquals("eve:srv-1:client-1", fields["serviceKey"])
                assertEquals("volume_ended", fields["notificationKind"])
                assertEquals("renewed", fields["reason"])
            }
        }
        enqueueDepletion("gw-observe")

        EveSmsQueue.drainOne()

        assertTrue(events.contains("VALIDATION_SUPERSEDED"))
        assertFalse("no native submit may be traced", events.contains("NATIVE_SUBMIT_STARTED"))
        assertFalse(events.contains("NATIVE_SEND_CONFIRMED"))
    }

    @Test
    fun validSendEmitsNativeSubmitAndConfirmEvents() {
        val events = mutableListOf<String>()
        boot(valid())
        EveSmsQueue.observer = EveSmsQueue.Observer { name, _ -> events.add(name) }
        enqueueDepletion("gw-events")

        EveSmsQueue.drainOne()

        assertEquals(
            listOf("VALIDATION_VALID", "NATIVE_SUBMIT_STARTED", "NATIVE_SEND_CONFIRMED"),
            events
        )
    }
}
