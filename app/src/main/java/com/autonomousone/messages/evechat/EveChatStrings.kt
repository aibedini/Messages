package com.autonomousone.messages.evechat

import android.content.Context
import androidx.annotation.StringRes
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.push.EveChatPushRegistrar

/**
 * Every user-facing word the support feature says, resolved through the app's own resources.
 *
 * Nothing here may be a literal: the support screens run on phones whose locale is English,
 * Persian or anything else, and a hard-coded Persian word is exactly the bug this file exists
 * to prevent (the app used to paint "پشتیبانی" on an English build).
 */

@StringRes
fun ApiError.Kind.messageRes(): Int = when (this) {
    ApiError.Kind.NETWORK -> R.string.eve_err_network
    ApiError.Kind.TIMEOUT -> R.string.eve_err_timeout
    ApiError.Kind.UNAUTHORIZED -> R.string.eve_err_unauthorized
    ApiError.Kind.FORBIDDEN -> R.string.eve_err_forbidden
    ApiError.Kind.NOT_FOUND -> R.string.eve_err_not_found
    ApiError.Kind.CONFLICT -> R.string.eve_err_conflict
    ApiError.Kind.RATE_LIMITED -> R.string.eve_err_rate_limited
    ApiError.Kind.SERVER -> R.string.eve_err_server
    ApiError.Kind.CLIENT -> R.string.eve_err_client
    ApiError.Kind.PARSE -> R.string.eve_err_parse
}

/**
 * A sentence for the UI.
 *
 * The server's own `message` is deliberately not shown: EveChat answers in Persian regardless
 * of the caller's language, so an English build would print Persian again. It stays in the logs.
 */
fun ApiError.message(context: Context): String = context.getString(kind.messageRes())

@StringRes
fun EveChatSession.Connection.messageRes(): Int = when (this) {
    EveChatSession.Connection.ONLINE -> R.string.eve_conn_online
    EveChatSession.Connection.SYNCING -> R.string.eve_conn_syncing
    EveChatSession.Connection.CONNECTING -> R.string.eve_conn_connecting
    EveChatSession.Connection.OFFLINE -> R.string.eve_conn_offline
}

@StringRes
fun EveChatPushRegistrar.Status.messageRes(): Int = when (this) {
    EveChatPushRegistrar.Status.ACTIVE -> R.string.eve_push_active
    EveChatPushRegistrar.Status.REGISTERING -> R.string.eve_push_registering
    EveChatPushRegistrar.Status.FAILED -> R.string.eve_push_failed
    EveChatPushRegistrar.Status.SERVER_UNSUPPORTED -> R.string.eve_push_unsupported
    EveChatPushRegistrar.Status.NOT_CONFIGURED -> R.string.eve_push_not_configured
    EveChatPushRegistrar.Status.NO_PLAY_SERVICES -> R.string.eve_push_no_play_services
    EveChatPushRegistrar.Status.DISABLED -> R.string.eve_push_disabled
}
