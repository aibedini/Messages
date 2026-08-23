package com.autonomousone.messages.gateway

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.mms.MmsSender
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.sms.SmsSender
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Native Android ServerSocket-based HTTP REST API Server for SMS & MMS Gateway.
 * Does not depend on com.sun.net.httpserver which is absent in Android runtime.
 */
class GatewayServer(
    private val context: Context,
    private val port: Int,
    private val apiKey: String,
    private val bindAllInterfaces: Boolean = false,
    private val onRequestLog: ((String) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    // Dedicated single-thread acceptor so slow handlers can never starve accept().
    private val acceptExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gateway-acceptor").apply { isDaemon = true }
    }
    // Handlers live in their own pool; slowloris-style connections are bounded
    // by the 10s socket timeout plus this fixed pool size.
    private val handlerExecutor = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "gateway-handler").apply { isDaemon = true }
    }
    private var isListening = false

    private val smsSender = SmsSender(context)
    private val mmsSender = MmsSender(context)
    private val smsRepository = SmsRepository(context)

    companion object {
        private const val TAG = "GATEWAY_SERVER"

        // ── Brute-force protection state (per client IP) ──
        // value = [consecutiveFailures, windowStartMs, lockedUntilMs]
        private val authFailures = ConcurrentHashMap<String, LongArray>()
        private const val MAX_AUTH_FAILURES = 8
        private const val AUTH_WINDOW_MS = 600_000L      // 10-minute failure window
        private const val LOCKOUT_MS = 300_000L          // 5-minute lockout after 8 failures
        private const val FAILURE_PURGE_THRESHOLD = 64   // purge stale records above this size

        // ── Request limits (DoS hardening) ──
        private const val MAX_BODY_BYTES = 1_000_000     // 1 MB request body cap
        private const val MAX_HEADERS = 100              // header count cap
        private const val MAX_IMAGE_DOWNLOAD_BYTES = 10_000_000 // 10 MB remote image cap

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "127.0.0.1"
        }
    }

    @Synchronized
    fun start() {
        if (isListening) return

        try {
            // Default: bind only to the detected LAN IPv4 address instead of all
            // interfaces (0.0.0.0), limiting exposure to the local network.
            val bindAddress = if (bindAllInterfaces) null else getLocalIpAddress()
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port))
            }
            isListening = true
            Log.d(TAG, "GatewayServer listening on ${bindAddress ?: "0.0.0.0"}:$port")
            onRequestLog?.invoke("✅ Server listening on http://${bindAddress ?: "0.0.0.0"}:$port")

            acceptExecutor.execute {
                while (isListening && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handlerExecutor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isListening) break
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            isListening = false
            Log.e(TAG, "Failed to start GatewayServer on port $port", e)
            onRequestLog?.invoke("❌ Start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        isListening = false
        try {
            serverSocket?.close()
            serverSocket = null
            acceptExecutor.shutdownNow()
            handlerExecutor.shutdownNow()
            Log.d(TAG, "GatewayServer stopped")
            onRequestLog?.invoke("🛑 Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GatewayServer", e)
        }
    }

    fun isRunning(): Boolean = isListening && serverSocket != null && !serverSocket!!.isClosed

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            // 1. Parse HTTP Request Line: "POST /api/v1/sms/send HTTP/1.1"
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // 2. Parse HTTP Headers (capped to prevent header-flood memory growth)
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                if (headers.size >= MAX_HEADERS) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val key = headerParts[0].trim().lowercase()
                    val value = headerParts[1].trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // 3. Parse HTTP Body — reject oversized bodies instead of allocating them
            val body = when {
                contentLength > MAX_BODY_BYTES -> {
                    onRequestLog?.invoke("🚫 413 Payload Too Large [$method $path] ($contentLength bytes)")
                    sendResponse(output, 413, JSONObject().put("error", "Request body too large"))
                    socket.close()
                    return
                }
                contentLength > 0 -> {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buffer, read, contentLength - read)
                        if (r <= 0) break
                        read += r
                    }
                    String(buffer, 0, read)
                }
                else -> ""
            }

            // 4. Authenticate — constant-time comparison + per-IP rate limiting
            val clientApiKey = headers["x-api-key"]
                ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()

            if (apiKey.isNotBlank()) {
                val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
                when (checkAuth(clientApiKey, clientIp)) {
                    AuthResult.Locked -> {
                        onRequestLog?.invoke("⛔ 429 Too Many Requests [$method $path]")
                        sendResponse(output, 429, JSONObject().put("error", "Too many failed attempts, try again later"))
                        socket.close()
                        return
                    }
                    AuthResult.Denied -> {
                        onRequestLog?.invoke("⚠️ 401 Unauthorized [$method $path]")
                        sendResponse(output, 401, JSONObject().put("error", "Unauthorized: Invalid X-API-Key"))
                        socket.close()
                        return
                    }
                    AuthResult.Ok -> { /* proceed */ }
                }
            }

            val cleanPath = path.substringBefore("?").removeSuffix("/")

            // 5. Route request
            when {
                cleanPath == "/api/v1/sms/send" && method == "POST" -> {
                    val json = JSONObject(body)
                    val phone = json.optString("phone", "").trim()
                    val message = json.optString("message", "").trim()

                    if (phone.isBlank() || message.isBlank()) {
                        sendResponse(output, 400, JSONObject().put("error", "phone and message required"))
                    } else {
                        val sentId = smsSender.send(phone, message)
                        onRequestLog?.invoke("📩 POST /api/v1/sms/send -> $phone")
                        sendResponse(output, 200, JSONObject().apply {
                            put("status", "success")
                            put("id", sentId)
                            put("phone", phone)
                            put("message", message)
                        })
                    }
                }
                cleanPath == "/api/v1/mms/send" && method == "POST" -> {
                    val json = JSONObject(body)
                    val phone = json.optString("phone", "").trim()
                    val imageUrl = json.optString("imageUrl", "").trim()

                    if (phone.isBlank() || imageUrl.isBlank()) {
                        sendResponse(output, 400, JSONObject().put("error", "phone and imageUrl required"))
                    } else {
                        val imageUri = resolveImageUri(imageUrl)
                        if (imageUri == null) {
                            onRequestLog?.invoke("🖼 POST /api/v1/mms/send rejected (unsupported imageUrl)")
                            sendResponse(output, 400, JSONObject().put(
                                "error", "imageUrl must be an https:// URL or a content:// URI"
                            ))                        } else {
                            val success = mmsSender.sendImage(phone, imageUri)
                            onRequestLog?.invoke("🖼 POST /api/v1/mms/send -> $phone (success=$success)")
                            sendResponse(output, if (success) 200 else 500, JSONObject().apply {
                                put("status", if (success) "success" else "failed")
                                put("phone", phone)
                            })
                        }
                    }
                }
                cleanPath == "/api/v1/sms/inbox" && method == "GET" -> {
                    try {
                        val smsList = smsRepository.getAllSms().take(50)
                        val jsonArray = JSONArray()
                        smsList.forEach { sms ->
                            jsonArray.put(JSONObject().apply {
                                put("id", sms.id)
                                put("sender", sms.sender)
                                put("message", sms.message)
                                put("date", sms.date)
                                put("type", if (sms.type == 1) "received" else "sent")
                            })
                        }
                        onRequestLog?.invoke("📬 GET /api/v1/sms/inbox -> ${smsList.size} items")
                        sendResponse(output, 200, JSONObject().apply {
                            put("status", "success")
                            put("count", smsList.size)
                            put("messages", jsonArray)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Inbox query failed", e)
                        onRequestLog?.invoke("❌ GET /api/v1/sms/inbox error: ${e.message}")
                        // Do not leak internal exception details to API clients.
                        sendResponse(output, 500, JSONObject().apply {
                            put("status", "error")
                            put("error", "Failed to query SMS database")
                        })
                    }
                }
                cleanPath == "/api/v1/sms" && method == "GET" -> {
                    try {
                        val queryParams = parseQueryParams(path)
                        val limit = queryParams["limit"]?.toIntOrNull()
                        val offset = queryParams["offset"]?.toIntOrNull()
                        val type = queryParams["type"]
                        val phone = queryParams["phone"] ?: queryParams["from"]
                        val fromDateStr = queryParams["from_date"]
                        val toDateStr = queryParams["to_date"]

                        val fromDate = fromDateStr?.let { parseDateToMillis(it) }
                        val toDate = toDateStr?.let { parseDateToMillis(it) }

                        val smsList = smsRepository.getSmsWithFilters(
                            limit = limit,
                            offset = offset,
                            type = type,
                            phone = phone,
                            fromDate = fromDate,
                            toDate = toDate
                        )

                        val jsonArray = JSONArray()
                        smsList.forEach { sms ->
                            jsonArray.put(JSONObject().apply {
                                put("id", sms.id)
                                put("sender", sms.sender)
                                put("message", sms.message)
                                put("date", sms.date)
                                put("type", if (sms.type == 1) "received" else "sent")
                                put("threadId", sms.threadId)
                                put("unread", sms.unread)
                            })
                        }

                        onRequestLog?.invoke("📬 GET /api/v1/sms -> ${smsList.size} items (limit=$limit, offset=$offset)")
                        sendResponse(output, 200, JSONObject().apply {
                            put("status", "success")
                            put("count", smsList.size)
                            put("limit", limit ?: "all")
                            put("offset", offset ?: 0)
                            put("messages", jsonArray)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS query failed", e)
                        onRequestLog?.invoke("❌ GET /api/v1/sms error: ${e.message}")
                        // Do not leak internal exception details to API clients.
                        sendResponse(output, 500, JSONObject().apply {
                            put("status", "error")
                            put("error", "Failed to query SMS database")
                        })
                    }
                }
                cleanPath == "/api/v1/status" -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val defaultSms = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
                            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
                        } else {
                            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
                        }
                    } catch (e: Exception) { false }

                    onRequestLog?.invoke("ℹ️ GET /api/v1/status -> 200 OK")
                    sendResponse(output, 200, JSONObject().apply {
                        put("status", "online")
                        put("version", "1.0")
                        put("ip", getLocalIpAddress())
                        put("port", port)
                        put("batteryLevel", batteryLevel)
                        put("isDefaultSmsApp", defaultSms)
                        put("timestamp", System.currentTimeMillis())
                    })
                }
                else -> {
                    sendResponse(output, 404, JSONObject().put("error", "Endpoint not found"))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Client handler exception", e)
            try {
                val output = socket.getOutputStream()
                // Do not leak internal exception details to API clients.
                sendResponse(output, 500, JSONObject().put("error", "Internal server error"))
            } catch (ignored: Exception) {}
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private enum class AuthResult { Ok, Denied, Locked }

    /**
     * Constant-time API-key comparison with per-IP brute-force throttling.
     */
    private fun checkAuth(clientApiKey: String?, clientIp: String): AuthResult {
        val now = System.currentTimeMillis()

        // Periodically purge stale failure records so the map cannot grow unbounded.
        if (authFailures.size > FAILURE_PURGE_THRESHOLD) {
            authFailures.entries.removeIf {
                it.value[2] <= now && now - it.value[1] > AUTH_WINDOW_MS
            }
        }

        val record = authFailures[clientIp]
        // Lockout still active?
        if (record != null && record[2] > now) {
            return AuthResult.Locked
        }

        // Window expired → reset the failure counter
        var failures = if (record == null || now - record[1] > AUTH_WINDOW_MS) 0 else record[0].toInt()

        val authorized = clientApiKey != null && MessageDigest.isEqual(
            apiKey.toByteArray(Charsets.UTF_8),
            clientApiKey.toByteArray(Charsets.UTF_8)
        )

        return if (authorized) {
            authFailures.remove(clientIp)
            AuthResult.Ok
        } else {
            failures++
            val windowStart = if (failures == 1) now else (record?.get(1) ?: now)
            val lockedUntil = if (failures >= MAX_AUTH_FAILURES) now + LOCKOUT_MS else 0L
            authFailures[clientIp] = longArrayOf(failures.toLong(), windowStart, lockedUntil)
            AuthResult.Denied
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, json: JSONObject) {
        val statusMsg = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Internal Server Error"
        }
        val bodyBytes = json.toString(2).toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"

        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    /**
     * Resolve the caller-supplied MMS image reference into a readable Uri:
     *  - `content://` URIs pass through (local provider access).
     *  - `https://` URLs are downloaded into app cache and exposed through
     *    FileProvider (this also makes remote-image MMS actually work, since
     *    ContentResolver cannot open https URLs directly).
     *  - Plain `http://` is rejected: targetSdk 36 blocks cleartext traffic, so
     *    such downloads would silently fail — and plaintext fetches leak metadata.
     * Anything else (`file://`, custom schemes, etc.) is rejected.
     */
    private fun resolveImageUri(imageUrl: String): Uri? {
        return try {
            when {
                imageUrl.startsWith("content://") -> Uri.parse(imageUrl)
                imageUrl.startsWith("https://") -> downloadImageToCache(imageUrl)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve MMS imageUrl", e)
            null
        }
    }

    /** Download [urlString] into cache dir, capped at MAX_IMAGE_DOWNLOAD_BYTES. */
    private fun downloadImageToCache(urlString: String): Uri? {
        val conn = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 200..299) return null
            val reported = conn.contentLengthLong
            if (reported > MAX_IMAGE_DOWNLOAD_BYTES) return null

            val file = java.io.File(context.cacheDir, "mms_${System.currentTimeMillis()}.img")
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    var total = 0L
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        total += r
                        if (total > MAX_IMAGE_DOWNLOAD_BYTES) {
                            file.delete()
                            return null
                        }
                        out.write(buf, 0, r)
                    }
                }
            }
            return androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseQueryParams(path: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val queryStart = path.indexOf('?')
        if (queryStart == -1 || queryStart == path.length - 1) return params

        val query = path.substring(queryStart + 1)
        query.split('&').forEach { param ->
            val keyValue = param.split('=', limit = 2)
            if (keyValue.size == 2) {
                val key = java.net.URLDecoder.decode(keyValue[0], "UTF-8")
                val value = java.net.URLDecoder.decode(keyValue[1], "UTF-8")
                params[key] = value
            }
        }
        return params
    }

    private fun parseDateToMillis(dateStr: String): Long? {
        return try {
            if (dateStr.matches(Regex("^\\d+$"))) {
                dateStr.toLong()
            } else {
                val parts = dateStr.split('-')
                if (parts.size == 3) {
                    val year = parts[0].toInt()
                    val month = parts[1].toInt()
                    val day = parts[2].toInt()
                    val cal = java.util.Calendar.getInstance()
                    cal.set(year, month - 1, day, 0, 0, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing date: $dateStr", e)
            null
        }
    }
}
