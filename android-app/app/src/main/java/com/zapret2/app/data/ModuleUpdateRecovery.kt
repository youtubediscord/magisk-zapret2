package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Durable recovery for an interrupted hot module directory swap. */
object ModuleUpdateRecovery {

    private const val MODULE_DIR = ServiceLifecycleController.MODULE_DIR
    private val safeToken = Regex("[A-Za-z0-9._-]+")

    /** Injectable boundary used by direct recovery tests. */
    internal interface RecoveryDependencies {
        suspend fun executeRoot(command: String): ServiceLifecycleController.CommandResult
        suspend fun inspectLock(): UpdateLockProtocol.State
        suspend fun acquireLock(
            pid: Int,
            token: String,
            requireTransaction: Boolean,
        ): UpdateLockProtocol.Record?
        suspend fun releaseLock(record: UpdateLockProtocol.Record): Boolean
        fun processId(): Int
        fun newToken(): String
        suspend fun delay(millis: Long)
    }

    private object ProductionRecoveryDependencies : RecoveryDependencies {
        override suspend fun executeRoot(command: String) = ServiceLifecycleController.executeRoot(command)
        override suspend fun inspectLock() = UpdateLockProtocol.inspect()
        override suspend fun acquireLock(
            pid: Int,
            token: String,
            requireTransaction: Boolean,
        ) = UpdateLockProtocol.acquire(pid, token, requireTransaction).getOrNull()
        override suspend fun releaseLock(record: UpdateLockProtocol.Record) =
            UpdateLockProtocol.release(record).success
        override fun processId(): Int = android.os.Process.myPid()
        override fun newToken(): String = UUID.randomUUID().toString()
        override suspend fun delay(millis: Long) = kotlinx.coroutines.delay(millis)
    }

    private class RecoveryDependenciesElement(
        val value: RecoveryDependencies,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RecoveryDependenciesElement>
    }

    private suspend fun dependencies(): RecoveryDependencies =
        currentCoroutineContext()[RecoveryDependenciesElement]?.value ?: ProductionRecoveryDependencies

    sealed class Result {
        object NotNeeded : Result()
        object Recovered : Result()
        data class Blocked(val message: String) : Result()
        data class Failed(val message: String) : Result()
    }

    internal data class Transaction(
        val transactionId: String,
        val phase: String,
        val createdEpoch: String,
        val preUpdateState: ModuleUpdateStatePolicy.VerifiedState,
        val disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        val ownerPid: String,
        val ownerStarttime: String,
        val ownerCreatedEpoch: String,
        val ownerBootId: String,
        val updateDir: String,
        val backupDir: String,
        val failedDir: String
    ) {
        val recoveryDir: String
            get() = "/data/adb/modules/.zapret2-recovery-$transactionId"
    }

    private data class TransactionSnapshot(val lines: List<String>, val sha256: String)
    private data class CleanupSnapshot(val lines: List<String>, val sha256: String)

    internal data class OwnerRebindPlan(
        val transaction: Transaction,
        val digest: String,
        val command: String,
    )

    private enum class MarkerState {
        ABSENT,
        ACTIVE,
        STALE,
        MALFORMED
    }

    internal data class DirectoryState(
        val module: Boolean,
        val update: Boolean,
        val backup: Boolean,
        val failed: Boolean,
        val recovery: Boolean,
    )

    internal enum class ActiveMoveEvidence {
        NOT_ACTIVE_MOVE,
        BEFORE_MOVE,
        MOVED_IN_FLIGHT,
        RESULT_PUBLISHED,
        AMBIGUOUS,
    }

    internal fun classifyActiveMoveEvidence(
        phase: String,
        modulePresent: Boolean,
        backupPresent: Boolean,
    ): ActiveMoveEvidence = when {
        phase == "active_move_intent" && modulePresent && !backupPresent -> ActiveMoveEvidence.BEFORE_MOVE
        phase == "active_move_intent" && backupPresent -> ActiveMoveEvidence.MOVED_IN_FLIGHT
        phase == "active_moved" && backupPresent -> ActiveMoveEvidence.RESULT_PUBLISHED
        phase in setOf("active_move_intent", "active_moved") -> ActiveMoveEvidence.AMBIGUOUS
        else -> ActiveMoveEvidence.NOT_ACTIVE_MOVE
    }

    private data class RecoveryMarker(
        val pid: Int,
        val starttime: String,
        val createdEpoch: String,
        val bootId: String,
        val token: String
    )

