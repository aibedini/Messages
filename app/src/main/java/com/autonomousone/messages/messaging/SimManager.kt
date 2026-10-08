package com.autonomousone.messages.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * One detected SIM line (subscription) on the device.
 *
 * [subscriptionId] is Android-local authority — it is what `SubscriptionManager`, `SmsManager` and the
 * transport gate actually take. [simRef] is the same line's **cross-system** identity: opaque, keyed
 * and safe to publish. They are never interchangeable (see [SimRef]).
 *
 * The display fields ([slotIndex], [displayName], [carrierName]) are presentation only. Routing by
 * them is forbidden: a slot hosts whatever card is in it today, so a slot is not an identity.
 */
data class SimInfo(
    val subscriptionId: Int,
    /** Physical slot index, 0-based ("SIM slot 1" = 0). DISPLAY ONLY — never an identity. */
    val slotIndex: Int,
    val carrierName: String,
    val displayName: String,
    val number: String,
    /** True when this is the platform default subscription. */
    val isSystemDefault: Boolean,
    /**
     * The opaque cross-system reference for this subscription.
     *
     * Defaults to empty so a construction site that predates the identity (or a test fixture) still
     * compiles; an empty value is deliberately **not** a valid ref, so a blank field can never be
     * mistaken for a real identity by [SimRefFormat.isValid].
     */
    val simRef: String = ""
)

/**
 * Identifies the SIM slots/subscriptions available on the device so the user
 * can pick which line sends SMS. Requires READ_PHONE_STATE (requested at
 * runtime from the Messaging settings screen); without it the list is empty.
 *
 * @param simRefProvider derives the opaque cross-system ref for each subscription. Injectable so the
 *   inventory — including the ref it carries — can be built in a JVM test with a known key.
 */
