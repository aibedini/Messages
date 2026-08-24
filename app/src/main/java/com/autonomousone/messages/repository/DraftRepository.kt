package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-conversation message drafts.
 *
 * A draft is the text typed into a conversation that was NOT sent when the
 * user left the screen. Drafts are keyed by a stable conversation key:
 *  - "t<threadId>" for existing threads,
 *  - "a<normalized-phone>" for brand-new (not-yet-persisted) chats.
 *
 * Storage: one JSON object in SharedPreferences — drafts are tiny and there
 * can't be more than a few dozen; no Room needed. The Home list reads
 * [draftFor] to render the italic "Draft: …" line under the contact name.
 */
class DraftRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "messages_drafts"
        private const val KEY_DRAFTS = "drafts_json"

        /** Stable key for a conversation. */
        fun keyFor(threadId: Long, phone: String): String =
            if (threadId > 0) "t$threadId"
            else "a${ContactRepository.normalizePhone(
                com.autonomousone.messages.utils.DigitNormalizer.toAsciiDigits(phone)
            )}"
    }

    private fun load(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(prefs.getString(KEY_DRAFTS, "{}") ?: "{}")
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
        } catch (_: Exception) { /* corrupted → start empty */ }
        return map
    }

    private fun save(map: Map<String, String>) {
        prefs.edit().putString(KEY_DRAFTS, JSONObject(map).toString()).apply()
    }

    fun get(key: String): String = load()[key].orEmpty()

    /** All non-empty drafts, key → text. */
    fun all(): Map<String, String> = load()

    /** Stores or clears (blank text) the draft for [key]. */
    fun set(key: String, text: String) {
        val map = load()
        if (text.isBlank()) map.remove(key) else map[key] = text
        save(map)
    }
}
