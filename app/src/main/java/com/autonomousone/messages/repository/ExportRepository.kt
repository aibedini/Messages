package com.autonomousone.messages.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.autonomousone.messages.model.Sms
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports all SMS conversations into a single JSON document and exposes it as
 * a shareable content:// URI (via FileProvider) so the user can save or send
 * the archive anywhere — no storage permission required.
 *
 * Shape:
 * {
 *   "app": "com.autonomousone.messages",
 *   "exportedAt": 1755900000000,
 *   "conversationCount": 12,
 *   "messageCount": 340,
 *   "conversations": [
 *     { "address": "+98912...", "threadId": 3, "messages": [
 *         { "type": "received|sent", "sender": "...", "message": "...",
 *           "date": 0, "dateSent": 0, "status": -1 } ] }
 *   ]
 * }
 */
class ExportRepository(private val context: Context) {

    /** Builds the JSON archive and returns a shareable URI, or null on failure. */
    fun exportAllChats(): Uri? {
        return try {
            val repository = SmsRepository(context)
            val all = repository.getSmsWithFilters(
                limit = null, offset = null, type = null,
                phone = null, fromDate = null, toDate = null
            )

            val grouped = all.groupBy { if (it.threadId != 0L) "t${it.threadId}" else "a${it.sender}" }

            val conversations = JSONArray()
            grouped.values.forEach { messages ->
                val sorted = messages.sortedBy { it.date }
                val arr = JSONArray()
                sorted.forEach { sms -> arr.put(sms.toJson()) }
                conversations.put(
                    JSONObject()
                        .put("address", sorted.firstOrNull()?.sender ?: "")
                        .put("threadId", sorted.firstOrNull()?.threadId ?: 0L)
                        .put("messages", arr)
                )
            }

            val doc = JSONObject()
                .put("app", context.packageName)
                .put("exportedAt", System.currentTimeMillis())
                .put("conversationCount", grouped.size)
                .put("messageCount", all.size)
                .put("conversations", conversations)

            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "sms_export_${System.currentTimeMillis()}.json")
            file.writeText(doc.toString(2), Charsets.UTF_8)

            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            android.util.Log.e("EXPORT", "exportAllChats failed", e)
            null
        }
    }

    private fun Sms.toJson(): JSONObject = JSONObject()
        .put("type", if (type == 1) "received" else "sent")
        .put("sender", sender)
        .put("message", message)
        .put("date", date)
        .put("dateSent", dateSent)
        .put("status", status)
}
