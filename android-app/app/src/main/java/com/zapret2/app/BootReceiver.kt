package com.zapret2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.topjohnwu.superuser.Shell

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if autostart is enabled in config
            Shell.cmd(
                "grep -q 'AUTOSTART=1' /data/adb/modules/zapret2/zapret2/config.sh && " +
                "sh /data/adb/modules/zapret2/zapret2/scripts/zapret-start.sh"
            ).submit()
        }
    }
}
