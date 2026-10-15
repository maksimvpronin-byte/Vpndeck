package com.vpndeck.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.vpndeck.VpnDeckApp
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the underlying (non-VPN) default network and reports it to the core,
 * which binds its outgoing sockets to it and resets connections when it
 * changes (Wi-Fi <-> mobile). Logic follows the official sing-box client.
 */
object NetworkMonitor {
    @Volatile
    var defaultNetwork: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    /** Called when a new underlying network becomes the default (Wi-Fi <-> mobile). */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null
    private var registered = false

    private val handler by lazy { Handler(HandlerThread("network-monitor").apply { start() }.looper) }

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build() // NOT_VPN is in the builder's defaults, so our own tunnel is never matched

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == defaultNetwork) update(network)
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (network == defaultNetwork) update(network)
        }
        override fun onLost(network: Network) {
            if (network == defaultNetwork) update(null)
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val cm = VpnDeckApp.connectivity
        // registerBestMatchingNetworkCallback / requestNetwork follow THE default
        // network; registerDefaultNetworkCallback would hand us the VPN on P+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cm.registerBestMatchingNetworkCallback(request, callback, handler)
        } else {
            cm.requestNetwork(request, callback, handler)
        }
        registered = true
        defaultNetwork = cm.activeNetwork
    }

    @Synchronized
    fun stop() {
        onNetworkChanged = null
        if (!registered) return
        runCatching { VpnDeckApp.connectivity.unregisterNetworkCallback(callback) }
        registered = false
        defaultNetwork = null
    }

    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        handler.post { notifyListener(defaultNetwork) }
    }

    private fun update(network: Network?) {
        val changed = network != null && network != defaultNetwork
        defaultNetwork = network
        notifyListener(network)
        if (changed) onNetworkChanged?.invoke()
    }

    private fun notifyListener(network: Network?) {
        val l = listener ?: return
        if (network == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        // Link properties can lag behind onAvailable briefly; retry like the official client.
        repeat(10) {
            val lp = VpnDeckApp.connectivity.getLinkProperties(network)
            val index = lp?.interfaceName?.let { name -> runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() }
            if (lp != null && index != null) {
                val caps = VpnDeckApp.connectivity.getNetworkCapabilities(network)
                val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                l.updateDefaultInterface(lp.interfaceName, index, expensive, false)
                return
            }
            Thread.sleep(100)
        }
    }
}
