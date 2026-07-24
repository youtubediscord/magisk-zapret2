package com.zapret2.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zapret2.app.AppDebugLog
import java.lang.ref.WeakReference

internal fun projectIptablesDetail(
    status: ServiceLifecycleController.ServiceStatus,
): NetworkStatsManager.IptablesDetail = NetworkStatsManager.IptablesDetail(
    rulesOk = if (status.rulesetVerified) status.nfqueueRulesCount else 0,
    rulesFail = (status.expectedRulesCount - status.nfqueueRulesCount).coerceAtLeast(0),
    rulesTotal = status.nfqueueRulesCount,
    status = status.declaredStatus,
    ownPid = status.pid,
    pidVerified = status.pidVerified,
    ownPidStarttime = status.pidStarttime,
    ownerGeneration = status.ownerGeneration,
    qnum = status.qnum,
    ipv4Active = status.ipv4Active,
    ipv6Active = status.ipv6Active,
    chains = status.chainsCount,
    anchors = status.anchorsCount,
    nfqueueSupported = status.nfqueueSupported,
    queueBypassSupported = status.queueBypassSupported,
    rulesExpected = status.expectedRulesCount,
    ipv4Rules = status.ipv4RulesCount,
    ipv6Rules = status.ipv6RulesCount,
    rulesetVerified = status.rulesetVerified,
    ownerMetadataVerified = status.ownerMetadataVerified,
    metadataComplete = status.metadataComplete,
)

/**
 * Projects the module's typed status contract into UI-facing network details.
 *
 * Firewall ownership and topology are interpreted only by the module lifecycle
 * boundary. The app deliberately does not read privileged owner metadata or parse firewall rules a
 * second time: doing so creates a competing contract and can reject a topology
 * that the module has already verified and published.
 */
class NetworkStatsManager(context: Context) {

    companion object {
        private const val TAG = "NetworkStatsManager"
    }

    private val contextRef = WeakReference(context.applicationContext)

    private val connectivityManager: ConnectivityManager? by lazy {
        contextRef.get()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    enum class NetworkType {
        WIFI,
        MOBILE,
        ETHERNET,
        VPN,
        NONE,
    }

    data class IptablesDetail(
        val rulesOk: Int = 0,
        val rulesFail: Int = 0,
        val rulesTotal: Int = 0,
        val status: String = "unknown",
        val ownPid: String = "",
        val pidVerified: Boolean = false,
        val ownPidStarttime: String = "",
        val ownerGeneration: String = "",
        val qnum: Int? = null,
        val ipv4Active: Boolean = false,
        val ipv6Active: Boolean = false,
        val chains: Int = 0,
        val anchors: Int = 0,
        val nfqueueSupported: Boolean = false,
        val queueBypassSupported: Boolean = false,
        val rulesExpected: Int = 0,
        val ipv4Rules: Int = 0,
        val ipv6Rules: Int = 0,
        val rulesetVerified: Boolean = false,
        val ownerMetadataVerified: Boolean = false,
        val metadataComplete: Boolean = false,
    )

    data class NetworkStats(
        val networkType: NetworkType,
        val iptablesDetail: IptablesDetail,
    )

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
        } catch (error: Exception) {
            AppDebugLog.error(TAG, "Error getting network type", error)
            NetworkType.NONE
        }
    }

    /** Pure projection of the single authoritative module status payload. */
    internal fun getIptablesDetail(
        status: ServiceLifecycleController.ServiceStatus,
    ): IptablesDetail = projectIptablesDetail(status)

    fun getNetworkStats(
        status: ServiceLifecycleController.ServiceStatus,
    ): NetworkStats = NetworkStats(
        networkType = getNetworkType(),
        iptablesDetail = getIptablesDetail(status),
    )
}
