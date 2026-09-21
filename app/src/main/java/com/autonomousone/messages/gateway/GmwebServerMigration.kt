package com.autonomousone.messages.gateway

/**
 * The ONE-TIME fold of the legacy two-URL configuration into the single origin.
 *
 * Kept pure and separate from [GatewayPreferences] because its rules are the kind that are
 * easy to get subtly wrong and impossible to notice: an app that silently adopts the wrong
 * server pulls its send-requests from one place and uploads its events to another.
 *
 * ── WHY THE "EXPLICITLY STORED" DISTINCTION MATTERS ──────────────────────────
 * The old `backendUrl` getter fell back to a domain COMPILED INTO THE APK when nothing was
 * stored. If the migration asked for "the backend URL" it would receive that baked domain for
 * every existing install — including installs whose owner never chose it — and would then
 * persist it as if the user had. So the caller must pass `null` for "never written" and the
 * migration must treat that as "no legacy configuration", not as a value.
 */
object GmwebServerMigration {

    /**
     * @param legacyGmwebUrl the raw stored `gmweb_url`, or null when never written.
     * @param storedBackendUrl the raw stored `cloud_backend_url`, or **null when the key was
     *   never written**. Must never be the compiled-in default.
     * @param alreadyMigrated true once this migration has run before.
     */
    data class StoredConfig(
        val legacyGmwebUrl: String? = null,
        val storedBackendUrl: String? = null,
        val alreadyMigrated: Boolean = false
    )

    /**
     * @param origin the origin to persist, or null when there is nothing to migrate or the
     *   migration has already run.
     */
    data class Decision(val origin: String?) {
        val hasOriginToWrite: Boolean get() = !origin.isNullOrBlank()
    }

    /**
     * The priority, and why it is this order:
     *
     *  1. the old `gmwebUrl` — it drove the pull bridge, the leg the user was actually
     *     configuring when they set anything at all;
     *  2. an EXPLICITLY STORED `backendUrl` — a real user choice from an older build;
     *  3. nothing: the app starts unconfigured and the user enters their server.
     *
     * A legacy value that does not normalize (http, a stray path, a typo) is skipped rather than
     * adopted, and the next candidate is tried — a migration must never install an address that
     * the current build would reject if the user typed it.
     */
    fun decide(config: StoredConfig): Decision {
        if (config.alreadyMigrated) return Decision(origin = null)

        val fromGmweb = GmwebServerProfile.profileOf(config.legacyGmwebUrl)
        val fromBackend = GmwebServerProfile.profileOf(config.storedBackendUrl)
        return Decision(origin = (fromGmweb ?: fromBackend)?.origin)
    }
}
