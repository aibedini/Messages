package com.autonomousone.messages.messaging

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A pre-defined reply template, expandable in the chat input via its shortcut (e.g. "/c1"). */
data class QuickReply(val shortcut: String, val text: String)

/**
 * WhatsApp-Business-style quick replies. Users manage them in
 * Settings → Quick replies; typing "/" in a conversation shows matching
 * suggestions and tapping one fills the input with the template text.
 */
class QuickRepliesPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "quick_replies_prefs"
        private const val KEY_ITEMS = "items_json"
        private const val KEY_SEEDED = "defaults_seeded"

        /** Sensible starter set; users can edit/delete freely. */
        val DEFAULTS = listOf(
            QuickReply("/c1", "Done ✅"),
            QuickReply("/c2", "Working on it ⏳"),
            QuickReply("/ty", "Thank you! 🙏"),
            QuickReply("/soon", "I'll get back to you soon."),
            QuickReply("/later", "Talk later 👋")
        )
    }

    fun getAll(): List<QuickReply> {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            save(DEFAULTS)
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return DEFAULTS
        }
        return parse(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
    }

    fun save(items: List<QuickReply>) {
        val arr = JSONArray()
        items.forEach { q ->
            arr.put(JSONObject().put("shortcut", q.shortcut).put("text", q.text))
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun upsert(reply: QuickReply) {
        val cleaned = reply.copy(
            shortcut = reply.shortcut.trim().let { if (it.startsWith("/")) it else "/$it" },
            text = reply.text.trim()
        )
        if (cleaned.shortcut.isBlank() || cleaned.text.isBlank()) return
        val items = getAll().filterNot { it.shortcut.equals(cleaned.shortcut, ignoreCase = true) }
        save(items + cleaned)
    }

    fun remove(shortcut: String) {
        save(getAll().filterNot { it.shortcut.equals(shortcut, ignoreCase = true) })
    }

    /** Longest-shortcut-first: "/c10" must win over "/c1". */
    fun match(query: String): List<QuickReply> =
        getAll()
            .filter { it.shortcut.startsWith(query, ignoreCase = true) }
            .sortedByDescending { it.shortcut.length }

    /** Exact shortcut (case-insensitive) → template text, or null. */
    fun exact(shortcut: String): QuickReply? =
        getAll().firstOrNull { it.shortcut.equals(shortcut, ignoreCase = true) }

    private fun parse(json: String): List<QuickReply> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            QuickReply(o.optString("shortcut"), o.optString("text"))
        }.filter { it.shortcut.isNotBlank() && it.text.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }
}
