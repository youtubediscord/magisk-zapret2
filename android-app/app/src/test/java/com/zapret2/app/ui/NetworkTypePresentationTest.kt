package com.zapret2.app.ui

import com.zapret2.app.R
import com.zapret2.app.data.NetworkStatsManager.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTypePresentationTest {

    @Test
    fun everyNetworkTypeMapsToAStableStringResource() {
        assertEquals(R.string.network_type_wifi, NetworkType.WIFI.labelRes)
        assertEquals(R.string.network_type_mobile, NetworkType.MOBILE.labelRes)
        assertEquals(R.string.network_type_ethernet, NetworkType.ETHERNET.labelRes)
        assertEquals(R.string.network_type_vpn, NetworkType.VPN.labelRes)
        assertEquals(R.string.network_type_none, NetworkType.NONE.labelRes)
    }
}
