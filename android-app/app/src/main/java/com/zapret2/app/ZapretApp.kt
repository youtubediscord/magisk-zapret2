package com.zapret2.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZapretApp : Application() {

    companion object {
        private var shellInitialized = false
    }

    override fun onCreate() {
        super.onCreate()
        initShell()
    }

    private fun initShell() {
        if (!shellInitialized) {
            try {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(30)
                )
                shellInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
