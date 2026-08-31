package com.autonomousone.messages

import android.app.Application
import android.util.Log
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * App-owned process hooks.
 *
 * Global crash context: coroutine jobs inside ViewModels are guarded, but a
 * crash can still come from the provider callbacks, notification actions, or
 * third-party receivers. Without a handler the process dies and logcat
 * rotates past it before anyone looks — the user just sees "the app closed".
 * We LOG the full context and delegate to the previous default handler so
 * the platform still produces its standard tombstone/ANR trail. Never
 * swallow: a dead process with a written log beats a dead process blind.
 */
class MessagesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Holders.init(this)
        DiagnosticLog.initialize(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // getRunningTasks only returns our own stack since API 21 and
                // Activity callbacks don't reach here cheaply — keep the
                // line honest: thread name + full trace. The ViewModels'
                // crashGuard logs carry the structured fields (threadId,
                // page, syncState); this one is the last-resort net.
                Log.e("CRASH_GUARD", "Uncaught on '${thread.name}'", error)
                DiagnosticLog.event("CRASH", "uncaught thread=${thread.name}", error)
            } catch (_: Throwable) {
                // Logging must never mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
