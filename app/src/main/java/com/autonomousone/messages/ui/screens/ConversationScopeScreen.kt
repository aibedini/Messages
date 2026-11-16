package com.autonomousone.messages.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.viewmodel.ConversationScopeEditor
import com.autonomousone.messages.viewmodel.ConversationScopeViewModel
import com.autonomousone.messages.viewmodel.ScopeConversationRow
import com.autonomousone.messages.viewmodel.ScopeEditorNotice
import com.autonomousone.messages.viewmodel.ScopePauseNotice
import com.autonomousone.messages.viewmodel.ScopePauseState
import java.text.DateFormat
import java.util.Date

/**
 * LDV-004 PR-E (slice 2b) — "AI conversation access": which conversations one linked
 * `AI_AGENT` device may read.
 *
 * Reached from the AI device row on [LinkedDevicesScreen] ("مدیریت دامنهٔ مکالمات"). The screen
 * is deliberately dumb: every rule (which conversations may be offered, what "all
 * conversations" means, when Save is allowed) lives in the pure
 * [ConversationScopeEditor], and the publication decision lives in
 * [com.autonomousone.messages.security.ConversationGrantCoordinator]. This file only renders
 * the editor and forwards taps, so a Composable can never invent a broader scope than the
 * policy allows.
 *
 * ## Language
 *
 * Persian labels, matching the Persian action that opens the screen and the Persian build the
 * linked-device flow is used in (`res/values-fa`). They are collected in
 * [ConversationScopeTexts] so migrating this one surface to `values`/`values-fa` resources is a
 * single mechanical edit. Layout direction is NOT forced: on a Persian device the app is RTL
 * and the labels render RTL; on any other locale, the Persian strings still shape correctly
 * through bidi while the surrounding layout keeps the app's own direction.
 *
 * ## What this screen cannot do (by design)
 *
 *  - A sensitive or `LOCAL_ONLY` conversation is never listed (design §4.6/§5.8); the count of
 *    hidden conversations is shown so the operator is not surprised by a shorter list.
 *  - An empty cut-down list is not a scope: Save stays disabled (the policy refuses
 *    `Conversations(emptySet())`). Withdrawing everything is a different decision, the
 *    explicit master pause below (slice 2d) — never the absence of checkmarks.
 *  - There is no "publish anyway" path: a failure leaves the device on its last approved scope.
 *
 * ## Master pause (slice 2d)
 *
 * «توقف دسترسی AI به همهٔ مکالمات» is the only action that WITHDRAWS access. It is a
 * danger-styled, full-width action at the bottom of the list, disabled while a decision is in
 * flight and once the device is already paused. Tapping it opens a Persian confirmation dialog
 * that says plainly that every grant is revoked and the AI can no longer read future messages;
 * only its confirm button calls [ConversationScopeViewModel.pauseAll]. A successful pause
 * replaces the editor with the revoked (empty) scope, so the list re-renders with nothing
 * checked and Save disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScopeScreen(
    navController: NavController,
    deviceId: String,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ConversationScopeViewModel = viewModel(
        key = "conversation_scope_$deviceId",
        factory = ConversationScopeViewModel.Factory(application, deviceId),
    )
    val state by viewModel.uiState.collectAsState()
    // The pause is NEVER silent: this flag only opens the explicit Persian danger dialog, and
    // the decision is submitted from its confirm button (slice 2d).
    var pauseDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ConversationScopeTexts.TITLE) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = ConversationScopeTexts.BACK,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    ) { padding ->
        when (val current = state) {
            ConversationScopeViewModel.UiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        ConversationScopeTexts.LOADING,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            is ConversationScopeViewModel.UiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    errorText(current.reason),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::load) {
                        Text(ConversationScopeTexts.RETRY)
                    }
                    OutlinedButton(onClick = { navController.popBackStack() }) {
                        Text(ConversationScopeTexts.CANCEL)
                    }
                }
            }

            is ConversationScopeViewModel.UiState.Content -> {
                ConversationScopeContent(
                    deviceName = current.deviceName,
                    editor = current.editor,
                    onToggle = viewModel::toggle,
                    onAllConversations = viewModel::setAllConversations,
                    onSave = viewModel::save,
                    onCancel = { navController.popBackStack() },
                    onDismissNotice = viewModel::dismissNotice,
                    onRequestPause = { pauseDialog = true },
                    onDismissPauseNotice = viewModel::dismissPauseNotice,
                    modifier = Modifier.padding(padding),
                )
                if (pauseDialog) {
                    AlertDialog(
                        onDismissRequest = { pauseDialog = false },
                        title = { Text(ConversationScopeTexts.PAUSE_DIALOG_TITLE) },
                        text = { Text(ConversationScopeTexts.PAUSE_DIALOG_BODY) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pauseDialog = false
                                    viewModel.pauseAll()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text(ConversationScopeTexts.PAUSE_DIALOG_CONFIRM) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pauseDialog = false }) {
                                Text(ConversationScopeTexts.CANCEL)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationScopeContent(
    deviceName: String,
    editor: ConversationScopeEditor,
    onToggle: (String, Boolean) -> Unit,
    onAllConversations: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDismissNotice: () -> Unit,
    onRequestPause: () -> Unit,
    onDismissPauseNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    ConversationScopeTexts.EXPLAINER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    ConversationScopeTexts.deviceLine(deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = editor.accountWide,
                    onCheckedChange = { onAllConversations(it) },
                    enabled = !editor.busy,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) {
                    Text(
                        ConversationScopeTexts.ALL_CONVERSATIONS,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        ConversationScopeTexts.ALL_CONVERSATIONS_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ConversationScopeTexts.summary(editor.checkedVisibleCount, editor.totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (editor.hiddenSensitiveCount > 0) {
                    Text(
                        ConversationScopeTexts.hiddenSensitive(editor.hiddenSensitiveCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editor.emptySelection) {
                    Text(
                        ConversationScopeTexts.EMPTY_SELECTION_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (editor.rows.isEmpty()) {
            item {
                Text(
                    ConversationScopeTexts.NO_CONVERSATIONS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(editor.rows, key = { it.conversationId }) { row ->
            ConversationScopeRowItem(
                row = row,
                // The account grant covers every conversation, so the per-row boxes are shown
                // checked but not editable: a box that could be un-checked while the account
                // grant still covers the conversation would be a lie.
                checked = editor.isChecked(row.conversationId),
                enabled = !editor.accountWide && !editor.busy,
                onCheckedChange = { enabled -> onToggle(row.conversationId, enabled) },
            )
        }

        editor.notice?.let { notice ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        noticeText(notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice == ScopeEditorNotice.SAVED ||
                            notice == ScopeEditorNotice.UP_TO_DATE
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissNotice) {
                        Text(ConversationScopeTexts.DISMISS)
                    }
                }
            }
        }

        // ── The master pause (slice 2d) ───────────────────────────────────────
        // The only action on this screen that WITHDRAWS access. It is separated from Save,
        // coloured as a danger action, and reachable only through the explicit confirmation
        // dialog in the parent composable. After a success the editor is the revoked (empty)
        // scope, so every row renders unchecked and Save is disabled.
        item {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    ConversationScopeTexts.PAUSE_SECTION_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ConversationScopeTexts.PAUSE_EXPLAINER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onRequestPause,
                    enabled = editor.pauseActionEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (editor.pause == ScopePauseState.Pausing) {
                            ConversationScopeTexts.PAUSE_IN_PROGRESS
                        } else {
                            ConversationScopeTexts.PAUSE_ACTION
                        }
                    )
                }
                if (editor.pause == ScopePauseState.Paused) {
                    Text(
                        ConversationScopeTexts.PAUSE_ACTIVE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                editor.pauseNotice?.let { notice ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pauseNoticeText(notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notice == ScopePauseNotice.PAUSED ||
                                notice == ScopePauseNotice.ALREADY_PAUSED ||
                                notice == ScopePauseNotice.NOTHING_GRANTED
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissPauseNotice) {
                            Text(ConversationScopeTexts.DISMISS)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(ConversationScopeTexts.CANCEL)
                }
                Button(
                    onClick = onSave,
                    enabled = editor.canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (editor.saving) {
                            ConversationScopeTexts.SAVING
                        } else {
                            ConversationScopeTexts.SAVE
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationScopeRowItem(
    row: ScopeConversationRow,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatLastActivity(row.lastActivity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatLastActivity(timestamp: Long): String =
    if (timestamp <= 0L) "" else DateFormat.getDateTimeInstance().format(Date(timestamp))

private fun errorText(reason: ConversationScopeViewModel.LoadError): String = when (reason) {
    ConversationScopeViewModel.LoadError.DEVICE_NOT_FOUND -> ConversationScopeTexts.ERROR_NOT_FOUND
    ConversationScopeViewModel.LoadError.NOT_AN_AI_DEVICE -> ConversationScopeTexts.ERROR_NOT_AI
    ConversationScopeViewModel.LoadError.DEVICE_NOT_TRUSTED -> ConversationScopeTexts.ERROR_NOT_TRUSTED
    ConversationScopeViewModel.LoadError.READ_FAILED -> ConversationScopeTexts.ERROR_READ
}

private fun noticeText(notice: ScopeEditorNotice): String = when (notice) {
    ScopeEditorNotice.SAVED -> ConversationScopeTexts.NOTICE_SAVED
    ScopeEditorNotice.UP_TO_DATE -> ConversationScopeTexts.NOTICE_UP_TO_DATE
    ScopeEditorNotice.EMPTY_SELECTION -> ConversationScopeTexts.NOTICE_EMPTY
    ScopeEditorNotice.REJECTED_UNSAFE -> ConversationScopeTexts.NOTICE_UNSAFE
    ScopeEditorNotice.FAILED_KEY -> ConversationScopeTexts.NOTICE_NO_KEY
    ScopeEditorNotice.FAILED_EPOCH -> ConversationScopeTexts.NOTICE_NO_EPOCH
    ScopeEditorNotice.FAILED_OUTBOX -> ConversationScopeTexts.NOTICE_OUTBOX
    ScopeEditorNotice.FAILED_PERSIST -> ConversationScopeTexts.NOTICE_PERSIST
    ScopeEditorNotice.FAILED_OTHER -> ConversationScopeTexts.NOTICE_OTHER
}

private fun pauseNoticeText(notice: ScopePauseNotice): String = when (notice) {
    ScopePauseNotice.PAUSED -> ConversationScopeTexts.NOTICE_PAUSED
    ScopePauseNotice.ALREADY_PAUSED -> ConversationScopeTexts.NOTICE_ALREADY_PAUSED
    ScopePauseNotice.NOTHING_GRANTED -> ConversationScopeTexts.NOTICE_NOTHING_GRANTED
    ScopePauseNotice.FAILED_OUTBOX -> ConversationScopeTexts.NOTICE_PAUSE_OUTBOX
    ScopePauseNotice.FAILED_PERSIST -> ConversationScopeTexts.NOTICE_PAUSE_PERSIST
    ScopePauseNotice.FAILED_OTHER -> ConversationScopeTexts.NOTICE_PAUSE_OTHER
}

/**
 * Every user-facing word this screen says, in one place.
 *
 * Persian, because the linked-device flow this screen belongs to is Persian-facing (the action
 * that opens it is Persian too) — and because the single-source object is what makes the move
 * to `res/values` + `res/values-fa` a one-file change instead of a sweep through the
 * Composable. Nothing here is a security decision.
 */
