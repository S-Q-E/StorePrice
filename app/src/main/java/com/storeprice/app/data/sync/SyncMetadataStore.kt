package com.storeprice.app.data.sync

import android.content.Context

/**
 * Device-local cursor for incremental catalog synchronization.
 *
 * [cursorMillis] is the newest server `updated_at` value applied to Room. The
 * next incremental request asks for rows with `updated_at >= cursorMillis`; the
 * `>=` is intentional because the server stores microseconds while this cursor
 * keeps milliseconds. Re-fetching a row at the boundary is harmless because the
 * cache upsert is idempotent.
 *
 * [lastSyncedAtMillis] is the local time of the last fully confirmed sync and is
 * shown in the UI even when the network is unavailable.
 */
internal interface SyncMetadataStore {
    var cursorMillis: Long?
    var lastSyncedAtMillis: Long?
}

/** Persists sync metadata in private preferences, separate from Room user data. */
internal class SharedPreferencesSyncMetadataStore(
    context: Context,
) : SyncMetadataStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var cursorMillis: Long?
        get() = preferences.getLong(KEY_CURSOR, 0L).takeIf { it > 0L }
        set(value) {
            val editor = preferences.edit()
            if (value == null || value <= 0L) {
                editor.remove(KEY_CURSOR)
            } else {
                editor.putLong(KEY_CURSOR, value)
            }
            editor.apply()
        }

    override var lastSyncedAtMillis: Long?
        get() = preferences.getLong(KEY_LAST_SYNCED_AT, 0L).takeIf { it > 0L }
        set(value) {
            val editor = preferences.edit()
            if (value == null || value <= 0L) {
                editor.remove(KEY_LAST_SYNCED_AT)
            } else {
                editor.putLong(KEY_LAST_SYNCED_AT, value)
            }
            editor.apply()
        }

    internal companion object {
        const val FILE_NAME = "storeprice_sync_state"

        private const val KEY_CURSOR = "cursor_millis"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at_millis"
    }
}
