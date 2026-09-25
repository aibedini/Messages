package com.autonomousone.messages.gateway

/**
 * Which foregroundServiceType a gateway start may use, and whether it may start at all (Workstream C/F).
 *
 * WHY THIS EXISTS
 *
 * `GatewayService` is started from three different places, and they do not have the same
 * permissions. A start driven by the user (app in the foreground) may use the honest `dataSync`
 * type. A start driven by `BOOT_COMPLETED` may not: Android 15 (API 35) forbids apps targeting 35+
 * from launching a `dataSync` foreground service from a boot receiver, and this app targets 36.
 * Because `BootGatewayReceiver` called the same `startForegroundService` path as everything else,
 * the reboot re-arm was expected to throw `ForegroundServiceStartNotAllowedException` at boot —
 * and since the gateway's only scheduler is a one-time send worker, nothing else would have brought
 * it back. The gateway would stay dead after every reboot on a modern device until the user opened
 * the app.
 *
 * The rule is deliberately expressed as a PURE FUNCTION of (platform version, start reason) so it is
 * a unit-testable decision rather than a condition buried in a Service callback, and so the answer
 * for a platform this build has never run on is still inspectable.
 *
 * WHY `specialUse` RATHER THAN DEFERRING EVERYTHING
 *
 * The documented Android 15 restriction covers `dataSync` and a small set of other types;
 * `specialUse` is the escape hatch for a use case the other types do not describe, which is what
 * the service's LAN-server half genuinely is. Boot therefore starts with `specialUse` only, and the
 * richer `dataSync` type is used whenever a foreground start is allowed. This is not a trick to
 * evade the restriction: the durability of history does not depend on this service at all
 * (docs/production-validation-plan.md §7 — bounded checkpointed units are the durability model),
 * so anything the OS refuses is recoverable rather than lost.
 *
 * DEFENCE IN DEPTH: this decision is advisory. `GatewayService` still wraps the actual start in a
 * try/catch and defers to WorkManager when the platform refuses, so the app is correct even if a
 * future Android version restricts more types than are listed here. The policy improves the common
 * case; it is never the thing correctness rests on.
 */
object GatewayForegroundStartPolicy {

    /** Why a start was requested. Boot is the only case with a type restriction. */
    enum class StartReason {
        /** The device just booted: a manifest receiver is asking. */
        BOOT,

        /** The user or an in-app component (Activity, ViewModel, watchdog alarm) asked. */
        USER_OR_APP,

        /** A retry after a previous start was refused or the service died. */
        RETRY;

        companion object {
            fun fromExtra(raw: String?): StartReason =
                entries.firstOrNull { it.name == raw } ?: USER_OR_APP
        }
    }

    /** What the caller should do. */
    enum class Decision {
        /** Start with `dataSync or specialUse` — the honest pair while a foreground start is legal. */
        START_WITH_DATA_SYNC,

        /** Start with `specialUse` only (a boot start on a platform that forbids `dataSync`). */
        START_SPECIAL_USE_ONLY
    }

    /**
     * The first API level whose boot receivers may not launch a `dataSync` foreground service when
     * the app targets a recent SDK. Android 15 = API 35.
     */
    const val BOOT_DATA_SYNC_FORBIDDEN_FROM_API = 35

    /**
     * @param apiLevel      `Build.VERSION.SDK_INT` of the device.
     * @param startReason   who is asking.
     */
    fun decide(apiLevel: Int, startReason: StartReason): Decision =
        if (startReason == StartReason.BOOT && apiLevel >= BOOT_DATA_SYNC_FORBIDDEN_FROM_API) {
            Decision.START_SPECIAL_USE_ONLY
        } else {
            Decision.START_WITH_DATA_SYNC
        }

    /**
     * True when the decision means the `dataSync` type must be left out.
     *
     * Expressed as its own question because the service needs it in two places — the type bitmask
     * and the permission/type consistency check Android performs against the manifest — and a
     * second, independently written condition is how the two would drift apart.
     */
    fun includesDataSync(decision: Decision): Boolean = decision == Decision.START_WITH_DATA_SYNC

    /** How the gateway may be revived after the system stops it. */
    enum class RestartMechanism {
        /**
         * A background foreground-service start is still permitted, so the alarm can bring the
         * service back directly and quickly, with WorkManager as the durable backstop.
         */
        ALARM_AND_WORKMANAGER,

        /**
         * A background start is refused by the platform, so only WorkManager may schedule the retry.
         */
        WORKMANAGER_ONLY
    }

    /**
     * The first API level that refuses a foreground service started from the background.
     *
     * Android 12 (API 31) introduced the restriction; Android 15 tightened it further for `dataSync`
     * (a long-running one is stopped and cannot be restarted into the background).
     */
    const val BACKGROUND_START_RESTRICTED_FROM_API = 31

    /**
     * Which revival mechanism is actually effective on this platform.
     *
     * WHY THIS MATTERS, AND WHY IT IS A PURE FUNCTION: the service's restart watchdog armed an
     * `AlarmManager` alarm with a `PendingIntent.getForegroundService`. From API 31 that is a
     * **background** foreground-service start and is refused — and because the SYSTEM performs the
     * start, the refusal never calls back into this process. No exception, no log, nothing: the
     * watchdog emitted "gateway restart in 15s" and the bridge stayed dark. On Android 15 the
     * `dataSync` budget makes it worse, since even a permitted restart is time-limited.
     *
     * WorkManager is therefore the only mechanism relied on for the retry, on every version: it
     * schedules inside the platform's own execution windows and its worker RETRIES, so a refusal is
     * caught, logged and backed off instead of vanishing. The alarm is kept only where a direct
     * background start genuinely works, so older devices keep the fast path.
     *
     * NOT A GUARANTEE: when the platform refuses background execution outright — the `dataSync`
     * budget exhausted, or the app force-stopped — nothing can start the gateway until the app is
     * foregrounded or the budget resets. The honest response is to record that, not to imply the
     * bridge is alive.
     */
    fun restartMechanism(apiLevel: Int): RestartMechanism =
        if (apiLevel >= BACKGROUND_START_RESTRICTED_FROM_API) {
            RestartMechanism.WORKMANAGER_ONLY
        } else {
            RestartMechanism.ALARM_AND_WORKMANAGER
        }

    /** True when the alarm is worth arming on this platform. */
    fun includesAlarm(mechanism: RestartMechanism): Boolean =
        mechanism == RestartMechanism.ALARM_AND_WORKMANAGER
}
