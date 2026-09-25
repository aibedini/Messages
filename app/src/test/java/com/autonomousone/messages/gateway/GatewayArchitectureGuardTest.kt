package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural guards for the ONE-GMweb-server rule (v3.4.6).
 *
 * These are SOURCE scans, not behaviour tests, and that is deliberate. The failure they
 * prevent is a regression by ADDITION: someone adds a helper that reads a second URL, or a
 * convenience fallback that hardcodes the deployment, and every behavioural test still passes
 * because no test exercises the new path. A grep-level guard is the only kind that catches it.
 *
 * The specific thing being protected: the app used to hold `gmwebUrl` AND `backendUrl`, with
 * `backendUrl` defaulting to a domain compiled into the APK — so a phone could pull its
 * send-requests from one server and upload its events to another, with nothing in the UI
 * saying so.
 */
class GatewayArchitectureGuardTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun mainSources(): List<File> =
        mainRoots.map { File(it) }.firstOrNull { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            ?: error("cannot locate main sources; looked in ${mainRoots.joinToString()}")

    /**
     * Resolves a main-source file by its path BELOW `src/main`, trying the module-relative and
     * repo-relative roots. Returns null instead of throwing so a caller can try the next
     * candidate — an earlier version threw inside the lookup and made the fallback unreachable.
     */
    private fun mainSource(relative: String): File? =
        mainRoots.map { File(it, relative) }.firstOrNull { it.isFile }

    private fun requireMainSource(relative: String): File =
        mainSource(relative) ?: error(
            "cannot locate $relative; looked in " +
                mainRoots.joinToString { File(it, relative).path }
        )

    private fun mainSourceText(name: String): String =
        mainSources().firstOrNull { it.name == name }?.readText()
            ?: error("cannot locate $name among the main sources")

    /**
     * The domains that must never be baked in again — neither the original IP-based host nor the
     * legacy cloud hostname, and not the hostname that DNS-resolves to the same IP.
     */
    private val forbiddenHosts = listOf(
        "gmweb.46.31.76.103.nip.io",
        "gaitway.autonomousone.in",
        "46.31.76.103"
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // No baked production server anywhere in the shipped sources
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `noProductionSourceHardcodesAGmwebHost`() {
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            val text = file.readText()
            forbiddenHosts.forEach { host ->
                if (text.contains(host)) offenders += "${file.path} contains $host"
            }
        }

        assertTrue(
            "a GMweb host must be configuration, never a compiled-in constant:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `theGradleBuildHasNoDefaultServerDomain`() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot locate build.gradle.kts")
        val declaration = build
            .lineSequence()
            .firstOrNull { it.contains("GATEWAY_BACKEND_URL") && it.contains("findProperty") }
            ?: error("the GATEWAY_BACKEND_URL declaration disappeared — update this guard")

        assertFalse(
            "the build property must have no fallback domain, but the declaration is: $declaration",
            declaration.contains("?:")
        )
        assertTrue(
            "an unset property must resolve to an empty string: $declaration",
            declaration.contains("orEmpty()")
        )
    }

    /** Strips line comments and KDoc continuation lines, leaving executable text. */
    private fun codeOnly(text: String): String = text.lineSequence()
        .map { line ->
            val withoutLineComment = line.substringBefore("//")
            // A KDoc/block-comment continuation line starts with `*`; drop those wholesale.
            if (withoutLineComment.trimStart().startsWith("*")) "" else withoutLineComment
        }
        .joinToString("\n")

    @Test
    fun `BuildConfigIsNeverReadAsAServerAddressAtRuntime`() {
        // It may still be NAMED in a comment explaining why it is gone; it must never be USED.
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            codeOnly(file.readText()).lineSequence().forEachIndexed { index, line ->
                if (line.contains("BuildConfig.GATEWAY_BACKEND_URL")) {
                    offenders += "${file.path}:${index + 1}"
                }
            }
        }

        assertTrue(
            "the compiled-in server address must not be read at runtime:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // One authority, and only one
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theTwoLegacyUrlNamesAreAliasesOfTheSingleOrigin`() {
        val prefs = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayPreferences.kt"
        ).readText()

        // Both legacy names must resolve to the single stored origin…
        assertTrue(
            "gmwebUrl must be an alias of gmwebServerOrigin",
            prefs.contains("var gmwebUrl: String") && prefs.contains("get() = gmwebServerOrigin")
        )
        assertTrue(
            "backendUrl must be an alias of gmwebServerOrigin",
            prefs.contains("var backendUrl: String")
        )
        // …and the getter must not fall back to the compiled-in default any more.
        assertFalse(
            "the legacy getter must not fall back to a build constant",
            codeOnly(prefs).contains("BuildConfig.GATEWAY_BACKEND_URL")
        )
    }

    @Test
    fun `theMigrationNeverAdoptsACompiledInDefault`() {
        val text = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayPreferences.kt"
        ).readText()

        assertTrue(
            "the migration must ask whether the legacy key was ever WRITTEN",
            text.contains("prefs.contains(KEY_BACKEND_URL)")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The UI actually renders the health work, and keeps the two concepts apart
    // ═══════════════════════════════════════════════════════════════════════════

    private fun gatewayScreen(): String = mainSourceText("GatewayScreen.kt")

    @Test
    fun `theGatewayScreenRendersTheHealthAndLogCards`() {
        val screen = gatewayScreen()

        assertTrue(
            "GatewayHealthCard must be rendered, not merely compiled in an unused file",
            screen.contains("GatewayHealthCard(")
        )
        assertTrue(
            "GatewayLogFeedCard must be rendered",
            screen.contains("GatewayLogFeedCard(")
        )
        assertTrue(
            "the legacy raw log card must be gone",
            !screen.contains("Live Server Logs")
        )
    }

    @Test
    fun `theLocalApiCardNeverUsesTheGmwebOrigin`() {
        val screen = gatewayScreen()

        // The card that lists the phone's OWN endpoints must build them from the LAN base.
        assertTrue(
            "the local API base must be the phone's LAN address",
            screen.contains("val localBase = \"http://\${viewModel.localIpAddress}:\${viewModel.port}\"")
        )
        // And the old conflation must not come back: a "Cloud Mode" badge that swapped the base
        // URL of the LOCAL endpoints to the GMweb origin.
        assertFalse("the misleading Cloud Mode badge must be gone", screen.contains("Cloud Mode"))
        assertFalse("the LAN Mode badge must be gone", screen.contains("LAN Mode"))
        assertFalse(
            "the second hardcoded cloud host must be gone",
            screen.contains("https://gaitway.autonomousone.in")
        )
        assertFalse(
            "the local endpoints must not be built from a cloud URL variable",
            screen.contains("val effectiveBaseUrl") || screen.contains("val cloudUrl")
        )
    }

    @Test
    fun `theReconnectLogNoLongerClaimsEndToEndSuccess`() {
        val vm = mainSourceText("GatewayViewModel.kt")

        assertFalse(
            "enrollment success must not be reported as a full reconnection",
            vm.contains("\"✅ Reconnected to cloud backend\"")
        )
        assertTrue(
            "it must name the control plane for what it proves",
            vm.contains("Device identity authenticated")
        )
    }

    @Test
    fun `theEventAckSpamIsNotThePrimaryFeed`() {
        // Comments may quote the OLD line to explain why it is gone; executable code must not
        // contain it.
        val uploader = codeOnly(mainSourceText("EventUploader.kt"))

        assertFalse(
            "the raw 'N/N event(s) ACKed by GMweb' line must no longer be the user-facing message",
            uploader.contains("ACKed by GMweb")
        )
        assertTrue(
            "the user-facing line must describe the sync",
            uploader.contains("Sync uploaded")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Security invariants that must not regress by ADDITION (mission §41/§43/§73/§78)
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Same reasoning as the host guards above: these protect properties whose failure mode is a
    // new helper or a convenience path that no behavioural test happens to exercise. Each one pins
    // a specific defect that was found and fixed, so it cannot quietly come back.

    @Test
    fun `noSourceRelaxesCertificateOrHostnameValidation`() {
        // The one prohibition that must never be traded away for convenience. Only executable text
        // is scanned, because the KDoc in two files deliberately NAMES these APIs to forbid them.
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            codeOnly(file.readText()).lineSequence().forEachIndexed { index, line ->
                listOf(
                    "TrustAllCerts",
                    "X509TrustManager()",
                    "ALLOW_ALL_HOSTNAME_VERIFIER",
                    "setHostnameVerifier",
                    "checkServerTrusted"
                ).forEach { pattern ->
                    if (line.contains(pattern)) offenders += "${file.path}:${index + 1} $pattern"
                }
            }
        }

        assertTrue(
            "certificate and hostname validation must never be relaxed. If a legitimate use is " +
                "genuinely needed, that is a security decision and this guard must be changed " +
                "deliberately:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `theScheduleRegistryStoresNoPlaintextRecipientOrBody`() {
        val scheduler = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayScheduler.kt"
        ).readText()

        assertTrue("the recipient must be stored encrypted", scheduler.contains("\"phoneC\""))
        assertTrue("the body must be stored encrypted", scheduler.contains("\"messageC\""))
        assertFalse(
            "the recipient must never be WRITTEN in plaintext (it may still be read for legacy rows)",
            scheduler.contains("put(\"phone\"")
        )
        assertFalse(
            "the body must never be WRITTEN in plaintext",
            scheduler.contains("put(\"message\"")
        )
        // WorkManager persists its input data in the clear, so carrying either one there would
        // store the same secrets a second time, unencrypted.
        assertFalse(
            "the recipient must not travel through WorkManager input data",
            scheduler.contains("KEY_PHONE")
        )
        assertFalse(
            "the body must not travel through WorkManager input data",
            scheduler.contains("KEY_MESSAGE")
        )
    }

    @Test
    fun `noMmsPayloadIsSilentlyDropped`() {
        // The defect: `cr.openOutputStream(partUri)?.use { out -> out.write(bytes) }` followed by
        // `return mmsId`. The `?.` makes a null stream indistinguishable from a successful write, so a
        // part with NO bytes is created, the row is handed to the platform, and the caller reports
        // success — for a message whose content silently is not there.
        val sender = codeOnly(mainSourceText("MmsSender.kt"))

        assertFalse(
            "a null part stream must not be indistinguishable from a successful write",
            sender.contains("openOutputStream(partUri)?.use")
        )
        assertTrue(
            "the payload must be judged before any provider row is created",
            sender.contains("MmsPayloadPolicy.classify(")
        )
        assertTrue(
            "and an unreadable source must be named rather than skipped",
            sender.contains("loadBounded(")
        )
        assertTrue(
            "an incomplete row we created must be rolled back, not left as a phantom message",
            sender.contains("rollback(cr, mmsId)")
        )
        // The PUBLIC send API must carry a reason, not a bare Boolean. An internal helper may still
        // return one — `writePart` does, and should.
        listOf(
            "fun sendImage(phone: String, imageUri: Uri): Boolean",
            "fun sendAudio(phone: String, audioUri: Uri): Boolean",
            "fun sendGroupText(recipients: List<String>, text: String): Boolean"
        ).forEach { signature ->
            assertFalse(
                "a refusal with no reason is unusable by every caller: $signature",
                sender.contains(signature)
            )
        }
    }

    @Test
    fun `everyCallerOfAnMmsSendHonoursTheResult`() {
        // Both callers used to discard it, and the conversation screen went further: it added an
        // optimistic bubble and emitted an "outgoing sent" event unconditionally, so a refused photo
        // was displayed as sent.
        val viewModel = codeOnly(mainSourceText("ConversationViewModel.kt"))
        assertTrue(
            "the composer must branch on the result",
            viewModel.contains("is MmsSendResult.Rejected ->")
        )
        assertFalse(
            "and must not call the sender for its side effect alone",
            viewModel.contains("mmsSender.sendGroupText(recipients, trimmedMsg)\n") ||
                viewModel.contains("mmsSender.sendAudio(targetPhone, audioUri)\n") ||
                viewModel.contains("mmsSender.sendImage(targetPhone, imageUri)\n")
        )

        val server = codeOnly(mainSourceText("GatewayServer.kt"))
        assertTrue(
            "the REST endpoint must report the failure shape it always did, plus a reason",
            server.contains("put(\"reason\", result.reason)")
        )
        assertTrue(
            "and keep the legacy status field",
            server.contains("put(\"status\", if (result is MmsSendResult.Queued) \"success\" else \"failed\")")
        )
    }

    /**
     * The variable names a recipient can travel under in these files.
     *
     * The guard used to check one literal (`"-> \$phone @"`) and then one variable name (`$phone`), and
     * each time a raw number survived under a different spelling: the MMS handler used `$phone` and
     * evaded the literal; the EVE handler used `$to` and evaded the name. Three occurrences of the same
     * rule is enough to state it as a rule.
     */
    private val recipientVariables = listOf("to", "phone", "rawPhone", "address", "recipient", "sender")

    @Test
    fun `recipientsAreOnlyEverLoggedAsTokens`() {
        // The three concrete leaks that were found and fixed.
        val poller = codeOnly(mainSourceText("OutboxPoller.kt"))
        assertFalse(
            "a pulled task's recipient must never be logged raw",
            poller.contains("+ task.to")
        )
        assertTrue("it must be reported as a token", poller.contains("PhoneToken.of(task.to)"))

        val server = codeOnly(mainSourceText("GatewayServer.kt"))
        assertFalse(
            "a scheduled recipient must never be logged raw",
            server.contains("-> \$phone @")
        )
        assertTrue("it must be reported as a token", server.contains("PhoneToken.of(phone)"))

        val viewModel = codeOnly(mainSourceText("GatewayViewModel.kt"))
        assertFalse(
            "no fragment of the gateway API key may be logged",
            viewModel.contains("apiKey.take(7)") || viewModel.contains("takeLast(4)")
        )
    }

    @Test
    fun `noRecipientReachesALogUnderAnyName`() {
        // The RULE, not a spelling of it. Any string template that interpolates a recipient-named
        // variable in these files must tokenise it (or use the mission's own `***1234` form). A
        // False positive is possible in principle — a variable named `address` that is not a phone
        // number — and if one ever appears, this guard should be narrowed deliberately rather than
        // deleted, because it is the only thing standing between a support log and a dialable number.
        val offenders = mutableListOf<String>()
        listOf("GatewayServer.kt", "OutboxPoller.kt").forEach { name ->
            codeOnly(mainSourceText(name)).lineSequence().forEachIndexed { index, line ->
                val interpolatesRecipient = recipientVariables.any { variable ->
                    Regex("\\$$variable\\b").containsMatchIn(line)
                }
                if (!interpolatesRecipient) return@forEachIndexed
                val sanitised = line.contains("PhoneToken.of(") || line.contains("takeLast(4)")
                if (!sanitised) offenders += "$name:${index + 1} ${line.trim()}"
            }
        }

        assertTrue(
            "a recipient in a log line must be a token or `***1234`:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `bothSendPathsPersistAnAtMostOnceMarker`() {
        // mission §78: a duplicate SMS is irreversible, so BOTH remote send paths must record
        // "a submit is starting" before invoking the sender, and must refuse to repeat it.
        val eve = requireMainSource(
            "java/com/autonomousone/messages/eve/EveSmsQueue.kt"
        ).readText()
        assertTrue("the pull-bridge queue must carry a submit marker", eve.contains("submittedOnce"))
        assertTrue(
            "and must refuse to resend after one",
            eve.contains("REASON_INTERRUPTED_AFTER_SUBMIT")
        )

        val scheduler = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayScheduler.kt"
        ).readText()
        assertTrue("the scheduled send must carry a submit marker", scheduler.contains("submittedOnce"))
        assertTrue(
            "and must refuse to resend after one",
            scheduler.contains("REASON_INTERRUPTED_AFTER_SUBMIT")
        )
        assertTrue(
            "and must record the marker BEFORE the send, not after",
            scheduler.contains("markSubmittedBeforeSend")
        )
    }

    @Test
    fun `theSelectedLineIsNeverSilentlyIgnored`() {
        // mission §50. The pre-Android-12 branch used the platform-default manager on the stated
        // grounds that the per-subscription API was "no longer exposed by current SDK stubs" — which
        // was false (`getSmsManagerForSubscriptionId` is public since API 22 and present in the SDK
        // this app compiles against; minSdk here is 26). So every supported device below Android 12
        // ignored the user's chosen line, for as long as that comment stood.
        val sender = requireMainSource(
            "java/com/autonomousone/messages/sms/SmsSender.kt"
        ).readText()
        // Executable text only. The KDoc above this branch NAMES the API it forbids, exactly as the
        // other guards in this file do — and the first version of this guard read the whole file, so
        // it was satisfied by its own documentation and passed with the defect reintroduced. Caught
        // by falsifying it, which is the only reason to trust a guard at all.
        val code = codeOnly(sender)

        assertTrue(
            "a selected SIM must be honoured below API 31: getSmsManagerForSubscriptionId is public " +
                "since API 22 and deprecated — not removed — at 31",
            code.contains("SmsManager.getSmsManagerForSubscriptionId(subId)")
        )
        assertTrue(
            "and the decision must go through the one policy that knows when a mismatch is provable",
            code.contains("SendSimPolicy.decide(")
        )
        assertTrue(
            "a refused send must be recorded as SIM_UNAVAILABLE, not as a generic dispatch rejection",
            code.contains("SmsSendFailure.SimUnavailable")
        )
    }

    @Test
    fun `theSendLedgerNeverRecordsTheRequestedSimAsIfItWereUsed`() {
        // The other half of §50, and the more insidious one: the per-SIM send ledger is durable
        // evidence, and writing the REQUESTED subscription into it attributes a message to a line
        // that may never have carried it. The sender must pass the manager's read-back instead.
        val sender = codeOnly(
            requireMainSource("java/com/autonomousone/messages/sms/SmsSender.kt").readText()
        )

        assertFalse(
            "the requested id must not reach the segment ledger",
            sender.contains("recordSegmentSubmissions(sentId, parts.size, effectiveSubId)")
        )
        assertTrue(
            "it must be the id the manager reported",
            sender.contains("recordSegmentSubmissions(sentId, parts.size, recordedSubId)")
        )
        assertTrue(
            "and the callbacks that write the same ledger must agree",
            sender.contains("SmsStatusReceiver.ACTION_SMS_SENT, sentId, part, parts.size, recordedSubId")
        )
    }

    @Test
    fun `theLegacyPullPathCarriesTheWebsCorrelationKeyIntoTheSend`() {
        // mission §49 on the DEFAULT-ACTIVE transport. GMweb's correlation key travelled as far as
        // `EveSmsQueue.Record.correlationId` and stopped, because the queue's sender seam took
        // `(to, text)` and there was no parameter for it — so a web-requested send reached GMweb with
        // no way to tie it to the request, and neither could its later status change.
        //
        // Executable text only: the lambda's KDoc names the fields it passes.
        val server = codeOnly(mainSourceText("GatewayServer.kt"))

        assertTrue(
            "the pulled task's correlation key must reach the send",
            server.contains("clientMessageId = record.correlationId")
        )
        assertTrue(
            "and the sender must be handed the record, not a stripped-down (to, text) pair",
            server.contains("EveSmsQueue.start(") && server.contains("record ->")
        )
        assertFalse(
            "the two-argument send used to make the key unreachable; it must not come back here",
            server.contains("sendForResult(record.to, record.text)")
        )
    }

    @Test
    fun `theMmsSendResultHasAPlaceToArrive`() {
        // The defect: `sendMultimediaMessage(..., null)` is not "no callback needed", it is silence.
        // The platform's outcome had nowhere to go, so a failed MMS stayed in the OUTBOX looking like
        // a message that was still being sent and neither the app nor GMweb could tell a picture that
        // left the device from one that never did.
        val sender = codeOnly(mainSourceText("MmsSender.kt"))

        assertTrue(
            "the send result must be delivered somewhere",
            sender.contains("MmsStatusReceiver::class.java")
        )
        assertFalse(
            "a null result PendingIntent is silence, not a default",
            sender.contains("sendMultimediaMessage(context, mmsUri, null, null, null)")
        )
        assertTrue(
            "the outcome must be published to the gateway, or the web shows it pending for ever",
            sender.contains("MmsStatusReceiver.EXTRA_MMS_ID")
        )

        // And the receiver must exist, be manifest-declared (so it outlives the sending process) and
        // be non-exported (only our own explicit PendingIntent targets it). Matched as a tagged block
        // rather than by exact whitespace, so reformatting the manifest cannot break the guard.
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
        val receiverTag = Regex("<receiver[^>]*\\.mms\\.MmsStatusReceiver[^>]*>")
            .find(manifest)?.value
        assertTrue("MmsStatusReceiver must be manifest-declared", receiverTag != null)
        assertTrue(
            "and must not be exported: $receiverTag",
            receiverTag!!.contains("android:exported=\"false\"")
        )

        // The receiver must publish the outcome, and must not write an MMS row id into the per-SMS
        // segment ledger: that ledger is keyed by an SMS provider row id, and the two id spaces
        // overlap numerically, so an MMS outcome would land on an unrelated SMS row.
        val receiver = codeOnly(mainSourceText("MmsStatusReceiver.kt"))
        assertTrue(receiver.contains("providerRowChanged("))
        assertFalse(
            "an MMS row id must never reach the SMS segment ledger",
            receiver.contains("SendSegmentEntity") || receiver.contains("sendSegmentDao")
        )
    }

    @Test
    fun `theVerificationSweepIsActuallyWiredToTheSupervisor`() {
        // A regression by OMISSION, and the one this session keeps finding: `ManagedComponents` fields
        // have no-op defaults, so a sweep that is declared but never passed in compiles, builds and
        // silently never runs. The window check has the same shape, so both are pinned.
        val service = codeOnly(mainSourceText("GatewayService.kt"))

        assertTrue(
            "the full-mirror verification must be wired, or it is a declared no-op",
            service.contains("verifyMirror = {")
        )
        assertTrue(
            "and it must call the coordinator's page walk",
            service.contains(".verifyMirrorPage()")
        )
        assertTrue(
            "the window reconcile must stay wired too",
            service.contains("reconcileMissedEvents = {")
        )
    }

    @Test
    fun `theLocalSendEndpointIsRetrySafe`() {
        // `POST /api/v1/sms/send` is remotely callable (GMweb on the LAN) and had NO dedupe: a caller
        // whose connection dropped after the phone accepted the message had no way to retry without
        // sending a second SMS. Mission §78 applies to every remotely requested send, not just the
        // control-plane one.
        val server = codeOnly(mainSourceText("GatewayServer.kt"))

        assertTrue(
            "the endpoint must honour a caller-supplied idempotency key",
            server.contains("sendIdempotentBlocking(")
        )
        assertTrue(
            "and accept the same header convention the EVE endpoint already uses",
            server.contains("headers[\"idempotency-key\"]")
        )
        assertTrue(
            "a duplicate is not a failure: it keeps the 202 the first call got",
            server.contains("put(\"duplicate\", true)")
        )
        assertTrue(
            "and a request with no key must not be presented as retry-safe",
            server.contains("put(\"retrySafe\", false)")
        )
    }

    @Test
    fun `theQueueRecordsWhichDeviceQueuedACommand`() {
        // The marker the drain keys off. `enqueueSendSms` accepted a `sourceDeviceId` argument and then
        // DROPPED it, so a locally-queued row was indistinguishable from a remote one — which is exactly
        // what would let the drain execute, fail or ACK a request nobody made.
        val pipeline = codeOnly(mainSourceText("GatewayOutgoingPipeline.kt"))

        assertTrue(
            "the local origin must be recorded on the row",
            pipeline.contains("senderDeviceId = sourceDeviceId")
        )
        assertTrue(
            "and the drain must honour it",
            codeOnly(mainSourceText("CommandDrainPolicy.kt"))
                .contains("senderDeviceId == LOCAL_SOURCE_DEVICE_ID")
        )
    }

    @Test
    fun `aFailedInboxWriteIsNeverReportedAsAVisibilityDelay`() {
        // An inbound message that could not be persisted exists NOWHERE — this app is the default SMS
        // app, so no other app wrote the row, and the ContentObserver the old log line deferred to had
        // nothing to find. Collapsing "the write failed" into "written but not readable yet" made a lost
        // message look routine.
        val receiver = codeOnly(mainSourceText("SmsReceiver.kt"))

        assertTrue(
            "the insert must report WHY it failed",
            receiver.contains("InboxWriteAttempt.Threw(")
        )
        assertTrue(
            "a persistent failure must be reported as one",
            receiver.contains("InboxPersistOutcome.Failed") &&
                receiver.contains("INCOMING_PERSIST_FAILED")
        )
        // Non-vacuous, unlike a literal that could never appear: the old shape assigned the raw insert
        // result straight into `persistedId`, which is what made a failed write indistinguishable from a
        // successful one. The id may now only come from a `Persisted` outcome.
        assertFalse(
            "a failed write must not flow into the read-back as though a row existed",
            receiver.contains("persistedId = inserted.first")
        )
        assertTrue(
            "the id comes from the outcome that means success",
            receiver.contains("is InboxPersistOutcome.Persisted -> persistedId = outcome.rowId")
        )
        assertFalse(
            "nor may the broadcast payload be published as a synthetic row: `insert` can commit and " +
                "still fail to return the URI, and a second bubble would then duplicate the real row",
            receiver.contains("offlineSmsFromBroadcast(")
        )
    }

    @Test
    fun `aHeldInboundMessageIsActuallyRetried`() {
        // A regression by OMISSION, and the shape this session keeps finding: the store can hold a message
        // perfectly while nothing ever drains it, which would be *worse* than not holding it — a durable
        // record that looks like recovery and is not. Two things must be true: the receiver asks for a
        // pass, and the app guarantees a periodic one.
        val receiver = codeOnly(mainSourceText("SmsReceiver.kt"))
        assertTrue(
            "holding a message must ask for the recovery pass",
            receiver.contains("PendingInboundWorker.scheduleRetry(")
        )

        // A CALL, not the definition. The first version of this assertion matched the string
        // `PendingInboundWorker.ensureScheduled(` — which the helper's own body contains — so deleting the
        // call from `onCreate` left the guard green while nothing scheduled the sweep. The same
        // declaration-versus-call-site mistake has produced a false pass three times in this file.
        val app = codeOnly(mainSourceText("MessagesApp.kt"))
        val invocations = app.lineSequence()
            .filter { it.contains("scheduleInboundPersistRetry()") && !it.contains("private fun") }
            .count()
        assertTrue(
            "the sweep must be SCHEDULED, not merely defined: found $invocations call site(s)",
            invocations >= 1
        )

        val worker = codeOnly(mainSourceText("PendingInboundWorker.kt"))
        assertTrue(
            "the worker must LOOK before writing, or the retry duplicates the message it recovers",
            worker.contains("providerHolds(") && worker.contains("PendingInboundPolicy.decide(")
        )
        assertFalse(
            "and it must never carry the message in WorkManager input data (§41)",
            worker.contains("setInputData")
        )
    }

    @Test
    fun `theTrustPredicateHasExactlyOneDefinition`() {
        // The trust predicate decides which linked devices receive key material and history. It lived in
        // two places, and the copies were identical in the trust expression but NOT in the capability
        // parse: the repository's version called `JSONArray(...)` unguarded, so one malformed
        // `capabilitiesJson` threw out of `encrypt` and aborted the encryption of the message being
        // enqueued — a message that then could not replicate at all.
        val repository = codeOnly(
            requireMainSource(
                "java/com/autonomousone/messages/security/ConversationKeyRepository.kt"
            ).readText()
        )

        assertTrue(
            "the crypto path must use the shared predicate",
            repository.contains("TrustedDevicePolicy.isTrusted(") &&
                repository.contains("TrustedDevicePolicy.capabilities(") &&
                repository.contains("TrustedDevicePolicy.isEligibleForHistory(")
        )
        assertFalse(
            "it must not re-derive the status/expiry/certificate rule",
            repository.contains("device.status in setOf(")
        )
        assertFalse(
            "nor parse the capability list itself — an unguarded parse throws on malformed JSON",
            repository.contains("JSONArray(device.capabilitiesJson)")
        )
        assertFalse(
            "nor re-type the grant name, the one string that could drift silently",
            repository.contains("historyGrant == \"FULL_HISTORY\"")
        )
    }

    @Test
    fun `theSchedulerRefusesToPersistRatherThanDowngradeToPlaintext`() {
        // mission §41: no plaintext fallback, ever. A Keystore failure must produce a refused
        // request, not an unencrypted row.
        val scheduler = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayScheduler.kt"
        ).readText()

        assertTrue(
            "an encryption failure must abort the write",
            scheduler.contains("refusing to persist in plaintext")
        )
        assertTrue(
            "and must surface as a refused request",
            scheduler.contains("secure_storage_unavailable")
        )
    }

    @Test
    fun `aTerminalCommandTransitionAlwaysRecordsItsReason`() {
        // WS-K: a transition that changes STATE without the fields that make the state meaningful is
        // a split transition. For a command, the terminal fields are `lastErrorCode` (why it ended
        // this way — the whole point of the structured codes) and `completedAt` (how long it took).
        //
        // `markCommandState` sets ONLY the state; `finishCommand` sets state + completedAt +
        // lastErrorCode + clears the lease. So using `markCommandState` to reach a TERMINAL state
        // leaves a row that cannot explain itself, and a web-requested send that failed arrives at
        // GMweb as FAILED with no reason.
        //
        // A window is scanned rather than a single line because these calls wrap:
        //     repo.markCommandState(
        //         cmd.commandId, RemoteCommandEntity.STATE_FAILED,
        //         listOf(...))
        // and a line-based scan would miss the very site this guard was written for.
        val terminalStates = listOf("STATE_COMPLETED", "STATE_FAILED", "STATE_EXPIRED")
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            val lines = codeOnly(file.readText()).lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("markCommandState(")) return@forEachIndexed
                val call = lines.subList(index, (index + 4).coerceAtMost(lines.size))
                    .joinToString("\n")
                // Only up to the closing paren: a following unrelated line should not flag it.
                val body = call.substringBefore(")\n")
                if (terminalStates.any { body.contains(it) }) {
                    offenders += "${file.name}:${index + 1}"
                }
            }
        }

        // RATCHET: empty. Round 49 measured five offenders; round 50 moved all of them (and a sixth
        // that this scan FALSE-PASSED — it passed a local `terminal` variable, so no literal state
        // name appeared on the line) to `finishCommandFrom`.
        //
        // The scan is kept because it catches the literal form cheaply, but it is NOT the
        // enforcement: a shape it cannot see would sail through. The enforcement is in the callee —
        // `GatewaySyncRepository.markCommandState` refuses a terminal target state outright — which
        // is why this can be empty without relying on a textual heuristic.
        assertEquals(
            "a terminal command transition must record why it ended and when. This list is a " +
                "ratchet: it must stay empty, and a new offender is a regression",
            emptyList<String>(),
            offenders.sorted()
        )

        // …and the ENFORCEMENT must still be there. Emptying this list only means the literal shapes
        // are gone; the callee rule is what covers the shapes this scan cannot see (it already missed
        // one that passed a local `terminal` variable). Removing that call would silently restore the
        // defect for every future call site, so it is asserted separately.
        // NOTE: in THIS file `codeOnly` takes source TEXT, not a path (the other guard file has a
        // path-taking overload). Passing a path here silently returns the path string, so the
        // assertion reads as "the rule is missing" no matter what the file says — a false failure
        // that this helper shape has now produced once.
        val repository = codeOnly(
            requireMainSource(
                "java/com/autonomousone/messages/repository/GatewaySyncRepository.kt"
            ).readText()
        )
        assertTrue(
            "markCommandState must refuse a terminal target state, or the defect returns for any " +
                "call-site shape this scan cannot see",
            repository.contains("refusalForStateOnlyTransition(state)")
        )
    }

    @Test
    fun `theCommandRecoveryPrimitivesAllHaveACaller`() {
        // A regression by OMISSION, which is the class of defect this round fixed: every command
        // recovery primitive already existed and nothing called any of them, so the features were
        // absent while the code looked complete. A behavioural test cannot catch a query that is
        // simply never run, so the guard asserts a CALLER exists in production sources.
        //
        // Counting raw occurrences would be a false guarantee: the declarations themselves contain
        // the symbol, so the guard would pass with zero callers — verified by deleting the only
        // call and watching it still pass. Only lines that are not a declaration count.
        val declaration = Regex("""^(\s*)(@\w+\s+)*(override\s+|private\s+|internal\s+)*""" +
            """(suspend\s+)?fun\s""")

        fun callersOf(symbol: String): List<String> = mainSources()
            .map { it.name to codeOnly(it.readText()) }
            .flatMap { (name, text) ->
                text.lineSequence()
                    .filter { it.contains(symbol) && !declaration.containsMatchIn(it) }
                    .map { name }
            }
            .toList()

        listOf(
            "reclaimExpiredCommandLeases(",
            "nonTerminalCommands(",
            "expireUnclaimedCommands("
        ).forEach { symbol ->
            assertTrue(
                "nothing in production calls $symbol, so command recovery is dead code again",
                callersOf(symbol).isNotEmpty()
            )
        }

        assertTrue(
            "nothing records command execution attempts, so `remote_command_executions` is dead " +
                "schema again and exactly-once rests on the idempotency index alone",
            callersOf("beginCommandExecution(").isNotEmpty()
        )
    }

    @Test
    fun `theDrainNeverMakesASendReRunnable`() {
        // mission §78 pinned at the source level, next to the other irreversibility guards: the set
        // of re-runnable command types must stay free of the one command that sends an SMS. The
        // behavioural proof lives in CommandDrainPolicyTest; this exists so that deleting the test
        // and widening the set cannot both go unnoticed.
        val policy = requireMainSource(
            "java/com/autonomousone/messages/sync/CommandDrainPolicy.kt"
        ).readText()
        val declaration = policy.lineSequence()
            .firstOrNull { it.contains("val RE_DRIVABLE_TYPES") }
            ?: error("RE_DRIVABLE_TYPES disappeared — update this guard deliberately")

        assertFalse(
            "a re-runnable send type is a duplicate SMS waiting to happen: $declaration",
            declaration.contains("SEND_SMS")
        )
        assertTrue(
            "the declaration must still be an explicit allow-list: $declaration",
            declaration.contains("setOf(")
        )
    }

    @Test
    fun `theEnrollmentRescueStaysNarrowAndReBudgeted`() {
        // PR-11 pinned at the source level. The behaviour is proven by EnrollmentRescueSqlTest
        // against a real SQLite database; this exists because the two defects that made the rescue
        // useless were both invisible to a reader:
        //
        //   * the statement was `WHERE state = 'DEAD_LETTER'` — every dead letter ever recorded,
        //     resurrected on every enrollment (and now on every auth-driven re-enrollment), which
        //     puts permanently rejected events back into bounded batches ahead of live ones and
        //     wipes the reason columns that explain them;
        //   * it left `attemptCount` alone, and since `EventUploader` tests `exhausted()` BEFORE
        //     incrementing, a dead letter is already past `MAX_ATTEMPTS` — so a rescued row died
        //     again on its next failure with zero retries.
        //
        // A guard is required in addition to the SQL test because the statement could be replaced
        // by an inline copy inside the annotation, at which point the test would still pass while
        // the shipped query did something else.
        val sync = requireMainSource("java/com/autonomousone/messages/data/GatewaySync.kt").readText()
        val code = codeOnly(sync)

        assertTrue(
            "the enrollment rescue must EXECUTE the shared statement, not an inline copy that " +
                "no test can run",
            code.contains("@Query(GatewayEventOutboxEntity.RESCUE_ENROLLMENT_SQL)")
        )

        assertTrue(
            "the crypto-rollout rescue must EXECUTE its shared statement too",
            code.contains("@Query(GatewayEventOutboxEntity.RESET_CRYPTO_DEAD_LETTER_SQL)")
        )

        val definition = code.lineSequence()
            .dropWhile { !it.contains("val RESCUE_ENROLLMENT_SQL") }
            .drop(1)
            .takeWhile { !Regex("""^\s*(const\s+)?val\s""").containsMatchIn(it) }
            .joinToString("\n")
        assertTrue("RESCUE_ENROLLMENT_SQL disappeared — update this guard deliberately", definition.isNotBlank())

        listOf(
            "state = 'DEAD_LETTER'" to "the rescue must still be scoped to dead letters",
            "lastErrorCode IN (" to "the rescue must filter by the reason the row died",
            "RESCUE_ENROLLMENT_CODES_SQL" to "the filter must use the shared, asserted code list",
            "attemptCount = 0" to "a rescued row must get its retry budget back, or the rescue is a no-op",
            "nextAttemptAt = 0" to "a rescued row must not wait out a stale backoff"
        ).forEach { (clause, why) ->
            assertTrue("$why — missing `$clause` in: $definition", definition.contains(clause))
        }
    }

    /**
     * Every SQL statement in the main sources that returns dead letters to service, as
     * `file:line` → statement text.
     *
     * Found through the WRITE, not the predicate. Keying on the predicate alone would sweep in the
     * read-only diagnostics (`SELECT COUNT(*) … WHERE state = 'DEAD_LETTER'`), which are perfectly
     * legal and must not be forced to grow a retry budget.
     */
    private fun deadLetterResets(): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        mainSources().forEach { file ->
            val lines = codeOnly(file.readText()).lines()
            lines.forEachIndexed { index, _ ->
                // Walk back to the start of the statement this line belongs to: the annotation, or
                // the blank line codeOnly leaves where a KDoc used to be.
                val start = (index downTo (index - 30).coerceAtLeast(0))
                    .firstOrNull { lines[it].isBlank() || lines[it].contains("@Query") }
                    ?: (index - 30).coerceAtLeast(0)
                val statement = lines.subList(start, index + 1).joinToString("\n")
                // A reset writes rows back into service; `markDead` moves rows the other way and
                // is deliberately excluded by requiring the PENDING write.
                if (statement.contains("UPDATE") &&
                    statement.contains("state = 'PENDING'") &&
                    statement.contains("state = 'DEAD_LETTER'")
                ) {
                    found += "${file.name}:${index + 1}" to statement
                }
            }
        }
        return found
    }

    @Test
    fun `everyDeadLetterRescueRestoresTheRetryBudget`() {
        // The generalised rule, and the reason it is a separate test: the SAME defect existed
        // twice, in the enrollment rescue and in the one-time v3-rollout rescue, and both were
        // invisible because `attemptCount` was simply absent from the statement.
        //
        // `EventUploader` consults `exhausted(attemptCount)` BEFORE `markDead` increments it, so a
        // dead letter is written at 26 or more — already past `MAX_ATTEMPTS`. Any reset that leaves
        // the count alone therefore hands the row back for exactly one attempt and dead-letters it
        // again on the first failure, transient or not. For the crypto rescue that is worse than
        // wasteful: it consumes a one-time preference flag, so the rows can never be rescued again.
        //
        // A rescue that does not restore the budget is not a rescue. This is asserted over EVERY
        // reset rather than the two known ones, so a third added later is covered by construction.
        val resets = deadLetterResets()

        assertTrue(
            "no dead-letter reset found — the detector is broken, not the code",
            resets.isNotEmpty()
        )

        // The blanket form. `[ \t]*` deliberately does NOT cross a newline: a raw-string query may
        // legitimately END on a bare dead-letter predicate.
        val blanketPredicate = Regex("""state\s*=\s*'DEAD_LETTER'[ \t]*""" + "\"")

        val blanket = mutableListOf<String>()
        val unbudgeted = mutableListOf<String>()
        val unclaimable = mutableListOf<String>()

        resets.forEach { (where, statement) ->
            // A reset must say WHICH dead letters it revives. Without a filter it resurrects
            // events the server has already permanently rejected, and erases why they failed.
            if (blanketPredicate.containsMatchIn(statement)) blanket += where
            // …and must give them the attempts they are owed under whatever just changed.
            if (!statement.contains("attemptCount = 0")) unbudgeted += where
            // A reset that leaves the stale backoff in place can sit invisible for up to 5 minutes.
            if (!statement.contains("nextAttemptAt = 0")) unclaimable += where
        }

        assertTrue(
            "a dead-letter reset with no filter resurrects events the server has already " +
                "permanently rejected, and erases why they failed: $blanket",
            blanket.isEmpty()
        )
        assertTrue(
            "these dead-letter resets leave `attemptCount` alone, so a rescued row is already past " +
                "MAX_ATTEMPTS and dies again on its first failure with zero retries: $unbudgeted",
            unbudgeted.isEmpty()
        )
        assertTrue(
            "these dead-letter resets leave a stale `nextAttemptAt`, so the rescued rows stay " +
                "invisible until that backoff expires: $unclaimable",
            unclaimable.isEmpty()
        )
    }

    @Test
    fun `theCallbackEvidenceToStatusDerivationHasExactlyOneDefinition`() {
        // `SmsStatusPolicy` carried TWO derivations from the same callback evidence: `nextStatus`
        // (which is the real pipeline — SmsStatusReceiver calls it and writes the provider row) and
        // `aggregateSendState` (which returned a durable `SendState` that NOTHING could consume).
        //
        // The second one was not harmless dead weight. Its KDoc asserted that it existed "so the
        // provider status, the durable state machine and the UI overlay can never disagree about
        // what the device actually knows" — a guarantee about a durable state machine that does not
        // exist and a UI overlay that does not exist (a scan of `ui/` for SEND_UNCONFIRMED,
        // SENT_CONFIRMED and DISPATCHED returns nothing). It also left the evidence->verdict rule
        // written twice, which is the defect shape this project keeps finding: change one copy and
        // the other silently disagrees, and here only one of them is on the real path.
        //
        // The durable evidence it claimed to provide already exists and is already used: the
        // per-segment ledger (`send_segments.callbackState`, persisted BY NAME so it survives
        // reboot) is what SmsStatusReceiver reads to compute the counts both derivations took.
        //
        // So the rule is now single-definition, keyed on the derivation's own signature: the
        // parameter declaration `sentConfirmedParts: Int` may appear exactly once in production.
        val declarations = mainSources()
            .map { it.name to codeOnly(it.readText()) }
            .flatMap { (name, text) ->
                text.lineSequence()
                    .filter { it.contains("sentConfirmedParts: Int") }
                    .map { name }
            }
            .toList()

        assertEquals(
            "the callback-evidence -> status rule must be defined exactly once; a second " +
                "derivation can silently disagree with the one on the real path: $declarations",
            1,
            declarations.size
        )
    }
}
