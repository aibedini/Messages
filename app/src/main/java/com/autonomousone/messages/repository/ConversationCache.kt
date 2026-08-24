package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.autonomousone.messages.model.Sms
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent conversation cache — the "database as single source of truth"
 * layer Google Messages-style apps rely on.
 *
 * Why: the SMS ContentProvider has no fast "list my threads" query that works
 * instantly on every device; a full scan can take seconds on big inboxes.
 * Instead we snapshot the thread list to disk after every successful load and
 * hydrate from it at app start, so the UI renders INSTANTLY (no skeleton,
 * no "syncing" flash). The provider scan then runs silently behind it and
 * swaps in fresh data when it differs.
 *
 * Storage shape: compact JSON array of the fields the list actually renders.
 */
class ConversationCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "conversation_cache"
        private const val KEY_THREADS = "threads_json"
        private const val KEY_SAVED_AT = "saved_at"

        /** Cache older than this is considered stale (still shown, but marked). */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        @Volatile
        private var instance: ConversationCache? = null

        fun get(context: Context): ConversationCache =
            instance ?: synchronized(this) {
                instance ?: ConversationCache(context.applicationContext).also { instance = it }
            }
    }

    data class Snapshot(val threads: List<Sms>, val savedAt: Long)

    fun load(): Snapshot {
        return try {
            val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
            val arr = JSONArray(prefs.getString(KEY_THREADS, "[]") ?: "[]")
            val threads = ArrayList<Sms>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                threads.add(
                    Sms(
                        id = o.getLong("id"),
                        threadId = o.getLong("threadId"),
                        sender = o.getString("sender"),
                        message = o.optString("message", ""),
                        date = o.getLong("date"),
                        unread = o.optBoolean("unread", false),
                        type = o.optInt("type", 1)
                    )
                )
            }
            Snapshot(threads, savedAt)
        } catch (e: Exception) {
            Log.w("ConvCache", "load failed", e)
            Snapshot(emptyList(), 0L)
        }
    }

    /** Persists [threads] (capped) with the current timestamp. */
    fun save(threads: List<Sms>) {
        try {
            val arr = JSONArray()
            threads.take(500).forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("threadId", s.threadId)
                        .put("sender", s.sender)
                        .put("message", s.message.take(120))
                        .put("date", s.date)
                        .put("unread", s.unread)
                        .put("type", s.type)
                )
            }
            prefs.edit()
                .putString(KEY_THREADS, arr.toString())
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w("ConvCache", "save failed", e)
        }
    }

    fun lastSavedAt(): Long = prefs.getLong(KEY_SAVED_AT, 0L)
}
