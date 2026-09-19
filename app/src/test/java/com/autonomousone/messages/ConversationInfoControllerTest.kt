package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.data.StarredMessageRow
import com.autonomousone.messages.repository.AssetCounter
import com.autonomousone.messages.repository.AutomaticCategoryLookup
import com.autonomousone.messages.repository.BlocklistPort
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.ConversationInfoController
import com.autonomousone.messages.repository.ConversationInfoLogic
import com.autonomousone.messages.repository.ConversationMute
import com.autonomousone.messages.repository.ConversationParticipantState
import com.autonomousone.messages.repository.ConversationPreferences
import com.autonomousone.messages.repository.ConversationTrashing
import com.autonomousone.messages.repository.NotificationChannelGate
import com.autonomousone.messages.repository.ParticipantLookup
import com.autonomousone.messages.repository.SpamActionPlan
import com.autonomousone.messages.repository.SpamBlockPort
import com.autonomousone.messages.repository.SpamRepository
import com.autonomousone.messages.repository.StarredStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversation Info controller (v3.4.0 FEATURE 5).
 *
 * The controller owns every decision the screen makes, so these tests drive it
 * with in-memory fakes: mute arithmetic through the REAL preference port
 * contract, the custom-channel flow (create the channel and open the SYSTEM
 * sheet, or just re-open it), spam/block provenance through the REAL
 * [SpamRepository], star toggling with the COMPOSITE identity, and the rule that
 * a failed action is REPORTED rather than silently swallowed.
 *
 * The controller runs on `Dispatchers.Unconfined` here, so every action completes
 * synchronously and each assertion reads settled state.
 */
class ConversationInfoControllerTest {

    private val threadId = 7L
    private val phone = "+989121234567"

    // ── Fakes ───────────────────────────────────────────────────────────────

    private class FakeStarredStore : StarredStore {
        val states = mutableMapOf<String, MessageUserStateEntity>()
        val pageCalls = mutableListOf<Pair<Int, Int>>()
        var failPage = false

        private fun id(source: String, providerId: Long) = "$source:$providerId"

        override suspend fun isStarred(source: String, providerId: Long): Boolean =
            states[id(source, providerId)]?.starred == true

        override fun observeStarred(
            source: String,
            providerId: Long
        ): Flow<MessageUserStateEntity?> = flowOf(states[id(source, providerId)])

        override suspend fun setStarred(
            key: MessageKey,
            threadId: Long,
            starred: Boolean,
            now: Long
        ) {
            val existing = states[id(key.source, key.providerId)]
            states[id(key.source, key.providerId)] = MessageUserStateEntity(
                source = key.source,
                providerId = key.providerId,
                threadId = threadId,
                starred = starred,
                starredAt = if (starred) now else 0L,
                keepFromOtpCleanup = existing?.keepFromOtpCleanup == true,
                updatedAt = now
            )
        }

        override suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow> =
            rows(limit, offset)

        override suspend fun starredPageInThread(
            threadId: Long,
            limit: Int,
            offset: Int
        ): List<StarredMessageRow> {
            pageCalls.add(limit to offset)
            if (failPage) throw IllegalStateException("starred page unavailable")
            return rows(limit, offset).filter { it.threadId == threadId }
        }

        override fun observeStarredCountInThread(threadId: Long): Flow<Int> =
            flowOf(states.values.count { it.threadId == threadId && it.starred })

        private fun rows(limit: Int, offset: Int): List<StarredMessageRow> {
            val sorted = states.values
                .filter { it.starred }
                .sortedByDescending { it.providerId }
                .map { state ->
                    StarredMessageRow(
                        source = state.source,
                        providerId = state.providerId,
                        threadId = state.threadId,
                        body = "hi",
                        date = state.starredAt,
                        rawAddress = "+989121234567",
                        normalizedAddress = "+989121234567",
                        starredAt = state.starredAt
                    )
                }
            return sorted.drop(offset).take(limit)
        }
    }

    private class FakePreferences : ConversationPreferences {
        val flow = MutableStateFlow<ConversationPreferenceEntity?>(null)
        val mutes = mutableListOf<Long>()
        val categories = mutableListOf<String?>()
        var customEnabled = false
        var spamToggles = 0

