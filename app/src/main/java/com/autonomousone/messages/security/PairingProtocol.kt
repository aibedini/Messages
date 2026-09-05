package com.autonomousone.messages.security

import org.json.JSONObject
import java.security.MessageDigest

/** UTF-8 netstrings. This encoder never calls a platform JSON serializer. */
object PairingProtocol {
    const val PROTOCOL = "GMweb-Pairing-v1"
    private fun encode(kind: String, fields: List<String>): String =
        "$PROTOCOL:$kind\n" + fields.joinToString("") { value ->
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (Character.isHighSurrogate(ch)) {
                    require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1]))
                    i += 2
                } else {
                    require(!Character.isLowSurrogate(ch))
                    i++
                }
            }
            "${value.toByteArray(Charsets.UTF_8).size}:$value,"
        }
    private fun number(o: JSONObject, key: String): String {
        val value = o.get(key)
        require(value is Number)
        val n = value.toLong()
        require(n in 0..9007199254740991L && value.toDouble() == n.toDouble())
        return n.toString()
    }
    private fun string(o: JSONObject, key: String): String = (o.get(key) as? String) ?: error("invalid protocol string")
    fun transcript(t: JSONObject): String {
        require(t.getString("protocol") == PROTOCOL)
        return encode("transcript", listOf("pairingSessionId", "webDeviceId", "webSigningPublicKey",
            "webEncryptionPublicKey", "ephemeralPublicKey", "nonce", "apiOrigin", "webOrigin")
            .map { string(t, it) } + number(t, "expiresAt"))
    }
    fun certificate(c: JSONObject): String {
        require(c.getString("protocol") == PROTOCOL)
        val a = c.getJSONArray("capabilities")
        val caps = (0 until a.length()).map { (a.get(it) as? String) ?: error("invalid capability") }.sorted()
        require(caps.distinct().size == caps.size)
        return encode("certificate", listOf("accountId", "deviceId", "deviceType", "signingPublicKey", "encryptionPublicKey")
            .map { string(c, it) } + caps.size.toString() + caps + string(c, "historyGrant") +
            listOf("trustSequence", "issuedAt", "expiresAt").map { number(c, it) } +
            listOf("pairingTranscriptHash", "pairingSessionId", "apiOrigin", "webOrigin").map { string(c, it) })
    }
    fun enrollment(c: JSONObject): String {
        val keys = c.getJSONObject("publicKeys")
        return encode("enrollment", listOf(string(c, "claim"), string(c, "deviceId"),
            string(keys, "signing"), string(keys, "encryption"), string(keys, "trustRoot"), string(c, "apiOrigin")))
    }
    fun challenge(c: JSONObject): String = encode("challenge",
        listOf("pairingSessionId", "deviceId", "challenge", "apiOrigin", "webOrigin").map { string(c, it) } + number(c, "issuedAt"))
    fun transcriptHash(t: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(transcript(t).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
