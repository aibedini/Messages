package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.security.ConversationGrantCoordinator
import com.autonomousone.messages.security.ConversationGrantSchema
import com.autonomousone.messages.security.ConversationGrantWiring
import com.autonomousone.messages.security.ConversationKeyRepository
import com.autonomousone.messages.security.ConversationScope
import com.autonomousone.messages.security.GrantTarget
import com.autonomousone.messages.security.MessageCrypto
import com.autonomousone.messages.security.PairingCapabilityContract
import com.autonomousone.messages.security.ScopeApplyResult
import com.autonomousone.messages.security.ScopeApplyStage
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ThreadLocalOnlyPolicy
import com.autonomousone.messages.sync.TrustedDevicePolicy
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LDV-004 PR-E (slice 2b) — the conversation-scope screen's ViewModel.
 *
 * It owns the three reads the screen needs and nothing else:
 *
 *  1. the AI device row from the trust registry (the caller passes `deviceId`);
 *  2. the conversations that can actually be granted — a conversation must have an opaque
 *     cloud identity (`remote_conversation_map`) because that id is what a grant names, and
 *     it must pass [ConversationScope.eligible], so a sensitive or LOCAL_ONLY conversation is
 *     never even listed (design §4.6/§5.8);
 *  3. the scope persisted for that device (slice 2a).
 *
 * Saving delegates the whole decision to [ConversationGrantCoordinator] through the production
 * wiring; the ViewModel never builds a payload, touches a key or writes the outbox itself. All
 * interactive state lives in the pure [ConversationScopeEditor], so the behaviour is unit
 * tested without Android.
 *
 * ## Why the keyring producer is nudged before a save
 *
 * A grant is bound to the account `READ_MESSAGES` epoch (`epochId`). That epoch is created by
 * `ConversationKeyRepository` when it publishes the keyring — normally on the uploader's drain
 * cycle, which may not have run yet on a freshly linked device. Nudging the existing producer
 * once before the save is what makes "link the AI device, open this screen, save" work without
 * minting key material here (this class never creates a CKE). The nudge is best-effort: if it
 * fails, the coordinator still reports a typed EPOCH failure and the scope stays unchanged.
 *
 * ## Known behaviour worth stating (not fixed in this slice)
 *
 * A conversation that becomes sensitive or LOCAL_ONLY AFTER it was granted is no longer
 * listed here, and [ConversationScopeEditor] deliberately preserves a persisted decision the
 * screen cannot show, so its grant is not silently revoked by opening and saving this screen.
 * The real boundary stays the message firewall: a sensitive message never becomes a cloud
 * event at all (design §5.8). Automatically revoking a conversation on reclassification is a
 * scope-policy decision for a later slice, not something a screen should do on load.
 *
 * No message body, key or payload is ever logged; only a category and a failure class.
 */
