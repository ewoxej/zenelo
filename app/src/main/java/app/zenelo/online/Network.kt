package app.zenelo.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device is online (the default network has internet), followed live: offline, server
 * tracks without a copy on the device are dimmed in lists and skipped in the queue.
 */
class Network(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(check())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) {
                _online.value = check()
            }

            override fun onCapabilitiesChanged(network: AndroidNetwork, caps: NetworkCapabilities) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            override fun onLost(network: AndroidNetwork) {
                _online.value = false
            }
        })
    }

    private fun check(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
