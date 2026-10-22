package com.autonomousone.messages.evechat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object EveChatApiClient {

    data class TokenPair(val accessToken: String, val refreshToken: String)

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String, val httpStatus: Int? = null) : Result<Nothing>()
    }

    suspend fun login(serverUrl: String, email: String, password: String): Result<TokenPair> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${serverUrl.trimEnd('/')}/api/v1/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

                val status = conn.responseCode
                if (status in 200..299) {
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)
                    Result.Success(
                        TokenPair(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.getString("refresh_token")
                        )
                    )
                } else {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (_: Exception) { "" }
                    val msg = try {
                        JSONObject(errorBody).optString("error", "Login failed")
                    } catch (_: Exception) {
                        "HTTP $status"
                    }
                    Result.Error(msg, status)
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Connection failed")
            }
        }

    suspend fun refreshToken(serverUrl: String, refreshToken: String): Result<TokenPair> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${serverUrl.trimEnd('/')}/api/v1/auth/refresh")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("refresh_token", refreshToken)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

                val status = conn.responseCode
                if (status in 200..299) {
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)
                    Result.Success(
                        TokenPair(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.getString("refresh_token")
                        )
                    )
                } else {
                    Result.Error("Token refresh failed", status)
                }
            } catch (e: Exception) {
                Result.Error(e.message ?: "Connection failed")
            }
        }
}
