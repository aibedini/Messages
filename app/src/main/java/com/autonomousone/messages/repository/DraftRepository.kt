package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences
import com.autonomousone.messages.utils.DigitNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-conversation message drafts — a process-wide reactive store.
 *
 * Architecture note (why not "re-read prefs on resume"):
 * The app is single-activity; navigating chat → home does NOT go through
 * Activity.onResume, so any "reload drafts when resumed" scheme never runs.
 * Instead the repository holds one [StateFlow] that both writers (chat screen)
 * and readers (conversation list) observe. Persistence to SharedPreferences is
 * a write-through side effect for cold start only.
 */
class DraftRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _drafts = MutableStateFlow(loadFromDisk())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    companion object {
        private const val PREFS_NAME = "messages_drafts"
        private const val KEY_DRAFTS = "drafts_json"

        @Volatile
        private var instance: DraftRepository? = null

        /** Process-wide singleton — every screen sees the same live map. */
        fun get(context: Context): DraftRepository =
            instance ?: synchronized(this) {
                instance ?: DraftRepository(context.applicationContext).also { instance = it }
            }

        /**
         * Stable conversation key:
         *  - "t<threadId>" once a thread exists,
         *  - "a<normalized-ascii-phone>" for chats that were never persisted.
         */
        fun keyFor(threadId: Long, phoneRaw: String): String {
            if (threadId > 0) return "t$threadId"
            val ascii = DigitNormalizer.toAsciiDigits(phoneRaw)
            return "a${ContactRepository.normalizePhone(ascii)}"
        }
    }

    private fun loadFromDisk(): Map<String, String> {
        return try {
            val obj = org.json.JSONObject(prefs.getString(KEY_DRAFTS, "{}") ?: "{}")
            buildMap {
                obj.keys().forEach { k -> put(k, obj.getString(k)) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(map: Map<String, String>) {
        try {
            val obj = org.json.JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit().putString(KEY_DRAFTS, obj.toString()).apply()
        } catch (_: Exception) { /* best-effort */ }
    }

    /** Current snapshot (non-reactive callers). */
    fun get(key: String): String = _drafts.value[key].orEmpty()

    fun all(): Map<String, String> = _drafts.value

    /** Stores or clears (blank text) the draft for [key] and notifies observers. */
    fun set(key: String, text: String) {
        val next = _drafts.value.toMutableMap()
        if (text.isBlank()) next.remove(key) else next[key] = text
        _drafts.value = next
        persist(next)
    }

    /**
     * Migrates a draft from an address-keyed entry to its threadId-keyed form
     * after the first send creates a real thread (keeps the draft attached).
     */
    fun migrateKey(oldKey: String, newKey: String) {
        val text = _drafts.value[oldKey] ?: return
        set(oldKey, "")
        set(newKey, text)
    }
}