class SimManager(
    private val context: Context,
    private val simRefProvider: SimRefProvider = SimRefProvider()
) {

    fun hasReadPhoneState(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasReadPhoneState() before the platform call
    fun getActiveSims(): List<SimInfo> = when (val result = discover()) {
        is SimDiscoveryResult.Available -> result.sims
        // Callers that ask "which lines are there" get an empty list for both non-answers. That is
        // the right shape for them (a send must fail closed either way) but it is NOT the whole
        // truth — see discover(), which is what telemetry and diagnostics use.
        SimDiscoveryResult.PermissionMissing -> emptyList()
        is SimDiscoveryResult.Failed -> emptyList()
    }

    /**
     * The full answer: available (possibly with zero SIMs), permission missing, or a platform
     * failure. Never collapses the three into an empty list.
     */
    fun discover(): SimDiscoveryResult = SimDiscovery.classify(hasReadPhoneState()) {
        queryActiveSims()
    }

    /** The default SMS subscription, or the platform's INVALID id when it cannot be read. */
    fun defaultSmsSubscriptionId(): Int = try {
        SubscriptionManager.getDefaultSmsSubscriptionId()
    } catch (e: Exception) {
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * The default SMS subscription, or NULL when the platform did not answer.
     *
     * The distinction the send path needs: `INVALID_SUBSCRIPTION_ID` from
     * [defaultSmsSubscriptionId] means "the platform answered: there is no default", which must fail
     * closed, while a failure to ask must not be read as the same fact.
     */
    fun defaultSmsSubscriptionIdOrNull(): Int? = try {
        SubscriptionManager.getDefaultSmsSubscriptionId()
    } catch (e: Exception) {
        null
    }

    @SuppressLint("MissingPermission") // reached only through discover(), which checks the permission
    private fun queryActiveSims(): List<SimInfo> {
        val result = mutableListOf<SimInfo>()
        val sm = context.getSystemService(SubscriptionManager::class.java)
            ?: SubscriptionManager.from(context)
        val infos: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: emptyList()
        val defaultSubId = defaultSmsSubscriptionId()

        for (info in infos) {
            val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    info.getNumber().orEmpty()
                } catch (e: Exception) {
                    ""
                }
            } else {
                // Pre-13: per-subscription number lookup is no longer exposed
                // by current SDK stubs; the carrier label is still shown.
                ""
            }
            result += SimInfo(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrierName = info.carrierName?.toString().orEmpty(),
                displayName = info.displayName?.toString().orEmpty(),
                number = number,
                isSystemDefault = info.subscriptionId == defaultSubId,
                // Derived from the subscription id ONLY. No ICCID, no IMSI, no SIM serial is read
                // here — and none may be added: this is the one place the cross-system identity is
                // minted, so it is the place a privileged-identifier leak would start.
                simRef = simRefFor(info.subscriptionId)
            )
        }
        result.sortBy { it.slotIndex }
        return result
    }

    /** Human-readable label for a subscription, e.g. "Slot 1 · Irancell". */
    fun labelFor(sim: SimInfo): String {
        val carrier = sim.carrierName.ifBlank { sim.displayName.ifBlank { "SIM" } }
        return "SIM ${sim.slotIndex + 1} · $carrier"
    }

    /**
     * The cross-system ref for [subscriptionId], or **empty** when the device secret is unavailable.
     *
     * Empty is the fail-closed answer and it is not a valid ref ([SimRefFormat.isValid] rejects it), so
     * a line whose identity cannot be minted simply cannot be *selected* — it degrades to
     * "unavailable" rather than to "whatever the default is". Returning a partial or substitute value
     * here would be worse than returning nothing, because a wrong ref is indistinguishable from a
     * right one at every later comparison.
     *
     * This never throws: one broken Keystore must not empty the whole SIM inventory, which would take
     * the Messaging settings screen and send-path validation down with it.
     */
    internal fun simRefFor(subscriptionId: Int): String = try {
        simRefProvider.simRefFor(subscriptionId).value
    } catch (e: Exception) {
        DiagnosticLog.event(
            "SIM_REF",
            "sub=$subscriptionId state=UNRESOLVED detail=keystore_unavailable"
        )
        ""
    }

    /**
     * Resolve an opaque [reference] against the CURRENT active inventory.
     *
     * The inventory is read fresh for this call, because a cached one can be minutes stale and the
     * whole point of resolving at execution time is that the phone's SIMs at this instant decide.
     *
     * Deliberately not offered: a slot-shaped overload. Nothing here accepts a slot, so no caller can
     * express "the SIM in slot 0" and have it silently matched to a different card (mission §5).
     */
    @SuppressLint("MissingPermission") // all failures become InventoryUnavailable, never a wrong SIM
    fun resolveActiveRef(reference: String): SimRefResolution = when (val result = discover()) {
        is SimDiscoveryResult.Available ->
            simRefProvider.resolveSubscriptionId(
                reference = reference,
                activeSubscriptionIds = result.sims.map { it.subscriptionId },
                inventoryReadable = true
            )
        // "The app could not look" is NOT "the SIM is gone": a missing permission must never be
        // reported as an unavailable SIM, or the user is told to reselect a card that is still there.
        SimDiscoveryResult.PermissionMissing -> SimRefResolution.InventoryUnavailable
        is SimDiscoveryResult.Failed -> SimRefResolution.InventoryUnavailable
    }

    /** The refs of the current active inventory, for telemetry that must not carry a subscription id. */
    fun activeSimRefs(): List<String> = getActiveSims().map { it.simRef }.filter { it.isNotEmpty() }

    /**
     * v2.6.14: read the SMSC actually programmed on the (U)SIM for this
     * subscription via SmsManager.getSmscAddress() (API 30+). The API is
     * only callable by the default SMS app; everything else — permission,
     * older OS, RIL refusing — returns null (UI shows "network default").
     */
    @SuppressLint("MissingPermission") // all permission failures are converted to null below
    fun readSmsc(subscriptionId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val subInfo = sm.activeSubscriptionInfoList?.firstOrNull {
                it.subscriptionId == subscriptionId
            } ?: return null
            val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subInfo.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subInfo.subscriptionId)
            }
            mgr.smscAddress?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