        override fun observe(threadId: Long): Flow<ConversationPreferenceEntity?> = flow

        private fun current() = flow.value ?: ConversationPreferenceEntity(threadId = 7L)

        override suspend fun setMutedUntil(threadId: Long, mutedUntil: Long, now: Long) {
            mutes.add(mutedUntil)
            flow.value = current().copy(mutedUntil = mutedUntil)
        }

        override suspend fun unmute(threadId: Long, now: Long) {
            mutes.add(ConversationMute.NOT_MUTED)
            flow.value = current().copy(mutedUntil = 0L)
        }

        override suspend fun enableCustomNotificationChannel(threadId: Long, now: Long) {
            customEnabled = true
            flow.value = current().copy(customNotificationChannel = true)
        }

        override suspend fun setCategoryOverride(threadId: Long, category: String?, now: Long) {
            categories.add(category)
            flow.value = current().copy(categoryOverride = category)
        }

        /** Mirrors the field-scoped DAO writer: only the spam columns change. */
        fun applySpam(spam: Boolean, reportedAt: Long) {
            spamToggles++
            flow.value = current().copy(spam = spam, spamReportedAt = reportedAt)
        }
    }

    private class FakeParticipant(
        private val state: ConversationParticipantState
    ) : ParticipantLookup {
        override suspend fun getParticipantState(phone: String): ConversationParticipantState = state
    }

    private class FakeCategory(private val category: MessageCategory?) : AutomaticCategoryLookup {
        override suspend fun automaticCategory(threadId: Long): MessageCategory? = category
    }

    private class FakeAssets(private val count: Int) : AssetCounter {
        override fun observeCountInThread(threadId: Long): Flow<Int> = flowOf(count)
    }

    private class FakeBlocklist(initial: Set<String> = emptySet()) : BlocklistPort {
        val blocked = initial.toMutableSet()

        override fun isBlocked(address: String): Boolean =
            blocked.contains(BlocklistRepository.normalize(address)) || blocked.contains(address)
    }

    private class FakeSpamPort(
        private val blocklist: FakeBlocklist,
        /** Mirrors the field-scoped DAO writer, which re-emits the preference row. */
        private val preferences: FakePreferences? = null
    ) : SpamBlockPort {
        var spam = false
        var blockedByReport = false

        /** True when the port was asked BEFORE the report wrote anything. */
        var lastWasBlockedBefore = false

        override fun isBlocked(normalizedAddress: String): Boolean {
            val answer = blocklist.blocked.contains(normalizedAddress)
            lastWasBlockedBefore = answer
            return answer
        }

        override fun block(address: String) {
            blocklist.blocked.add(BlocklistRepository.normalize(address))
        }

        override fun unblock(address: String) {
            blocklist.blocked.remove(BlocklistRepository.normalize(address))
        }

        override suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long) {
            spam = true
            this.blockedByReport = blockedByReport
            preferences?.applySpam(true, now)
        }

