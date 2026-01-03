package com.zapret2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages network statistics and monitoring for the Zapret2 app.
 * Provides information about:
 * - Current network type (WiFi/Mobile/None)
 * - WiFi SSID (when connected to WiFi)
 * - iptables rules status
 * - NFQUEUE rules count
 */
class NetworkStatsManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeListener: NetworkChangeListener? = null

    /**
     * Network type enumeration
     */
    enum class NetworkType {
        WIFI,
        MOBILE,
        ETHERNET,
        VPN,
        NONE
    }

    /**
     * Data class containing network statistics
     */
    data class NetworkStats(
        val networkType: NetworkType,
        val wifiSsid: String?,
        val iptablesActive: Boolean,
        val nfqueueRulesCount: Int
    )

    /**
     * Listener interface for network changes
     */
    interface NetworkChangeListener {
        fun onNetworkChanged(stats: NetworkStats)
    }

    /**
     * Gets the current network type
     */
    fun getNetworkType(): NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.NONE
        }
    }

    /**
     * Gets the current WiFi SSID (if connected to WiFi)
     * Returns null if not connected to WiFi or SSID is not available
     */
    @Suppress("DEPRECATION")
    fun getWifiSsid(): String? {
        val networkType = getNetworkType()
        if (networkType != NetworkType.WIFI) return null

        // Try to get SSID from WifiManager
        val wifiInfo = wifiManager.connectionInfo
        var ssid = wifiInfo?.ssid

        // SSID comes wrapped in quotes, remove them
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }

        // On Android 8.1+, SSID may be <unknown ssid> without location permission
        if (ssid == "<unknown ssid>" || ssid == "0x") {
            return null
        }

        return ssid
    }

    /**
     * Checks if iptables NFQUEUE rules are active
     * Must be called from IO dispatcher
     */
    suspend fun checkIptablesActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("iptables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                val count = result.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
                return@withContext count > 0
            }
        } catch (e: Exception) {
            // Ignore errors
        }
        false
    }

    /**
     * Gets the count of NFQUEUE rules in iptables
     * Must be called from IO dispatcher
     */
    suspend fun getNfqueueRulesCount(): Int = withContext(Dispatchers.IO) {
        try {
            // Count NFQUEUE rules in OUTPUT chain (mangle table)
            val outputResult = Shell.cmd("iptables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE").exec()
            val outputCount = if (outputResult.isSuccess && outputResult.out.isNotEmpty()) {
                outputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            } else 0

            // Count NFQUEUE rules in INPUT chain (mangle table)
            val inputResult = Shell.cmd("iptables -t mangle -L INPUT -n 2>/dev/null | grep -c NFQUEUE").exec()
            val inputCount = if (inputResult.isSuccess && inputResult.out.isNotEmpty()) {
                inputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            } else 0

            return@withContext outputCount + inputCount
        } catch (e: Exception) {
            // Ignore errors
        }
        0
    }

    /**
     * Gets all network statistics at once
     * Must be called from IO dispatcher
     */
    suspend fun getNetworkStats(): NetworkStats = withContext(Dispatchers.IO) {
        val networkType = getNetworkType()
        val wifiSsid = if (networkType == NetworkType.WIFI) getWifiSsid() else null
        val iptablesActive = checkIptablesActive()
        val nfqueueRulesCount = getNfqueueRulesCount()

        NetworkStats(
            networkType = networkType,
            wifiSsid = wifiSsid,
            iptablesActive = iptablesActive,
            nfqueueRulesCount = nfqueueRulesCount
        )
    }

    /**
     * Registers a listener for network changes
     */
    fun registerNetworkChangeListener(listener: NetworkChangeListener) {
        networkChangeListener = listener

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyNetworkChange()
            }

            override fun onLost(network: Network) {
                notifyNetworkChange()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                notifyNetworkChange()
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            // Ignore if already registered
        }
    }

    /**
     * Unregisters the network change listener
     */
    fun unregisterNetworkChangeListener() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore if not registered
            }
        }
        networkCallback = null
        networkChangeListener = null
    }

    private fun notifyNetworkChange() {
        // Notify listener on main thread
        // Note: The actual stats fetching should be done in a coroutine by the caller
        networkChangeListener?.let { listener ->
            // We need to fetch stats async, so we create a simple stats object
            // with just network type info for immediate notification
            val quickStats = NetworkStats(
                networkType = getNetworkType(),
                wifiSsid = getWifiSsid(),
                iptablesActive = false, // Will be updated by caller
                nfqueueRulesCount = 0   // Will be updated by caller
            )
            listener.onNetworkChanged(quickStats)
        }
    }

    /**
     * Returns a human-readable string for the network type
     */
    fun getNetworkTypeString(type: NetworkType): String {
        return when (type) {
            NetworkType.WIFI -> "WiFi"
            NetworkType.MOBILE -> "Mobile Data"
            NetworkType.ETHERNET -> "Ethernet"
            NetworkType.VPN -> "VPN"
            NetworkType.NONE -> "No Connection"
        }
    }

    /**
     * Returns the drawable resource ID for the network type icon
     */
    fun getNetworkTypeIcon(type: NetworkType): Int {
        return when (type) {
            NetworkType.WIFI -> R.drawable.ic_wifi
            NetworkType.MOBILE -> R.drawable.ic_mobile
            NetworkType.ETHERNET -> R.drawable.ic_network
            NetworkType.VPN -> R.drawable.ic_firewall
            NetworkType.NONE -> R.drawable.ic_wifi
        }
    }
}
