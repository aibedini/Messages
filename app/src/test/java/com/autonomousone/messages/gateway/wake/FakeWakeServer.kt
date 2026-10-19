package com.autonomousone.messages.gateway.wake

import okhttp3.tls.HandshakeCertificates
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket

/**
 * An in-process TLS WebSocket server for the STAGE-4 JVM tests.
 *
 * It implements exactly the surface the client uses: the RFC 6455 upgrade, text frames,
 * protocol pings/pongs, close and abrupt disconnect. It also records the upgrade headers, so the
 * handshake test asserts what actually went on the wire rather than what a fake signer returned.
 *
 * No GMweb code and no device are involved.
 */
class FakeWakeServer(
    private val certificates: HandshakeCertificates,
    private val status: Int = 101,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Closeable {

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private val serverSocket: SSLServerSocket = certificates.sslContext()
        .serverSocketFactory
        .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

    val port: Int get() = serverSocket.localPort

    val requestCount = AtomicInteger(0)
    val upgradeCount = AtomicInteger(0)
    val pingCount = AtomicInteger(0)
    val pongCount = AtomicInteger(0)
    val clientTextFrames = AtomicInteger(0)

    @Volatile
    var lastRequestLine: String? = null
        private set

    @Volatile
    var lastRequestHeaders: Map<String, String> = emptyMap()
        private set

    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val writeLock = Any()

    @Volatile
    private var upgradedSocket: Socket? = null

    @Volatile
    private var closed = false

    private val acceptThread = Thread({
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            sockets.add(socket)
            Thread({ handle(socket) }, "fake-wake-connection").apply {
                isDaemon = true
                start()
            }
        }
    }, "fake-wake-accept").apply {
        isDaemon = true
        start()
    }

    fun sendWake(): Boolean = sendText("{\"t\":\"wake\"}")

    fun sendText(text: String): Boolean = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(bytes: ByteArray): Boolean = sendFrame(0x2, bytes)

    /** Drops the upgraded socket without a WebSocket close handshake. */
    fun dropConnection() {
        runCatching { upgradedSocket?.close() }
        upgradedSocket = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        runCatching {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            lastRequestLine = requestLine
            lastRequestHeaders = headers
            requestCount.incrementAndGet()

            val output = socket.getOutputStream()
            if (status != 101) {
                val body = "rejected"
                output.write(
                    ("HTTP/1.1 $status Unauthorized\r\n" +
                        "Content-Length: ${body.length}\r\n" +
                        "Connection: close\r\n\r\n$body").toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                runCatching { socket.close() }
                return
            }

            val key = headers["sec-websocket-key"] ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
            )
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                extraHeaders.forEach { (name, value) ->
                    append(name).append(": ").append(value).append("\r\n")
                }
                append("\r\n")
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
            upgradedSocket = socket
            upgradeCount.incrementAndGet()
            frameLoop(socket, input)
        }.onFailure {
            runCatching { socket.close() }
        }
    }

    private fun frameLoop(socket: Socket, input: InputStream) {
        while (!closed) {
            val first = input.read()
            if (first < 0) return
            val second = input.read()
            if (second < 0) return
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                length = 0L
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            if (length > 1_000_000L) return
            val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(length.toInt())
            input.readFully(payload)
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x1 -> clientTextFrames.incrementAndGet()
                0x8 -> {
                    sendFrame(0x8, ByteArray(0))
                    runCatching { socket.close() }
                    return
                }
                0x9 -> {
                    pingCount.incrementAndGet()
                    sendFrame(0xA, payload)
                }
                0xA -> pongCount.incrementAndGet()
                else -> Unit
            }
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray): Boolean = synchronized(writeLock) {
        val socket = upgradedSocket ?: return false
        return try {
            val output: OutputStream = socket.getOutputStream()
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.write((payload.size ushr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    repeat(8) { shift -> output.write(((payload.size.toLong() ushr (56 - shift * 8)) and 0xFF).toInt()) }
                }
            }
            output.write(payload)
            output.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        acceptThread.interrupt()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read < 0) throw java.io.EOFException("socket closed")
            offset += read
        }
    }
}
