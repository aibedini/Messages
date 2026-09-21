package com.autonomousone.messages.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The single source of truth for "is there a network the gateway can use".
 *
 * A validated CONNECTED network (WiFi or cellular) is what matters: an
 * interface that is up but has no route (captive portal, carrier drop,
 * airplane mode) reports NET_CAPABILITY_VALIDATED absent and must NOT be
 * treated as online — otherwise the supervisors wake up, every request fails,
 * and the backoff ladder resets itself into a retry storm.
 *
 * [isOnline] is a snapshot for the call sites that ask "right now" (OutboxPoller
 * gate, HeartbeatManager wait predicate); [onlineFlow] emits only on TRANSITIONS
 * (distinctUntilChanged) so a ConnectionSupervisor can suspend until the
 * network flips without polling.
 */
class NetworkMonitor private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: NetworkMonitor? = null

        fun get(context: Context): NetworkMonitor = instance ?: synchronized(this) {
            instance ?: NetworkMonitor(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean = try {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    /**
     * A human label for the transport carrying traffic right now.
     *
     * The status card shows it because "no network" and "network is fine, the server is
     * not" are different problems, and on a phone the difference is often invisible: Wi-Fi
     * can be associated and still have no validated route.
     */
    fun transportLabel(): String = try {
        val network = connectivityManager.activeNetwork ?: return "None"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "None"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    } catch (_: Exception) {
        "Other"
    }

    /**
     * Flow of online/offline transitions. Registers a NetworkCallback; the
     * callback's own event loop is what drives emissions — zero polling.
     */
    fun onlineFlow(): Flow<Boolean> = callbackFlow {
        trySend(isOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Fires when a network GAINS validation (captive portal solved,
                // carrier completes the check) — the exact moment "online" flips
                // true without any interface change.
                trySend(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
