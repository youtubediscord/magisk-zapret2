package com.zapret2.app

import android.util.Log

/** Debug-only application logging; R8 removes these branches from release builds. */
internal object AppDebugLog {

    fun error(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
    }
}
