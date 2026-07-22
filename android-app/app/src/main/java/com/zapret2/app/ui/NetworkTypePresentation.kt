package com.zapret2.app.ui

import androidx.annotation.StringRes
import com.zapret2.app.R
import com.zapret2.app.data.NetworkStatsManager.NetworkType

/** Maps the typed network state to localized presentation copy. */
@get:StringRes
internal val NetworkType.labelRes: Int
    get() = when (this) {
        NetworkType.WIFI -> R.string.network_type_wifi
        NetworkType.MOBILE -> R.string.network_type_mobile
        NetworkType.ETHERNET -> R.string.network_type_ethernet
        NetworkType.VPN -> R.string.network_type_vpn
        NetworkType.NONE -> R.string.network_type_none
    }
