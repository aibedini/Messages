package com.autonomousone.messages.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.gateway.ConnectionSupervisor
import com.autonomousone.messages.gateway.DeviceIdentity
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayService
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder

/**
 * Gathers [ReplicationInputs] from the places that actually own each fact.
 *
 * This is the adapter that makes [ReplicationPrerequisiteEvaluator] usable: the evaluator stays
 * pure and testable, and every Android-specific read lives here, once. Today the same conditions
 * are read independently in eight files (`docs/gateway-replication-audit.md`, Blocker 2) with
 * different combinations — that divergence is how the strategic command poller came to omit
 * consent entirely.
 *
 * NOT ON THE MAIN THREAD: [read] touches the Keystore and Room. Call it from `Dispatchers.IO`.
 *
 * Every read is individually guarded. A failure yields the *safe* answer for that one input
 * rather than aborting the whole evaluation, because a diagnostic that throws is worse than a
 * diagnostic that admits one unknown:
 *
 *  - the trusted-device query failing yields "no consumer", which fails closed;
 *  - the Keystore probe failing yields "key unavailable", which also fails closed — never send
 *    unencrypted, and never claim readiness on a key we could not confirm.
 */
class ReplicationInputsReader(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = GatewayPreferences(appContext)

    suspend fun read(now: Long = System.currentTimeMillis()): ReplicationInputs {
        val health = GatewayHealthRecorder.rawSnapshot(now)
        val db = MessagesDatabase.get(appContext)

        val devices = runCatching {
            db.trustedDeviceDao().all()
        }.getOrDefault(emptyList())

        return ReplicationInputs(
            // USER INTENT, not the derived transmission gate. `prefs.isEnabled` is written by the
            // supervisor and cleared while offline (`ConnectionSupervisor.kt:326`, and its own
            // comment at :389 calls it "runtime state ... never clobbered elsewhere"). Reading it
            // here would report an offline device as GatewayDisabled and tell the user to switch
            // on a gateway that is already switched on.
            gatewayEnabled = prefs.gatewayDesiredEnabled,
            consentGranted = prefs.hasGatewayConsent,
            // The SAME value the existing upload gate tests: `gmwebUrl` is a deprecated alias
            // whose getter returns `gmwebServerOrigin`, so reading the origin directly cannot
            // change when an upload is attempted — it only avoids the deprecated accessor.
            serverOriginConfigured = prefs.gmwebServerOrigin.isNotBlank(),
            identityRegistered = prefs.identityRegistered,
            // Only a real credential rejection may set this. `AuthHealth.rejected` is true for
            // AuthVerification.REJECTED, which in turn is only produced by 401/403 — never by a
            // 400 or a timeout.
            authRejected = health.authentication.rejected,
            // A durable standing verdict, not an inference. The control plane's own answer to an
            // AUTHENTICATED agent call is the explicit revocation signal, and it is recorded by the
            // designated auth producer (the heartbeat) in `control_plane_auth_state` so it survives a
            // process death — the in-memory health registry would forget it and the app would come
            // back reporting no auth blocker at all.
            //
            // Only a 403 sets it, and only after consecutive refusals (see ControlPlaneAuthPolicy);
            // a 401 is reported as AuthenticationRequired instead, because "your credential is stale"
            // and "the server refuses this device" need different human responses.
            //
            // Read failures yield false: this feeds the upload gate, and a diagnostic row that cannot
            // be read must never be able to freeze every upload.
            deviceRevoked = ControlPlaneAuthStateStore(db.controlPlaneAuthStateDao()).isRevoked(),
            cryptoKeyAvailable = DeviceIdentity.isEnrolled(),
            telephonyPermissionGranted = canReadTelephony(),
            historyGrantPresent = TrustedDevicePolicy.hasFullHistoryConsumer(devices, now),
            // The supervisor's own declaration, not the health registry's `validatedInternet`:
            // that value is published only on the online path (`publishHealthContext` is called
            // at ConnectionSupervisor.kt:396, after the offline branch has already returned), so
            // on an offline device it still holds the last online reading.
            networkValidated = !supervisorOffline()
        )
    }

    private fun supervisorOffline(): Boolean =
        GatewayService.supervisorState == ConnectionSupervisor.State.WAITING_FOR_NETWORK

    private fun canReadTelephony(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
