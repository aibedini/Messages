package com.autonomousone.messages.data

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 *   → [Scope] readExactMessage("sms", 348201) → mutate(Upsert)
 *   → O(1)
 *
 * Fallback:
 *   URI = content://sms (no id) → reconcile(FullSync)
 *   → bounded window sync
 *
 * IMPORTANT: [route] is invoked from the ContentObserver, which fires on the
 * MAIN looper. It must never block there — every provider read is offloaded to
 * [scope] (Dispatchers.IO) and the mutation is then queued to the coordinator,
 * which processes it on its own background loop.
 */
object ChangeRouter {

    private const val TAG = "CHANGE_ROUTER"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Parse the observer URI and dispatch the cheapest possible mutation.
     * Never does provider I/O on the calling (main) thread.
     */
    fun route(context: Context, uri: Uri?) {
        val coordinator = TelephonySyncCoordinator.get(context)

        if (uri == null) {
            // Unknown change type → bounded reconcile.
            coordinator.reconcile(ReconcileRequest.FullSync)
            return
        }

        val id = extractRowIdFromPath(uri.path)
        if (id != null && id > 0L) {
            // The authority identifies the TABLE (content://mms/… vs
            // content://sms/…). Matching on the path substring misroutes
            // sms-thread URIs (content://sms/thread/…) and any OEM
            // "mms-sms"-prefixed SMS URIs into the MMS reader.
            val source = when {
                uri.authority?.startsWith("mms") == true -> MessageEntity.SOURCE_MMS
                else -> MessageEntity.SOURCE_SMS
            }
            // Targeted mutation: read the exact row off the main thread.
            scope.launch {
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
            }
        } else {
            // URI without extractable ID → bounded reconcile.
            coordinator.reconcile(ReconcileRequest.FullSync)
        }
    }

    /**
     * Try to extract a numeric row ID from a content URI path.
     * content://sms/12345 → path "//sms/12345" → 12345
     * content://sms → path "//sms" → null
     */
    internal fun extractRowIdFromPath(path: String?): Long? {
        if (path.isNullOrBlank()) return null
        val lastSegment = path.substringAfterLast('/')
        if (lastSegment.isBlank() || !lastSegment.all { it.isDigit() }) return null
        return lastSegment.toLongOrNull()
    }
}
