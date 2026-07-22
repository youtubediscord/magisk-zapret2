package com.zapret2.app.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalReducedMotionEnabled = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reducedMotion by remember(resolver) {
        mutableStateOf(true)
    }
    var refreshGeneration by remember(resolver) { mutableLongStateOf(0L) }

    LaunchedEffect(resolver, refreshGeneration) {
        reducedMotion = readReducedMotionSetting(resolver)
    }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshGeneration++
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    return reducedMotion
}

private suspend fun readReducedMotionSetting(
    resolver: android.content.ContentResolver,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        isReducedMotionScale(
            Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ),
        )
    }.getOrDefault(true)
}

internal fun isReducedMotionScale(scale: Float): Boolean = !scale.isFinite() || scale <= 0f
