package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences
import com.autonomousone.messages.utils.DigitNormalizer

/**
 * Per-contact SIM routing rules: "when texting this number, always use SIM N".
 *
 * Storage: JSON map of normalized-phone → subscriptionId in SharedPreferences.
 * The chat SIM switcher consults this before falling back to the global
 * default, so each conversation can pin its own line.
 */
class SimRulesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "sim_rules_prefs"
        private const val KEY_RULES = "rules_json"

        @Volatile
        private var instance: SimRulesRepository? = null

        fun get(context: Context): SimRulesRepository =
            instance ?: synchronized(this) {
                instance ?: SimRulesRepository(context.applicationContext).also { instance = it }
            }
    }

    private fun load(): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            val obj = org.json.JSONObject(prefs.getString(KEY_RULES, "{}") ?: "{}")
            obj.keys().forEach { k -> map[k] = obj.getInt(k) }
        } catch (_: Exception) { /* corrupted → start empty */ }
        return map
    }

    private fun save(map: Map<String, Int>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_RULES, obj.toString()).apply()
    }

    private fun keyFor(phoneRaw: String): String =
        ContactRepository.normalizePhone(DigitNormalizer.toAsciiDigits(phoneRaw))

    /** Returns the pinned subscriptionId for [phone], or null when no rule exists. */
    fun ruleFor(phoneRaw: String): Int? {
        val key = keyFor(phoneRaw)
        if (key.isBlank()) return null
        val rules = load()
        // Exact match first, then suffix match (country-code variants).
        rules[key]?.let { return it }
        rules.entries.firstOrNull { key.endsWith(it.key) || it.key.endsWith(key) }?.let { return it.value }
        return null
    }

    /** Sets or removes (subscriptionId = null) the rule for [phone]. */
    fun setRule(phoneRaw: String, subscriptionId: Int?) {
        val key = keyFor(phoneRaw)
        if (key.isBlank()) return
        val map = load()
        if (subscriptionId == null) map.remove(key) else map[key] = subscriptionId
        save(map)
    }
}
