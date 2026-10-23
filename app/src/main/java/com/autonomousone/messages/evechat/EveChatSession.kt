package com.autonomousone.messages.evechat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import com.autonomousone.messages.evechat.data.EveChatDatabase
import com.autonomousone.messages.evechat.model.ConvStatus
import com.autonomousone.messages.evechat.model.EveChatAgent
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatVisitor
import com.autonomousone.messages.evechat.model.EveChatVisitorSnapshot
import com.autonomousone.messages.evechat.model.SendState
import com.autonomousone.messages.evechat.model.SenderType
import com.autonomousone.messages.evechat.network.EveChatEventParser
import com.autonomousone.messages.evechat.network.EveChatSocket
import com.autonomousone.messages.evechat.network.ReconnectBackoff
import com.autonomousone.messages.evechat.network.SocketEvent
import com.autonomousone.messages.evechat.push.EveChatPush
import com.autonomousone.messages.evechat.push.EveChatPushRegistrar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The EveChat engine. One per process, alive independently of any screen.
 *
 * Sync model (local-first, like WhatsApp/Telegram):
 *  - The UI only ever reads Room; the network writes Room.
 *  - Live: socket events are applied to Room as they arrive.
 *  - Catch-up: every (re)connect, app foreground and network return re-reads the inbox and
 *    fetches each open thread's messages since the newest one cached, so nothing missed while
 *    offline is lost. Overlapping pages are deduplicated by server id.
 *  - Outbox: a message is written locally first and delivered by a single sequential worker
 *    with idempotent `client_message_id`, retried with backoff until the server accepts or
 *    permanently rejects it.
 */
class EveChatSession private constructor(private val app: Application) {

    enum class Connection { OFFLINE, CONNECTING, SYNCING, ONLINE }

    sealed interface AuthState {
        data object SignedOut : AuthState
        data class SignedIn(val email: String, val server: String) : AuthState
    }

