package com.autonomousone.messages.gateway.wake

import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.fail
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Shared fixtures for the STAGE-4 JVM tests: a real self-signed certificate and a trusting client.
 *
 * Nothing here is production code, and nothing needs an Android device.
 */
object WakeTestHarness {

    val serverCertificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("127.0.0.1")
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build()

    val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate)
        .build()

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    fun endpoint(port: Int): WakeSocketEndpoint =
        WakeSocketEndpoint("wss://127.0.0.1:$port/gateway/wake", "127.0.0.1")

    fun pinFor(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return "sha256/" + Base64.getEncoder().encodeToString(digest)
    }

    fun awaitUntil(
        timeoutMs: Long = 5_000L,
        description: String = "condition",
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        fail("timed out waiting for $description")
    }
}

/** A clock the rate-limit tests can move without sleeping for a minute. */
class MutableClock(initial: Long) : () -> Long {
    @Volatile
    var now: Long = initial

    override fun invoke(): Long = now
}
