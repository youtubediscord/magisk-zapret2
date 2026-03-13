package com.zapret2.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceEventBus @Inject constructor() {
    private val _serviceRestarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serviceRestarted = _serviceRestarted.asSharedFlow()

    fun notifyServiceRestarted() {
        _serviceRestarted.tryEmit(Unit)
    }
}
