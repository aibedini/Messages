package com.autonomousone.messages.gateway

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Base64
import com.autonomousone.messages.data.GatewayEventDiagnosticCount
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.SyncStateEntity
import com.autonomousone.messages.security.PairingEndpointResolver
import com.autonomousone.messages.security.PrimaryTrustRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Runs a memory-only, diagnostic-token-gated health check without exposing secrets. */
object ConnectionDiagnostics {
    data class Check(val name: String, val pass: Boolean, val detail: String? = null)
    data class Report(val checks: List<Check>) {
        val passed: Boolean get() = checks.all { it.pass }
        fun asText(): String = buildString {
            checks.forEach { check ->
                append(check.name.padEnd(30)).append(if (check.pass) "PASS" else "FAIL")
                check.detail?.let { append("  ").append(it) }
                appendLine()
            }
            append("Overall: ").append(if (passed) "PASS" else "FAIL")
        }
    }

    suspend fun run(context: Context, token: String): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = GatewayPreferences(app)
        val supervisor = ConnectionSupervisor.peek()
        val trust = TrustStatementPublisher.health.value
        val database = MessagesDatabase.get(app)
        val eventDao = database.gatewayEventOutboxDao()
        val local = mutableListOf<Check>()
        val deviceId = prefs.stableDeviceId(app)
        local += Check("Android stable device ID", deviceId.isNotBlank(), deviceId.take(8) + "…")

        val operationalKey = runCatching {
            DeviceIdentity.signWithOperationalKey("connection-diagnostic".toByteArray(Charsets.UTF_8))
        }
        local += Check("Android operational key", operationalKey.isSuccess)

        val trustRoot = runCatching { PrimaryTrustRoot.publicKeyBase64() }
        local += Check("Android trust-root key", trustRoot.isSuccess)
        local += Check("Gateway service", supervisor != null && supervisor.desiredEnabled,
            supervisor?.stateFlow?.value?.name ?: "not running")
        local += Check("Connection supervisor", supervisor?.stateFlow?.value == ConnectionSupervisor.State.CONNECTED,
            supervisor?.stateFlow?.value?.name ?: "unavailable")
        local += Check("Event uploader", EventUploader.running.value)
        local += Check("Event outbox", eventDao.pendingDepth() == 0,
            "${eventDao.pendingDepth()} pending · ${eventDao.deadLetterDepth()} dead letter")
        local += Check("Trust publisher", trust.running, "${trust.pendingCount} pending")
        local += Check("Trust outbox", trust.pendingCount == 0,
            "oldest ${trust.oldestPendingSequence ?: "none"}" +
                (trust.lastFailureReason?.let { " · $it" } ?: ""))

        val smsProviderCount = providerCount(app, Telephony.Sms.CONTENT_URI)
        val mmsProviderCount = providerCount(app, Telephony.Mms.CONTENT_URI)
        val roomTotal = database.messageDao().count()
        val roomSms = database.messageDao().countBySource(MessageEntity.SOURCE_SMS)
        val roomMms = database.messageDao().countBySource(MessageEntity.SOURCE_MMS)
        val smsState = database.syncStateDao().forSource(MessageEntity.SOURCE_SMS)
        val mmsState = database.syncStateDao().forSource(MessageEntity.SOURCE_MMS)
        val eventCounts = eventDao.diagnosticCounts()
        val maxAckedSequence = eventDao.maxAckedServerSequence()

        local += providerCheck("Telephony SMS rows", smsProviderCount)
        local += providerCheck("Telephony MMS rows", mmsProviderCount)
        local += Check("Room message rows", roomTotal == roomSms + roomMms,
            "$roomTotal total · $roomSms SMS · $roomMms MMS")
        local += Check("Room SMS rows", smsProviderCount != null && roomSms == smsProviderCount,
            roomSms.toString())
        local += Check("Room MMS rows", mmsProviderCount != null && roomMms == mmsProviderCount,
            roomMms.toString())
        local += mirrorCheck("SMS initial mirror", smsState, initial = true)
        local += mirrorCheck("SMS history backfill", smsState, initial = false)
        local += watermarkCheck("SMS provider watermarks", smsState)
        local += mirrorCheck("MMS initial mirror", mmsState, initial = true)
        local += mirrorCheck("MMS history backfill", mmsState, initial = false)
        local += watermarkCheck("MMS provider watermarks", mmsState)
        local += eventSummaryCheck("Cloud MESSAGE_CREATED", "MESSAGE_CREATED", eventCounts)
        val cloudKeyType = listOf("HISTORY_KEY_GRANT", "KEYRING_ENTRY", "KEY_GRANT")
            .firstOrNull { type -> eventCounts.any { it.eventType == type && it.state == "ACKED" } }
            ?: "HISTORY_KEY_GRANT"
        local += eventSummaryCheck("Cloud message key", cloudKeyType, eventCounts)
        local += Check("Cloud event groups", eventCounts.none { it.state == "DEAD_LETTER" },
            eventCounts.joinToString(" · ").ifBlank { "none" })
        local += Check("Last Android server sequence", maxAckedSequence > 0, maxAckedSequence.toString())

