package com.zapret2.app.data

/** Pure update state decisions, separated from root I/O for deterministic tests. */
internal object ModuleUpdateStatePolicy {

    enum class VerifiedState(val wireValue: String) {
        RUNNING("running"),
        STOPPED("stopped");

        companion object {
            fun fromWireValue(value: String): VerifiedState? = entries.firstOrNull { it.wireValue == value }
        }
    }

    sealed class RollbackResult {
        data class Restored(val state: VerifiedState) : RollbackResult()
        data class Incomplete(
            val message: String,
            val backupRetained: Boolean,
            val transactionRetained: Boolean,
        ) : RollbackResult()
    }

    fun snapshot(healthy: Boolean, fullyStopped: Boolean): VerifiedState? = when {
        healthy && !fullyStopped -> VerifiedState.RUNNING
        fullyStopped && !healthy -> VerifiedState.STOPPED
        else -> null
    }

    fun matches(state: VerifiedState, healthy: Boolean, fullyStopped: Boolean): Boolean = when (state) {
        VerifiedState.RUNNING -> healthy
        VerifiedState.STOPPED -> fullyStopped
    }
}
