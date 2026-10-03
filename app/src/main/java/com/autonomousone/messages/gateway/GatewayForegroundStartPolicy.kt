package com.autonomousone.messages.gateway

/**
 * Which foregroundServiceType a gateway start may use, and whether it may start at all (Workstream C/F).
 *
 * Android 15 time-limits `dataSync` foreground services. Once that budget is exhausted, even a
 * later start can be rejected with `ForegroundServiceStartNotAllowedException`. This gateway is a
 * long-lived SMS/LAN control-plane service and already declares a documented `specialUse` subtype,
 * so API 35+ must not consume or depend on the `dataSync` budget at all.
 *
 * Below Android 15 the older dataSync behavior is retained. On Android 15+ every start reason uses
 * specialUse only. This also covers BOOT_COMPLETED / MY_PACKAGE_REPLACED, which are separately
 * restricted from starting dataSync foreground services.
 */
object GatewayForegroundStartPolicy {

    enum class StartReason {
        BOOT,
        APP_UPDATED,
        USER_OR_APP,
        RETRY;

        companion object {
            fun fromExtra(raw: String?): StartReason =
                entries.firstOrNull { it.name == raw } ?: USER_OR_APP
        }
    }

    enum class Decision {
        START_WITH_DATA_SYNC,
        START_SPECIAL_USE_ONLY
    }

    /** Android 15 / API 35: dataSync is time-limited and unsuitable for this persistent gateway. */
    const val DATA_SYNC_TIME_LIMITED_FROM_API = 35

    /** Kept as a compatibility alias for existing tests/docs referring to the old boot-only rule. */
    const val BOOT_DATA_SYNC_FORBIDDEN_FROM_API = DATA_SYNC_TIME_LIMITED_FROM_API

    fun decide(apiLevel: Int, startReason: StartReason): Decision =
        if (apiLevel >= DATA_SYNC_TIME_LIMITED_FROM_API) {
            Decision.START_SPECIAL_USE_ONLY
        } else {
            Decision.START_WITH_DATA_SYNC
        }

    fun includesDataSync(decision: Decision): Boolean = decision == Decision.START_WITH_DATA_SYNC

    /** How the gateway may be revived after the system stops it. */
    enum class RestartMechanism {
        ALARM_AND_WORKMANAGER,
        WORKMANAGER_ONLY
    }

    /** Android 12 / API 31 introduced background foreground-service start restrictions. */
    const val BACKGROUND_START_RESTRICTED_FROM_API = 31

    fun restartMechanism(apiLevel: Int): RestartMechanism =
        if (apiLevel >= BACKGROUND_START_RESTRICTED_FROM_API) {
            RestartMechanism.WORKMANAGER_ONLY
        } else {
            RestartMechanism.ALARM_AND_WORKMANAGER
        }

    fun includesAlarm(mechanism: RestartMechanism): Boolean =
        mechanism == RestartMechanism.ALARM_AND_WORKMANAGER
}
