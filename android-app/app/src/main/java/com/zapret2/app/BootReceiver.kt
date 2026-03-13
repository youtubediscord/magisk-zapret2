package com.zapret2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.RuntimeConfigStore

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val coreReadResult = RuntimeConfigStore.readCoreResultBlocking()
            val autostart = coreReadResult.values["autostart"]?.takeIf { it.isNotEmpty() } ?: "1"
            if (coreReadResult.usesRuntimeConfig && autostart == "1") {
                Shell.cmd("sh /data/adb/modules/zapret2/zapret2/scripts/zapret-start.sh").submit()
            }
        }
    }
}
