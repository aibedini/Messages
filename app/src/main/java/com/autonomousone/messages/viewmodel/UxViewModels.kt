package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.repository.AndroidNotificationChannelGate
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.ContactMapNameResolver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ConversationInfoController
import com.autonomousone.messages.repository.ConversationMute
import com.autonomousone.messages.repository.ConversationPreferenceRepository
import com.autonomousone.messages.repository.MessageUserStateRepository
import com.autonomousone.messages.repository.NewestMessageLookup
import com.autonomousone.messages.repository.RepositoryBlocklistPort
import com.autonomousone.messages.repository.RepositoryConversationPreferences
import com.autonomousone.messages.repository.RepositoryConversationTrashing
import com.autonomousone.messages.repository.RepositoryParticipantLookup
import com.autonomousone.messages.repository.RepositoryStarredStore
import com.autonomousone.messages.repository.RoomAssetCounter
import com.autonomousone.messages.repository.RoomAutomaticCategoryLookup
import com.autonomousone.messages.repository.SpamRepository
import com.autonomousone.messages.repository.StarredMessageItem
import com.autonomousone.messages.repository.StarredMessagesController
import com.autonomousone.messages.repository.TrashRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Backing state for the Conversation Info screen (v3.4.0 FEATURE 5).
 *
 * A thin Android wiring shell: every decision lives in
 * [ConversationInfoController], which is Android-free and unit-tested. This class
 * binds the production repositories (through the narrow ports) to
 * `viewModelScope`, so the screen never builds a repository itself and no
 * Room/provider work can land on the Compose main thread.
 */
class ConversationInfoViewModel(
    application: Application,
    private val threadId: Long
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val controller: ConversationInfoController

    val state: StateFlow<ConversationInfoController.ConversationInfoUiState>
    val events: StateFlow<ConversationInfoController.InfoEvent?>

    init {
        val database = MessagesDatabase.get(appContext)
        val messageDao = database.messageDao()
        val userState = MessageUserStateRepository(appContext)
        val preferences = ConversationPreferenceRepository(appContext)
        val blocklist = BlocklistRepository(appContext)

        controller = ConversationInfoController(
            threadId = threadId,
            starredStore = RepositoryStarredStore(userState),
            preferences = RepositoryConversationPreferences(preferences),
            participants = RepositoryParticipantLookup(ContactRepository(appContext)),
            automaticCategories = RoomAutomaticCategoryLookup(
                database.conversationClassificationDao()
            ),
            assetCounter = RoomAssetCounter(database.messageAssetDao()),
            blocklist = RepositoryBlocklistPort(blocklist),
            spam = SpamRepository(preferences.spamBlockPort(blocklist)),
            channelGate = AndroidNotificationChannelGate(appContext),
            newestMessage = NewestMessageLookup { id ->
                withContext(Dispatchers.IO) { messageDao.newestForThread(id) }
            },
            // Provider half of a PURGE is never used by this screen: Conversation
            // Info only creates the durable, reversible tombstone. The phase-6
            // provider path performs the actual purge from Recently Deleted.
            trashing = RepositoryConversationTrashing(
                trash = TrashRepository(appContext, NoProviderPurge),
                newestMessage = NewestMessageLookup { id ->
                    withContext(Dispatchers.IO) { messageDao.newestForThread(id) }
                }
            ),
            scope = viewModelScope
        )

        state = controller.state
        events = controller.events
    }

    fun load(phone: String) = controller.load(phone)

    fun toggleStar(key: MessageKey, starred: Boolean) = controller.toggleStar(key, starred)

    fun setMuted(preset: ConversationMute.Preset) = controller.setMuted(preset)

    fun unmute() = controller.unmute()

    fun chooseCustomNotifications(displayName: String) =
        controller.chooseCustomNotifications(displayName)

    fun setCategoryOverride(category: MessageCategory?) = controller.setCategoryOverride(category)

    fun reportSpam(address: String) = controller.reportSpam(address)

    fun notSpam(address: String, blockedByReport: Boolean) =
        controller.notSpam(address, blockedByReport)

    fun setBlocked(address: String, blocked: Boolean) = controller.setBlocked(address, blocked)

    fun trashConversation() = controller.trashConversation()

    fun requestOpenMedia() = controller.requestOpenMedia()

    fun requestOpenStarred() = controller.requestOpenStarred()

    fun consumeEvent(event: ConversationInfoController.InfoEvent) =
        controller.consumeEvent(event)

    fun clearError() = controller.clearError()

    companion object {
        /**
         * Purge is never triggered from Conversation Info, so the provider half
         * refuses explicitly instead of silently reporting success: a future
         * caller of `purgeDue` on this instance counts the thread as FAILED and
         * keeps its trash state for the real purger to retry.
         */
        private val NoProviderPurge = TrashRepository.ProviderPurger { false }

        fun factory(threadId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = ConversationInfoViewModel(
                    application = requireApplication(extras),
                    threadId = threadId
                ) as T

                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    create(modelClass, CreationExtras.Empty)
            }
    }
}

/**
 * Backing state for the Starred messages list (v3.4.0 FEATURE 7).
 *
 * [threadId] null drives the GLOBAL list; a thread id drives the in-conversation
 * list. Both use the same controller (and therefore the same paging, dedup and
 * empty/error rules) and differ only in the SQL the port calls, so a starred row
 * behaves identically on both screens.
 */
class StarredMessagesViewModel(
    application: Application,
    threadId: Long?
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val store = RepositoryStarredStore(MessageUserStateRepository(appContext))

    private val controller: StarredMessagesController

    val state: StateFlow<StarredMessagesController.UiState>

    init {
        controller = StarredMessagesController(
            contacts = { ContactMapNameResolver.load(ContactRepository(appContext)) },
            coroutineScope = viewModelScope
        )
        controller.load(sourceFor(threadId))
        state = controller.state
    }

    fun loadMore() = controller.loadMore()

    fun refresh() = controller.refresh()

    fun unstar(item: StarredMessageItem) = controller.unstar(store, item)

    fun clearError() = controller.clearError()

    private fun sourceFor(threadId: Long?): StarredMessagesController.PageSource =
        if (threadId == null || threadId <= 0L) {
            StarredMessagesController.PageSource.Global(
                StarredMessagesController.Pages { limit, offset ->
                    store.starredPage(limit, offset)
                }
            )
        } else {
            StarredMessagesController.PageSource.Thread(
                threadId = threadId,
                pages = StarredMessagesController.Pages { limit, offset ->
                    store.starredPageInThread(threadId, limit, offset)
                }
            )
        }

    companion object {
        fun factory(threadId: Long?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = StarredMessagesViewModel(
                    application = requireApplication(extras),
                    threadId = threadId
                ) as T

                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    create(modelClass, CreationExtras.Empty)
            }
    }
}

/**
 * The process [Application] for the ViewModel factories.
 *
 * Read from [ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY], which
 * every ComponentActivity-backed `LocalViewModelStoreOwner` supplies — so no
 * global mutable holder (and no edit to `MessagesApp`) is needed.
 */
private fun requireApplication(extras: CreationExtras): Application =
    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
        ?: error("No Application in CreationExtras; the factory must be used from a ComponentActivity")
