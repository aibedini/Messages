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
import java.util.concurrent.Executors

/**
 * Native Android ServerSocket-based HTTP REST API Server for SMS & MMS Gateway.
 * Does not depend on com.sun.net.httpserver which is absent in Android runtime.
 */
class GatewayServer(
    private val context: Context,
    private val port: Int,
    private val apiKey: String,
    private val onRequestLog: ((String) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(4)
    private var isListening = false

    private val smsSender = SmsSender(context)
    private val mmsSender = MmsSender(context)
    private val smsRepository = SmsRepository(context)

    companion object {
        private const val TAG = "GATEWAY_SERVER"

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
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            isListening = true
            Log.d(TAG, "GatewayServer listening on port $port")
            onRequestLog?.invoke("✅ Server listening on http://${getLocalIpAddress()}:$port")

            executor.execute {
                while (isListening && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(clientSocket) }
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

            // 2. Parse HTTP Headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
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

            // 3. Parse HTTP Body
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(buffer, read, contentLength - read)
                    if (r <= 0) break
                    read += r
                }
                String(buffer, 0, read)
            } else ""

            // 4. Authenticate
            val clientApiKey = headers["x-api-key"]
                ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()

            if (apiKey.isNotBlank() && clientApiKey != apiKey) {
                onRequestLog?.invoke("⚠️ 401 Unauthorized [$method $path]")
                sendResponse(output, 401, JSONObject().put("error", "Unauthorized: Invalid X-API-Key"))
                socket.close()
                return
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
                        val success = mmsSender.sendImage(phone, Uri.parse(imageUrl))
                        onRequestLog?.invoke("🖼 POST /api/v1/mms/send -> $phone (success=$success)")
                        sendResponse(output, if (success) 200 else 500, JSONObject().apply {
                            put("status", if (success) "success" else "failed")
                            put("phone", phone)
                        })
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
                        sendResponse(output, 500, JSONObject().apply {
                            put("status", "error")
                            put("error", e.message ?: "Failed to query SMS database")
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
                sendResponse(output, 500, JSONObject().put("error", e.message ?: "Internal server error"))
            } catch (ignored: Exception) {}
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, json: JSONObject) {
        val statusMsg = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val bodyBytes = json.toString(2).toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"

        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }
}
