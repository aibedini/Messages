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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

class GatewayServer(
    private val context: Context,
    private val port: Int,
    private val apiKey: String,
    private val onRequestLog: ((String) -> Unit)? = null
) {

    private var server: HttpServer? = null
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
        if (server != null) return

        try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/api/v1/sms/send", SendSmsHandler())
                createContext("/api/v1/mms/send", SendMmsHandler())
                createContext("/api/v1/sms/inbox", InboxHandler())
                createContext("/api/v1/status", StatusHandler())
                executor = Executors.newFixedThreadPool(4)
                start()
            }
            Log.d(TAG, "GatewayServer started on port $port")
            onRequestLog?.invoke("✅ Server listening on http://${getLocalIpAddress()}:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GatewayServer on port $port", e)
            onRequestLog?.invoke("❌ Start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop(0)
            server = null
            Log.d(TAG, "GatewayServer stopped")
            onRequestLog?.invoke("🛑 Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GatewayServer", e)
        }
    }

    fun isRunning(): Boolean = server != null

    // ── Authentication Check ──────────────────────────────────────────────────
    private fun authenticate(exchange: HttpExchange): Boolean {
        if (apiKey.isBlank()) return true // No key configured = open
        val clientKey = exchange.requestHeaders.getFirst("X-API-Key")
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return clientKey == apiKey
    }

    // ── HTTP Handlers ─────────────────────────────────────────────────────────

    private inner class SendSmsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod

            if (method != "POST") {
                sendJsonResponse(exchange, 450, JSONObject().put("error", "Method not allowed"))
                return
            }

            if (!authenticate(exchange)) {
                onRequestLog?.invoke("⚠️ 401 Unauthorized request to $path")
                sendJsonResponse(exchange, 401, JSONObject().put("error", "Unauthorized: Invalid X-API-Key"))
                return
            }

            try {
                val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
                val json = JSONObject(bodyText)
                val phone = json.optString("phone", "").trim()
                val message = json.optString("message", "").trim()

                if (phone.isBlank() || message.isBlank()) {
                    sendJsonResponse(exchange, 400, JSONObject().put("error", "phone and message are required"))
                    return
                }

                val sentId = smsSender.send(phone, message)
                onRequestLog?.invoke("📩 POST /api/v1/sms/send -> Sent to $phone")

                val response = JSONObject().apply {
                    put("status", "success")
                    put("id", sentId)
                    put("phone", phone)
                    put("message", message)
                }
                sendJsonResponse(exchange, 200, response)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling send SMS", e)
                sendJsonResponse(exchange, 500, JSONObject().put("error", e.message ?: "Internal error"))
            }
        }
    }

    private inner class SendMmsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            if (method != "POST") {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed"))
                return
            }

            if (!authenticate(exchange)) {
                sendJsonResponse(exchange, 401, JSONObject().put("error", "Unauthorized: Invalid X-API-Key"))
                return
            }

            try {
                val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
                val json = JSONObject(bodyText)
                val phone = json.optString("phone", "").trim()
                val imageUrl = json.optString("imageUrl", "").trim()
                val caption = json.optString("caption", "").trim()

                if (phone.isBlank() || imageUrl.isBlank()) {
                    sendJsonResponse(exchange, 400, JSONObject().put("error", "phone and imageUrl are required"))
                    return
                }

                val imageUri = Uri.parse(imageUrl)
                val success = mmsSender.sendImage(phone, imageUri)
                onRequestLog?.invoke("🖼 POST /api/v1/mms/send -> Sent to $phone (success=$success)")

                val response = JSONObject().apply {
                    put("status", if (success) "success" else "failed")
                    put("phone", phone)
                    put("imageUrl", imageUrl)
                }
                sendJsonResponse(exchange, if (success) 200 else 500, response)

            } catch (e: Exception) {
                sendJsonResponse(exchange, 500, JSONObject().put("error", e.message ?: "Internal error"))
            }
        }
    }

    private inner class InboxHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed"))
                return
            }

            if (!authenticate(exchange)) {
                sendJsonResponse(exchange, 401, JSONObject().put("error", "Unauthorized"))
                return
            }

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

                onRequestLog?.invoke("📬 GET /api/v1/sms/inbox -> Returned ${smsList.size} items")
                sendJsonResponse(exchange, 200, JSONObject().apply {
                    put("status", "success")
                    put("count", smsList.size)
                    put("messages", jsonArray)
                })

            } catch (e: Exception) {
                sendJsonResponse(exchange, 500, JSONObject().put("error", e.message ?: "Internal error"))
            }
        }
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val defaultSms = try {
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            } catch (e: Exception) { false }

            val response = JSONObject().apply {
                put("status", "online")
                put("version", "1.0")
                put("ip", getLocalIpAddress())
                put("port", port)
                put("batteryLevel", batteryLevel)
                put("isDefaultSmsApp", defaultSms)
                put("timestamp", System.currentTimeMillis())
            }

            onRequestLog?.invoke("ℹ️ GET /api/v1/status -> 200 OK")
            sendJsonResponse(exchange, 200, response)
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: JSONObject) {
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { os ->
            os.write(bytes)
        }
    }
}
