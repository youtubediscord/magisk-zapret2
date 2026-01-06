package com.zapret2.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Manages network statistics and monitoring for the Zapret2 app.
 * Provides information about:
 * - Current network type (WiFi/Mobile/None)
 * - WiFi SSID (when connected to WiFi)
 * - iptables rules status
 * - NFQUEUE rules count
 */
class NetworkStatsManager(context: Context) {

    companion object {
        private const val TAG = "NetworkStatsManager"
    }

    // Use WeakReference to avoid memory leaks
    private val contextRef = WeakReference(context.applicationContext)

    // Lazy initialization with null safety
    private val connectivityManager: ConnectivityManager? by lazy {
        contextRef.get()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private val wifiManager: WifiManager? by lazy {
        contextRef.get()?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    // Handler for posting callbacks to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val cm = connectivityManager ?: return NetworkType.NONE

        return try {
            val activeNetwork = cm.activeNetwork ?: return NetworkType.NONE
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.NONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network type", e)
            NetworkType.NONE
        }
    }

    /**
     * Gets the current WiFi SSID (if connected to WiFi)
     * Returns null if not connected to WiFi or SSID is not available
     *
     * Note: On Android 8.1+ requires ACCESS_FINE_LOCATION permission.
     * On Android 12+ also requires ACCESS_WIFI_STATE permission.
     */
    @Suppress("DEPRECATION")
    fun getWifiSsid(): String? {
        return try {
            val networkType = getNetworkType()
            if (networkType != NetworkType.WIFI) return null

            val wm = wifiManager ?: return null

            // Try to get SSID from WifiManager
            val wifiInfo = wm.connectionInfo ?: return null
            var ssid = wifiInfo.ssid

            // SSID comes wrapped in quotes, remove them
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }

            // On Android 8.1+, SSID may be <unknown ssid> without location permission
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>" || ssid == "0x" || ssid == WifiManager.UNKNOWN_SSID) {
                return null
            }

            ssid
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied when getting WiFi SSID", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi SSID", e)
            null
        }
    }

    /**
     * Checks if iptables NFQUEUE rules are active
     * Must be called from IO dispatcher
     *
     * Note: grep -c returns exit code 1 when no matches found (even though it outputs "0"),
     * so we must check the output value, not the exit code.
     * Also checks ip6tables for IPv6 rules.
     */
    suspend fun checkIptablesActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check IPv4 iptables - grep -c outputs count even on exit code 1 (no matches)
            val ipv4Result = Shell.cmd("iptables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            val ipv4Count = ipv4Result.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            // Check IPv6 ip6tables
            val ipv6Result = Shell.cmd("ip6tables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            val ipv6Count = ipv6Result.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            return@withContext (ipv4Count + ipv6Count) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking iptables active", e)
        }
        false
    }

    /**
     * Gets the count of NFQUEUE rules in iptables
     * Must be called from IO dispatcher
     *
     * Note: grep -c returns exit code 1 when no matches found (even though it outputs "0"),
     * so we use "|| true" to ensure command always succeeds and we can read the count.
     * Also checks both iptables (IPv4) and ip6tables (IPv6).
     */
    suspend fun getNfqueueRulesCount(): Int = withContext(Dispatchers.IO) {
        try {
            var totalCount = 0

            // IPv4: Count NFQUEUE rules in OUTPUT chain (mangle table)
            val ipv4OutputResult = Shell.cmd("iptables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            totalCount += ipv4OutputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            // IPv4: Count NFQUEUE rules in INPUT chain (mangle table)
            val ipv4InputResult = Shell.cmd("iptables -t mangle -L INPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            totalCount += ipv4InputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            // IPv6: Count NFQUEUE rules in OUTPUT chain (mangle table)
            val ipv6OutputResult = Shell.cmd("ip6tables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            totalCount += ipv6OutputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            // IPv6: Count NFQUEUE rules in INPUT chain (mangle table)
            val ipv6InputResult = Shell.cmd("ip6tables -t mangle -L INPUT -n 2>/dev/null | grep -c NFQUEUE || true").exec()
            totalCount += ipv6InputResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            return@withContext totalCount
        } catch (e: Exception) {
            Log.e(TAG, "Error counting NFQUEUE rules", e)
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
     * @return true if registration succeeded, false otherwise
     */
    fun registerNetworkChangeListener(listener: NetworkChangeListener): Boolean {
        val cm = connectivityManager
        if (cm == null) {
            Log.e(TAG, "Cannot register network listener: ConnectivityManager is null")
            return false
        }

        // Unregister existing callback first to avoid duplicates
        unregisterNetworkChangeListener()

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

        return try {
            cm.registerNetworkCallback(networkRequest, networkCallback!!)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when registering network callback", e)
            networkCallback = null
            networkChangeListener = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
            networkCallback = null
            networkChangeListener = null
            false
        }
    }

    /**
     * Unregisters the network change listener
     * Safe to call multiple times
     */
    fun unregisterNetworkChangeListener() {
        val callback = networkCallback
        if (callback != null) {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Callback was not registered - ignore
                Log.d(TAG, "Network callback was not registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null
        networkChangeListener = null

        // Remove any pending callbacks from the handler
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun notifyNetworkChange() {
        // Capture listener reference to avoid race conditions
        val listener = networkChangeListener ?: return

        // NetworkCallback methods are called on a background thread,
        // so we must post to main thread for safe UI updates
        mainHandler.post {
            try {
                // Check if listener is still valid (may have been unregistered)
                if (networkChangeListener == null) return@post

                // We need to fetch stats async, so we create a simple stats object
                // with just network type info for immediate notification
                val quickStats = NetworkStats(
                    networkType = getNetworkType(),
                    wifiSsid = getWifiSsid(),
                    iptablesActive = false, // Will be updated by caller
                    nfqueueRulesCount = 0   // Will be updated by caller
                )
                listener.onNetworkChanged(quickStats)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying network change", e)
            }
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