internal object ConversationScopeTexts {
    const val TITLE = "مدیریت دامنهٔ مکالمات"
    const val BACK = "بازگشت"
    const val LOADING = "در حال بارگذاری…"
    const val RETRY = "تلاش دوباره"
    const val SAVE = "ذخیره"
    const val SAVING = "در حال ذخیره…"
    const val CANCEL = "انصراف"
    const val DISMISS = "بستن"

    const val EXPLAINER =
        "فقط مکالماتی که اینجا انتخاب می‌کنید برای این دستگاه هوش مصنوعی قابل خواندن است. " +
            "مکالمات کد ورود، بانکی و «فقط روی همین گوشی» هرگز در این فهرست نمی‌آیند و ارسال نمی‌شوند."

    const val ALL_CONVERSATIONS = "همهٔ مکالمات"
    const val ALL_CONVERSATIONS_HINT =
        "دسترسی به همهٔ مکالماتِ مجاز. گزینه‌های پایین در این حالت قابل تغییر نیستند."

    const val NO_CONVERSATIONS = "مکالمه‌ای برای نمایش نیست."
    const val EMPTY_SELECTION_HINT = "حداقل یک مکالمه انتخاب کنید یا «همهٔ مکالمات» را بزنید."

    const val ERROR_NOT_FOUND = "دستگاه پیدا نشد."
    const val ERROR_NOT_AI = "این دستگاه از نوع هوش مصنوعی نیست."
    const val ERROR_NOT_TRUSTED = "دستگاه فعال یا معتبر نیست."
    const val ERROR_READ = "خواندن مکالمات ناموفق بود."

