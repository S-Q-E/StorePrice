package com.storeprice.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reports whether the device currently has a usable default network.
 *
 * The flow emits the current state immediately and then changes when the system
 * reports network availability. It is intentionally coarse: it does not verify
 * that Supabase is reachable, only that the device has an internet-capable
 * network. A failed request still keeps Room intact and updates the sync status.
 */
internal class NetworkMonitor(context: Context) {

    private val connectivityManager = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    ) { "ConnectivityManager is unavailable" }

    fun isOnline(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnlineNow())
            }

            override fun onLost(network: Network) {
                trySend(isOnlineNow())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(isOnlineNow())
            }
        }

        trySend(isOnlineNow())
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    /** Snapshot used as the initial value before the callback flow emits. */
    fun isOnlineNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
