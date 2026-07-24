package com.zapret2.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Serializes every app-process mutation of the installed module. */
object ModuleMutationCoordinator {

    const val STATE_DIR = RuntimeStatePaths.STATE_DIR
    const val FULL_ROLLBACK_TRANSACTION = "$STATE_DIR/full-rollback.transaction"
    const val UNINSTALL_TOMBSTONE = "$STATE_DIR/uninstall.tombstone"
    const val MODULE_REMOVE_MARKER = "${RootModuleContract.ACTIVE_MODULE_DIR}/remove"
    const val MODULE_DISABLE_MARKER = "${RootModuleContract.ACTIVE_MODULE_DIR}/disable"

    class MutationBlockedException(message: String) : IllegalStateException(message)

    internal data class SafetyProbe(
        val fullRollbackTransaction: Boolean,
        val uninstallTombstone: Boolean,
        val moduleRemove: Boolean,
        val moduleDisabled: Boolean,
    )

    internal enum class Operation {
        MUTATION,
        PACKAGE_STAGING,
        LIFECYCLE_SCRIPT,
    }

    private sealed interface OwnedLeaseInspection {
        data class Owned(val lease: LifecycleMutationLockProtocol.Lease) : OwnedLeaseInspection
        data object Absent : OwnedLeaseInspection
        data object Unknown : OwnedLeaseInspection
    }

    private class Ownership(
        val operation: Operation,
        val lease: LifecycleMutationLockProtocol.Lease?,
    ) : AbstractCoroutineContextElement(Key), ThreadContextElement<Ownership?> {
        companion object Key : CoroutineContext.Key<Ownership>

        override fun updateThreadContext(context: CoroutineContext): Ownership? =
            threadOwnership.get().also { threadOwnership.set(this) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Ownership?) {
            if (oldState == null) threadOwnership.remove() else threadOwnership.set(oldState)
        }
    }

    private val mutex = Mutex()
    private val threadOwnership = ThreadLocal<Ownership?>()

    suspend fun <T> withMutation(block: suspend () -> T): T =
        withExclusive(Operation.MUTATION, checkUpdateState = true, checkRemovalState = true, block)

    /**
     * Runs a multi-step module mutation to its commit or rollback outcome after ownership is
     * admitted. Callers must use this boundary for snapshot/write/verify/restore sequences so a
     * screen or ViewModel cancellation cannot release the lifecycle lease between those steps.
     */
    suspend fun <T> withNonCancellableMutation(block: suspend () -> T): T =
        withMutation { withContext(NonCancellable) { block() } }

    suspend fun <T> withModuleStaging(block: suspend () -> T): T =
        withExclusive(Operation.PACKAGE_STAGING, checkUpdateState = false, checkRemovalState = true, block)

    /** Lifecycle scripts acquire lifecycle.lock themselves and must not be wrapped in an app lease. */
    suspend fun <T> withLifecycleScript(block: suspend () -> T): T =
        withExclusive(Operation.LIFECYCLE_SCRIPT, checkUpdateState = false, checkRemovalState = false, block)

