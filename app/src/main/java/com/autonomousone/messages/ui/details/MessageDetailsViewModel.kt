package com.autonomousone.messages.ui.details

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.messaging.SimInfo
import com.autonomousone.messages.messaging.SimManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing state for Message Details, keyed by the COMPOSITE message identity.
 *
 * ## Why the constructor takes source + providerId
 *
 * An SMS `_id` and an MMS `_id` are independent sequences, so `100` names two different messages. This
 * screen shows the send evidence of exactly one of them, and opening the wrong one would display a
 * confident, wrong answer — worse than showing nothing.
 *
 * ## What it does NOT do
 *
 * It does not touch the provider. Everything shown is already-mirrored durable state, read by keyed
 * queries. No conversation window is loaded, no thread is scanned, and the body is passed in from the
 * screen that already had it rather than re-read.
 */
class MessageDetailsViewModel(
    application: Application,
    private val threadId: Long,
    private val source: String,
    private val providerId: Long,
    /** The body and address the conversation already holds, so this screen reads no provider data. */
    private val body: String,
    private val recipient: String,
    private val sender: String
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        MessageDetailsUiState(
            loaded = false, missing = false, isOutgoing = false,
            body = body, recipient = recipient, sender = sender, createdAt = 0L,
            state = null, headline = "", steps = emptyList(), canRetry = false, duplicateRisk = false,
            sentWithLabel = null, sentSlotIndex = null, simEvidenceConflict = false, partCount = null,
            transportState = null, sentResultCode = null, radioErrorCode = null, failureCode = null,
            deliveryResultCode = null, deliveryEvidence = null, deliveryTpStatus = null,
            deliveryCallbackAt = null, submittedAt = null, dateSent = null,
            providerId = providerId, threadId = threadId, source = source,
            parts = emptyList(), appVersion = BuildConfig.APP_VERSION
        )
    )
    val state: StateFlow<MessageDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Read the persisted evidence and build the state.
     *
     * Runs on IO: the queries touch Room, and nothing here may block the composition.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        val db = MessagesDatabase.get(getApplication())
        val evidence = runCatching { db.messageDao().technicalEvidenceOf(source, providerId) }.getOrNull()
        val segments = runCatching { db.sendSegmentDao().segmentsForRow(providerId) }
            .getOrDefault(emptyList())

        // Resolve a subscription to a display label from the CURRENT inventory. This is used only to
        // LABEL a send-time subscription id the ledger already recorded — never to decide which SIM
        // sent the message. If the line is gone the label falls back to the id, because the id is
        // still evidence and dropping it would hide what the phone actually recorded.
        val labeler: (Int) -> String? = { subId ->
            val sims: List<SimInfo> = runCatching {
                SimManager(getApplication()).getActiveSims()
            }.getOrDefault(emptyList())
            val match = sims.firstOrNull { it.subscriptionId == subId }
            when {
                match != null -> "SIM ${match.slotIndex + 1}" +
                    match.carrierName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                // Not in the current inventory: name the recorded id rather than a slot, because the
                // slot is exactly the thing that can no longer be trusted after a card change.
                else -> "Subscription $subId (no longer active)"
            }
        }

        _state.value = MessageDetailsUiState.of(
            evidence = evidence,
            segments = segments,
            body = body,
            recipient = recipient,
            sender = sender,
            appVersion = BuildConfig.APP_VERSION,
            simLabel = labeler
        )
    }

    /** Build the copyable diagnostics for THIS message from the same persisted rows the screen shows. */
    suspend fun technicalText(): String? = withContext(Dispatchers.IO) {
        val db = MessagesDatabase.get(getApplication())
        val e = runCatching { db.messageDao().technicalEvidenceOf(source, providerId) }.getOrNull()
            ?: return@withContext null
        val parts = runCatching { db.sendSegmentDao().segmentsForRow(providerId).size }
            .getOrNull()?.takeIf { it > 0 }
        com.autonomousone.messages.sms.TechnicalDetailsText.build(
            uiState = _state.value.state?.name,
            evidence = com.autonomousone.messages.sms.TechnicalDetailsText.Evidence(
                source = e.source,
                providerId = e.providerId,
                threadId = e.threadId,
                isOutgoing = e.type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT,
                sentAt = e.date,
                dateSent = e.dateSent,
                transportState = e.sendTransportState,
                sendFailureCode = e.sendFailureCode,
                sendResultCode = e.sendResultCode,
                radioErrorCode = e.sendRadioErrorCode,
                sendStateUpdatedAt = e.sendStateUpdatedAt,
                deliveryEvidence = e.deliveryEvidence,
                deliveryTpStatus = e.deliveryTpStatus,
                deliveryResultCode = e.deliveryResultCode,
                deliveryCallbackAt = e.deliveryCallbackAt
            ),
            appVersion = BuildConfig.APP_VERSION,
            // The send-time label the screen shows, so the clipboard and the screen agree.
            simLabel = _state.value.sentWithLabel,
            numberTail = com.autonomousone.messages.sms.TechnicalDetailsText.numberTail(recipient),
            partCount = parts
        )
    }

    companion object {
        /** The source vocabulary, so a caller cannot pass an arbitrary string. */
        fun sourceFor(isMms: Boolean): String =
            if (isMms) MessageEntity.SOURCE_MMS else MessageEntity.SOURCE_SMS
    }
}
