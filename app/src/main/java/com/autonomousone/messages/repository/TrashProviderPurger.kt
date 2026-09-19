package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.TrashedThreadEntity
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The REAL [TrashRepository.ProviderPurger]: the provider half of a TRASH purge,
 * built on the existing strict provider-write path in [SmsRepository].
 *
 * WHAT IT DOES, AND WHAT IT REFUSES TO DO
 * ---------------------------------------
 *  - It deletes ONLY the canonical range the tombstone stands for
 *    (`TrashProviderRange`: everything at-or-before the cutoff for the cutoff's
 *    source, plus strictly-older rows of the other source). A message that
 *    arrived AFTER the conversation was deleted is never touched — that message
 *    is the reason the conversation is visible again.
 *  - It NEVER runs a whole-thread Telephony rescan. The only reads are the
 *    delete itself and a `LIMIT 1` existence probe over the same indexed range,
 *    used to VERIFY the delete instead of trusting it.
 *  - It returns false on ANY provider failure (no default-SMS-app permission,
 *    binder death, a provider that silently dropped the delete). The caller then
 *    keeps the tombstone and the Room rows, so Trash still shows the
 *    conversation and a later run retries. Nothing is ever reported as destroyed
 *    while the provider still holds it.
 *  - It does NOT remove Room state: [TrashRepository] does that, after this
 *    returns true. Keeping the ordering in one visible place is the point of the
 *    `ProviderPurger` seam.
 *
 * The provider delete makes the system fire a ContentObserver burst for that
 * thread; the resulting repair is idempotent and only re-confirms a range that is
 * already gone (and may enqueue a proven-absence check for local rows), so no
 * self-write note is needed to narrow it.
 */
class TrashProviderPurger(context: Context) : TrashRepository.ProviderPurger {

    private val repository = SmsRepository(context.applicationContext)

    override suspend fun purge(tombstone: TrashedThreadEntity): Boolean = withContext(Dispatchers.IO) {
        if (tombstone.threadId <= 0L) {
            // No addressable provider thread: there is no range to delete, and the
            // Room-side state of a tombstone that cannot describe provider rows is
            // the only thing left to clear.
            DiagnosticLog.event("TRASH", "purge skipped: unaddressable thread=${tombstone.threadId}")
            return@withContext true
        }
        when (val result = repository.deleteThreadSnapshotStrict(tombstone)) {
            is SourceWriteResult.Success -> {
                DiagnosticLog.event(
                    "TRASH",
                    "provider range deleted thread=${tombstone.threadId} rows=${result.updatedRows}"
                )
                true
            }

            SourceWriteResult.NotApplicable -> {
                DiagnosticLog.event("TRASH", "purge not applicable thread=${tombstone.threadId}")
                true
            }

            is SourceWriteResult.Failure -> {
                DiagnosticLog.event(
                    "TRASH",
                    "provider delete FAILED thread=${tombstone.threadId} reason=${result.reason}",
                    result.cause
                )
                false
            }
        }
    }
}
