package com.autonomousone.messages.diagnostics

/**
 * The decision that keeps [MainThreadStallWatchdog] off a normal user's phone.
 *
 * Split out from `MessagesApp.installDiagnostics()` so it is a pure rule a JVM test can pin: the
 * watchdog runs in DEBUG builds always, and in release builds only when the developer option is
 * switched on. `BuildConfig.DEBUG` is the only build-type input.
 */
internal object StallWatchdogGate {

    fun shouldInstall(isDebugBuild: Boolean, preferenceEnabled: Boolean): Boolean =
        isDebugBuild || preferenceEnabled
}
