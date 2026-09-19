package com.autonomousone.messages.repository

import com.autonomousone.messages.data.ConversationClassificationDao
import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageAssetDao
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.StarredMessageRow
import com.autonomousone.messages.data.MessageUserStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * NARROW production adapters for the Conversation Info / Starred controllers
 * (v3.4.0 FEATURES 5 and 7).
 *
 * WHY NARROW PORTS
 * ----------------
 * The controllers must be testable on the JVM. Depending on
 * `MessageUserStateRepository` / `ConversationPreferenceRepository` directly
 * would force Room + Context into every unit test, so each controller declares
 * exactly what it needs as an interface and these adapters bind that interface to
 * the EXISTING authorities. There is no second data path: every method here
 * delegates to the DAO/repository that owns the table.
 */

/** Per-message user state (star / OTP-cleanup opt-out) as the screens need it. */
interface StarredStore {

    suspend fun isStarred(source: String, providerId: Long): Boolean

    /**
     * Live user state of ONE message, so a star toggled anywhere (this screen, the
     * bubble menu, the starred list) is reflected on the other surfaces without a
     * reload. Emits null when no state row exists yet (= not starred).
     */
    fun observeStarred(source: String, providerId: Long): Flow<MessageUserStateEntity?>

    /** `starredAt` is set to [now] on star and cleared to 0 on unstar. */
    suspend fun setStarred(key: MessageKey, threadId: Long, starred: Boolean, now: Long)

    /** Global starred page, newest first, ACTIVE-UI filtered. */
    suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow>

    /** In-conversation starred page, newest first, ACTIVE-UI filtered. */
    suspend fun starredPageInThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): List<StarredMessageRow>

    /**
     * Live starred count for one conversation. Emits on every change to
     * `message_user_state` for the thread, so the Conversation Info badge is
     * reactive rather than polled.
     */
    fun observeStarredCountInThread(threadId: Long): Flow<Int>
}

class RepositoryStarredStore(
    private val repository: MessageUserStateRepository
) : StarredStore {

    override suspend fun isStarred(source: String, providerId: Long): Boolean =
        repository.isStarred(source, providerId)

    override fun observeStarred(source: String, providerId: Long): Flow<MessageUserStateEntity?> =
        repository.observe(source, providerId)

    override suspend fun setStarred(
        key: MessageKey,
        threadId: Long,
        starred: Boolean,
        now: Long
    ) = repository.setStarred(key, threadId, starred, now)

    override suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow> =
        repository.starredPage(limit, offset)

    override suspend fun starredPageInThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): List<StarredMessageRow> = repository.starredPageInThread(threadId, limit, offset)

    override fun observeStarredCountInThread(threadId: Long): Flow<Int> =
        repository.observeStarredCountInThread(threadId)
}

/** Per-conversation preference state (mute / channel / category override). */
interface ConversationPreferences {
    fun observe(threadId: Long): Flow<ConversationPreferenceEntity?>
    suspend fun setMutedUntil(threadId: Long, mutedUntil: Long, now: Long)
    suspend fun unmute(threadId: Long, now: Long)
    suspend fun enableCustomNotificationChannel(threadId: Long, now: Long)

    /** `null` clears the override back to the automatic category. */
    suspend fun setCategoryOverride(threadId: Long, category: String?, now: Long)
}

class RepositoryConversationPreferences(
    private val repository: ConversationPreferenceRepository
) : ConversationPreferences {

    override fun observe(threadId: Long): Flow<ConversationPreferenceEntity?> =
        repository.observe(threadId)

    override suspend fun setMutedUntil(threadId: Long, mutedUntil: Long, now: Long) =
        repository.setMutedUntil(threadId, mutedUntil, now)

    override suspend fun unmute(threadId: Long, now: Long) = repository.unmute(threadId, now)

    override suspend fun enableCustomNotificationChannel(threadId: Long, now: Long) =
        repository.enableCustomNotificationChannel(threadId, now)

    override suspend fun setCategoryOverride(threadId: Long, category: String?, now: Long) =
        repository.setCategoryOverride(threadId, category, now)
}

/** Conversation address → participant identity (the v3.3.6 contact lookup). */
interface ParticipantLookup {
    suspend fun getParticipantState(phone: String): ConversationParticipantState
}

class RepositoryParticipantLookup(
    private val repository: ContactRepository
) : ParticipantLookup {
    override suspend fun getParticipantState(phone: String): ConversationParticipantState =
        repository.getParticipantState(phone)
}

/**
 * Blocklist read surface.
 *
 * Takes the RAW address because [BlocklistRepository.isBlocked] is the fuzzy
 * "should this be hidden?" question. Writes never happen through this port: a
 * plain Block/Unblock goes through [SpamRepository.setBlocked], which normalizes
 * and owns the manual-block provenance rule.
 */
interface BlocklistPort {
    fun isBlocked(address: String): Boolean
}

