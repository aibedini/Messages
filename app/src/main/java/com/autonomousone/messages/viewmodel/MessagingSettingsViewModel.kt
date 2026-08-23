package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