    suspend fun recoverIfNeeded(): Result {
        return try {
            ModuleMutationCoordinator.withRecovery {
                ServiceLifecycleController.runExclusiveLifecycleTask {
                    recoverLocked()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.Failed("Interrupted module update recovery failed")
        }
    }

    /** Caller must hold both the module-mutation and lifecycle coordinators. */
    internal suspend fun recoverLocked(): Result = recoverLockedInternal()

    /** Runs the production recovery state machine against an injectable root/lock boundary. */
    internal suspend fun recoverLocked(dependencies: RecoveryDependencies): Result =
        withContext(RecoveryDependenciesElement(dependencies)) { recoverLockedInternal() }

    private suspend fun recoverLockedInternal(): Result {
        val transactionSnapshot = readTransaction()
        val cleanupSnapshot = readCleanupPending()
        val markerState = readMarkerState()
        if (transactionSnapshot == null) {
            if (cleanupSnapshot != null) {
                val pending = UpdateTransactionProtocol.parseCleanupPending(cleanupSnapshot.lines)
                    ?: return Result.Failed("Committed update cleanup metadata is malformed; cleanup paths were preserved")
                if (markerState == MarkerState.ACTIVE) return Result.Blocked("Committed update cleanup is still active")
                if (markerState == MarkerState.MALFORMED) {
                    return Result.Failed("The module update marker is malformed; committed cleanup was preserved")
                }
                return withRecoveryMarker(
                    requireTransaction = false,
                    unavailableMessage = "Unable to acquire protected ownership of committed update cleanup",
                ) { recoveryMarker ->
                    recoverPendingCleanup(pending, cleanupSnapshot.sha256, recoveryMarker)
                }
            }
            return when (markerState) {
                MarkerState.ABSENT -> Result.NotNeeded
                MarkerState.ACTIVE -> Result.Blocked("A module update is still active")
                MarkerState.MALFORMED -> Result.Failed(
                    "The module update marker is malformed; lifecycle recovery is required"
                )
                MarkerState.STALE -> reclaimStaleMarkerWithoutTransaction()
            }
        }
        val transaction = parseTransaction(transactionSnapshot.lines)
            ?: return Result.Failed("The interrupted module update metadata is malformed; no files were changed")
        val uncommittedCleanup = cleanupSnapshot?.let { snapshot ->
            UpdateTransactionProtocol.parseCleanupPending(snapshot.lines)?.takeIf {
                it.transactionDigest == transactionSnapshot.sha256
            } ?: return Result.Failed(
                "Uncommitted cleanup metadata does not match the protected transaction; all files were preserved"
            )
        }

        if (markerState == MarkerState.ACTIVE) return Result.Blocked("A module update is still active")
        return withRecoveryMarker(
            requireTransaction = true,
            unavailableMessage = "Unable to acquire protected ownership of the interrupted update",
        ) { recoveryMarker ->
            if (uncommittedCleanup != null) {
                val discarded = dependencies().executeRoot(
                    requireNotNull(UpdateTransactionProtocol.buildDiscardUncommittedCleanup(
                        recoveryOwner = recoveryMarker.transactionOwner(),
                        expectedTransactionDigest = transactionSnapshot.sha256,
                        expectedPendingDigest = cleanupSnapshot.sha256,
                    ))
                )
                if (!discarded.success) {
                    return@withRecoveryMarker Result.Failed(
                        "Uncommitted cleanup preparation could not be discarded safely; transaction was preserved"
                    )
                }
            }
            val rebound = rebindTransactionOwner(transaction, transactionSnapshot.sha256, recoveryMarker)
                ?: return@withRecoveryMarker Result.Failed(
                    "The interrupted update could not be atomically rebound to its fresh recovery lock"
                )
            recoverTransaction(rebound, recoveryMarker)
        }
    }

    private suspend fun recoverPendingCleanup(
        pending: UpdateTransactionProtocol.CleanupPending,
        expectedDigest: String,
        marker: RecoveryMarker,
    ): Result {
        val command = UpdateTransactionProtocol.buildPendingCleanupRecovery(
            recoveryOwner = marker.transactionOwner(),
            pending = pending,
            expectedPendingDigest = expectedDigest,
        ) ?: return Result.Failed("Committed update cleanup metadata failed validation")
        return if (dependencies().executeRoot(command).success) {
            Result.Recovered
        } else {
            Result.Failed("Committed update cleanup remains pending and can be retried safely")
        }
    }

    private suspend fun recoverTransaction(
        transaction: Transaction,
        marker: RecoveryMarker
    ): Result {
        var directories = inspectDirectories(transaction)
            ?: return Result.Failed("Unable to inspect interrupted module update directories")

        if (classifyActiveMoveEvidence(transaction.phase, directories.module, directories.backup) ==
            ActiveMoveEvidence.AMBIGUOUS
        ) {
            return Result.Failed(
                "The active-to-backup move evidence is incomplete; all remaining copies and metadata were preserved"
            )
        }

        if (directories.backup) {
            if (directories.module && transaction.phase == "verified" &&
                verifyPublishedGeneration(transaction.disableMarkerExpectation)
            ) {
                return finishTerminalRecovery(transaction, transaction.phase, marker)
            }
            if (directories.module && transaction.phase == "restored" &&
                restoreExpectedState(
                    transaction.preUpdateState,
                    transaction.disableMarkerExpectation,
                    marker,
                    requireInstallGeneration = false,
                )
            ) {
                return finishTerminalRecovery(transaction, transaction.phase, marker)
            }
            return restoreRetainedBackup(transaction, marker, directories.module)
        }

        directories = inspectDirectories(transaction)
            ?: return Result.Failed("Unable to re-inspect interrupted module update directories")
        if (directories.module) {
            val unambiguousActive = transaction.phase in setOf(
                "prepared",
                "stopped",
                "candidate_ready",
                "active_move_intent",
                "verified",
                "restored",
                "restore_candidate_active"
            )
            if (!unambiguousActive) {
                return Result.Failed("The update backup is missing in phase ${transaction.phase}; ambiguous files were preserved")
            }
            if (transaction.phase == "verified") {
                if (!verifyPublishedGeneration(transaction.disableMarkerExpectation)) {
                    return Result.Failed(
                        "The committed software generation failed integrity verification; update metadata was preserved"
                    )
                }
                return finishTerminalRecovery(transaction, "verified", marker)
            }
            if (!restoreExpectedState(
                    transaction.preUpdateState,
                    transaction.disableMarkerExpectation,
                    marker,
                    requireInstallGeneration = false,
                )
            ) {
                return Result.Failed("The only available module copy could not reach the verified pre-update state; update metadata was preserved")
            }
            val terminalPhase = if (transaction.phase == "verified") "verified" else "restored"
            return finishTerminalRecovery(transaction, terminalPhase, marker)
        }

        if (!directories.update) {
            return Result.Failed("The module directory is absent and neither a backup nor prepared candidate is available")
        }
        return Result.Failed(
            "The module directory and backup are absent; the remaining candidate is not a verified copy of the previous module"
        )
    }

    private fun Result.message(): String = when (this) {
        Result.NotNeeded -> "No recovery was needed"
        Result.Recovered -> "Recovery completed"
        is Result.Blocked -> message
        is Result.Failed -> message
    }

    private suspend fun reclaimStaleMarkerWithoutTransaction(): Result {
        return withRecoveryMarker(
            requireTransaction = false,
            unavailableMessage = "A stale marker without a transaction could not be reclaimed safely",
        ) { Result.Recovered }
    }

    /**
     * Publishes ownership and installs its finally block in one cancellation-safe scope. The
     * assignment happens inside NonCancellable, so cancellation at the handoff cannot orphan a
     * freshly published recovery lock.
     */
    private suspend fun withRecoveryMarker(
        requireTransaction: Boolean,
        unavailableMessage: String,
        block: suspend (RecoveryMarker) -> Result,
    ): Result {
        var marker: RecoveryMarker? = null
        var outcome: Result = Result.Failed(unavailableMessage)
        var thrown: Throwable? = null
        try {
            withContext(NonCancellable) {
                marker = acquireRecoveryMarker(requireTransaction)
            }
            marker?.let { ownedMarker ->
                currentCoroutineContext().ensureActive()
                outcome = block(ownedMarker)
            }
        } catch (error: Throwable) {
            thrown = error
            throw error
        } finally {
            marker?.let { ownedMarker ->
                val released = withContext(NonCancellable) {
                    releaseRecoveryMarker(ownedMarker)
                }
                if (!released) {
                    val releaseError = IllegalStateException(
                        "The recovery ownership marker could not be released"
                    )
                    if (thrown != null) {
                        thrown.addSuppressed(releaseError)
                    } else {
                        outcome = Result.Failed(
                            "${outcome.message()}; recovery ownership marker could not be released",
                        )
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    internal fun parseTransaction(lines: List<String>): Transaction? {
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val currentRequired = setOf(
            "version",
            "transaction_id",
            "phase",
            "created_epoch",
            "pre_update_state",
            "disable_marker_expectation",
            "owner_pid",
            "owner_starttime",
            "owner_created_epoch",
            "owner_boot_id",
            "module_dir",
            "update_dir",
            "backup_dir",
            "failed_dir"
        )
        val legacyRequired = currentRequired - "owner_boot_id"
        val counts = pairs.groupingBy { it.first }.eachCount()
        val values = pairs.toMap()
        val version = values["version"] ?: return null
        val required = when (version) {
            UpdateTransactionProtocol.TRANSACTION_VERSION -> currentRequired
            UpdateTransactionProtocol.LEGACY_TRANSACTION_VERSION -> legacyRequired
            else -> return null
        }
        if (pairs.size != required.size || counts.keys != required || required.any { counts[it] != 1 }) return null
        val id = values["transaction_id"].orEmpty()
        val phase = values["phase"].orEmpty()
        val created = values["created_epoch"].orEmpty()
        val preUpdateState = ModuleUpdateStatePolicy.VerifiedState.fromWireValue(
            values["pre_update_state"].orEmpty()
        ) ?: return null
        val disableMarkerExpectation = ModuleUpdatePreservation.DisableMarkerExpectation.fromWireValue(
            values["disable_marker_expectation"].orEmpty()
        ) ?: return null
        val ownerPid = values["owner_pid"].orEmpty()
        val ownerStarttime = values["owner_starttime"].orEmpty()
        val ownerCreatedEpoch = values["owner_created_epoch"].orEmpty()
        val ownerBootId = values["owner_boot_id"].orEmpty()
        if (!safeToken.matches(id)) return null
        if (phase !in setOf(
                "prepared",
                "stopped",
                "candidate_ready",
                "active_move_intent",
                "active_moved",
                "candidate_active",
                "verified",
                "restored",
                "restore_copying",
                "restore_candidate_ready",
                "restore_active_moved",
                "restore_candidate_active"
            )
        ) return null
        if (!ProtocolDecimal.isCanonicalNonNegativeLong(created) || created == "0") return null
        if (!ownerPid.matches(Regex("[1-9][0-9]*")) || ownerPid.toIntOrNull() == null ||
            !ProtocolDecimal.isCanonicalNonNegativeLong(ownerStarttime) ||
            !ProtocolDecimal.isCanonicalNonNegativeLong(ownerCreatedEpoch) ||
            version == UpdateTransactionProtocol.TRANSACTION_VERSION &&
                !ownerBootId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
            version == UpdateTransactionProtocol.LEGACY_TRANSACTION_VERSION && ownerBootId.isNotEmpty()
        ) return null
        if (disableMarkerExpectation == ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT &&
            preUpdateState == ModuleUpdateStatePolicy.VerifiedState.RUNNING
        ) return null
        if (values["module_dir"] != MODULE_DIR) return null

        val expectedUpdate = "/data/adb/modules/.zapret2-update-$id"
        val expectedBackup = "/data/adb/modules/.zapret2-backup-$id"
        val expectedFailed = "/data/adb/modules/.zapret2-failed-$id"
        if (values["update_dir"] != expectedUpdate ||
            values["backup_dir"] != expectedBackup ||
            values["failed_dir"] != expectedFailed
        ) return null

        return Transaction(
            id,
            phase,
            created,
            preUpdateState,
            disableMarkerExpectation,
            ownerPid,
            ownerStarttime,
            ownerCreatedEpoch,
            ownerBootId,
            expectedUpdate,
            expectedBackup,
            expectedFailed,
        )
    }

    private suspend fun readTransaction(): TransactionSnapshot? {
        val path = RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)
        val stateDir = RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)
        val result = dependencies().executeRoot(
            """
                if [ ! -e $path ] && [ ! -L $path ]; then
                    echo Z2_TRANSACTION_ABSENT=1
                    exit 0
                fi
                [ -d $stateDir ] && [ ! -L $stateDir ] || exit 1
                [ "${'$'}(stat -c %u $stateDir 2>/dev/null)" = "0" ] || exit 1
                [ "${'$'}(stat -c %a $stateDir 2>/dev/null)" = "700" ] || exit 1
                [ -f $path ] && [ ! -L $path ] && [ -r $path ] || exit 1
                [ "${'$'}(stat -c %u $path 2>/dev/null)" = "0" ] || exit 1
                [ "${'$'}(stat -c %a $path 2>/dev/null)" = "600" ] || exit 1
                [ "${'$'}(stat -c %h $path 2>/dev/null)" = "1" ] || exit 1
                size=${'$'}(stat -c %s $path 2>/dev/null) || exit 1
                [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le 4096 ] || exit 1
                digest_before=${'$'}(sha256sum $path 2>/dev/null) || exit 1
                digest_before=${'$'}{digest_before%% *}
                case "${'$'}digest_before" in ''|*[!0-9a-f]*) exit 1 ;; esac
                [ "${'$'}{#digest_before}" -eq 64 ] || exit 1
                content=${'$'}(cat $path) || exit 1
                captured_digest=${'$'}(printf '%s\n' "${'$'}content" | sha256sum 2>/dev/null) || exit 1
                captured_digest=${'$'}{captured_digest%% *}
                [ "${'$'}captured_digest" = "${'$'}digest_before" ] || exit 1
                digest_after=${'$'}(sha256sum $path 2>/dev/null) || exit 1
                digest_after=${'$'}{digest_after%% *}
                [ "${'$'}digest_after" = "${'$'}digest_before" ] || exit 1
                echo Z2_TRANSACTION_SHA256=${'$'}digest_before
                printf '%s\n' "${'$'}content"
            """.trimIndent()
        )
        if (!result.success) {
            throw IllegalStateException(
                result.diagnosticText().ifBlank { "Unable to read protected module update metadata" }
            )
        }
        if (result.stdout == listOf("Z2_TRANSACTION_ABSENT=1")) return null
        if (result.stdout.any { it == "Z2_TRANSACTION_ABSENT=1" }) {
            throw IllegalStateException("Protected module update metadata returned an ambiguous absence record")
        }
        val digestLines = result.stdout.filter { it.startsWith("Z2_TRANSACTION_SHA256=") }
        if (digestLines.size != 1 || result.stdout.firstOrNull() != digestLines.single()) {
            throw IllegalStateException("Protected module update metadata returned an invalid digest record")
        }
        val digest = digestLines.single().substringAfter('=')
        if (!digest.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalStateException("Protected module update metadata digest is invalid")
        }
        return TransactionSnapshot(result.stdout.drop(1), digest)
    }

    private suspend fun readCleanupPending(): CleanupSnapshot? {
        val path = RootFileIo.shellQuote(UpdateTransactionProtocol.CLEANUP_PENDING)
        val stateDir = RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)
        val result = dependencies().executeRoot(
            """
                if [ ! -e $path ] && [ ! -L $path ]; then
                    echo Z2_CLEANUP_ABSENT=1
                    exit 0
                fi
                [ -d $stateDir ] && [ ! -L $stateDir ] || exit 1
                [ "${'$'}(stat -c %u $stateDir 2>/dev/null)" = "0" ] || exit 1
                [ "${'$'}(stat -c %a $stateDir 2>/dev/null)" = "700" ] || exit 1
                [ -f $path ] && [ ! -L $path ] && [ -r $path ] || exit 1
                [ "${'$'}(stat -c %u $path 2>/dev/null)" = "0" ] || exit 1
                [ "${'$'}(stat -c %a $path 2>/dev/null)" = "600" ] || exit 1
                [ "${'$'}(stat -c %h $path 2>/dev/null)" = "1" ] || exit 1
                size=${'$'}(stat -c %s $path 2>/dev/null) || exit 1
                [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le 4096 ] || exit 1
                digest_before=${'$'}(sha256sum $path 2>/dev/null) || exit 1
                digest_before=${'$'}{digest_before%% *}
                case "${'$'}digest_before" in ''|*[!0-9a-f]*) exit 1 ;; esac
                [ "${'$'}{#digest_before}" -eq 64 ] || exit 1
                content=${'$'}(cat $path) || exit 1
                captured_digest=${'$'}(printf '%s\n' "${'$'}content" | sha256sum 2>/dev/null) || exit 1
                captured_digest=${'$'}{captured_digest%% *}
                [ "${'$'}captured_digest" = "${'$'}digest_before" ] || exit 1
                digest_after=${'$'}(sha256sum $path 2>/dev/null) || exit 1
                digest_after=${'$'}{digest_after%% *}
                [ "${'$'}digest_after" = "${'$'}digest_before" ] || exit 1
                echo Z2_CLEANUP_SHA256=${'$'}digest_before
                printf '%s\n' "${'$'}content"
            """.trimIndent()
        )
        if (!result.success) {
            throw IllegalStateException(
                result.diagnosticText().ifBlank { "Unable to read protected committed cleanup metadata" }
            )
        }
        if (result.stdout == listOf("Z2_CLEANUP_ABSENT=1")) return null
        if (result.stdout.any { it == "Z2_CLEANUP_ABSENT=1" }) {
            throw IllegalStateException("Committed cleanup metadata returned an ambiguous absence record")
        }
        val digestLines = result.stdout.filter { it.startsWith("Z2_CLEANUP_SHA256=") }
        if (digestLines.size != 1 || result.stdout.firstOrNull() != digestLines.single()) {
            throw IllegalStateException("Committed cleanup metadata returned an invalid digest record")
        }
        val digest = digestLines.single().substringAfter('=')
        if (!digest.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalStateException("Committed cleanup metadata digest is invalid")
        }
        return CleanupSnapshot(result.stdout.drop(1), digest)
    }

    private suspend fun readMarkerState(): MarkerState {
        return when (dependencies().inspectLock()) {
            UpdateLockProtocol.State.ABSENT -> MarkerState.ABSENT
            UpdateLockProtocol.State.ACTIVE -> MarkerState.ACTIVE
            UpdateLockProtocol.State.STALE -> MarkerState.STALE
            UpdateLockProtocol.State.MALFORMED -> MarkerState.MALFORMED
        }
    }

    /** Claims a fresh current-boot v2 lock before orphan-recovery lifecycle commands are allowed. */
    private suspend fun acquireRecoveryMarker(requireTransaction: Boolean = true): RecoveryMarker? {
        val backend = dependencies()
        val pid = backend.processId()
        val token = backend.newToken()
        val record = backend.acquireLock(pid, token, requireTransaction) ?: return null
        return RecoveryMarker(pid, record.starttime, record.createdEpoch, record.bootId, record.token)
    }

    private suspend fun releaseRecoveryMarker(marker: RecoveryMarker): Boolean {
        return dependencies().releaseLock(
            UpdateLockProtocol.Record(
                pid = marker.pid.toString(),
                starttime = marker.starttime,
                createdEpoch = marker.createdEpoch,
                bootId = marker.bootId,
                token = marker.token,
            )
        )
    }

    private fun authorizedRecoveryLifecycle(
        marker: RecoveryMarker,
        script: String,
        arguments: List<String> = emptyList(),
    ): String {
        val suffix = arguments.joinToString(separator = "") { argument ->
            " ${RootFileIo.shellQuote(argument)}"
        }
        return "ZAPRET2_UPDATE_TOKEN=${RootFileIo.shellQuote(marker.token)} " +
            "ZAPRET2_UPDATE_OWNER_PID=${marker.pid} " +
            "ZAPRET2_UPDATE_OWNER_START=${RootFileIo.shellQuote(marker.starttime)} " +
            "ZAPRET2_UPDATE_OWNER_CREATED=${RootFileIo.shellQuote(marker.createdEpoch)} " +
            "ZAPRET2_UPDATE_OWNER_BOOT=${RootFileIo.shellQuote(marker.bootId)} " +
            "sh ${RootFileIo.shellQuote(script)}$suffix"
    }

    private fun verifiedRecoveryLifecycle(
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        marker: RecoveryMarker,
        script: String,
        arguments: List<String> = emptyList(),
        requireInstallGeneration: Boolean,
    ): String = buildString {
        append("{ ").append(activeModuleIntegrityPredicate(requireInstallGeneration)).append("; } && ")
        append("{ ").append(
            ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)
        ).append("; } && ")
        append(authorizedRecoveryLifecycle(marker, script, arguments))
    }

    /** Exact minimum trusted active tree needed before executing a module lifecycle script. */
    internal fun activeModuleIntegrityPredicate(requireInstallGeneration: Boolean): String =
        moduleIntegrityPredicate(MODULE_DIR, requireInstallGeneration)

    /** The same exact tree predicate is reused before copying or promoting a retained backup. */
    internal fun moduleIntegrityPredicate(
        moduleDir: String,
        requireInstallGeneration: Boolean,
    ): String = buildString {
        append(safeRootDirectoryPredicate(moduleDir))
        append(" && ").append(safeRootDirectoryPredicate("$moduleDir/zapret2"))
        append(" && ").append(safeRootDirectoryPredicate("$moduleDir/zapret2/scripts"))
        append(" && ").append(secureRootRegularFilePredicate("$moduleDir/module.prop", "644"))
        if (requireInstallGeneration) {
            append(" && ").append(
                secureRootRegularFilePredicate(
                    "$moduleDir/${ModulePackageContract.LIFECYCLE_CONTRACT_PATH}",
                    "644",
                )
            )
            append(" && [ \"${'$'}(cat ")
                .append(RootFileIo.shellQuote("$moduleDir/${ModulePackageContract.LIFECYCLE_CONTRACT_PATH}"))
                .append(" 2>/dev/null)\" = 2 ]")
        }
        append(" && ").append(secureRootRegularFilePredicate("$moduleDir/zapret2/nfqws2", "755"))
        listOf(
            "common.sh",
            "command-builder.sh",
            "zapret-start.sh",
            "zapret-stop.sh",
            "zapret-status.sh",
        ).forEach { script ->
            append(" && ").append(
                secureRootRegularFilePredicate("$moduleDir/zapret2/scripts/$script", "755")
            )
        }
        append(" && ").append(
            InstallGenerationMetadata.shellValidator(
                RootFileIo.shellQuote("$moduleDir/${InstallGenerationMetadata.RELATIVE_PATH}"),
                required = requireInstallGeneration,
            )
        )
    }

    internal fun safeRootDirectoryOrAbsentPredicate(path: String): String {
        val quoted = RootFileIo.shellQuote(path)
        return "{ [ ! -e $quoted ] && [ ! -L $quoted ]; } || " +
            "{ ${safeRootDirectoryPredicate(path)}; }"
    }

    internal fun safeRootDirectoryPredicate(path: String): String {
        val quoted = RootFileIo.shellQuote(path)
        return "[ -d $quoted ] && [ ! -L $quoted ] && " +
            "[ \"${'$'}(stat -c %u $quoted 2>/dev/null)\" = 0 ] && " +
            "{ z2_dir_mode=${'$'}(stat -c %a $quoted 2>/dev/null) && " +
            "case \"${'$'}z2_dir_mode\" in 700|711|750|751|755) true ;; *) false ;; esac; }"
    }

    private fun secureRootRegularFilePredicate(path: String, mode: String): String {
        val quoted = RootFileIo.shellQuote(path)
        return "[ -f $quoted ] && [ ! -L $quoted ] && " +
            "[ \"${'$'}(stat -c %u $quoted 2>/dev/null)\" = 0 ] && " +
            "[ \"${'$'}(stat -c %a $quoted 2>/dev/null)\" = $mode ] && " +
            "[ \"${'$'}(stat -c %h $quoted 2>/dev/null)\" = 1 ]"
    }

    private fun transactionContent(transaction: Transaction, phase: String = transaction.phase): String = buildString {
        append("version=").append(UpdateTransactionProtocol.TRANSACTION_VERSION).append('\n')
        append("transaction_id=").append(transaction.transactionId).append('\n')
        append("phase=").append(phase).append('\n')
        append("created_epoch=").append(transaction.createdEpoch).append('\n')
        append("pre_update_state=").append(transaction.preUpdateState.wireValue).append('\n')
        append("disable_marker_expectation=").append(transaction.disableMarkerExpectation.wireValue).append('\n')
        append("owner_pid=").append(transaction.ownerPid).append('\n')
        append("owner_starttime=").append(transaction.ownerStarttime).append('\n')
        append("owner_created_epoch=").append(transaction.ownerCreatedEpoch).append('\n')
        append("owner_boot_id=").append(transaction.ownerBootId).append('\n')
        append("module_dir=").append(MODULE_DIR).append('\n')
        append("update_dir=").append(transaction.updateDir).append('\n')
        append("backup_dir=").append(transaction.backupDir).append('\n')
        append("failed_dir=").append(transaction.failedDir).append('\n')
    }

    /** CAS-rebinds an authenticated transaction snapshot to the fresh live recovery lock. */
    private suspend fun rebindTransactionOwner(
        transaction: Transaction,
        expectedSha256: String,
        marker: RecoveryMarker,
    ): Transaction? {
        val plan = buildOwnerRebindPlan(
            transaction = transaction,
            expectedSha256 = expectedSha256,
            ownerPid = marker.pid,
            ownerStarttime = marker.starttime,
            ownerCreatedEpoch = marker.createdEpoch,
            ownerBootId = marker.bootId,
            ownerToken = marker.token,
        ) ?: return null
        return plan.transaction.takeIf {
            dependencies().executeRoot(plan.command).success
        }
    }

    internal fun buildOwnerRebindPlan(
        transaction: Transaction,
        expectedSha256: String,
        ownerPid: Int,
        ownerStarttime: String,
        ownerCreatedEpoch: String,
        ownerBootId: String,
        ownerToken: String,
    ): OwnerRebindPlan? {
        if (!expectedSha256.matches(Regex("[0-9a-f]{64}")) || ownerPid <= 0 ||
            !ProtocolDecimal.isCanonicalNonNegativeLong(ownerStarttime) ||
            !ProtocolDecimal.isCanonicalNonNegativeLong(ownerCreatedEpoch) ||
            !ownerBootId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
            !safeToken.matches(ownerToken)
        ) return null
        val rebound = transaction.copy(
            ownerPid = ownerPid.toString(),
            ownerStarttime = ownerStarttime,
            ownerCreatedEpoch = ownerCreatedEpoch,
            ownerBootId = ownerBootId,
        )
        val reboundContent = transactionContent(rebound)
        val reboundDigest = UpdateTransactionProtocol.sha256(reboundContent)
        val tempPath = "${ModuleMutationCoordinator.STATE_DIR}/.update.transaction.rebind.$ownerPid"
        val delimiter = "__ZAPRET_RECOVERY_REBIND_${ownerPid}_${reboundDigest.take(16)}__"
        val command = buildString {
            append("state_dir=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)).append('\n')
            append("lock=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)).append('\n')
            append("transaction=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)).append('\n')
            append("tmp=").append(RootFileIo.shellQuote(tempPath)).append('\n')
            append("[ -d \"${'$'}state_dir\" ] && [ ! -L \"${'$'}state_dir\" ] || exit 1\n")
            append("[ \"${'$'}(stat -c %u \"${'$'}state_dir\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %a \"${'$'}state_dir\" 2>/dev/null)\" = 700 ] || exit 1\n")
            append(UpdateLockProtocol.shellParser()).append('\n')
            append("z2_read_update_lock \"${'$'}lock\" && z2_update_lock_owner_alive || exit 1\n")
            append("[ \"${'$'}z2_lock_pid\" = ").append(ownerPid).append(" ] && ")
                .append("[ \"${'$'}z2_lock_start\" = ").append(RootFileIo.shellQuote(ownerStarttime)).append(" ] && ")
                .append("[ \"${'$'}z2_lock_created\" = ").append(RootFileIo.shellQuote(ownerCreatedEpoch)).append(" ] && ")
                .append("[ \"${'$'}z2_lock_boot\" = ").append(RootFileIo.shellQuote(ownerBootId)).append(" ] && ")
                .append("[ \"${'$'}z2_lock_token\" = ").append(RootFileIo.shellQuote(ownerToken)).append(" ] || exit 1\n")
            append("[ -f \"${'$'}transaction\" ] && [ ! -L \"${'$'}transaction\" ] && ")
                .append("[ \"${'$'}(stat -c %u \"${'$'}transaction\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %a \"${'$'}transaction\" 2>/dev/null)\" = 600 ] && ")
                .append("[ \"${'$'}(stat -c %h \"${'$'}transaction\" 2>/dev/null)\" = 1 ] || exit 1\n")
            append("old_digest=${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }') || exit 1\n")
            append("[ \"${'$'}old_digest\" = ").append(RootFileIo.shellQuote(expectedSha256)).append(" ] || exit 1\n")
            append("umask 077\nrm -f \"${'$'}tmp\"\n")
            append("cat > \"${'$'}tmp\" <<'").append(delimiter).append("'\n")
            append(reboundContent).append(delimiter).append('\n')
            append("chmod 0600 \"${'$'}tmp\" || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}(stat -c %u \"${'$'}tmp\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %a \"${'$'}tmp\" 2>/dev/null)\" = 600 ] && ")
                .append("[ \"${'$'}(stat -c %h \"${'$'}tmp\" 2>/dev/null)\" = 1 ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("new_digest=${'$'}(sha256sum \"${'$'}tmp\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }') || exit 1\n")
            append("[ \"${'$'}new_digest\" = ").append(RootFileIo.shellQuote(reboundDigest))
                .append(" ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(expectedSha256)).append(" ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("z2_read_update_lock \"${'$'}lock\" && z2_update_lock_owner_alive || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}z2_lock_pid:${'$'}z2_lock_start:${'$'}z2_lock_created:${'$'}z2_lock_boot:${'$'}z2_lock_token\" = ")
                .append(RootFileIo.shellQuote("$ownerPid:$ownerStarttime:$ownerCreatedEpoch:$ownerBootId:$ownerToken"))
                .append(" ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("mv -f \"${'$'}tmp\" \"${'$'}transaction\" || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("sync || exit 1\n")
            append("[ \"${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = \"${'$'}new_digest\" ]\n")
        }
        return OwnerRebindPlan(rebound, reboundDigest, command)
    }

    private suspend fun inspectDirectories(transaction: Transaction): DirectoryState? {
        val result = dependencies().executeRoot(buildDirectoryProbe(transaction))
        if (!result.success) return null
        return parseDirectoryProbe(result.stdout)
    }

    /** Rejects links, non-root ownership, and unexpected directory modes before recovery acts. */
    internal fun buildDirectoryProbe(transaction: Transaction): String = """
        z2_probe_update_dir() {
          z2_probe_key="${'$'}1"
          z2_probe_path="${'$'}2"
          if [ ! -e "${'$'}z2_probe_path" ] && [ ! -L "${'$'}z2_probe_path" ]; then
            echo "${'$'}z2_probe_key=0"
            return 0
          fi
          [ -d "${'$'}z2_probe_path" ] && [ ! -L "${'$'}z2_probe_path" ] &&
            [ "${'$'}(stat -c %u "${'$'}z2_probe_path" 2>/dev/null)" = 0 ] || return 1
          z2_probe_mode=${'$'}(stat -c %a "${'$'}z2_probe_path" 2>/dev/null) || return 1
          case "${'$'}z2_probe_mode" in 700|711|750|751|755) ;; *) return 1 ;; esac
          echo "${'$'}z2_probe_key=1"
        }
        z2_probe_update_dir Z2_DIR_MODULE ${RootFileIo.shellQuote(MODULE_DIR)} || exit 1
        z2_probe_update_dir Z2_DIR_UPDATE ${RootFileIo.shellQuote(transaction.updateDir)} || exit 1
        z2_probe_update_dir Z2_DIR_BACKUP ${RootFileIo.shellQuote(transaction.backupDir)} || exit 1
        z2_probe_update_dir Z2_DIR_FAILED ${RootFileIo.shellQuote(transaction.failedDir)} || exit 1
        z2_probe_update_dir Z2_DIR_RECOVERY ${RootFileIo.shellQuote(transaction.recoveryDir)} || exit 1
        echo Z2_DIR_PROBE_COMPLETE=1
    """.trimIndent()

    internal fun parseDirectoryProbe(lines: List<String>): DirectoryState? {
        val completion = "Z2_DIR_PROBE_COMPLETE"
        val keys = setOf(
            "Z2_DIR_MODULE", "Z2_DIR_UPDATE", "Z2_DIR_BACKUP", "Z2_DIR_FAILED",
            "Z2_DIR_RECOVERY", completion,
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
        return DirectoryState(
            module = values["Z2_DIR_MODULE"] == "1",
            update = values["Z2_DIR_UPDATE"] == "1",
            backup = values["Z2_DIR_BACKUP"] == "1",
            failed = values["Z2_DIR_FAILED"] == "1",
            recovery = values["Z2_DIR_RECOVERY"] == "1",
        )
    }

    private suspend fun stopActiveModuleForRollback(
        transaction: Transaction,
        marker: RecoveryMarker,
    ): Boolean = restoreExpectedState(
        state = ModuleUpdateStatePolicy.VerifiedState.STOPPED,
        disableMarkerExpectation = transaction.disableMarkerExpectation,
        marker = marker,
        requireInstallGeneration = false,
    )

    /** Restores from a copy while keeping backupDir untouched until terminal commit is durable. */
    private suspend fun restoreRetainedBackup(
        transaction: Transaction,
        marker: RecoveryMarker,
        moduleInitiallyPresent: Boolean,
    ): Result {
        var journal = transaction
        if (!verifyDisableMarkerExpectation(transaction.backupDir, transaction.disableMarkerExpectation)) {
            return Result.Failed("The retained backup does not match the durable disable-marker expectation")
        }
        if (moduleInitiallyPresent && !stopActiveModuleForRollback(transaction, marker)) {
            return Result.Failed("Unable to stop the interrupted update candidate before rollback")
        }
        journal = writeTransactionPhase(journal, "restore_copying", marker)
            ?: return Result.Failed("Unable to journal retained-backup recovery before copying")
        val copyGuard = recoveryTransactionGuard(journal, marker)
            ?: return Result.Failed("The recovery owner/journal identity became invalid before copying")
        val copied = dependencies().executeRoot(
            """
                $copyGuard
                { ${moduleIntegrityPredicate(transaction.backupDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(transaction.backupDir, transaction.disableMarkerExpectation)}; } || exit 1
                { ${safeRootDirectoryOrAbsentPredicate(transaction.recoveryDir)}; } || exit 1
                rm -rf ${RootFileIo.shellQuote(transaction.recoveryDir)} || exit 1
                $copyGuard
                { ${moduleIntegrityPredicate(transaction.backupDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(transaction.backupDir, transaction.disableMarkerExpectation)}; } || exit 1
                [ ! -e ${RootFileIo.shellQuote(transaction.recoveryDir)} ] && [ ! -L ${RootFileIo.shellQuote(transaction.recoveryDir)} ] || exit 1
                cp -a ${RootFileIo.shellQuote(transaction.backupDir)} ${RootFileIo.shellQuote(transaction.recoveryDir)} || exit 1
                { ${moduleIntegrityPredicate(transaction.recoveryDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(transaction.recoveryDir, transaction.disableMarkerExpectation)}; } || exit 1
                sync || exit 1
            """.trimIndent()
        )
        if (!copied.success) {
            return Result.Failed("Unable to create a durable recovery copy; the verified backup was retained")
        }
        journal = writeTransactionPhase(journal, "restore_candidate_ready", marker)
            ?: return Result.Failed("Unable to create a durable recovery copy; the verified backup was retained")
        val moveGuard = recoveryTransactionGuard(journal, marker)
            ?: return Result.Failed("The recovery owner/journal identity became invalid before moving the candidate")

        val movedActive = dependencies().executeRoot(
            """
                $moveGuard
                if [ -e ${RootFileIo.shellQuote(MODULE_DIR)} ] || [ -L ${RootFileIo.shellQuote(MODULE_DIR)} ]; then
                    { ${moduleIntegrityPredicate(MODULE_DIR, requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, transaction.disableMarkerExpectation)}; } || exit 1
                    { ${safeRootDirectoryOrAbsentPredicate(transaction.failedDir)}; } || exit 1
                    rm -rf ${RootFileIo.shellQuote(transaction.failedDir)} || exit 1
                    $moveGuard
                    { ${moduleIntegrityPredicate(MODULE_DIR, requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, transaction.disableMarkerExpectation)}; } || exit 1
                    [ ! -e ${RootFileIo.shellQuote(transaction.failedDir)} ] && [ ! -L ${RootFileIo.shellQuote(transaction.failedDir)} ] || exit 1
                    mv ${RootFileIo.shellQuote(MODULE_DIR)} ${RootFileIo.shellQuote(transaction.failedDir)} || exit 1
                fi
                sync || exit 1
            """.trimIndent()
        )
        if (!movedActive.success) {
            return Result.Failed("Unable to make room for the recovery copy; the verified backup was retained")
        }
        journal = writeTransactionPhase(journal, "restore_active_moved", marker)
            ?: return Result.Failed("Unable to make room for the recovery copy; the verified backup was retained")
        val promotionGuard = recoveryTransactionGuard(journal, marker)
            ?: return Result.Failed("The recovery owner/journal identity became invalid before promotion")

        val promoted = dependencies().executeRoot(
            """
                $promotionGuard
                [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ] || exit 1
                { ${moduleIntegrityPredicate(transaction.recoveryDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(transaction.recoveryDir, transaction.disableMarkerExpectation)}; } || exit 1
                $promotionGuard
                [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ] || exit 1
                { ${moduleIntegrityPredicate(transaction.recoveryDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(transaction.recoveryDir, transaction.disableMarkerExpectation)}; } || exit 1
                mv ${RootFileIo.shellQuote(transaction.recoveryDir)} ${RootFileIo.shellQuote(MODULE_DIR)} || exit 1
                sync || exit 1
                { ${moduleIntegrityPredicate(MODULE_DIR, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, transaction.disableMarkerExpectation)}; } || exit 1
            """.trimIndent()
        )
        if (!promoted.success) {
            return Result.Failed("Unable to activate the recovery copy; the verified backup was retained")
        }
        journal = writeTransactionPhase(journal, "restore_candidate_active", marker)
            ?: return Result.Failed("Unable to activate the recovery copy; the verified backup was retained")
        if (!restoreExpectedState(
                transaction.preUpdateState,
                transaction.disableMarkerExpectation,
                marker,
                requireInstallGeneration = false,
            )
        ) {
            return Result.Failed("The recovery copy did not return to its verified pre-update state; backup retained")
        }
        return finishTerminalRecovery(journal, "restored", marker)
    }

    private suspend fun restoreExpectedState(
        state: ModuleUpdateStatePolicy.VerifiedState,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        marker: RecoveryMarker,
        requireInstallGeneration: Boolean,
    ): Boolean {
        val script = when (state) {
            ModuleUpdateStatePolicy.VerifiedState.RUNNING -> "$MODULE_DIR/zapret2/scripts/zapret-start.sh"
            ModuleUpdateStatePolicy.VerifiedState.STOPPED -> "$MODULE_DIR/zapret2/scripts/zapret-stop.sh"
        }
        val action = dependencies().executeRoot(
            verifiedRecoveryLifecycle(
                disableMarkerExpectation = disableMarkerExpectation,
                marker = marker,
                script = script,
                requireInstallGeneration = requireInstallGeneration,
            )
        )
        if (!action.success) return false
        repeat(5) {
            val statusResult = dependencies().executeRoot(
                verifiedRecoveryLifecycle(
                    disableMarkerExpectation = disableMarkerExpectation,
                    marker = marker,
                    script = "$MODULE_DIR/zapret2/scripts/zapret-status.sh",
                    arguments = listOf("--machine"),
                    requireInstallGeneration = requireInstallGeneration,
                )
            )
            val status = ServiceLifecycleController.parseStatusCommandResult(statusResult)
            if (ModuleUpdateStatePolicy.matches(state, status.healthy, status.fullyStopped)) return true
            dependencies().delay(250)
        }
        return false
    }

    private suspend fun verifyDisableMarkerExpectation(
        moduleDir: String,
        expectation: ModuleUpdatePreservation.DisableMarkerExpectation,
    ): Boolean = dependencies().executeRoot(
        ModuleUpdatePreservation.expectedDisableMarkerPredicate(moduleDir, expectation)
    ).success

    private suspend fun verifyPublishedGeneration(
        expectation: ModuleUpdatePreservation.DisableMarkerExpectation,
    ): Boolean = dependencies().executeRoot(
        "{ ${activeModuleIntegrityPredicate(requireInstallGeneration = true)}; } && " +
            "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, expectation)}; }"
    ).success

    private suspend fun writeTransactionPhase(
        transaction: Transaction,
        phase: String,
        marker: RecoveryMarker,
    ): Transaction? {
        if (phase !in setOf(
                "verified",
                "restored",
                "restore_copying",
                "restore_candidate_ready",
                "restore_active_moved",
                "restore_candidate_active",
            )
        ) return null
        val pid = dependencies().processId()
        val tempPath = "${ModuleMutationCoordinator.STATE_DIR}/.update.transaction.recovery.$pid"
        val next = transaction.copy(phase = phase)
        val publication = UpdateTransactionProtocol.buildPublication(
            owner = marker.transactionOwner(),
            content = transactionContent(next),
            expectedPriorDigest = UpdateTransactionProtocol.sha256(transactionContent(transaction)),
            tempPath = tempPath,
        ) ?: return null
        return next.takeIf { dependencies().executeRoot(publication.command).success }
    }

    private suspend fun finishTerminalRecovery(
        transaction: Transaction,
        terminalPhase: String,
        marker: RecoveryMarker,
    ): Result {
        if (terminalPhase !in setOf("verified", "restored")) {
            return Result.Failed("The active module was verified, but its terminal recovery phase was not durable")
        }
        val terminal = writeTransactionPhase(transaction, terminalPhase, marker)
            ?: return Result.Failed("The active module was verified, but its terminal recovery phase was not durable")

        val plan = buildTerminalCommitPlan(terminal, marker.token)
        val committed = CancellationSafeTerminalCommit.run(
            command = { executeRecoveryTerminalPlan(marker, plan) },
            commandSucceeded = { it == UpdateTransactionProtocol.TerminalResolution.COMMITTED },
            onCommitted = {},
        )
        if (committed !is CancellationSafeTerminalCommit.Outcome.Committed) {
            val resolution = (committed as CancellationSafeTerminalCommit.Outcome.Failed).commandResult
            return if (resolution == UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED) {
                Result.Failed(
                    "Terminal recovery may already be committed; exact journal and cleanup evidence were retained for retry"
                )
            } else {
                Result.Failed("The active module was verified, but its terminal transaction was not committed")
            }
        }

        return Result.Recovered
    }

    private suspend fun executeRecoveryTerminalPlan(
        marker: RecoveryMarker,
        plan: UpdateTransactionProtocol.TerminalPlan,
    ): UpdateTransactionProtocol.TerminalResolution {
        var primarySucceeded = false
        try {
            primarySucceeded = dependencies().executeRoot(plan.command).success
        } catch (_: Exception) {
            // Probe the explicit commit point below.
        }
        if (primarySucceeded) return UpdateTransactionProtocol.TerminalResolution.COMMITTED
        val probe = UpdateTransactionProtocol.buildTerminalCommitProbe(marker.transactionOwner(), plan)
            ?: return UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED
        return try {
            val result = dependencies().executeRoot(probe)
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = result.success,
                probeLines = result.stdout,
            )
        } catch (_: Exception) {
            UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED
        }
    }

    internal fun buildTerminalCommitPlan(
        transaction: Transaction,
        ownerToken: String,
    ): UpdateTransactionProtocol.TerminalPlan =
        requireNotNull(UpdateTransactionProtocol.buildTerminalDeletePlan(
            owner = UpdateTransactionProtocol.Owner(
                transaction.ownerPid,
                transaction.ownerStarttime,
                transaction.ownerCreatedEpoch,
                transaction.ownerBootId,
                ownerToken,
            ),
            expectedDigest = UpdateTransactionProtocol.sha256(transactionContent(transaction)),
            prerequisite = recoveryTerminalPrerequisite(transaction, ownerToken),
            cleanupPaths = listOf(
                transaction.updateDir,
                transaction.backupDir,
                transaction.failedDir,
                transaction.recoveryDir,
            ),
        ))

    private fun recoveryTerminalPrerequisite(transaction: Transaction, ownerToken: String): String {
        val marker = RecoveryMarker(
            pid = transaction.ownerPid.toInt(),
            starttime = transaction.ownerStarttime,
            createdEpoch = transaction.ownerCreatedEpoch,
            bootId = transaction.ownerBootId,
            token = ownerToken,
        )
        val statusScript = "$MODULE_DIR/zapret2/scripts/zapret-status.sh"
        val expectedExit = if (transaction.preUpdateState == ModuleUpdateStatePolicy.VerifiedState.RUNNING) 0 else 1
        val activeIntegrity = activeModuleIntegrityPredicate(
            requireInstallGeneration = transaction.phase == "verified"
        )
        val generationPrerequisite = "{ $activeIntegrity; } && " +
            "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, transaction.disableMarkerExpectation)}; }"
        if (transaction.phase == "verified") return generationPrerequisite
        return "$generationPrerequisite && " +
            "{ z2_terminal_status=0; ${authorizedRecoveryLifecycle(marker, statusScript, listOf("--machine"))} >/dev/null 2>&1 || " +
            "z2_terminal_status=${'$'}?; [ \"${'$'}z2_terminal_status\" -eq $expectedExit ]; }"
    }

    private fun RecoveryMarker.transactionOwner() = UpdateTransactionProtocol.Owner(
        pid = pid.toString(),
        starttime = starttime,
        createdEpoch = createdEpoch,
        bootId = bootId,
        token = token,
    )

    private fun recoveryTransactionGuard(
        transaction: Transaction,
        marker: RecoveryMarker,
    ): String? = UpdateTransactionProtocol.buildOwnerTransactionGuard(
        owner = marker.transactionOwner(),
        expectedDigest = UpdateTransactionProtocol.sha256(transactionContent(transaction)),
    )
}