    enum class InboxFilter { PENDING, MINE, OPEN, RESOLVED }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            EveChatLog.e("session", "uncaught in session scope", e)
        }
    )

    private val prefs = EveChatPreferences(app)
    private val db by lazy { EveChatDatabase.build(app) }
    private val dao get() = db.dao()
    private val api = EveChatApiClient { prefs.serverUrl }
    private val auth = EveChatAuth(prefs, api) { scope.launch { expireSession() } }
    private val notifier = EveChatNotifier(app)
    private val socket = EveChatSocket(
        scope = scope,
        endpoint = { if (prefs.hasSession) auth.accessToken()?.let { prefs.serverUrl to it } else null },
        refreshToken = { stale -> auth.refresh(stale) != null }
    )

    private val _auth = MutableStateFlow<AuthState>(currentAuth())
    val authState: StateFlow<AuthState> = _auth.asStateFlow()

    private val _me = MutableStateFlow(prefs.agentId.takeIf { it.isNotBlank() })
    val myAgentId: StateFlow<String?> = _me.asStateFlow()

    /** The signed-in agent's display name, shown in the inbox header so the account is never a mystery. */
    private val _meName = MutableStateFlow(prefs.agentName.takeIf { it.isNotBlank() })
    val myAgentName: StateFlow<String?> = _meName.asStateFlow()

    val push = EveChatPushRegistrar(
        context = app,
        prefs = prefs,
        api = api,
        auth = auth,
        scope = scope,
        signedIn = { _auth.value is AuthState.SignedIn },
        agentId = { _me.value }
    )
    val pushStatus: StateFlow<EveChatPushRegistrar.Status> get() = push.status

    private val syncing = MutableStateFlow(false)
    private val networkUp = MutableStateFlow(true)

    val connection: StateFlow<Connection> =
        combine(socket.state, syncing, networkUp) { s, sync, net ->
            when {
                !net -> Connection.OFFLINE
                s == EveChatSocket.State.CONNECTED && sync -> Connection.SYNCING
                s == EveChatSocket.State.CONNECTED -> Connection.ONLINE
                s == EveChatSocket.State.IDLE -> Connection.OFFLINE
                else -> Connection.CONNECTING
            }
        }.stateIn(scope, SharingStarted.Eagerly, Connection.OFFLINE)

    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Visitor profiles by conversation, exactly as the last detail fetch returned them.
     *
     * Server-owned and time-sensitive, so they are not written to Room: a stale cache would keep
     * showing a plan or an IP the server has already changed. The sheet says "Updated …" from
     * [EveChatVisitorSnapshot.fetchedAt] instead of pretending the data is live.
     */
    private val _visitors = MutableStateFlow<Map<String, EveChatVisitorSnapshot>>(emptyMap())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Volatile private var foreground = false
    @Volatile private var activeConversationId: String? = null
    private var startedActivities = 0
    private var backgroundStop: Job? = null
    private val catchUpMutex = Mutex()
    private val authMutex = Mutex()
    @Volatile private var catchUpAgain = false
    private val outboxMutex = Mutex()
    private var outboxRetry: Job? = null
    private val readJobs = ConcurrentHashMap<String, Job>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    /**
     * Plain agents receive live updates for the whole project but may only read their own and
     * unclaimed conversations; remembering refusals stops one 403 request per foreign update.
     */
    private val inaccessible = ConcurrentHashMap<String, Long>()
    private var installed = false

    // ── wiring ───────────────────────────────────────────────────────────

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        notifier.ensureChannels()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkUp.value = true
                    if (_auth.value is AuthState.SignedIn && foreground) {
                        EveChatLog.i("net", "network available")
                        socket.reconnectNow()
                        kickOutbox()
                    }
                }

                override fun onLost(network: Network) {
                    networkUp.value = cm.activeNetwork != null
                }
            })
        }.onFailure { EveChatLog.w("net", "network callback unavailable", it) }
        scope.launch { socket.events.collect { handle(it) } }
        scope.launch { expireTypingLoop() }
        scope.launch { push.status.collect { onPushStatusChanged() } }
        if (prefs.hasSession) push.ensureRegistered()
    }

    private fun onForeground() {
        foreground = true
        backgroundStop?.cancel()
        if (_auth.value !is AuthState.SignedIn) return
        socket.start()
        socket.reconnectNow()
        scope.launch { catchUp("foreground") }
        activeConversationId?.let { scheduleMarkRead(it) }
        push.ensureRegistered()
    }

    /** Keep the socket briefly so a quick app switch does not drop it; the worker covers the rest. */
    private fun onBackground() {
        foreground = false
        backgroundStop?.cancel()
        backgroundStop = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            if (!foreground) {
                EveChatLog.i("session", "background grace elapsed; socket paused")
                socket.stop()
            }
        }
    }

    // ── auth ─────────────────────────────────────────────────────────────

    suspend fun login(serverInput: String, email: String, password: String): ApiResult<Unit> {
        val server = when (val n = EveChatServerUrl.normalize(serverInput)) {
            is EveChatServerUrl.Result.Invalid ->
                return ApiResult.Err(ApiError(ApiError.Kind.CLIENT, serverMessage = "invalid server url"))
            is EveChatServerUrl.Result.Ok -> n.url
        }
        return when (val r = api.login(server, email.trim(), password)) {
            is ApiResult.Err -> {
                EveChatLog.w("auth", "login failed", null, "kind" to r.error.kind)
                r
            }
            is ApiResult.Ok -> {
                val stored = authMutex.withLock {
                    if (prefs.serverUrl != server || !prefs.agentEmail.equals(email.trim(), ignoreCase = true)) {
                        dao.clearAll()
                    }
                    prefs.serverUrl = server
                    prefs.agentEmail = email.trim()
                    auth.store(r.value)
                }
                if (!stored) {
                    return ApiResult.Err(ApiError(ApiError.Kind.CLIENT, serverMessage = "secure store unavailable"))
                }
                EveChatLog.i("auth", "signed in")
                _auth.value = currentAuth()
                refreshMe()
                push.onSignedIn()
                onPushStatusChanged()
                if (foreground) {
                    socket.start()
                    scope.launch { catchUp("login") }
                }
                ApiResult.Ok(Unit)
            }
        }
    }

    fun logout() {
        scope.launch { signOut("user") }
    }

    private suspend fun expireSession() {
        if (signOut("expired")) _errors.tryEmit(app.getString(com.autonomousone.messages.R.string.eve_session_expired))
    }

    /**
     * The state flips first so concurrent work (outbox, pushes, an expiry raised by a failing
     * request) sees a signed-out session immediately; cleanup then runs once under [authMutex].
     * @return false when another sign-out already handled it.
     */
    private suspend fun signOut(reason: String): Boolean = authMutex.withLock {
        if (_auth.value is AuthState.SignedOut && !prefs.hasSession) return@withLock false
        _auth.value = AuthState.SignedOut
        EveChatLog.i("auth", "signing out", "reason" to reason)
        socket.stop()
        outboxRetry?.cancel()
        EveChatSyncWorker.cancel(app)
        push.onSignedOut(accessToken = if (reason == "expired") null else auth.accessToken())
        auth.forget()
        prefs.clearSession()
        dao.clearAll()
        notifier.cancelAll()
        _me.value = null
        _meName.value = null
        _typing.value = emptyMap()
        _visitors.value = emptyMap()
        inaccessible.clear()
        true
    }

    private fun currentAuth(): AuthState =
        if (prefs.hasSession) AuthState.SignedIn(prefs.agentEmail, prefs.serverUrl) else AuthState.SignedOut

    val lastEmail: String get() = prefs.agentEmail
    val lastServer: String get() = prefs.serverUrl

    private suspend fun refreshMe() {
        val r = auth.authorized { api.me(it) }
        if (r is ApiResult.Ok) {
            prefs.agentId = r.value.id
            prefs.agentName = r.value.name
            _me.value = r.value.id
            _meName.value = r.value.name.takeIf { it.isNotBlank() }
        }
    }

    // ── reads for the UI ─────────────────────────────────────────────────

    fun observeInbox(filter: InboxFilter): Flow<List<EveChatConversation>> = when (filter) {
        InboxFilter.PENDING -> dao.observeByStatus(listOf(ConvStatus.PENDING))
        InboxFilter.OPEN -> dao.observeByStatus(ConvStatus.OPEN)
        InboxFilter.RESOLVED -> dao.observeByStatus(ConvStatus.DONE)
        InboxFilter.MINE -> _me.value?.let { dao.observeAssignedTo(it) } ?: flowOf(emptyList())
    }

    fun observeConversation(id: String) = dao.observeConversation(id)
    fun observeMessages(id: String) = dao.observeMessages(id)

    fun observeUnreadTotal(): Flow<Int> =
        combine(_auth, dao.observeUnreadTotal()) { a, n -> if (a is AuthState.SignedIn) n else 0 }

    fun observeTyping(conversationId: String): Flow<Boolean> = _typing.map { it.containsKey(conversationId) }

    fun observeVisitor(conversationId: String): Flow<EveChatVisitorSnapshot?> =
        _visitors.map { it[conversationId] }

    private fun rememberVisitor(conversationId: String, visitor: EveChatVisitor?) {
        if (visitor == null) return
        _visitors.value = _visitors.value + (conversationId to EveChatVisitorSnapshot(visitor))
    }

    // ── sync ─────────────────────────────────────────────────────────────

    /**
     * Single flight. A trigger that arrives while a run is in progress (typically the socket
     * opening during a foreground sync) schedules exactly one more run, because the running one
     * may already have read the server before that trigger's events were missed.
     */
    suspend fun catchUp(trigger: String) {
        if (_auth.value !is AuthState.SignedIn) return
        if (!catchUpMutex.tryLock()) {
            catchUpAgain = true
            return
        }
        try {
            do {
                catchUpAgain = false
                runCatchUp(trigger)
            } while (catchUpAgain && _auth.value is AuthState.SignedIn)
        } finally {
            catchUpMutex.unlock()
        }
    }

    private suspend fun runCatchUp(trigger: String) {
        val started = System.currentTimeMillis()
        syncing.value = true
        try {
            if (_me.value == null) refreshMe()
            val inboxOk = syncInbox()
            val threads = buildSet {
                activeConversationId?.let(::add)
                dao.unreadConversations().forEach { add(it.id) }
            }
            threads.forEach { syncThread(it) }
            flushOutbox()
            EveChatLog.i(
                "sync", "catch-up done", "trigger" to trigger, "inboxOk" to inboxOk,
                "threads" to threads.size, "ms" to System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            EveChatLog.e("sync", "catch-up failed", e, "trigger" to trigger)
        } finally {
            syncing.value = false
        }
    }

    /**
     * Re-reads every open conversation. Only after the full open list is known can a locally
     * open conversation that is missing from it be re-checked individually (it was resolved,
     * reassigned away or deleted while we were offline).
     */
    private suspend fun syncInbox(): Boolean {
        val seen = HashSet<String>()
        var offset = 0
        var total: Int
        do {
            val page = when (val r = auth.authorized { api.conversations(it, null, PAGE, offset) }) {
                is ApiResult.Err -> return false
                is ApiResult.Ok -> r.value
            }
            dao.mergeConversations(page.items)
            page.items.mapTo(seen) { it.id }
            total = page.total
            offset += page.items.size
        } while (page.items.size == PAGE && offset < total && offset < MAX_INBOX)

        if (offset >= total) {
            dao.openConversations().filter { it.id !in seen }.take(MAX_RECHECK).forEach { refreshConversation(it.id) }
        }
        return true
    }

    suspend fun refreshResolved() {
        when (val r = auth.authorized { api.conversations(it, ConvStatus.RESOLVED, PAGE, 0) }) {
            is ApiResult.Ok -> dao.mergeConversations(r.value.items)
            is ApiResult.Err -> _errors.tryEmit(r.error.message(app))
        }
    }

    private fun isInaccessible(id: String): Boolean {
        val at = inaccessible[id] ?: return false
        if (System.currentTimeMillis() - at < INACCESSIBLE_TTL_MS) return true
        inaccessible.remove(id)
        return false
    }

    private suspend fun forgetInaccessible(id: String, kind: ApiError.Kind) {
        if (kind != ApiError.Kind.NOT_FOUND && kind != ApiError.Kind.FORBIDDEN) return
        inaccessible[id] = System.currentTimeMillis()
        EveChatLog.i("sync", "conversation not accessible", "conv" to EveChatLog.id(id), "kind" to kind)
        dao.deleteConversation(id)
        notifier.cancel(id)
    }

    private suspend fun refreshConversation(id: String): Boolean {
        if (isInaccessible(id) || !fetching.add(id)) return false
        try {
            return when (val r = auth.authorized { api.conversation(it, id) }) {
                is ApiResult.Ok -> {
                    dao.mergeConversations(listOf(r.value.conversation))
                    dao.upsertServerMessages(r.value.messages)
                    rememberVisitor(id, r.value.visitor)
                    r.value.messages.lastOrNull()?.let(::touchPreview)
                    true
                }
                is ApiResult.Err -> {
                    forgetInaccessible(id, r.error.kind)
                    false
                }
            }
        } finally {
            fetching.remove(id)
        }
    }

    /**
     * Brings one thread up to date. The detail endpoint returns the newest window; if the cache
     * ends before that window starts, the gap is filled by paging forward from the cache.
     */
    suspend fun syncThread(id: String): Boolean {
        val cachedNewest = dao.newestSentAt(id)
        val detail = when (val r = auth.authorized { api.conversation(it, id) }) {
            is ApiResult.Ok -> r.value
            is ApiResult.Err -> {
                forgetInaccessible(id, r.error.kind)
                return false
            }
        }
        dao.mergeConversations(listOf(detail.conversation))
        dao.upsertServerMessages(detail.messages)
        rememberVisitor(id, detail.visitor)
        val windowStart = detail.messages.firstOrNull()?.createdAt
        if (cachedNewest == null) {
            dao.setHistoryComplete(id, detail.messages.size < DETAIL_WINDOW)
        } else if (windowStart != null && cachedNewest < windowStart) {
            pageForward(id, from = cachedNewest, until = windowStart)
        }
        detail.messages.lastOrNull()?.let(::touchPreview)
        if (id == activeConversationId && foreground) scheduleMarkRead(id)
        return true
    }

    /** Re-reads the visitor profile; used by the profile sheet's refresh action. */
    suspend fun refreshProfile(id: String): Boolean {
        if (_auth.value !is AuthState.SignedIn || isInaccessible(id)) return false
        return refreshConversation(id)
    }

    /** History older than the detail window, loaded from the beginning of the thread. */
    suspend fun loadOlder(id: String) {
        val conv = dao.conversation(id) ?: return
        if (conv.historyComplete) return
        val oldest = dao.oldestSentAt(id) ?: return
        if (pageForward(id, from = 0, until = oldest)) dao.setHistoryComplete(id, true)
    }

    /** @return true when the range was covered completely. */
    private suspend fun pageForward(id: String, from: Long, until: Long): Boolean {
        var since = from
        repeat(MAX_PAGES) {
            val page = when (val r = auth.authorized { api.messages(it, id, (since - OVERLAP_MS).coerceAtLeast(0), MSG_PAGE) }) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> return false
            }
            if (page.isEmpty()) return true
            val known = dao.countExisting(page.map { it.id })
            dao.upsertServerMessages(page)
            val newest = page.last().createdAt
            if (page.size < MSG_PAGE || newest >= until) return true
            if (known == page.size && newest <= since) return true
            since = newest
        }
        EveChatLog.w("sync", "history paging capped", null, "conv" to EveChatLog.id(id))
        return false
    }

    private fun touchPreview(m: EveChatMessage) {
        if (m.isInternal) return
        scope.launch { dao.updatePreview(m.conversationId, m.content.take(200), m.senderType, m.createdAt) }
    }

    // ── live events ──────────────────────────────────────────────────────

    private suspend fun handle(event: SocketEvent) {
        when (event) {
            SocketEvent.Connected -> scope.launch { catchUp("connected") }
            is SocketEvent.Hello -> Unit
            is SocketEvent.ConversationCreated -> {
                dao.mergeConversations(listOf(event.conversation))
                event.firstMessage?.let {
                    dao.upsertServerMessages(listOf(it))
                    touchPreview(it)
                }
                if (event.conversation.id != activeConversationId || !foreground) {
                    notifier.newConversation(event.conversation, event.firstMessage)
                }
            }
            is SocketEvent.ConversationChanged -> {
                val p = event.patch
                if (p.assignedAgentId == _me.value || p.status == ConvStatus.PENDING) inaccessible.remove(p.conversationId)
                if (isInaccessible(p.conversationId)) return
                val active = event.patch.conversationId == activeConversationId && foreground
                if (!dao.applyPatch(event.patch, active)) refreshConversation(event.patch.conversationId)
                if (event.patch.status != null && !ConvStatus.isOpen(event.patch.status)) {
                    notifier.cancel(event.patch.conversationId)
                }
            }
            is SocketEvent.MessageArrived -> onMessage(event.message)
            is SocketEvent.NoteArrived -> dao.upsertServerMessages(listOf(event.message))
            is SocketEvent.MessageAccepted -> Unit
            is SocketEvent.Typing -> _typing.value =
                if (event.typing) _typing.value + (event.conversationId to System.currentTimeMillis())
                else _typing.value - event.conversationId
            is SocketEvent.ServerError -> EveChatLog.w("socket", "server error frame", null, "code" to event.code)
            SocketEvent.Ping -> Unit
        }
    }

    private suspend fun onMessage(m: EveChatMessage) {
        inaccessible.remove(m.conversationId)
        if (dao.conversation(m.conversationId) == null) refreshConversation(m.conversationId)
        dao.upsertServerMessages(listOf(m))
        touchPreview(m)
        if (m.senderType != SenderType.VISITOR) return
        _typing.value = _typing.value - m.conversationId
        if (m.conversationId == activeConversationId && foreground) {
            scheduleMarkRead(m.conversationId)
        } else {
            dao.incrementUnread(m.conversationId)
            dao.conversation(m.conversationId)?.let { notifier.message(it, m, isMine(it)) }
        }
    }

    private fun isMine(c: EveChatConversation): Boolean = c.assignedAgentId == null || c.assignedAgentId == _me.value

    private suspend fun expireTypingLoop() {
        while (true) {
            delay(2_000)
            val now = System.currentTimeMillis()
            val current = _typing.value
            if (current.values.any { now - it > TYPING_TTL_MS }) {
                _typing.value = current.filterValues { now - it <= TYPING_TTL_MS }
            }
        }
    }

    // ── conversation screen ──────────────────────────────────────────────

    fun setActiveConversation(id: String?) {
        activeConversationId = id
        if (id != null) {
            notifier.cancel(id)
            scheduleMarkRead(id)
        }
    }

    private fun scheduleMarkRead(id: String) {
        readJobs.remove(id)?.cancel()
        readJobs[id] = scope.launch {
            dao.clearUnread(id)
            delay(READ_DEBOUNCE_MS)
            val upTo = dao.newestSentId(id)
            val r = auth.authorized { api.markRead(it, id, upTo) }
            if (r is ApiResult.Err) EveChatLog.d("sync", "mark read deferred", "kind" to r.error.kind)
            readJobs.remove(id)
        }
    }

    fun setTyping(conversationId: String, typing: Boolean) {
        socket.send(
            EveChatEventParser.command(
                if (typing) "typing.start" else "typing.stop",
                JSONObject().put("conversation_id", conversationId)
            )
        )
    }

    // ── outbox ───────────────────────────────────────────────────────────

    fun send(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            val clientId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.upsertMessageRows(
                listOf(
                    EveChatMessage(
                        id = EveChatMessage.LOCAL_PREFIX + clientId,
                        conversationId = conversationId,
                        clientMessageId = clientId,
                        senderType = SenderType.AGENT,
                        senderId = _me.value,
                        senderName = prefs.agentName.takeIf { it.isNotBlank() },
                        content = body,
                        createdAt = now,
                        sendState = SendState.PENDING
                    )
                )
            )
            dao.updatePreview(conversationId, body.take(200), SenderType.AGENT, now)
            dao.clearUnread(conversationId)
            EveChatLog.i("outbox", "queued", "conv" to EveChatLog.id(conversationId), "client" to EveChatLog.id(clientId))
            flushOutbox()
        }
    }

    fun retry(messageId: String) {
        scope.launch {
            dao.updateSendState(messageId, SendState.PENDING, null, 0)
            flushOutbox()
        }
    }

    fun discard(messageId: String) {
        scope.launch { dao.deleteMessage(messageId) }
    }

    private fun kickOutbox() {
        scope.launch { flushOutbox() }
    }

    /** Sequential, so messages reach the visitor in the order they were written. */
    private suspend fun flushOutbox() {
        if (_auth.value !is AuthState.SignedIn) return
        outboxMutex.withLock {
            for (m in dao.outbox()) {
                val clientId = m.clientMessageId ?: continue
                when (val r = auth.authorized { api.send(it, m.conversationId, m.content, clientId) }) {
                    is ApiResult.Ok -> {
                        dao.upsertServerMessages(listOf(r.value.message))
                        EveChatLog.i("outbox", "delivered", "client" to EveChatLog.id(clientId), "duplicate" to r.value.duplicate)
                    }
                    is ApiResult.Err -> if (r.error.retryable) {
                        dao.updateSendState(m.id, SendState.PENDING, r.error.message(app), 1)
                        scheduleOutboxRetry(m.attempts + 1)
                        return
                    } else {
                        dao.updateSendState(m.id, SendState.FAILED, r.error.message(app), 1)
                        EveChatLog.w("outbox", "rejected", null, "client" to EveChatLog.id(clientId), "kind" to r.error.kind)
                    }
                }
            }
        }
    }

    private fun scheduleOutboxRetry(attempt: Int) {
        outboxRetry?.cancel()
        outboxRetry = scope.launch {
            delay(ReconnectBackoff.delayFor(attempt.coerceAtMost(6)))
            flushOutbox()
        }
    }

    // ── conversation actions ─────────────────────────────────────────────

    suspend fun claim(id: String): ApiResult<Unit> = action(id, "claim") { t -> api.claim(t, id) }
    suspend fun resolve(id: String): ApiResult<Unit> = action(id, "resolve") { t -> api.resolve(t, id) }
    suspend fun reopen(id: String): ApiResult<Unit> = action(id, "reopen") { t -> api.reopen(t, id) }

    private suspend fun action(id: String, name: String, call: suspend (String) -> ApiResult<Unit>): ApiResult<Unit> {
        val r = auth.authorized(call)
        EveChatLog.i("action", name, "conv" to EveChatLog.id(id), "ok" to (r is ApiResult.Ok))
        refreshConversation(id)
        return r
    }

    // ── background ───────────────────────────────────────────────────────

    /** Periodic sync while the app is not in front; posts notifications for visitor messages. */
    suspend fun backgroundSync(): Boolean {
        if (_auth.value !is AuthState.SignedIn || foreground) return true
        val before = dao.openConversations().associate { it.id to (dao.newestSentAt(it.id) ?: -1L) }
        if (!syncInbox()) return false
        for (c in dao.unreadConversations()) {
            val previousNewest = before[c.id]
            syncThread(c.id)
            val fresh = when (previousNewest) {
                null, -1L -> listOfNotNull(dao.visitorMessagesAfter(c.id, 0).lastOrNull())
                else -> dao.visitorMessagesAfter(c.id, previousNewest)
            }
            val conv = dao.conversation(c.id) ?: continue
            fresh.takeLast(5).forEach { notifier.message(conv, it, isMine(conv)) }
        }
        flushOutbox()
        return true
    }

    // ── push ─────────────────────────────────────────────────────────────

    /** Polling is the safety net; with working push it can run far less often. */
    fun onPushStatusChanged() {
        if (_auth.value !is AuthState.SignedIn) return
        val minutes = if (push.status.value == EveChatPushRegistrar.Status.ACTIVE) 30L else 15L
        EveChatSyncWorker.schedule(app, minutes)
    }

    fun requestBackgroundSync() {
        if (_auth.value is AuthState.SignedIn) EveChatSyncWorker.runOnce(app)
    }

    /**
     * A push only says "something changed here". The conversation is re-read from the server and
     * notifications are built from that; a message that was already in the cache before the
     * fetch was delivered by the socket or an earlier sync and is not announced twice.
     */
    suspend fun handlePush(p: EveChatPush) {
        if (_auth.value !is AuthState.SignedIn) {
            EveChatLog.d("push", "dropped: signed out")
            return
        }
        val me = _me.value ?: prefs.agentId.takeIf { it.isNotBlank() }
        if (p.agentId != null && me != null && p.agentId != me) {
            EveChatLog.w("push", "dropped: addressed to another agent; re-registering")
            prefs.pushRecord = null
            push.ensureRegistered()
            return
        }
        push.recordDelivery()

        if (foreground && socket.state.value == EveChatSocket.State.CONNECTED) {
            scope.launch { catchUp("push") }
            return
        }
        val conversationId = p.conversationId
        if (p.type == EveChatPush.Type.SYNC || conversationId == null) {
            if (foreground) catchUp("push") else backgroundSync()
            return
        }
        inaccessible.remove(conversationId)
        when (p.type) {
            EveChatPush.Type.CONVERSATION_UPDATED -> {
                refreshConversation(conversationId)
                val c = dao.conversation(conversationId)
                if (c == null || !ConvStatus.isOpen(c.status) || !isMine(c)) notifier.cancel(conversationId)
            }
            EveChatPush.Type.MESSAGE, EveChatPush.Type.NEW_CONVERSATION -> {
                val knownBefore = p.messageId?.let { dao.message(it) != null } ?: false
                val newestBefore = dao.newestSentAt(conversationId)
                if (!syncThread(conversationId)) {
                    if (!knownBefore) notifier.fallback(p)
                    requestBackgroundSync()
                    return
                }
                kickOutbox()
                if (knownBefore) return
                val conv = dao.conversation(conversationId) ?: return
                if (conv.unreadAgent == 0 || !ConvStatus.isOpen(conv.status)) return
                val fresh = if (newestBefore == null) {
                    listOfNotNull(dao.visitorMessagesAfter(conversationId, 0).lastOrNull())
                } else {
                    dao.visitorMessagesAfter(conversationId, newestBefore)
                }
                if (p.type == EveChatPush.Type.NEW_CONVERSATION && newestBefore == null) {
                    notifier.newConversation(conv, fresh.lastOrNull())
                } else {
                    fresh.takeLast(5).forEach { notifier.message(conv, it, isMine(conv)) }
                }
            }
            EveChatPush.Type.SYNC -> Unit
        }
    }

    /** The push could not be handled within the FCM time budget: show what it carried, sync later. */
    fun onPushTimedOut(p: EveChatPush) {
        if (_auth.value !is AuthState.SignedIn) return
        if (p.type == EveChatPush.Type.MESSAGE || p.type == EveChatPush.Type.NEW_CONVERSATION) notifier.fallback(p)
        requestBackgroundSync()
    }

    companion object {
        private const val PAGE = 100
        private const val MAX_INBOX = 500
        private const val MAX_RECHECK = 20
        private const val DETAIL_WINDOW = 100
        private const val MSG_PAGE = 200
        private const val MAX_PAGES = 25
        private const val OVERLAP_MS = 1_000L
        private const val READ_DEBOUNCE_MS = 400L
        private const val TYPING_TTL_MS = 6_000L
        private const val BACKGROUND_GRACE_MS = 120_000L
        private const val INACCESSIBLE_TTL_MS = 10 * 60_000L

        @Volatile
        private var instance: EveChatSession? = null

        fun get(context: Context): EveChatSession =
            instance ?: synchronized(this) {
                instance ?: EveChatSession(context.applicationContext as Application).also { instance = it }
            }
    }
}