class ConversationScopeViewModel(
    application: Application,
    private val deviceId: String,
) : AndroidViewModel(application) {

    /** Typed load failures; the screen maps them to Persian copy. */
    enum class LoadError {
        /** No trust-registry row for this device. */
        DEVICE_NOT_FOUND,

        /** The device is not an `AI_AGENT`; the scope screen does not apply. */
        NOT_AN_AI_DEVICE,

        /** The device is revoked, expired or has no certificate. */
        DEVICE_NOT_TRUSTED,

        /** Reading the local conversations/scope failed. */
        READ_FAILED,
    }

    sealed interface UiState {
        data object Loading : UiState

        /** @param deviceName display-only label of the AI device. */
        data class Content(
            val deviceName: String,
            val editor: ConversationScopeEditor,
        ) : UiState

        data class Error(val reason: LoadError) : UiState
    }

    private val mutableState = MutableStateFlow<UiState>(UiState.Loading)

    /** Compose collects this; every mutation replaces the value. */
    val uiState: StateFlow<UiState> = mutableState.asStateFlow()

    /** Built lazily so a JVM unit test never touches Keystore/Room through this class. */
    private val coordinator: ConversationGrantCoordinator by lazy {
        ConversationGrantWiring.create(getApplication())
    }

    /** The loaded device row, snapshotted with the conversation list. */
    @Volatile
    private var device: TrustedDeviceEntity? = null

    /** conversationId -> stored category, exactly what the coordinator's policy needs. */
    @Volatile
    private var categories: Map<String, String> = emptyMap()

    init {
        load()
    }

    /** (Re)load device, conversations and persisted scope. Also the retry action. */
    fun load() {
        mutableState.value = UiState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                readContent()
            } catch (e: Exception) {
                DiagnosticLog.event("CONV_SCOPE", "screen-read-failed", e)
                UiState.Error(LoadError.READ_FAILED)
            }
        }
    }

    fun toggle(conversationId: String, enabled: Boolean) =
        updateEditor { it.toggle(conversationId, enabled) }

    fun setAllConversations(enabled: Boolean) =
        updateEditor { it.setAllConversations(enabled) }

    fun dismissNotice() = updateEditor { it.copy(notice = null) }

    /**
     * Publish the editor's selection.
     *
     * Returns immediately when there is nothing publishable (`canSave` is false) — an empty
     * cut-down list is NOT a "pause", so it must not reach the coordinator.
     */
    fun save() {
        val content = mutableState.value as? UiState.Content ?: return
        val editor = content.editor
        if (!editor.canSave) return
        val selection = editor.selection() ?: return
        mutableState.value = content.copy(editor = editor.markSaving())
        viewModelScope.launch {
            val result = try {
                applySelection(selection)
            } catch (e: Exception) {
                DiagnosticLog.event("CONV_SCOPE", "apply-failed", e)
                ScopeApplyResult.Failed(ScopeApplyStage.BUILD, cause = e)
            }
            val current = mutableState.value as? UiState.Content ?: return@launch
            mutableState.value = current.copy(editor = current.editor.applyResult(result))
        }
    }

    private fun updateEditor(transform: (ConversationScopeEditor) -> ConversationScopeEditor) {
        val current = mutableState.value as? UiState.Content ?: return
        val next = transform(current.editor)
        if (next != current.editor) mutableState.value = current.copy(editor = next)
    }

    private suspend fun applySelection(selection: ScopeSelection): ScopeApplyResult =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val target = device ?: return@withContext ScopeApplyResult.Failed(ScopeApplyStage.BUILD)
            val publicKey = try {
                MessageCrypto.unb64(target.encryptionPublicKey)
            } catch (e: Exception) {
                null
            }
            if (publicKey == null ||
                publicKey.size != ConversationGrantSchema.P256_PUBLIC_KEY_BYTES ||
                publicKey[0] != ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX
            ) {
                // A device row without a usable P-256 point can never receive a grant; report
                // it as a build failure instead of publishing something the device cannot open.
                return@withContext ScopeApplyResult.Failed(ScopeApplyStage.BUILD)
            }
            // Best effort: make sure the account READ_MESSAGES epoch exists (see the class
            // KDoc). A failure here is not fatal — the coordinator reports EPOCH if needed.
            runCatching { ConversationKeyRepository(MessagesDatabase.get(app)).drainHistoryGrants() }
            coordinator.applySelection(
                device = GrantTarget(deviceId = target.deviceId, encryptionPublicKey = publicKey),
                newSelection = selection,
                conversationCategory = { conversationId -> categories[conversationId] },
                expiresAtLimit = target.expiresAt,
            )
        }

    private suspend fun readContent(): UiState = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val db = MessagesDatabase.get(app)
        val target = db.trustedDeviceDao().byId(deviceId)
            ?: return@withContext UiState.Error(LoadError.DEVICE_NOT_FOUND)
        if (target.deviceType != PairingCapabilityContract.DEVICE_TYPE_AI_AGENT) {
            return@withContext UiState.Error(LoadError.NOT_AN_AI_DEVICE)
        }
        if (!TrustedDevicePolicy.isTrusted(target, System.currentTimeMillis())) {
            return@withContext UiState.Error(LoadError.DEVICE_NOT_TRUSTED)
        }

        val mappings = db.remoteConversationMapDao().observeAll().first()
            .associateBy { it.threadId }
        val categoriesByThread = db.conversationClassificationDao().all()
            .associate { it.threadId to it.category }
        val prefs = MessagingPreferences(app)
        val contactNames = runCatching { ContactRepository(app).getContactNameMapAsync() }
            .getOrDefault(emptyMap())

        val rows = ArrayList<ScopeConversationRow>()
        val resolvedCategories = HashMap<String, String>()
        var hiddenSensitive = 0
        for (conversation in db.conversationDao().allActive()) {
            // Only a thread with an opaque cloud identity can be named by a grant.
            val conversationId = mappings[conversation.threadId]?.conversationId ?: continue
            val address = conversation.rawAddress.ifBlank { conversation.normalizedAddress }
            val newestInbound = runCatching {
                db.messageDao().recentInbound(conversation.threadId, 1).firstOrNull()
            }.getOrNull()
            val localOnly = newestInbound != null &&
                ThreadLocalOnlyPolicy.isLocalOnly(address, newestInbound.body, prefs)
            val category = if (localOnly) {
                ConversationScope.LOCAL_ONLY
            } else {
                categoriesByThread[conversation.threadId].orEmpty()
            }
            if (!ConversationScope.eligible(conversationId, category)) {
                // Never listed, never grantable. The firewall having produced no events for it
                // is the real boundary; hiding it is the extra guard (design §5.8).
                hiddenSensitive++
                continue
            }
            resolvedCategories[conversationId] = category
            val displayName = contactNames[ContactRepository.normalizePhone(address)]
                ?: address
            rows += ScopeConversationRow(
                conversationId = conversationId,
                threadId = conversation.threadId,
                displayName = displayName.ifBlank { conversationId },
                lastActivity = conversation.lastMessageDate,
            )
        }

        device = target
        categories = resolvedCategories
        val persisted = db.linkedDeviceScopeDao().snapshot(deviceId).toSelection()
        UiState.Content(
            deviceName = target.displayName,
            editor = ConversationScopeEditor.fromPersisted(
                rows = rows,
                hiddenSensitiveCount = hiddenSensitive,
                persisted = persisted,
            ),
        )
    }

    /** Factory for `viewModel(factory = …)`; the device id is the screen's own argument. */
    class Factory(
        private val application: Application,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationScopeViewModel(application, deviceId) as T
    }
}