    const val NOTICE_SAVED = "دامنهٔ مکالمات ذخیره و در صف ارسال قرار گرفت."
    const val NOTICE_UP_TO_DATE = "تغییری برای ارسال نبود."
    const val NOTICE_EMPTY = "حداقل یک مکالمه انتخاب کنید."
    const val NOTICE_UNSAFE = "مکالمهٔ انتخاب‌شده حساس است و قابل واگذاری نیست."
    const val NOTICE_NO_KEY = "کلید مکالمه آماده نیست؛ کمی بعد دوباره تلاش کنید."
    const val NOTICE_NO_EPOCH = "کلید حساب هنوز روی این گوشی ساخته نشده است."
    const val NOTICE_OUTBOX = "ارسال در صف قرار نگرفت؛ دامنه تغییری نکرد."
    const val NOTICE_PERSIST = "ذخیرهٔ دامنه ناموفق بود؛ دامنه تغییری نکرد."
    const val NOTICE_OTHER = "خطای پیش‌بینی‌نشده؛ دامنه تغییری نکرد."

    // ── Master pause (slice 2d) ─────────────────────────────────────────────

    const val PAUSE_SECTION_TITLE = "توقف دسترسی"
    const val PAUSE_EXPLAINER =
        "این تنها کاری است که دسترسی این دستگاه را می‌گیرد، نه می‌دهد. " +
            "با توقف، همهٔ مجوزهای خواندنِ فعلی باطل می‌شوند. برای بازگرداندن دسترسی، " +
            "دوباره مکالمه‌ها را انتخاب و ذخیره کنید."