class RepositoryBlocklistPort(
    private val repository: BlocklistRepository
) : BlocklistPort {
    override fun isBlocked(address: String): Boolean = repository.isBlocked(address)
}

/**
 * The AUTOMATIC category of one conversation, or null when the classifier has
 * not produced a projection row yet.
 *
 * Null is deliberately not `UNKNOWN`: the Category row must be able to say "no
 * automatic category yet" rather than claim the classifier decided UNKNOWN.
 */
interface AutomaticCategoryLookup {
    suspend fun automaticCategory(threadId: Long): MessageCategory?
}

class RoomAutomaticCategoryLookup(
    private val dao: ConversationClassificationDao
) : AutomaticCategoryLookup {
    override suspend fun automaticCategory(threadId: Long): MessageCategory? =
        dao.get(threadId)?.let { MessageCategory.from(it.category) }
}

/**
 * Indexed asset counts for the "Media, links & files" subtitle.
 *
 * `observeInThread` combines the per-kind counts that already exist in
 * `message_assets` (which is indexed on `(threadId, date)`), so the subtitle is
 * reactive and costs one indexed COUNT per kind — never a message scan.
 */
interface AssetCounter {
    fun observeCountInThread(threadId: Long): Flow<Int>
}

class RoomAssetCounter(
    private val dao: MessageAssetDao
) : AssetCounter {
    override fun observeCountInThread(threadId: Long): Flow<Int> = combine(
        dao.observeCountByKind(threadId, KIND_MEDIA),
        dao.observeCountByKind(threadId, KIND_LINK),
        dao.observeCountByKind(threadId, KIND_FILE)
    ) { media, links, files -> media + links + files }

    private companion object {
        /** [com.autonomousone.messages.data.MessageAssetKind] names. */
        const val KIND_MEDIA = "MEDIA"
        const val KIND_LINK = "LINK"
        const val KIND_FILE = "FILE"
    }
}

/**
 * The canonical NEWEST message of one conversation, as Trash requires.
 *
 * The tombstone cutoff IS this row (`date, source, providerId`), so it must come
 * from the same query every other cutoff site uses. The port is a `fun interface`
 * so the ViewModel can bind it to `MessageDao.newestForThread`, and the controller
 * never needs a Room type of its own.
 */
fun interface NewestMessageLookup {
    suspend fun newestForThread(threadId: Long): MessageEntity?
}

/**
 * "Move conversation to Trash" as Conversation Info uses it.
 *
 * A narrow seam (rather than the controller holding a [TrashRepository] directly)
 * for two reasons: the provider-purge half of Trash is a different feature and is
 * never reachable from this screen, and the durable-tombstone contract stays
 * unit-testable on the JVM. Production delegates to the ONE existing
 * [TrashRepository]; there is no second delete flow.
 */
interface ConversationTrashing {
    suspend fun moveToTrash(threadId: Long, now: Long)
}

class RepositoryConversationTrashing(
    private val trash: TrashRepository,
    private val newestMessage: NewestMessageLookup
) : ConversationTrashing {

    /**
     * The cutoff is the canonical NEWEST ACTIVE message
     * (`MessageDao.newestForThread`), so the tombstone hides exactly the snapshot
     * the user was looking at, and a genuinely new incoming message stays visible.
     */
    override suspend fun moveToTrash(threadId: Long, now: Long) {
        trash.moveToTrash(
            threadId = threadId,
            newestActive = newestMessage.newestForThread(threadId),
            now = now
        )
    }
}

/**
 * Production [ContactNameResolver] for the starred rows.
 *
 * The name map is loaded ONCE per page load (a single indexed Phone lookup,
 * already implemented by [ContactRepository.getContactNameMapAsync]) and then
 * consulted in memory, so rendering 50 starred rows never issues 50 contact
 * queries. A missing name falls back to the RAW address, which is what the user
 * sees for an unknown number everywhere else in the app.
 */
class ContactMapNameResolver(private val names: Map<String, String>) : ContactNameResolver {

    override fun displayName(rawAddress: String, normalizedAddress: String): String {
        val normalized = ContactRepository.normalizePhone(normalizedAddress)
        names[normalized]?.let { if (it.isNotBlank()) return it }
        if (rawAddress.isNotBlank()) {
            names[ContactRepository.normalizePhone(rawAddress)]?.let {
                if (it.isNotBlank()) return it
            }
        }
        return rawAddress.ifBlank { normalizedAddress }
    }

    companion object {
        /** Resolver used before the contact map has loaded. */
        val EMPTY: ContactNameResolver = ContactNameResolver { raw, normalized ->
            raw.ifBlank { normalized }
        }

        /** One contact-map read, on the caller's dispatcher. */
        suspend fun load(repository: ContactRepository): ContactNameResolver =
            ContactMapNameResolver(repository.getContactNameMapAsync())
    }
}
