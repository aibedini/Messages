package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences
import android.provider.BlockedNumberContract

/**
 * Blocked-number store.
 *
 * Two layers:
 *  1. System block list ([BlockedNumberContract]) — enforced by the platform
 *     (no ring, no notification) when this app holds the blocking role.
 *  2. A local mirror in SharedPreferences — always readable by us so the
 *     conversation list can hide blocked threads and the receiver can drop
 *     notifications, even where the system contract is not writable.
 *
 * Writes go to both; reads use the local mirror for speed and reliability.
 */
class BlocklistRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All blocked numbers, normalized. */
    fun getBlocked(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun isBlocked(address: String): Boolean {
        val norm = normalize(address)
        if (norm.isBlank()) return false
        return getBlocked().any { it == norm || norm.endsWith(it) || it.endsWith(norm) }
    }

    /**
     * Blocks [address]: adds to the local mirror and tries the system
     * contract (best-effort — the platform enforces silently where available).
     */
    fun block(address: String): Boolean {
        val norm = normalize(address)
        if (norm.isBlank()) return false

        // Local mirror first (source of truth for our UI + receiver).
        val current = getBlocked().toMutableSet()
        current.add(norm)
        prefs.edit().putStringSet(KEY_BLOCKED, current).apply()

        // Platform enforcement (best-effort).
        try {
            val values = android.content.ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address)
                put(
                    BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER,
                    address.takeIf { it.startsWith("+") }
                )
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI, values
            )
        } catch (_: Exception) {
            // Contract unavailable on this build — the mirror still works.
        }
        return true
    }

    /** Unblocks [address] from both stores. */
    fun unblock(address: String) {
        val norm = normalize(address)
        val current = getBlocked().toMutableSet()
        current.removeAll { it == norm }
        prefs.edit().putStringSet(KEY_BLOCKED, current).apply()

        try {
            context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?",
                arrayOf(address)
            )
        } catch (_: Exception) {
            // Best-effort as above.
        }
    }

    companion object {
        private const val PREFS_NAME = "messages_blocklist"
        private const val KEY_BLOCKED = "blocked_numbers"

        /**
         * Normalizes a raw address to comparable digits: strips every non-digit
         * and folds Iranian country-code "98" prefixes onto the local "0…" form.
         */
        fun normalize(address: String): String {
            val digits = address.filter { it.isDigit() }
            return if (digits.length > 10 && digits.startsWith("98")) {
                digits.drop(2).let { if (it.startsWith("9")) "0$it" else it }
            } else digits
        }

        /** Static shortcut for broadcast receivers (no instance state needed). */
        fun isBlocked(context: Context, address: String): Boolean =
            BlocklistRepository(context).isBlocked(address)
    }
}
