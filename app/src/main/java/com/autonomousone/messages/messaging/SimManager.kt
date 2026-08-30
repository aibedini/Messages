package com.autonomousone.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** One detected SIM line (subscription) on the device. */
data class SimInfo(
    val subscriptionId: Int,
    /** Physical slot index, 0-based ("SIM slot 1" = 0). */
    val slotIndex: Int,
    val carrierName: String,
    val displayName: String,
    val number: String,
    /** True when this is the platform default subscription. */
    val isSystemDefault: Boolean
)

/**
 * Identifies the SIM slots/subscriptions available on the device so the user
 * can pick which line sends SMS. Requires READ_PHONE_STATE (requested at
 * runtime from the Messaging settings screen); without it the list is empty.
 */
class SimManager(private val context: Context) {

    fun hasReadPhoneState(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun getActiveSims(): List<SimInfo> {
        if (!hasReadPhoneState()) return emptyList()
        val result = mutableListOf<SimInfo>()
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
                ?: SubscriptionManager.from(context)
            val infos: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: emptyList()
            val defaultSubId = try {
                SubscriptionManager.getDefaultSubscriptionId()
            } catch (e: Exception) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }

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
                    isSystemDefault = info.subscriptionId == defaultSubId
                )
            }
            result.sortBy { it.slotIndex }
        } catch (e: Exception) {
            // Degrade gracefully — callers treat an empty list as "SIMs unknown".
        }
        return result
    }

    /** Human-readable label for a subscription, e.g. "Slot 1 · Irancell". */
    fun labelFor(sim: SimInfo): String {
        val carrier = sim.carrierName.ifBlank { sim.displayName.ifBlank { "SIM" } }
        return "SIM ${sim.slotIndex + 1} · $carrier"
    }

    /**
     * v2.6.14: read the SMSC actually programmed on the (U)SIM for this
     * subscription via SmsManager.getSmscAddress() (API 30+). The API is
     * only callable by the default SMS app; everything else — permission,
     * older OS, RIL refusing — returns null (UI shows "network default").
     */
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
