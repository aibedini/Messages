package com.autonomousone.messages.evechat

import com.autonomousone.messages.evechat.model.EveChatAgent
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.PagedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class ApiError(val kind: Kind, val httpStatus: Int? = null, val serverMessage: String? = null) {

    enum class Kind { NETWORK, TIMEOUT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, SERVER, CLIENT, PARSE }

    /** Network blips, rate limits and 5xx are worth retrying; a 4xx answer will not change. */
    val retryable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.RATE_LIMITED || kind == Kind.SERVER

    val userMessage: String
        get() = serverMessage ?: when (kind) {
            Kind.NETWORK -> "اتصال به سرور برقرار نشد"
            Kind.TIMEOUT -> "پاسخ سرور طول کشید؛ دوباره تلاش کنید"
            Kind.UNAUTHORIZED -> "نشست شما منقضی شده است؛ دوباره وارد شوید"
            Kind.FORBIDDEN -> "به این بخش دسترسی ندارید"
            Kind.NOT_FOUND -> "مورد درخواستی یافت نشد"
            Kind.CONFLICT -> "این گفتگو توسط کارشناس دیگری برداشته شده است"
            Kind.RATE_LIMITED -> "درخواست‌ها زیاد است؛ کمی صبر کنید"
            Kind.SERVER -> "خطای سرور؛ کمی بعد دوباره تلاش کنید"
            Kind.CLIENT -> "درخواست نامعتبر است"
            Kind.PARSE -> "پاسخ نامعتبر از سرور"
        }

    companion object {
        fun fromStatus(status: Int, serverMessage: String?) = ApiError(
            kind = when (status) {
                401 -> Kind.UNAUTHORIZED
                403 -> Kind.FORBIDDEN
                404 -> Kind.NOT_FOUND
                409 -> Kind.CONFLICT
                408 -> Kind.TIMEOUT
                429 -> Kind.RATE_LIMITED
                in 500..599 -> Kind.SERVER
                else -> Kind.CLIENT
            },
            httpStatus = status,
            serverMessage = serverMessage
        )

        /** EveChat errors are `{"code":"…","message":"…"}`; tolerate a bare `error` string too. */
        fun parseServerMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(body)
                json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
            }.getOrNull()
        }
    }
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}

data class TokenPair(val accessToken: String, val refreshToken: String)

data class ConversationDetail(
    val conversation: EveChatConversation,
    val messages: List<EveChatMessage>
)

data class SendResult(val message: EveChatMessage, val duplicate: Boolean)

/**
 * Stateless EveChat REST transport. Authentication policy (refresh on 401, single flight) lives
 * in [EveChatAuth]; this class only maps HTTP to typed results and logs each call without
 * bodies or credentials.
 */
class EveChatApiClient(private val baseUrl: () -> String) {

    suspend fun login(server: String, email: String, password: String): ApiResult<TokenPair> =
        call("POST", "/api/v1/auth/login", null, JSONObject().put("email", email).put("password", password), server)
            .parse { tokens(it) }

    suspend fun refresh(refreshToken: String): ApiResult<TokenPair> =
        call("POST", "/api/v1/auth/refresh", null, JSONObject().put("refresh_token", refreshToken))
            .parse { tokens(it) }

    suspend fun me(token: String): ApiResult<EveChatAgent> =
        call("GET", "/api/v1/me", token).parse { EveChatAgent.fromJson(it.getJSONObject("agent")) }

    /** [status] null means the server default: every open (pending + active) conversation. */
    suspend fun conversations(token: String, status: String?, limit: Int, offset: Int): ApiResult<PagedList<EveChatConversation>> {
        val query = buildString {
            append("?limit=").append(limit).append("&offset=").append(offset)
            if (status != null) append("&status=").append(enc(status))
        }
        return call("GET", "/api/v1/conversations$query", token).parse { json ->
            val items = json.optJSONArray("data").objects().map(EveChatConversation::fromJson)
            PagedList(items, json.optInt("total", items.size))
        }
    }