        if (token.isBlank() || trustRoot.isFailure) return@withContext Report(local)
        val path = "/api/v1/agent/diagnostics"
        val body = JSONObject()
            .put("token", token)
            .put("trustRootFingerprint", fingerprint(trustRoot.getOrThrow()))
            .toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            val base = PairingEndpointResolver.trustedServerUrl(app).trimEnd('/')
            require(base.startsWith("https://")) { "trusted_https_origin_required" }
            conn = URL(base + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            check(AgentAuth.sign(conn, deviceId, path, "POST", body)) { "agent_signing_failed" }
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            local += Check("GMweb API", status in 200..299, "HTTP $status")
            if (status !in 200..299) return@withContext Report(local)
            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val checks = response.getJSONObject("checks")
            val configuredOrigin = PairingEndpointResolver.canonicalOrigin(
                PairingEndpointResolver.trustedServerUrl(app)
            )
            val serverOriginRaw = checks.optString("publicApiOrigin").takeIf { it.isNotBlank() }
            val serverOrigin = serverOriginRaw?.let(PairingEndpointResolver::canonicalOrigin)
            local += Check("PUBLIC_API_ORIGIN",
                configuredOrigin != null && serverOrigin != null && configuredOrigin == serverOrigin,
                serverOriginRaw ?: "not configured")
            local += Check("Agent signature", checks.optBoolean("agentSignatureAccepted"))
            local += Check("Android identity", checks.optBoolean("identityEnrolled"))
            local += Check("Primary role", checks.optBoolean("isPrimary"), checks.optString("role"))
            local += Check("Trust root match", checks.optBoolean("trustRootMatch"))
            local += Check("Pairing metadata endpoint", checks.optBoolean("pairingMetadataEndpointAvailable"))
            local += Check("Diagnostic ping", checks.optBoolean("diagnosticPingAccepted"))
            local += Check("Server trust registry", true, "sequence ${checks.optLong("serverTrustSequence")}")
            local += Check("Server linked devices", true,
                "${checks.optInt("linkedDeviceCount")} devices · ${checks.optInt("activeLinkedSessionCount")} sessions")
            response.optJSONObject("sync")?.let { sync ->
                sync.optJSONObject("account")?.let { addServerSyncChecks(local, "GMweb", it) }
                sync.optJSONObject("sourceDevice")?.let { source ->
                    addServerSyncChecks(local, "This Android on GMweb", source)
                    local += Check(
                        "Android ↔ GMweb sequence",
                        maxAckedSequence > 0 && maxAckedSequence == source.optLong("maxSequence"),
                        "$maxAckedSequence Android · ${source.optLong("maxSequence")} GMweb"
                    )
                }
            }
        } catch (error: Exception) {
            local += Check("GMweb API", false, error.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
        Report(local)
    }

    private fun fingerprint(base64Spki: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Base64.decode(base64Spki, Base64.DEFAULT))
            .joinToString("") { "%02x".format(it) }

    private fun providerCount(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)
            ?.use { it.count }
    }.getOrNull()

    private fun providerCheck(name: String, count: Int?): Check =
        Check(name, count != null, count?.toString() ?: "unavailable")

    private fun mirrorCheck(name: String, state: SyncStateEntity?, initial: Boolean): Check {
        val ready = if (initial) state?.initialWindowReady == true else state?.historyBackfillComplete == true
        return Check(name, ready, if (ready) if (initial) "ready" else "complete" else "not ready")
    }

    private fun watermarkCheck(name: String, state: SyncStateEntity?): Check = Check(
        name,
        state != null && state.newestId > 0 && state.oldestId != Long.MAX_VALUE,
        state?.let { "newest ${it.newestDate}/${it.newestId} · oldest ${it.oldestDate}/${it.oldestId}" }
            ?: "missing"
    )

    private fun eventSummaryCheck(
        name: String,
        eventType: String,
        counts: List<GatewayEventDiagnosticCount>
    ): Check {
        fun state(value: String) = counts.filter { it.eventType == eventType && it.state == value }.sumOf { it.count }
        val pending = state("PENDING")
        val sending = state("SENDING")
        val acked = state("ACKED")
        val dead = state("DEAD_LETTER")
        return Check(name, acked > 0 && dead == 0,
            "$pending pending · $sending sending · $acked ACKed · $dead dead letter")
    }

    private fun addServerSyncChecks(target: MutableList<Check>, prefix: String, stats: JSONObject) {
        val total = stats.optLong("total")
        target += Check("$prefix sync_events", total > 0, total.toString())
        target += Check("$prefix MESSAGE_CREATED", stats.optLong("messageCreated") > 0,
            stats.optLong("messageCreated").toString())
        target += Check("$prefix MESSAGE_UPDATED", true, stats.optLong("messageUpdated").toString())
        target += Check("$prefix KEY_GRANT", stats.optLong("keyGrant") > 0,
            stats.optLong("keyGrant").toString())
        val crypto = stats.optJSONArray("byCryptoVersion")
        val cryptoDetail = buildList {
            if (crypto != null) for (index in 0 until crypto.length()) {
                val row = crypto.getJSONObject(index)
                add("v${row.optInt("value")}:${row.optLong("count")}")
            }
        }.joinToString(" · ").ifBlank { "none" }
        target += Check("$prefix crypto versions", true, cryptoDetail)
        target += Check("$prefix max sequence", stats.optLong("maxSequence") > 0,
            stats.optLong("maxSequence").toString())
    }
}
