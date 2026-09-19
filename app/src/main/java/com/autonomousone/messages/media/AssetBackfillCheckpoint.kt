package com.autonomousone.messages.media

import android.content.Context

/**
 * Durable checkpoint for the Media / Links / Files history sweep.
 *
 * SharedPreferences (not Room) deliberately: the sweep cursor is a SINGLE
 * position, it must survive process death and app upgrades, and adding a column
 * or table to the v16 schema for it would be a schema change this feature has no
 * business making. The values are plain primitives, written synchronously with
 * `commit()` so a checkpoint is durable before its batch is considered covered.
 */
internal class AssetBackfillCheckpoint(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cursor(): AssetBackfillCursor = AssetBackfillCursor(
        date = prefs.getLong(KEY_DATE, AssetBackfillCursor.START.date),
        source = prefs.getString(KEY_SOURCE, AssetBackfillCursor.START.source)
            ?: AssetBackfillCursor.START.source,
        providerId = prefs.getLong(KEY_PROVIDER_ID, AssetBackfillCursor.START.providerId)
    )

    fun save(cursor: AssetBackfillCursor) {
        prefs.edit()
            .putLong(KEY_DATE, cursor.date)
            .putString(KEY_SOURCE, cursor.source)
            .putLong(KEY_PROVIDER_ID, cursor.providerId)
            .commit()
    }

    fun isComplete(): Boolean = prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).commit()
    }

    /** Test/repair seam: forget the sweep so a later run covers history again. */
    fun reset() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS_NAME = "media_asset_backfill"
        const val KEY_DATE = "cursor_date"
        const val KEY_SOURCE = "cursor_source"
        const val KEY_PROVIDER_ID = "cursor_provider_id"
        const val KEY_COMPLETE = "complete"
    }
}
