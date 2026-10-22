package com.autonomousone.messages.evechat

import com.autonomousone.messages.evechat.model.EveChatAgent
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatTicket
import com.autonomousone.messages.evechat.model.PagedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object EveChatApiClient {

    data class TokenPair(val accessToken: String, val refreshToken: String)

    data class MeResponse(val agent: EveChatAgent, val projectId: String)

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String, val httpStatus: Int? = null) : Result<Nothing>()
    }

    private fun base(serverUrl: String) = serverUrl.trimEnd('/')

    private fun HttpURLConnection.configure(token: String? = null) {
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Content-Type", "application/json")
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    private fun HttpURLConnection.readBody(): String =
        inputStream.bufferedReader().use { it.readText() }

    private fun HttpURLConnection.readError(): String = try {
        errorStream?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: Exception) { "" }

    private fun errorMsg(errorBody: String, fallback: String): String = try {
        JSONObject(errorBody).optString("error", fallback)
    } catch (_: Exception) { fallback }

    suspend fun login(serverUrl: String, email: String, password: String): Result<TokenPair> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("${base(serverUrl)}/api/v1/auth/login")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.configure()
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val status = conn.responseCode
                if (status in 200..299) {
                    val json = JSONObject(conn.readBody())
                    Result.Success(
                        TokenPair(json.getString("access_token"), json.getString("refresh_token"))
                    )
                } else {
                    Result.Error(errorMsg(conn.readError(), "Login failed"), status)
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Connection failed")
            }
        }

    suspend fun refreshToken(serverUrl: String, refreshToken: String): Result<TokenPair> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("${base(serverUrl)}/api/v1/auth/refresh")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.configure()
                conn.doOutput = true
                val body = JSONObject().apply { put("refresh_token", refreshToken) }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val status = conn.responseCode
                if (status in 200..299) {
                    val json = JSONObject(conn.readBody())
                    Result.Success(
                        TokenPair(json.getString("access_token"), json.getString("refresh_token"))
                    )
                } else {
                    Result.Error("Token refresh failed", status)
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Connection failed")
            }
        }

    suspend fun getMe(serverUrl: String, token: String): Result<MeResponse> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("${base(serverUrl)}/api/v1/me")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.configure(token)
                val status = conn.responseCode
                if (status in 200..299) {
                    val json = JSONObject(conn.readBody())
                    val agent = EveChatAgent.fromJson(json.getJSONObject("agent"))
                    val projectId = json.optJSONObject("project")?.optString("id", "") ?: ""
                    Result.Success(MeResponse(agent, projectId))
                } else {
                    Result.Error("Failed to get profile", status)
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Connection failed")
            }
        }

    suspend fun listConversations(
        serverUrl: String,
        token: String,
        status: String? = null,
        agentId: String? = null,
        limit: Int = 25,
        offset: Int = 0
    ): Result<PagedList<EveChatConversation>> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf("limit=$limit", "offset=$offset")
            status?.let { params.add("status=$it") }
            agentId?.let { params.add("agent=$it") }
            val query = params.joinToString("&")
            val conn = URL("${base(serverUrl)}/api/v1/conversations?$query")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.configure(token)
            val code = conn.responseCode
            if (code in 200..299) {
                val json = JSONObject(conn.readBody())
                val arr = json.getJSONArray("data")
                val convs = (0 until arr.length()).map {
                    EveChatConversation.fromJson(arr.getJSONObject(it))
                }
                Result.Success(
                    PagedList(convs, json.optInt("total", convs.size), limit, offset)
                )
            } else {
                Result.Error("Failed to list conversations", code)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }

    data class ConversationDetail(
        val conversation: EveChatConversation,
        val messages: List<EveChatMessage>,
        val ticket: EveChatTicket?
    )

    suspend fun getConversation(
        serverUrl: String,
        token: String,
        conversationId: String
    ): Result<ConversationDetail> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${base(serverUrl)}/api/v1/conversations/$conversationId")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.configure(token)
            val code = conn.responseCode
            if (code in 200..299) {
                val json = JSONObject(conn.readBody())
                val conv = EveChatConversation.fromJson(json.getJSONObject("conversation"))
                val msgsArr = json.getJSONArray("messages")
                val msgs = (0 until msgsArr.length()).map {
                    EveChatMessage.fromJson(msgsArr.getJSONObject(it))
                }
                val ticket = json.optJSONObject("ticket")?.let { EveChatTicket.fromJson(it) }
                Result.Success(ConversationDetail(conv, msgs, ticket))
            } else {
                Result.Error("Failed to get conversation", code)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun listMessages(
        serverUrl: String,
        token: String,
        conversationId: String,
        limit: Int = 50,
        since: Long = 0
    ): Result<List<EveChatMessage>> = withContext(Dispatchers.IO) {
        try {
            val params = "limit=$limit" + if (since > 0) "&since=$since" else ""
            val conn = URL("${base(serverUrl)}/api/v1/conversations/$conversationId/messages?$params")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.configure(token)
            val code = conn.responseCode
            if (code in 200..299) {
                val json = JSONObject(conn.readBody())
                val arr = json.getJSONArray("data")
                Result.Success(
                    (0 until arr.length()).map { EveChatMessage.fromJson(arr.getJSONObject(it)) }
                )
            } else {
                Result.Error("Failed to list messages", code)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun sendMessage(
        serverUrl: String,
        token: String,
        conversationId: String,
        content: String,
        clientMessageId: String? = null,
        isInternal: Boolean = false
    ): Result<EveChatMessage> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${base(serverUrl)}/api/v1/conversations/$conversationId/messages")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.configure(token)
            conn.doOutput = true
            val body = JSONObject().apply {
                put("content", content)
                put("content_type", "text")
                clientMessageId?.let { put("client_message_id", it) }
                if (isInternal) put("is_internal", true)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code in 200..299) {
                Result.Success(EveChatMessage.fromJson(JSONObject(conn.readBody())))
            } else {
                Result.Error(errorMsg(conn.readError(), "Failed to send"), code)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun claimConversation(
        serverUrl: String, token: String, conversationId: String
    ): Result<Unit> = postAction(serverUrl, token, conversationId, "claim")

    suspend fun assignConversation(
        serverUrl: String, token: String, conversationId: String, agentId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${base(serverUrl)}/api/v1/conversations/$conversationId/assign")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.configure(token)
            conn.doOutput = true
            val body = JSONObject().apply { put("agent_id", agentId) }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code in 200..299) Result.Success(Unit)
            else Result.Error(errorMsg(conn.readError(), "Assign failed"), code)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun resolveConversation(
        serverUrl: String, token: String, conversationId: String
    ): Result<Unit> = postAction(serverUrl, token, conversationId, "resolve")

    suspend fun reopenConversation(
        serverUrl: String, token: String, conversationId: String
    ): Result<Unit> = postAction(serverUrl, token, conversationId, "reopen")

    suspend fun markRead(
        serverUrl: String, token: String, conversationId: String
    ): Result<Unit> = postAction(serverUrl, token, conversationId, "read")

    private suspend fun postAction(
        serverUrl: String, token: String, conversationId: String, action: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${base(serverUrl)}/api/v1/conversations/$conversationId/$action")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.configure(token)
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write("{}") }
            val code = conn.responseCode
            if (code in 200..299) Result.Success(Unit)
            else Result.Error(errorMsg(conn.readError(), "$action failed"), code)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection failed")
        }
    }
}
