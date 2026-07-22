package com.zapret2.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZapretApp : Application() {

    companion object {
        private const val TAG = "ZapretApp"

        @Volatile
        private var shellInitialized = false
    }

    override fun onCreate() {
        super.onCreate()
        initShell()
    }

    private fun initShell() {
        synchronized(ZapretApp::class.java) {
            if (shellInitialized) return

            try {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(30)
                )
                shellInitialized = true
            } catch (_: Exception) {
                AppDebugLog.error(TAG, "Failed to configure the root shell")
                return
            }
        }
    }
}
