package com.zapret2.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ServiceEventSource {
    CONTROL,
    PRESETS,
    PROFILES,
}

@Singleton
class ServiceEventBus @Inject constructor() {
    private val _serviceRestarted = MutableSharedFlow<ServiceEventSource>(extraBufferCapacity = 1)
    val serviceRestarted = _serviceRestarted.asSharedFlow()

    fun notifyServiceRestarted(source: ServiceEventSource) {
        _serviceRestarted.tryEmit(source)
    }
}
