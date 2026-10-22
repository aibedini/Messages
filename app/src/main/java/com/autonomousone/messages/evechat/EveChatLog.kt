package com.autonomousone.messages.evechat

import com.autonomousone.messages.utils.DiagnosticLog

/** EveChat's view of the app log: one tag, a component field, structured key/values. */
object EveChatLog {
    private const val TAG = "EVECHAT"

    fun d(component: String, message: String, vararg fields: Pair<String, Any?>) =
        DiagnosticLog.d(TAG, message, null, mapOf("c" to component, *fields))

    fun i(component: String, message: String, vararg fields: Pair<String, Any?>) =
        DiagnosticLog.i(TAG, message, null, mapOf("c" to component, *fields))

    fun w(component: String, message: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) =
        DiagnosticLog.w(TAG, message, error, mapOf("c" to component, *fields))

    fun e(component: String, message: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) =
        DiagnosticLog.e(TAG, message, error, mapOf("c" to component, *fields))

    /** Ids are opaque but still correlate a person across lines; keep a short, stable prefix. */
    fun id(value: String?): String = value?.take(8) ?: "-"
}