    private suspend fun <T> withExclusive(
        operation: Operation,
        checkUpdateState: Boolean,
        checkRemovalState: Boolean,
        block: suspend () -> T
    ): T {
        val inherited = currentCoroutineContext()[Ownership]
        if (inherited != null) {
            if (operation == Operation.PACKAGE_STAGING &&
                inherited.operation != Operation.PACKAGE_STAGING
            ) {
                throw MutationBlockedException("Module package staging cannot start inside another mutation")
            }
            if (operation == Operation.LIFECYCLE_SCRIPT && inherited.lease != null) {
                throw MutationBlockedException("A full lifecycle transaction cannot start inside a module write")
            }
            return block()
        }

        mutex.lock()
        return try {
            if (requiresCrossProcessLease(operation)) {
                CancellationSafeMutationLease.run(
                    acquire = ::acquireCrossProcessLease,
                    release = ::releaseCrossProcessLease,
                ) { lease ->
                    withContext(Ownership(operation, lease)) {
                        ensureMutationAllowed(checkUpdateState = true, checkRemovalState = true)
                        block()
                    }
                }
            } else {
                withContext(Ownership(operation, lease = null)) {
                    if (checkUpdateState || checkRemovalState) {
                        ensureMutationAllowed(checkUpdateState, checkRemovalState)
                    }
                    block()
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    internal fun requiresCrossProcessLease(operation: Operation): Boolean =
        operation == Operation.MUTATION

    /** RootFileIo calls this synchronously on the coroutine thread before every privileged write. */
    internal fun requirePrivilegedMutationContext() {
        check(threadOwnership.get()?.lease != null) {
            "Privileged module write attempted without cross-process lifecycle ownership"
        }
    }

    /** Allows a nested start/stop/restart script to inherit, but never release, the app lease. */
    internal fun inheritLifecycleLock(command: String): String {
        val lease = threadOwnership.get()?.lease ?: return command
        return buildInheritedLifecycleCommand(command, lease)
    }

    /**
     * Authenticates a read-only status query as belonging to the mutation in this coroutine.
     * The status script may observe that exact lease as caller-owned, but never acquires,
     * recovers, releases, or otherwise mutates lifecycle ownership.
     */
    internal fun inheritLifecycleObservation(command: String): String {
        val lease = threadOwnership.get()?.lease ?: return command
        return buildInheritedLifecycleCommand(command, lease)
    }

    /** Generation expected from a lifecycle script running under this exact app-owned lease. */
    internal fun currentLifecycleToken(): String? = threadOwnership.get()?.lease?.token

    internal fun buildInheritedLifecycleCommand(
        command: String,
        lease: LifecycleMutationLockProtocol.Lease,
    ): String {
        return buildString {
            append("ZAPRET2_LIFECYCLE_TOKEN=").append(RootFileIo.shellQuote(lease.token)).append(' ')
            append("ZAPRET2_LIFECYCLE_OWNER_PID=").append(RootFileIo.shellQuote(lease.pid)).append(' ')
            append("ZAPRET2_LIFECYCLE_OWNER_START=").append(RootFileIo.shellQuote(lease.starttime)).append(' ')
            append(command)
        }
    }

    private suspend fun acquireCrossProcessLease(): LifecycleMutationLockProtocol.Lease {
        val pid = android.os.Process.myPid()
        val token = "app.${UUID.randomUUID().toString().replace("-", "")}"
        val command = LifecycleMutationLockProtocol.buildAcquireCommand(pid, token)
            ?: throw MutationBlockedException("Unable to create valid lifecycle ownership metadata")
        val result = ServiceLifecycleController.executeRoot(command, RootCommandPolicy.MUTATION)
        val lease = result.takeIf { it.success }
            ?.let { LifecycleMutationLockProtocol.parseAcquireOutput(it.stdout, pid, token) }
        if (lease != null) return lease

        // The publication is the commit point, not stdout delivery. If the command result is
        // truncated or fails after publication, retire only our exact live record before failing.
        when (val inspection = inspectPublishedLease(pid, token)) {
            is OwnedLeaseInspection.Owned -> releaseCrossProcessLease(inspection.lease)
            OwnedLeaseInspection.Absent,
            OwnedLeaseInspection.Unknown,
            -> Unit
        }
        throw MutationBlockedException(
            result.diagnosticText().ifBlank {
                if (result.success) {
                    "Lifecycle ownership response was invalid; changes were not started"
                } else {
                    "Another module lifecycle operation is active; changes were not saved"
                }
            }
        )
    }

    private suspend fun inspectPublishedLease(
        pid: Int,
        token: String,
    ): OwnedLeaseInspection {
        val command = LifecycleMutationLockProtocol.buildOwnedLeaseProbeCommand(pid, token)
            ?: return OwnedLeaseInspection.Unknown
        val result = ServiceLifecycleController.executeRoot(command, RootCommandPolicy.OBSERVATION)
        if (!result.success) return OwnedLeaseInspection.Unknown
        if (LifecycleMutationLockProtocol.isOwnedLeaseAbsentOutput(result.stdout)) {
            return OwnedLeaseInspection.Absent
        }
        val lease = LifecycleMutationLockProtocol.parseAcquireOutput(result.stdout, pid, token)
            ?: return OwnedLeaseInspection.Unknown
        return OwnedLeaseInspection.Owned(lease)
    }

    private suspend fun releaseCrossProcessLease(lease: LifecycleMutationLockProtocol.Lease) {
        var result = executeLeaseRelease(lease)
        if (result.success && LifecycleMutationLockProtocol.parseReleaseOutput(result.stdout)) return

        when (inspectPublishedLease(lease.pid.toInt(), lease.token)) {
            OwnedLeaseInspection.Absent -> return
            is OwnedLeaseInspection.Owned -> {
                // Releasing an exact PID/starttime/boot/token lease is an idempotent cleanup
                // postcondition, not a retry of the protected user mutation. Retry once whenever
                // our exact record is still published, including a deterministic gate collision.
                result = executeLeaseRelease(lease)
                if (result.success &&
                    LifecycleMutationLockProtocol.parseReleaseOutput(result.stdout)
                ) {
                    return
                }
                if (inspectPublishedLease(lease.pid.toInt(), lease.token) ==
                    OwnedLeaseInspection.Absent
                ) {
                    return
                }
            }
            OwnedLeaseInspection.Unknown -> Unit
        }
        throw MutationBlockedException(
            result.diagnosticText().ifBlank { "Lifecycle ownership release could not be verified" }
        )
    }

    private suspend fun executeLeaseRelease(
        lease: LifecycleMutationLockProtocol.Lease,
    ): ServiceLifecycleController.CommandResult {
        val releaseToken = UUID.randomUUID().toString().replace("-", "")
        val command = LifecycleMutationLockProtocol.buildReleaseCommand(lease, releaseToken)
            ?: throw MutationBlockedException("Unable to create lifecycle release metadata")
        return ServiceLifecycleController.executeRoot(command, RootCommandPolicy.MUTATION)
    }

    private suspend fun ensureMutationAllowed(checkUpdateState: Boolean, checkRemovalState: Boolean) {
        if (checkUpdateState && ServiceLifecycleController.isAppUpdateInProgress()) {
            throw MutationBlockedException("Module update is in progress; your changes were not saved")
        }

        val result = ServiceLifecycleController.executeRoot(
            """
                if [ -e ${RootFileIo.shellQuote(FULL_ROLLBACK_TRANSACTION)} ] || [ -L ${RootFileIo.shellQuote(FULL_ROLLBACK_TRANSACTION)} ]; then
                    echo Z2_FULL_ROLLBACK_TRANSACTION=1
                else
                    echo Z2_FULL_ROLLBACK_TRANSACTION=0
                fi
                if [ -e ${RootFileIo.shellQuote(UNINSTALL_TOMBSTONE)} ] || [ -L ${RootFileIo.shellQuote(UNINSTALL_TOMBSTONE)} ]; then
                    echo Z2_UNINSTALL_TOMBSTONE=1
                else
                    echo Z2_UNINSTALL_TOMBSTONE=0
                fi
                if [ -e ${RootFileIo.shellQuote(MODULE_REMOVE_MARKER)} ] || [ -L ${RootFileIo.shellQuote(MODULE_REMOVE_MARKER)} ]; then
                    echo Z2_MODULE_REMOVE=1
                else
                    echo Z2_MODULE_REMOVE=0
                fi
                if [ -e ${RootFileIo.shellQuote(MODULE_DISABLE_MARKER)} ] || [ -L ${RootFileIo.shellQuote(MODULE_DISABLE_MARKER)} ]; then
                    echo Z2_MODULE_DISABLED=1
                else
                    echo Z2_MODULE_DISABLED=0
                fi
                echo Z2_MUTATION_PROBE_COMPLETE=1
            """.trimIndent()
        )
        val probe = result.takeIf { it.success }?.let { parseSafetyProbe(it.stdout) }
        if (probe == null) {
            throw MutationBlockedException(
                result.diagnosticText().ifBlank { "Unable to verify module mutation safety; changes were not saved" }
            )
        }
        if (checkRemovalState && probe.uninstallTombstone) {
            throw MutationBlockedException("Module uninstall tombstone is present; changes were not saved")
        }
        if (checkRemovalState && probe.moduleRemove) {
            throw MutationBlockedException("Root-manager module removal is pending; changes were not saved")
        }
        if (checkUpdateState && probe.fullRollbackTransaction) {
            throw MutationBlockedException("A full rollback transaction requires recovery; your changes were not saved")
        }
        if (checkUpdateState && probe.moduleDisabled) {
            throw MutationBlockedException("The module is disabled; changes were not saved")
        }
    }

    internal fun parseSafetyProbe(lines: List<String>): SafetyProbe? {
        val completion = "Z2_MUTATION_PROBE_COMPLETE"
        val keys = setOf(
            "Z2_FULL_ROLLBACK_TRANSACTION",
            "Z2_UNINSTALL_TOMBSTONE",
            "Z2_MODULE_REMOVE",
            "Z2_MODULE_DISABLED",
            completion,
        )
        if (lines.size != keys.size || lines.lastOrNull() != "$completion=1") return null
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val counts = pairs.groupingBy { it.first }.eachCount()
        val values = pairs.toMap()
        if (counts.keys != keys || keys.any { counts[it] != 1 } ||
            keys.any { values[it] != "0" && values[it] != "1" } || values[completion] != "1"
        ) return null
        return SafetyProbe(
            fullRollbackTransaction = values["Z2_FULL_ROLLBACK_TRANSACTION"] == "1",
            uninstallTombstone = values["Z2_UNINSTALL_TOMBSTONE"] == "1",
            moduleRemove = values["Z2_MODULE_REMOVE"] == "1",
            moduleDisabled = values["Z2_MODULE_DISABLED"] == "1",
        )
    }
}
