package com.autonomousone.messages.data

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.repository.SmsRepository

/**
 * Routes ContentObserver notifications into targeted mutations when the
 * URI carries an extractable ID, or falls back to bounded reconciliation.
 *
 * Android guarantees: when a single row changes, the observer MAY receive
 * content://sms/12345 (with the row id). But this is NOT guaranteed on all
 * OEM builds. So we treat extractable URIs as an optimization, not a contract.
 *
 * Hot path:
 *   URI = content://sms/348201 → extract id=348201
 *   → readExactMessage("sms", 348201) → mutate(Upsert)
 *   → O(1)
 *
 * Fallback:
 *   URI = content://sms (no id) → reconcile(FullSync)
 *   → bounded window sync
 */
object ChangeRouter {

    private const val TAG = "CHANGE_ROUTER"

    /**
     * Parse the observer URI and dispatch the cheapest possible mutation.
     */
    fun route(context: Context, uri: Uri?) {
        val coordinator = TelephonySyncCoordinator.get(context)

        if (uri == null) {
            // Unknown change type → bounded reconcile.
            coordinator.reconcile(ReconcileRequest.FullSync)
            return
        }

        val id = extractRowId(uri)
        if (id != null && id > 0L) {
            val source = when {
                uri.path?.contains("mms") == true -> MessageEntity.SOURCE_MMS
                else -> MessageEntity.SOURCE_SMS
            }
            // Try targeted mutation: read the exact row.
            val repo = SmsRepository(context)
            val fresh = when (source) {
                MessageEntity.SOURCE_SMS -> repo.querySmsRaw(
                    selection = "${Telephony.Sms._ID} = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = "${Telephony.Sms.DATE} DESC",
                    limit = 1
                ).firstOrNull()
                MessageEntity.SOURCE_MMS -> repo.queryMmsRaw(
                    selection = "${Telephony.Mms._ID} = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = "${Telephony.Mms.DATE} DESC",
                    limit = 1
                ).firstOrNull()
                else -> null
            }

            if (fresh != null) {
                coordinator.mutate(MessageMutation.Upsert(source = source, message = fresh))
            } else {
                // Row was deleted externally.
                coordinator.mutate(MessageMutation.Delete(source, id))
            }
        } else {
            // URI without extractable ID → bounded reconcile.
            coordinator.reconcile(ReconcileRequest.FullSync)
        }
    }

    /**
     * Try to extract a numeric row ID from a content URI.
     * content://sms/12345 → 12345
     * content://sms → null
     */
    private fun extractRowId(uri: Uri): Long? {
        val lastSegment = uri.lastPathSegment ?: return null
        return lastSegment.toLongOrNull()
    }
}
