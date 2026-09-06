package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Base64
import com.autonomousone.messages.data.MessagesDatabase
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
        val eventDao = MessagesDatabase.get(app).gatewayEventOutboxDao()
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

        if (token.isBlank() || trustRoot.isFailure) return@withContext Report(local)
        val path = "/api/v1/agent/diagnostics"
        val body = JSONObject()
            .put("token", token)
            .put("trustRootFingerprint", fingerprint(trustRoot.getOrThrow()))
            .toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            val base = prefs.gmwebUrl.trimEnd('/')
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
            val configuredOrigin = com.autonomousone.messages.security.PairingEndpointResolver
                .canonicalOrigin(prefs.gmwebUrl)
            val serverOrigin = checks.optString("publicApiOrigin").takeIf { it.isNotBlank() }
            local += Check("PUBLIC_API_ORIGIN", serverOrigin == configuredOrigin,
                serverOrigin ?: "not configured")
            local += Check("Agent signature", checks.optBoolean("agentSignatureAccepted"))
            local += Check("Android identity", checks.optBoolean("identityEnrolled"))
            local += Check("Primary role", checks.optBoolean("isPrimary"), checks.optString("role"))
            local += Check("Trust root match", checks.optBoolean("trustRootMatch"))
            local += Check("Pairing metadata endpoint", checks.optBoolean("pairingMetadataEndpointAvailable"))
            local += Check("Diagnostic ping", checks.optBoolean("diagnosticPingAccepted"))
            local += Check("Server trust registry", true, "sequence ${checks.optLong("serverTrustSequence")}")
            local += Check("Server linked devices", true,
                "${checks.optInt("linkedDeviceCount")} devices · ${checks.optInt("activeLinkedSessionCount")} sessions")
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
}
