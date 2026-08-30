package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.messaging.SimInfo
import com.autonomousone.messages.messaging.SimManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing state for the Messaging settings screen. Every option is persisted to
 * [MessagingPreferences] and stays OFF/unset until the user changes it.
 */
class MessagingSettingsViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val prefs = MessagingPreferences(application)
    private val simManager = SimManager(application)

    var deliveryReportsEnabled by mutableStateOf(prefs.deliveryReportsEnabled)
        private set
    var selectedSubscriptionId by mutableIntStateOf(prefs.sendSubscriptionId)
        private set
    var smscAddress by mutableStateOf(prefs.smscAddress)
        private set

    /** v2.6.14: SIM subscription id whose SMSC editor row is open. */
    var smscEditingSubId by mutableIntStateOf(-1)
        private set

    /** v2.6.14: saved manual SMSC overrides per SIM (Compose-observable). */
    val smscManual = mutableStateMapOf<Int, String?>()

    /** v2.6.14: live-read SMSC per SIM from the (U)SIM itself (API 30+). */
    val simSmscRead = mutableStateMapOf<Int, String?>()

    fun seedSmscManual() {
        sims.forEach { sim ->
            smscManual[sim.subscriptionId] = prefs.smscForSim(sim.subscriptionId)
        }
    }
    var reactionsAsEmojiEnabled by mutableStateOf(prefs.showIphoneReactionsAsEmoji)
        private set
    var groupMessagingEnabled by mutableStateOf(prefs.groupMessagingEnabled)
        private set

    val sims = mutableStateListOf<SimInfo>()

    fun hasPhonePermission(): Boolean = simManager.hasReadPhoneState()

    fun refreshSims() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = simManager.getActiveSims()
            withContext(Dispatchers.Main) {
                sims.clear()
                sims.addAll(list)
                seedSmscManual()
            }
        }
    }

    fun setDeliveryReports(enabled: Boolean) {
        deliveryReportsEnabled = enabled
        prefs.deliveryReportsEnabled = enabled
    }

    /** @param subscriptionId a real subscription id or [MessagingPreferences.SUBSCRIPTION_UNSET]. */
    fun selectSim(subscriptionId: Int) {
        selectedSubscriptionId = subscriptionId
        prefs.sendSubscriptionId = subscriptionId
    }

    fun saveSmsc(value: String) {
        smscAddress = value.trim()
        prefs.smscAddress = smscAddress
    }

    /** v2.6.14: read each SIM's programmed SMSC off the (U)SIM itself. */
    fun refreshSimSmsc() {
        viewModelScope.launch(Dispatchers.IO) {
            sims.forEach { sim ->
                val read = simManager.readSmsc(sim.subscriptionId)
                withContext(Dispatchers.Main) { simSmscRead[sim.subscriptionId] = read }
            }
        }
    }

    /** v2.6.14: open (-1 closes) the SMSC editor row for one SIM. */
    fun editSmscForSim(subscriptionId: Int) {
        smscEditingSubId = subscriptionId
    }

    fun saveSmscForSim(subscriptionId: Int, value: String) {
        prefs.setSmscForSim(subscriptionId, value)
        smscManual[subscriptionId] = prefs.smscForSim(subscriptionId)
        smscEditingSubId = -1
    }

    /** Remove the manual override for a SIM → that SIM falls back to its
     *  own programmed SMSC (or the global override, if the user set one). */
    fun clearSmscForSim(subscriptionId: Int) {
        prefs.setSmscForSim(subscriptionId, null)
        smscManual[subscriptionId] = null
        smscEditingSubId = -1
    }

    fun setReactionsAsEmoji(enabled: Boolean) {
        reactionsAsEmojiEnabled = enabled
        prefs.showIphoneReactionsAsEmoji = enabled
    }

    fun setGroupMessaging(enabled: Boolean) {
        groupMessagingEnabled = enabled
        prefs.groupMessagingEnabled = enabled
    }

    fun labelFor(sim: SimInfo): String = simManager.labelFor(sim)
}
