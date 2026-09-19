package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.messaging.ConversationNotificationChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Conversation Info (v3.4.0 FEATURE 5) — the observable state machine, kept free
 * of Android and Room so it can be unit-tested on the JVM.
 *
 * Everything the screen reads/writes goes through the narrow ports declared in
 * `ConversationInfoPorts.kt`, and every one of those delegates to the repository
 * that OWNS the durable column. This feature therefore introduces no second
 * store:
 *
 *  - mute / custom-channel flag / category override → `ConversationPreferenceRepository`
 *  - starred flag                                    → `MessageUserStateRepository`
 *  - block + spam provenance                        → `SpamRepository` (the ONE
 *    blocklist plus the field-scoped spam writers)
 *  - move conversation to Trash                     → `TrashRepository`
 *
 * Threading: the controller never touches Room/ContentResolver itself. Every
 * external call is a `suspend` function executed on [io] inside
 * [runCatchingIo], and the ViewModel drives it from `viewModelScope`. Nothing
 * here can run on the Compose main thread.
 */
class ConversationInfoController(
    private val threadId: Long,
    private val starredStore: StarredStore,
    private val preferences: ConversationPreferences,
    private val participants: ParticipantLookup,
    private val automaticCategories: AutomaticCategoryLookup,
    private val assetCounter: AssetCounter,
    private val blocklist: BlocklistPort,
    private val spam: SpamRepository,
    private val channelGate: NotificationChannelGate,
    private val trashing: ConversationTrashing,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** The durable preference row as the UI needs it (no Room entity leaks out). */
    data class PreferenceSnapshot(
        val mutedUntil: Long = ConversationMute.NOT_MUTED,
        val customNotificationChannel: Boolean = false,
        val categoryOverride: String? = null,
        val spam: Boolean = false
    ) {
        fun toEntity(): ConversationPreferenceEntity = ConversationPreferenceEntity(
            threadId = 0L,
            mutedUntil = mutedUntil,
            customNotificationChannel = customNotificationChannel,
            categoryOverride = categoryOverride,
            spam = spam
        )

        companion object {
            fun of(entity: ConversationPreferenceEntity?): PreferenceSnapshot =
                PreferenceSnapshot(
                    mutedUntil = entity?.mutedUntil ?: ConversationMute.NOT_MUTED,
                    customNotificationChannel = entity?.customNotificationChannel == true,
                    categoryOverride = entity?.categoryOverride,
                    spam = entity?.spam == true
                )
        }
    }

    /** One-shot navigation the screen must perform (never part of the state). */
    sealed interface InfoEvent {
        /** Media / Links / Files browser for this conversation. */
        data object OpenMedia : InfoEvent

        /** In-conversation starred list. */
        data object OpenStarred : InfoEvent

        /**
         * Android's per-channel notification settings. The system channel is the
         * authority for sound/vibration, so choosing Custom always ends here.
         */
        data object OpenNotificationSettings : InfoEvent

        /**
         * The conversation is now in Trash; the screen navigates up. Trash is
         * durable and reversible, so this is not "permanently deleted".
         */
        data object ConversationTrashed : InfoEvent
    }

    /** Everything the screen renders, in one immutable snapshot. */
    data class ConversationInfoUiState(
        val loading: Boolean = true,
        val error: String? = null,
        val participant: ConversationParticipantState = ConversationParticipantState(
            phone = "",
            normalizedPhone = "",
            displayName = "",
            isKnownContact = false
        ),
        val blocked: Boolean = false,
        val automaticCategory: MessageCategory? = null,
        val starredCount: Int? = null,
        val starredKeys: Set<MessageKey> = emptySet(),
        val assetCount: Int? = null,
        val preference: PreferenceSnapshot = PreferenceSnapshot(),
        val now: Long = 0L
    ) {
        /** Per-row derived state, from the ONE pure derivation object. */
        val sections: ConversationInfoLogic.Sections
            get() = ConversationInfoLogic.sections(
                preference = preference.toEntity(),
                now = now,
                starredCount = starredCount,
                blocked = blocked,
                participant = participant,
                assetCount = assetCount
            )

        /** The v3.3.6 participant action the header offers. */
        val contactAction: ParticipantContactAction
            get() = ConversationParticipantActions.primaryContactAction(participant)

        /** Effective category: spam report > override > classifier > UNKNOWN. */
        val effectiveCategory: MessageCategory
            get() = SpamReportPolicy.effectiveCategory(preference.toEntity(), automaticCategory)

        /** Spam & blocking row state. */
        val spamSection: SpamSectionState
            get() = ConversationInfoLogic.spamSection(
                spamReported = preference.spam,
                blocked = blocked,
                canBlock = participant.hasDialableNumber
            )

        fun isStarred(key: MessageKey): Boolean = key in starredKeys
    }

    private val _state = MutableStateFlow(ConversationInfoUiState(now = now()))
    val state: StateFlow<ConversationInfoUiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<InfoEvent?>(null)
    val events: StateFlow<InfoEvent?> = _events.asStateFlow()

    /** Live starred observers for the messages this screen knows about. */
    private var starredWatchers: Map<MessageKey, Job> = emptyMap()

    private class Loaded(
        val participant: ConversationParticipantState,
        val automaticCategory: MessageCategory?,
        val blocked: Boolean,
        val starredKeys: List<MessageKey>
    )

    // ── Reactivity ──────────────────────────────────────────────────────────

    init {
        scope.launch {
            preferences.observe(threadId)
                .catch { error -> setError("preferences", error) }
                .collect { entity ->
                    _state.update {
                        it.copy(preference = PreferenceSnapshot.of(entity), now = now())
                    }
                }
        }
        scope.launch {
            starredStore.observeStarredCountInThread(threadId)
                .catch { error -> setError("starred-count", error) }
                .collect { count -> _state.update { it.copy(starredCount = count) } }
        }
        scope.launch {
            assetCounter.observeCountInThread(threadId)
                // The asset count is a SUBTITLE: a failure here must never put the
                // screen into an error state, so it is dropped deliberately.
                .catch { }
                .collect { count -> _state.update { it.copy(assetCount = count) } }
        }
    }

    // ── Load ────────────────────────────────────────────────────────────────

    /**
     * Loads the non-reactive half of the screen: participant identity, the
     * automatic category, the blocklist answer and the initial starred set.
     *
     * [phone] is the address carried by the route; a blank one still resolves to
     * the unknown-participant state (display name = the address) so the header is
     * never empty.
     */
    fun load(phone: String) {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatchingIo {
                val participant = participants.getParticipantState(phone)
                val automatic = automaticCategories.automaticCategory(threadId)
                val blocked = participant.hasDialableNumber && blocklist.isBlocked(phone)
                val starred = starredStore.starredPageInThread(
                    threadId = threadId,
                    limit = STARRED_WATCH_LIMIT,
                    offset = 0
                )
                Loaded(
                    participant = participant,
                    automaticCategory = automatic,
                    blocked = blocked,
                    starredKeys = starred.map { MessageKey(it.source, it.providerId) }
                )
            }.onSuccess { loaded ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        participant = loaded.participant,
                        automaticCategory = loaded.automaticCategory,
                        blocked = loaded.blocked,
                        starredKeys = loaded.starredKeys.toSet(),
                        now = now()
                    )
                }
                watchStarred(loaded.starredKeys)
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = messageFor(error)) }
            }
        }
    }

    /**
     * Keeps star state live for the messages this screen shows. Bounded on
     * purpose: the info screen never holds one observer per message in a
     * 100K-message conversation.
     */
    private fun watchStarred(keys: List<MessageKey>) {
        starredWatchers.values.forEach { it.cancel() }
        starredWatchers = keys.associateWith { key ->
            scope.launch {
                starredStore.observeStarred(key.source, key.providerId)
                    // A missing row is simply "not starred"; never an error.
                    .catch { }
                    .collect { entity ->
                        val starred = entity?.starred == true
                        _state.update { current ->
                            current.copy(
                                starredKeys = if (starred) {
                                    current.starredKeys + key
                                } else {
                                    current.starredKeys - key
                                }
                            )
                        }
                    }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /**
     * Star / unstar one message. Identity is the COMPOSITE [MessageKey] — never a
     * raw id, because SMS 100 and MMS 100 are different messages.
     */
    fun toggleStar(key: MessageKey, starred: Boolean) = act("star") {
        starredStore.setStarred(key, threadId, starred, now())
    }

    fun setMuted(preset: ConversationMute.Preset) = act("mute") {
        preferences.setMutedUntil(threadId, ConversationMute.mutedUntil(now(), preset), now())
    }

    fun unmute() = act("unmute") {
        preferences.unmute(threadId, now())
    }

    /**
     * Custom notifications.
     *
     * Android O+ channels are the authority for sound/vibration, so this either
     * creates the per-conversation channel and then opens the SYSTEM sheet, or —
     * when the channel already exists — simply re-opens the sheet. There is never
     * an app-side sound setting to fake.
     */
    fun chooseCustomNotifications(displayName: String) {
        scope.launch {
            val alreadyCustom = _state.value.preference.customNotificationChannel
            runCatchingIo {
                if (!alreadyCustom) {
                    channelGate.ensure(threadId, displayName)
                    preferences.enableCustomNotificationChannel(threadId, now())
                }
                channelGate.openSystemSettings(threadId)
            }.onSuccess {
                _state.update {
                    it.copy(preference = it.preference.copy(customNotificationChannel = true))
                }
                emit(InfoEvent.OpenNotificationSettings)
            }.onFailure { error -> setError("channel", error) }
        }
    }

    /** `null` restores the AUTOMATIC category; an explicit choice always wins. */
    fun setCategoryOverride(category: MessageCategory?) = act("category") {
        preferences.setCategoryOverride(threadId, category?.name, now())
    }

    /**
     * "Report spam".
     *
     * Routed through [SpamRepository] so the block-provenance rule applies: the
     * report never claims a block the user had already added manually, which is
     * what keeps Not-Spam from silently unblocking their own decision.
     *
     * NOTE ON SCOPE: the Category row is never auto-set to SPAM by a report — the
     * spam flag plus [SpamReportPolicy] decide the effective category, so a
     * Not-Spam restores exactly the user's earlier override.
     */
    fun reportSpam(address: String) = act("report-spam", refreshBlock = true) {
        spam.reportSpam(threadId, address)
    }

    /** "Not spam": returns the conversation to the Inbox; nothing is deleted. */
    fun notSpam(address: String, blockedByReport: Boolean) = act("not-spam", refreshBlock = true) {
        spam.notSpam(threadId, address, blockedByReport)
    }

    /** Plain manual Block / Unblock — never writes the spam flag. */
    fun setBlocked(address: String, blocked: Boolean) = act("block", refreshBlock = true) {
        spam.setBlocked(address, blocked)
    }

    /**
     * Durably moves the conversation to Trash.
     *
     * The cutoff is the canonical newest ACTIVE message, resolved inside
     * [ConversationTrashing] with the same `MessageDao.newestForThread` every
     * other cutoff site uses, so the tombstone hides exactly the snapshot the user
     * is looking at. This is NEVER a permanent delete: provider rows are untouched
     * and Recently Deleted restores it.
     */
    fun trashConversation() {
        scope.launch {
            runCatchingIo { trashing.moveToTrash(threadId, now()) }
                .onSuccess { emit(InfoEvent.ConversationTrashed) }
                .onFailure { error -> setError("trash", error) }
        }
    }

    fun requestOpenMedia() = emit(InfoEvent.OpenMedia)

    fun requestOpenStarred() = emit(InfoEvent.OpenStarred)

    /** The screen marks a handled event so a recomposition cannot replay it. */
    fun consumeEvent(event: InfoEvent) {
        _events.compareAndSet(event, null)
    }

    /**
     * Drops the error banner without touching any other state: a failed mute must
     * not make the screen look like it reloaded itself.
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Runs one mutation on [io], then refreshes the read-side facts it could have
     * changed. A mutation failure is surfaced, never swallowed: a mute that did
     * not persist must not look like it did.
     */
    private fun act(
        label: String,
        refreshBlock: Boolean = false,
        block: suspend () -> Unit
    ) {
        scope.launch {
            runCatchingIo { block() }.onFailure { error -> setError(label, error) }
            if (refreshBlock) {
                val address = _state.value.participant.phone
                runCatchingIo {
                    _state.value.participant.hasDialableNumber && blocklist.isBlocked(address)
                }.onSuccess { blocked -> _state.update { it.copy(blocked = blocked) } }
            }
        }
    }

    private fun setError(label: String, error: Throwable) {
        if (error is CancellationException) throw error
        _state.update { it.copy(error = "$label: ${messageFor(error)}") }
    }

    private fun messageFor(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    private fun emit(event: InfoEvent) {
        _events.value = event
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(io) { block() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        /** How many starred messages this screen keeps live observers for. */
        const val STARRED_WATCH_LIMIT = 40
    }
}

/**
 * The two Android notification-channel operations Conversation Info needs.
 *
 * Kept behind an interface so the controller is JVM-testable; the production
 * implementation is a thin delegate to the EXISTING
 * [ConversationNotificationChannels] object (no second channel implementation and
 * no second channel-id scheme).
 */
interface NotificationChannelGate {
    fun ensure(threadId: Long, displayName: String)
    fun openSystemSettings(threadId: Long)
}

class AndroidNotificationChannelGate(
    private val context: Context
) : NotificationChannelGate {

    override fun ensure(threadId: Long, displayName: String) {
        ConversationNotificationChannels.ensure(context, threadId, displayName)
    }

    override fun openSystemSettings(threadId: Long) {
        ConversationNotificationChannels.openSystemSettings(context, threadId)
    }
}