        override suspend fun clearSpam(threadId: Long, clearBlockProvenance: Boolean, now: Long) {
            spam = false
            if (clearBlockProvenance) blockedByReport = false
            preferences?.applySpam(false, 0L)
        }
    }

    private class FakeChannelGate : NotificationChannelGate {
        val ensured = mutableListOf<Pair<Long, String>>()
        var opened = 0

        override fun ensure(threadId: Long, displayName: String) {
            ensured.add(threadId to displayName)
        }

        override fun openSystemSettings(threadId: Long) {
            opened++
        }
    }

    private class FakeTrashing : ConversationTrashing {
        val trashed = mutableListOf<Pair<Long, Long>>()
        var failure: Throwable? = null

        override suspend fun moveToTrash(threadId: Long, now: Long) {
            failure?.let { throw it }
            trashed.add(threadId to now)
        }
    }

    private class Fixture(
        automaticCategory: MessageCategory? = null,
        assetCount: Int = 0,
        blockedNumbers: Set<String> = emptySet()
    ) {
        val starred = FakeStarredStore()
        val preferences = FakePreferences()
        // The production blocklist stores NORMALIZED numbers, so an
        // already-blocked fixture must be normalized the same way — otherwise a
        // test could "prove" provenance with a number the real store would not
        // have matched.
        val blocklist = FakeBlocklist(
            blockedNumbers.map { BlocklistRepository.normalize(it) }.toSet()
        )
        val spamPort = FakeSpamPort(blocklist, preferences)
        val gate = FakeChannelGate()
        val trashing = FakeTrashing()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        var now: Long = 1_700_000_000_000L

        val controller = ConversationInfoController(
            threadId = 7L,
            starredStore = starred,
            preferences = preferences,
            participants = FakeParticipant(
                ConversationParticipantState(
                    phone = "+989121234567",
                    normalizedPhone = "+989121234567",
                    displayName = "Ali",
                    isKnownContact = true,
                    contactLookupUri = "content://contacts/lookup/1"
                )
            ),
            automaticCategories = FakeCategory(automaticCategory),
            assetCounter = FakeAssets(assetCount),
            blocklist = blocklist,
            spam = SpamRepository(port = spamPort, now = { 1_700_000_000_000L }),
            channelGate = gate,
            trashing = trashing,
            scope = scope,
            io = Dispatchers.Unconfined,
            now = { now }
        )
    }

    // ── Load ────────────────────────────────────────────────────────────────

    @Test
    fun `load fills the header, block state and starred set`() {
        val fixture = Fixture(automaticCategory = MessageCategory.OTP, blockedNumbers = setOf(phone))
        fixture.starred.states["sms:100"] = MessageUserStateEntity(
            source = "sms", providerId = 100L, threadId = threadId, starred = true, starredAt = 5L
        )
        fixture.starred.states["mms:100"] = MessageUserStateEntity(
            source = "mms", providerId = 100L, threadId = threadId, starred = true, starredAt = 6L
        )

        fixture.controller.load(phone)
        val state = fixture.controller.state.value

        assertEquals("Ali", state.participant.displayName)
        assertTrue(state.participant.hasDialableNumber)
        assertTrue(state.blocked)
        assertEquals(MessageCategory.OTP, state.automaticCategory)
        assertFalse(state.loading)
        assertNull(state.error)
        assertTrue(state.isStarred(MessageKey("sms", 100L)))
        assertTrue(state.isStarred(MessageKey("mms", 100L)))
    }

    // ── Mute ────────────────────────────────────────────────────────────────

    @Test
    fun `mute writes the preset instant and unmute writes zero`() {
        val fixture = Fixture()
        fixture.controller.load(phone)

        fixture.controller.setMuted(ConversationMute.Preset.ONE_HOUR)
        assertEquals(listOf(fixture.now + ConversationMute.HOUR), fixture.preferences.mutes)

        fixture.controller.unmute()
        assertEquals(
            listOf(fixture.now + ConversationMute.HOUR, ConversationMute.NOT_MUTED),
            fixture.preferences.mutes
        )
    }

    @Test
    fun `the state reflects the mute instant reactively`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.setMuted(ConversationMute.Preset.SEVEN_DAYS)

        val row = fixture.controller.state.value.sections.notifications
        assertTrue(row.isMuted)
        assertEquals(fixture.now + 7 * 24 * 60 * 60 * 1000L, row.showMutedUntil)
    }

    @Test
    fun `forever mute needs no date and only unmute ends it`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.setMuted(ConversationMute.Preset.FOREVER)

        val row = fixture.controller.state.value.sections.notifications
        assertTrue(row.isMuted)
        assertNull(row.showMutedUntil)
        // Far in the future the comparison still holds.
        fixture.now += 500L * 365 * 24 * 60 * 60 * 1000
        assertTrue(fixture.controller.state.value.sections.notifications.isMuted)
    }

    @Test
    fun `mute is separate from channel configuration`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.setMuted(ConversationMute.Preset.FOREVER)
        fixture.controller.chooseCustomNotifications("Ali")

        assertTrue(
            "muting does not clear the channel and the channel does not clear the mute",
            fixture.controller.state.value.sections.notifications.isMuted
        )
        assertTrue(fixture.preferences.customEnabled)
    }

    // ── Custom notifications ────────────────────────────────────────────────

    @Test
    fun `choosing custom creates the channel then opens the system sheet`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.chooseCustomNotifications("Ali")

        assertEquals(listOf(threadId to "Ali"), fixture.gate.ensured)
        assertEquals(1, fixture.gate.opened)
        assertTrue(fixture.preferences.customEnabled)
        assertEquals(
            ConversationInfoController.InfoEvent.OpenNotificationSettings,
            fixture.controller.events.value
        )
        assertTrue(fixture.controller.state.value.sections.customNotifications.isCustom)
    }

    @Test
    fun `an already custom conversation only re-opens the system sheet`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.chooseCustomNotifications("Ali")
        fixture.controller.consumeEvent(ConversationInfoController.InfoEvent.OpenNotificationSettings)

        fixture.controller.chooseCustomNotifications("Ali")

        assertEquals("no second channel create", 1, fixture.gate.ensured.size)
        assertEquals("the sheet is re-opened", 2, fixture.gate.opened)
    }

    // ── Category ────────────────────────────────────────────────────────────

    @Test
    fun `category override null means automatic and an explicit pick persists`() {
        val fixture = Fixture(automaticCategory = MessageCategory.PERSONAL)
        fixture.controller.load(phone)
        assertTrue(fixture.controller.state.value.sections.categoryIsAutomatic)

        fixture.controller.setCategoryOverride(MessageCategory.PROMOTION)
        assertEquals(listOf(MessageCategory.PROMOTION.name), fixture.preferences.categories)
        assertFalse(fixture.controller.state.value.sections.categoryIsAutomatic)
        assertEquals(
            MessageCategory.PROMOTION,
            fixture.controller.state.value.sections.category.category
        )

        fixture.controller.setCategoryOverride(null)
        assertEquals(2, fixture.preferences.categories.size)
        assertNull(fixture.preferences.categories[1])
        assertTrue(fixture.controller.state.value.sections.categoryIsAutomatic)
        assertEquals(
            "Automatic must fall back to the classifier, never to a sticky override",
            MessageCategory.PERSONAL,
            fixture.controller.state.value.effectiveCategory
        )
    }

    @Test
    fun `a category override never auto-reports spam`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.setCategoryOverride(MessageCategory.SPAM)

        assertFalse("SPAM category is not a spam report", fixture.spamPort.spam)
        assertFalse(fixture.controller.state.value.preference.spam)
    }

    // ── Stars ───────────────────────────────────────────────────────────────

    @Test
    fun `star and unstar use the composite identity`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        val sms = MessageKey("sms", 100L)
        val mms = MessageKey("mms", 100L)

        fixture.controller.toggleStar(sms, true)
        assertEquals(true, fixture.starred.states["sms:100"]?.starred)
        assertNull("MMS 100 is a DIFFERENT message", fixture.starred.states["mms:100"])

        fixture.controller.toggleStar(mms, true)
        assertEquals(true, fixture.starred.states["mms:100"]?.starred)
        assertEquals(true, fixture.starred.states["sms:100"]?.starred)

        fixture.controller.toggleStar(sms, false)
        assertEquals(false, fixture.starred.states["sms:100"]?.starred)
        assertEquals(
            "unstarring SMS 100 must never touch MMS 100",
            true,
            fixture.starred.states["mms:100"]?.starred
        )
    }

    @Test
    fun `unstarring keeps the OTP-cleanup opt-out`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.starred.states["sms:100"] = MessageUserStateEntity(
            source = "sms", providerId = 100L, threadId = threadId,
            starred = true, starredAt = 1L, keepFromOtpCleanup = true
        )

        fixture.controller.toggleStar(MessageKey("sms", 100L), false)

        val row = fixture.starred.states["sms:100"]!!
        assertFalse(row.starred)
        assertTrue("keepFromOtpCleanup is a separate user decision", row.keepFromOtpCleanup)
    }

    // ── Spam & blocking ─────────────────────────────────────────────────────

    @Test
    fun `report spam blocks the number, records provenance and shows the state`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.reportSpam(phone)

        assertTrue(fixture.spamPort.spam)
        assertTrue("the report created the block", fixture.spamPort.blockedByReport)
        assertTrue(fixture.controller.state.value.blocked)
        assertTrue(fixture.controller.state.value.preference.spam)
        assertEquals(
            com.autonomousone.messages.repository.SpamSectionState.ReportAction.NOT_SPAM,
            fixture.controller.state.value.spamSection.reportAction
        )
    }

    @Test
    fun `not spam after a manual block keeps the user's own block`() {
        val fixture = Fixture(blockedNumbers = setOf(phone))
        fixture.controller.load(phone)

        fixture.controller.reportSpam(phone)
        assertFalse(
            "a pre-existing manual block is not claimed by the report",
            fixture.spamPort.blockedByReport
        )

        fixture.controller.notSpam(phone, blockedByReport = false)
        assertFalse(fixture.spamPort.spam)
        assertTrue("the manual block must survive Not-Spam", fixture.controller.state.value.blocked)
        assertFalse(fixture.controller.state.value.preference.spam)
    }

    @Test
    fun `not spam undoes a block the report itself created`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.reportSpam(phone)
        assertTrue(fixture.controller.state.value.blocked)

        fixture.controller.notSpam(phone, blockedByReport = true)
        assertFalse(fixture.controller.state.value.blocked)
        assertFalse(fixture.controller.state.value.preference.spam)
    }

    @Test
    fun `plain block never writes the spam flag`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.setBlocked(phone, true)

        assertFalse("a manual block is not a spam report", fixture.spamPort.spam)
        assertTrue(fixture.controller.state.value.blocked)

        fixture.controller.setBlocked(phone, false)
        assertFalse(fixture.controller.state.value.blocked)
    }

    // ── Trash ───────────────────────────────────────────────────────────────

    @Test
    fun `move to trash records the tombstone and signals navigation`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.controller.trashConversation()

        assertEquals(listOf(threadId to fixture.now), fixture.trashing.trashed)
        assertEquals(
            ConversationInfoController.InfoEvent.ConversationTrashed,
            fixture.controller.events.value
        )
    }

    @Test
    fun `a failed trash keeps the screen and reports the failure`() {
        val fixture = Fixture()
        fixture.controller.load(phone)
        fixture.trashing.failure = IllegalStateException("disk full")
        fixture.controller.trashConversation()

        assertNull(fixture.controller.events.value)
        assertEquals("trash: disk full", fixture.controller.state.value.error)

        fixture.controller.clearError()
        assertNull(fixture.controller.state.value.error)
    }

    // ── Navigation requests ─────────────────────────────────────────────────

    @Test
    fun `media and starred rows request their routes`() {
        val fixture = Fixture()
        fixture.controller.requestOpenMedia()
        assertEquals(
            ConversationInfoController.InfoEvent.OpenMedia,
            fixture.controller.events.value
        )
        fixture.controller.consumeEvent(ConversationInfoController.InfoEvent.OpenMedia)
        assertNull("a consumed event is never replayed", fixture.controller.events.value)

        fixture.controller.requestOpenStarred()
        assertEquals(
            ConversationInfoController.InfoEvent.OpenStarred,
            fixture.controller.events.value
        )
    }

    // ── Failure handling ────────────────────────────────────────────────────

    @Test
    fun `a failed load reports an error instead of a blank screen`() {
        val fixture = Fixture()
        fixture.starred.failPage = true
        fixture.controller.load(phone)

        val state = fixture.controller.state.value
        assertFalse(state.loading)
        assertEquals("starred page unavailable", state.error)
    }

    @Test
    fun `an asset count is a subtitle and never fails the screen`() {
        val fixture = Fixture(assetCount = 3)
        fixture.controller.load(phone)

        val state = fixture.controller.state.value
        assertNull(state.error)
        assertEquals(3, state.assetCount)
        assertEquals(
            ConversationInfoLogic.Destination.MEDIA,
            state.sections.media
        )
    }

    @Test
    fun `spam report plan keeps the documented provenance precedence`() {
        val manual = SpamActionPlan.report("09120000000", wasBlockedBefore = true)
        assertFalse(manual.blockAddress)
        assertFalse(manual.blockedByReport)
        assertFalse(manual.clearBlockProvenanceOnUndo)

        val fresh = SpamActionPlan.report("09120000000", wasBlockedBefore = false)
        assertTrue(fresh.blockAddress)
        assertTrue(fresh.blockedByReport)
        assertTrue(fresh.clearBlockProvenanceOnUndo)
    }
}