    /** The required action label: the master pause of every conversation. */
    const val PAUSE_ACTION = "توقف دسترسی AI به همهٔ مکالمات"
    const val PAUSE_IN_PROGRESS = "در حال توقف دسترسی…"
    const val PAUSE_ACTIVE = "دسترسی AI به مکالمات متوقف است."

    const val PAUSE_DIALOG_TITLE = "توقف دسترسی AI به همهٔ مکالمات؟"
    const val PAUSE_DIALOG_BODY =
        "با تأیید، همهٔ مجوزهایی که به این دستگاه هوش مصنوعی داده شده باطل می‌شوند " +
            "(همهٔ مجوزهای مکالمه و مجوز حساب) و از این پس هوش مصنوعی نمی‌تواند پیام‌های " +
            "جدید را بخواند. پیام‌هایی که پیش از این خوانده شده‌اند روی آن دستگاه باقی " +
            "می‌مانند و پاک نمی‌شوند. این کار فقط با انتخاب دوبارهٔ مکالمه‌ها و ذخیره " +
            "برگشت‌پذیر است."
    const val PAUSE_DIALOG_CONFIRM = "بله، دسترسی را متوقف کن"

    const val NOTICE_PAUSED = "دسترسی AI به همهٔ مکالمات متوقف شد و مجوزها باطل شدند."
    const val NOTICE_ALREADY_PAUSED = "دسترسی AI از قبل متوقف بود."
    const val NOTICE_NOTHING_GRANTED = "هیچ مجوز فعالی برای توقف نبود."
    const val NOTICE_PAUSE_OUTBOX = "دستور توقف ارسال نشد؛ دسترسی متوقف نشد."
    const val NOTICE_PAUSE_PERSIST = "ثبت توقف ناموفق بود؛ دسترسی متوقف نشد."
    const val NOTICE_PAUSE_OTHER = "خطای پیش‌بینی‌نشده در توقف دسترسی؛ دوباره تلاش کنید."

    fun deviceLine(name: String): String = "دستگاه: $name"

    fun summary(selected: Int, total: Int): String =
        "$selected از $total مکالمه انتخاب شده · از این پس"

    fun hiddenSensitive(count: Int): String =
        "$count مکالمهٔ حساس پنهان شده است"
}