    suspend fun conversation(token: String, id: String): ApiResult<ConversationDetail> =
        call("GET", "/api/v1/conversations/${enc(id)}", token).parse { json ->
            ConversationDetail(
                conversation = EveChatConversation.fromJson(json.getJSONObject("conversation")),
                messages = json.optJSONArray("messages").objects().map(EveChatMessage::fromJson)
            )
        }

    /** Oldest first, strictly after [sinceMs] (second precision on the server — callers overlap and dedupe). */
    suspend fun messages(token: String, conversationId: String, sinceMs: Long, limit: Int): ApiResult<List<EveChatMessage>> {
        val since = if (sinceMs > 0) "&since=$sinceMs" else ""
        return call("GET", "/api/v1/conversations/${enc(conversationId)}/messages?limit=$limit$since", token)
            .parse { json -> json.optJSONArray("data").objects().map(EveChatMessage::fromJson) }
    }

    /** Idempotent: the server deduplicates on [clientMessageId], so the outbox may retry freely. */
    suspend fun send(token: String, conversationId: String, content: String, clientMessageId: String): ApiResult<SendResult> {
        val body = JSONObject()
            .put("content", content)
            .put("content_type", "text")
            .put("client_message_id", clientMessageId)
        return call("POST", "/api/v1/conversations/${enc(conversationId)}/messages", token, body).parse { json ->
            SendResult(EveChatMessage.fromJson(json.getJSONObject("message")), json.optBoolean("duplicate", false))
        }
    }

    suspend fun claim(token: String, id: String) = action(token, id, "claim", null)
    suspend fun resolve(token: String, id: String) = action(token, id, "resolve", null)
    suspend fun reopen(token: String, id: String) = action(token, id, "reopen", null)

    suspend fun markRead(token: String, id: String, upToMessageId: String?) =
        action(token, id, "read", upToMessageId?.let { JSONObject().put("up_to_message_id", it) })

    private suspend fun action(token: String, id: String, verb: String, body: JSONObject?): ApiResult<Unit> =
        call("POST", "/api/v1/conversations/${enc(id)}/$verb", token, body ?: JSONObject()).map { }

    private suspend fun call(
        method: String,
        path: String,
        token: String?,
        body: JSONObject? = null,
        server: String = baseUrl()
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString().take(12)
        val logPath = path.substringBefore('?')
        val started = System.nanoTime()
        if (server.isBlank()) return@withContext ApiResult.Err(ApiError(ApiError.Kind.CLIENT, serverMessage = "سرور تنظیم نشده است"))
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Request-ID", requestId)
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) } }
            val status = conn.responseCode
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (status in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                EveChatLog.d("http", "$method $logPath", "status" to status, "ms" to elapsed, "req" to requestId)
                ApiResult.Ok(text)
            } else {
                val errorBody = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                val error = ApiError.fromStatus(status, ApiError.parseServerMessage(errorBody))
                EveChatLog.w(
                    "http", "$method $logPath failed", null,
                    "status" to status, "ms" to elapsed, "req" to requestId, "kind" to error.kind
                )
                ApiResult.Err(error)
            }
        } catch (e: InterruptedIOException) {
            EveChatLog.w("http", "$method $logPath timeout", e, "req" to requestId)
            ApiResult.Err(ApiError(ApiError.Kind.TIMEOUT))
        } catch (e: IOException) {
            EveChatLog.w("http", "$method $logPath network error", null, "req" to requestId, "error" to e.javaClass.simpleName)
            ApiResult.Err(ApiError(ApiError.Kind.NETWORK))
        } catch (e: IllegalArgumentException) {
            ApiResult.Err(ApiError(ApiError.Kind.CLIENT, serverMessage = "آدرس سرور معتبر نیست"))
        } finally {
            conn?.disconnect()
        }
    }

    private inline fun <T> ApiResult<String>.parse(block: (JSONObject) -> T): ApiResult<T> = when (this) {
        is ApiResult.Err -> this
        is ApiResult.Ok -> try {
            ApiResult.Ok(block(JSONObject(value)))
        } catch (e: Exception) {
            EveChatLog.e("http", "unexpected response shape", e)
            ApiResult.Err(ApiError(ApiError.Kind.PARSE))
        }
    }

    private fun tokens(json: JSONObject) = TokenPair(json.getString("access_token"), json.getString("refresh_token"))

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
