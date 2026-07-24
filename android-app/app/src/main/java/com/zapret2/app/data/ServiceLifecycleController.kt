package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single entry point for root-backed Zapret2 lifecycle operations in the app process.
 *
 * Lifecycle commands are serialized so a boot broadcast, a user action and a status poll
 * cannot observe or modify the service halfway through another app-initiated operation.
 */
object ServiceLifecycleController {

    private const val START_SCRIPT = "${RootModuleContract.SCRIPTS_DIR}/zapret-start.sh"
    private const val STOP_SCRIPT = "${RootModuleContract.SCRIPTS_DIR}/zapret-stop.sh"
    private const val RESTART_SCRIPT = "${RootModuleContract.SCRIPTS_DIR}/zapret-restart.sh"
    private const val STATUS_SCRIPT = "${RootModuleContract.SCRIPTS_DIR}/zapret-status.sh"
    private const val FULL_ROLLBACK_SCRIPT =
        "${RootModuleContract.SCRIPTS_DIR}/zapret-full-rollback.sh"
    private val lifecycleMutex = Mutex()
    private val appUpdateInProgress = AtomicBoolean(false)
    private val fullRollbackInProgress = AtomicBoolean(false)

    enum class RootAccessState { GRANTED, DENIED, MANAGER_UNAVAILABLE, SHELL_FAILURE, TIMEOUT, BUSY }

    enum class LifecycleState {
        IDLE,
        RECOVERED,
        OWNED,
        ACTIVE,
        AMBIGUOUS,
        RECOVERY_FAILED,
        UNKNOWN,
    }

    data class RootAccess(
        val state: RootAccessState,
        val error: String? = null,
        val lifecycleError: LifecycleError? = null,
    ) {
        val granted: Boolean get() = state == RootAccessState.GRANTED
    }

    data class CommandResult(
        val success: Boolean,
        val stdout: List<String> = emptyList(),
        val stderr: List<String> = emptyList(),
        val rootGranted: Boolean = true,
        val error: String? = null,
        val exitCode: Int? = null,
        val rootAccessState: RootAccessState? = null,
        val lifecycleError: LifecycleError? = null,
        val indeterminate: Boolean = false,
    ) {
        fun diagnosticText(): String {
            return buildList {
                error?.takeIf { it.isNotBlank() }?.let(::add)
                lifecycleError?.takeUnless { it.isNone }?.diagnosticText()?.let(::add)
                addAll(stderr.filter { it.isNotBlank() })
                addAll(stdout.filter { it.startsWith("DIAGNOSTIC:") }.map { it.removePrefix("DIAGNOSTIC:").trim() })
                addAll(stdout.filter { it.startsWith("ERROR:") }.map { it.removePrefix("ERROR:").trim() })
            }.distinct().joinToString("\n")
        }
    }

    data class ServiceStatus(
        val rootGranted: Boolean,
        val processRunning: Boolean,
        val rootAccessState: RootAccessState = if (rootGranted) {
            RootAccessState.GRANTED
        } else {
            RootAccessState.SHELL_FAILURE
        },
        val pid: String = "",
        val nfqueueRulesCount: Int = 0,
        val iptablesActive: Boolean = false,
        val hasOwnedState: Boolean = false,
        val declaredStatus: String = "unknown",
        val pidVerified: Boolean = false,
        val pidStarttime: String = "",
        val ownerGeneration: String = "",
        val qnum: Int? = null,
        val ipv4Active: Boolean = false,
        val ipv6Active: Boolean = false,
        val expectedRulesCount: Int = 0,
        val ipv4RulesCount: Int = 0,
        val ipv6RulesCount: Int = 0,
        val chainsCount: Int = 0,
        val anchorsCount: Int = 0,
        val nfqueueSupported: Boolean = false,
        val queueBypassSupported: Boolean = false,
        val rulesetVerified: Boolean = false,
        val ownerMetadataVerified: Boolean = false,
        val updateBlocked: Boolean = false,
        val uninstallTombstone: Boolean = false,
        val lifecycleState: LifecycleState = LifecycleState.UNKNOWN,
        val lifecycleOwnerKind: String = "unknown",
        val metadataComplete: Boolean = false,
        val error: String? = null,
        val lifecycleError: LifecycleError? = null,
    ) {
        val healthy: Boolean
            get() =
                rootGranted &&
                    processRunning &&
                    iptablesActive &&
                    declaredStatus == "ok" &&
                    metadataComplete &&
                    pidVerified &&
                    ownerMetadataVerified &&
                    pid.matches(Regex("[1-9][0-9]*")) &&
                    ProtocolDecimal.isCanonicalNonNegativeLong(pidStarttime) &&
                    ownerGeneration.matches(Regex("[A-Za-z0-9._-]+")) &&
                    qnum in 1..65535 &&
                    ipv4Active &&
                    nfqueueSupported &&
                    queueBypassSupported &&
                    lifecycleState in setOf(
                        LifecycleState.IDLE,
                        LifecycleState.RECOVERED,
                        LifecycleState.OWNED,
                        LifecycleState.UNKNOWN,
                    ) &&
                    rulesetVerified &&
                    expectedRulesCount > 0 &&
                    nfqueueRulesCount == expectedRulesCount &&
                    ipv4RulesCount > 0 &&
                    ipv4RulesCount + ipv6RulesCount == expectedRulesCount &&
                    (ipv6Active || ipv6RulesCount == 0)

        val fullyStopped: Boolean
            get() =
                rootGranted &&
                    metadataComplete &&
                    declaredStatus == "stopped" &&
                    !processRunning &&
                    lifecycleState in setOf(
                        LifecycleState.IDLE,
                        LifecycleState.RECOVERED,
                        LifecycleState.OWNED,
                        LifecycleState.UNKNOWN,
                    ) &&
                    !hasOwnedState &&
                    !iptablesActive &&
                    nfqueueRulesCount == 0 &&
                    expectedRulesCount == 0 &&
                    ipv4RulesCount == 0 &&
                    ipv6RulesCount == 0
    }

    enum class Action {
        START,
        STOP,
        RESTART
    }

    data class LifecycleResult(
        val success: Boolean,
        val action: Action,
        val status: ServiceStatus,
        val command: CommandResult? = null,
        val error: String? = null
    ) {
        fun diagnosticText(): String {
            return listOfNotNull(
                error?.takeIf { it.isNotBlank() },
                command?.diagnosticText()?.takeIf { it.isNotBlank() },
                status.error?.takeIf { it.isNotBlank() },
                status.lifecycleError?.diagnosticText(),
            ).distinct().joinToString("\n")
        }
    }

    enum class FullRollbackStatus(val wireValue: String) {
        COMPLETE("complete"),
        PARTIAL("partial"),
        BLOCKED("blocked"),
        ERROR("error");

        companion object {
            fun fromWireValue(value: String): FullRollbackStatus? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    data class FullRollbackReport(
        val status: FullRollbackStatus,
        val processClean: Boolean,
        val firewallClean: Boolean,
        val rollbackArmed: Boolean,
        val hostsPreserved: Boolean,
        val rebootRequired: Boolean,
        val userDataPreserved: Boolean,
        val legacyAmbiguous: Boolean,
        val diagnostic: String,
    ) {
        val satisfiesCompleteContract: Boolean
            get() = status == FullRollbackStatus.COMPLETE &&
                processClean &&
                firewallClean &&
                rollbackArmed &&
                hostsPreserved &&
                rebootRequired &&
                userDataPreserved &&
                !legacyAmbiguous
    }

    sealed interface FullRollbackParseResult {
        data class Valid(val report: FullRollbackReport) : FullRollbackParseResult
        data class Invalid(val error: String) : FullRollbackParseResult
    }

    enum class FullRollbackOutcome {
        COMPLETE,
        PARTIAL,
        BLOCKED,
        ERROR,
        INVALID_PROTOCOL,
        COMMAND_FAILED,
        VERIFICATION_FAILED,
    }

    data class FullRollbackResult(
        val outcome: FullRollbackOutcome,
        val serviceStatus: ServiceStatus? = null,
        val report: FullRollbackReport? = null,
        val command: CommandResult? = null,
        val error: String? = null,
    ) {
        val success: Boolean get() = outcome == FullRollbackOutcome.COMPLETE
        val rebootRequired: Boolean get() = report?.rebootRequired == true

        fun diagnosticText(): String = listOfNotNull(
            error?.takeIf(String::isNotBlank),
            report?.diagnostic?.takeIf(String::isNotBlank),
            command?.diagnosticText()?.takeIf(String::isNotBlank),
            serviceStatus?.error?.takeIf(String::isNotBlank),
        ).distinct().joinToString("\n")
    }

    suspend fun checkRootAccess(): RootAccess {
        val result = executeRaw("id -u", requireRoot = false)
        val uid = result.stdout.firstOrNull()?.trim()
        return classifyRootAccess(result, uid, Shell.isAppGrantedRoot())
    }

    internal fun classifyRootAccess(
        result: CommandResult,
        uid: String?,
        appGrantedRoot: Boolean? = null,
    ): RootAccess {
        if (result.success && uid == "0") return RootAccess(RootAccessState.GRANTED)
        if (result.lifecycleError?.code == LifecycleErrorContract.ROOT_COMMAND_QUEUE_BUSY) {
            return RootAccess(
                state = RootAccessState.BUSY,
                error = result.error,
                lifecycleError = result.lifecycleError,
            )
        }
        val canonicalUid = uid?.takeIf { it.matches(Regex("(?:0|[1-9][0-9]*)")) }
        val diagnostic = listOfNotNull(
            result.error,
            result.stderr.joinToString("\n").takeIf(String::isNotBlank),
        ).joinToString("\n").ifBlank { "Root shell acquisition failed" }
        val normalized = diagnostic.lowercase()
        val state = when {
            result.success && canonicalUid != null -> RootAccessState.DENIED
            result.success -> RootAccessState.SHELL_FAILURE
            "timed out" in normalized || "timeout" in normalized -> RootAccessState.TIMEOUT
            listOf("not found", "no such file", "cannot find", "no su binary").any(normalized::contains) ->
                RootAccessState.MANAGER_UNAVAILABLE
            listOf("denied", "not granted", "permission").any(normalized::contains) ->
                RootAccessState.DENIED
            appGrantedRoot == false -> RootAccessState.DENIED
            else -> RootAccessState.SHELL_FAILURE
        }
        val message = if (result.success && canonicalUid != null) {
            "Root access was not granted (uid=$uid)"
        } else if (result.success) {
            "Root shell returned an invalid uid"
        } else if (appGrantedRoot == false) {
            "Root access was not granted by the root manager"
        } else {
            diagnostic
        }
        val classifiedTransportError = result.lifecycleError?.takeUnless {
            it.code == LifecycleErrorContract.ROOT_COMMAND_FAILED && it.stage == "ROOT_COMMAND"
        }
        val lifecycleError = classifiedTransportError ?: when (state) {
            RootAccessState.GRANTED -> null
            RootAccessState.DENIED ->
                LifecycleErrorContract.rootAccess(
                    LifecycleErrorContract.ROOT_DENIED,
                    message,
                )
            RootAccessState.MANAGER_UNAVAILABLE ->
                LifecycleErrorContract.rootAccess(
                    LifecycleErrorContract.ROOT_MANAGER_UNAVAILABLE,
                    message,
                )
            RootAccessState.SHELL_FAILURE ->
                LifecycleErrorContract.rootAccess(
                    LifecycleErrorContract.ROOT_SHELL_FAILED,
                    message,
                )
            RootAccessState.TIMEOUT ->
                LifecycleErrorContract.rootAccess(
                    LifecycleErrorContract.ROOT_COMMAND_TIMEOUT,
                    message,
                )
            RootAccessState.BUSY -> LifecycleErrorContract.rootQueueBusy
        }
        return RootAccess(state, message, lifecycleError)
    }

    /**
     * Executes on the bounded transport whose session factory already admits only a live
     * libsu root shell. A second `id -u` job before every command would duplicate transport work
     * and serialize an unrelated probe in front of every read.
     */
    internal suspend fun executeRoot(
        command: String,
        policy: RootCommandPolicy = RootCommandPolicy.OBSERVATION,
    ): CommandResult {
        return executeRaw(command, requireRoot = true, policy = policy)
    }

    suspend fun getStatus(): ServiceStatus {
        lifecycleMutex.lock()
        return try {
            getStatusLocked()
        } finally {
            lifecycleMutex.unlock()
        }
    }

    suspend fun start(): LifecycleResult = perform(Action.START)

    suspend fun stop(): LifecycleResult = perform(Action.STOP)

    suspend fun restart(): LifecycleResult = perform(Action.RESTART)

    fun tryBeginAppUpdate(): Boolean {
        if (fullRollbackInProgress.get() || ModulePurgeController.isInProgress()) return false
        if (!appUpdateInProgress.compareAndSet(false, true)) return false
        if (fullRollbackInProgress.get() || ModulePurgeController.isInProgress()) {
            appUpdateInProgress.set(false)
            return false
        }
        return true
    }

    fun finishAppUpdate() {
        appUpdateInProgress.set(false)
    }

    fun isAppUpdateInProgress(): Boolean = appUpdateInProgress.get()

    fun isFullRollbackInProgress(): Boolean = fullRollbackInProgress.get()

    /** Serializes a larger operation that invokes lifecycle scripts internally. */
    suspend fun <T> runExclusiveLifecycleTask(task: suspend () -> T): T {
        lifecycleMutex.lock()
        return try {
            task()
        } finally {
            lifecycleMutex.unlock()
        }
    }

    /** Only for code already running inside [runExclusiveLifecycleTask]; avoids re-locking Mutex. */
    internal suspend fun getStatusInsideExclusiveTask(): ServiceStatus = getStatusLocked()

    /**
     * Performs the durable full rollback under the mandatory module-mutation -> lifecycle lock
     * order. This operation never reboots the device.
     */
    suspend fun fullRollback(): FullRollbackResult {
        if (appUpdateInProgress.get() || ModulePurgeController.isInProgress() ||
            !fullRollbackInProgress.compareAndSet(false, true)
        ) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.BLOCKED,
                error = "Another update or full rollback is already in progress",
            )
        }
        if (appUpdateInProgress.get() || ModulePurgeController.isInProgress()) {
            fullRollbackInProgress.set(false)
            return FullRollbackResult(
                outcome = FullRollbackOutcome.BLOCKED,
                error = "A module update is already in progress",
            )
        }

        return try {
            val result = ModuleMutationCoordinator.withLifecycleScript {
                runExclusiveLifecycleTask {
                    withContext(NonCancellable) {
                        fullRollbackInsideExclusiveTask()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (blocked: ModuleMutationCoordinator.MutationBlockedException) {
            FullRollbackResult(
                outcome = FullRollbackOutcome.BLOCKED,
                error = blocked.message ?: "Module mutation is blocked",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FullRollbackResult(
                outcome = FullRollbackOutcome.ERROR,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            fullRollbackInProgress.set(false)
        }
    }

    private suspend fun fullRollbackInsideExclusiveTask(): FullRollbackResult {
        if (appUpdateInProgress.get()) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.BLOCKED,
                serviceStatus = getStatusInsideExclusiveTask(),
                error = "Service lifecycle is disabled while an update is in progress",
            )
        }

        val before = getStatusInsideExclusiveTask()
        if (!before.rootGranted) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.ERROR,
                serviceStatus = before,
                error = before.error ?: "Root access is required",
            )
        }

        val command = executeRoot(
            "/system/bin/sh '$FULL_ROLLBACK_SCRIPT' --machine",
            RootCommandPolicy.LIFECYCLE,
        )
        val parsed = parseFullRollbackOutput(command.stdout)
        val after = getStatusInsideExclusiveTask()
        if (parsed is FullRollbackParseResult.Invalid) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.INVALID_PROTOCOL,
                serviceStatus = after,
                command = command,
                error = parsed.error,
            )
        }

        val report = (parsed as FullRollbackParseResult.Valid).report
        val reportedOutcome = when (report.status) {
            FullRollbackStatus.COMPLETE -> null
            FullRollbackStatus.PARTIAL -> FullRollbackOutcome.PARTIAL
            FullRollbackStatus.BLOCKED -> FullRollbackOutcome.BLOCKED
            FullRollbackStatus.ERROR -> FullRollbackOutcome.ERROR
        }
        if (reportedOutcome != null) {
            return FullRollbackResult(
                outcome = reportedOutcome,
                serviceStatus = after,
                report = report,
                command = command,
            )
        }
        if (!command.success) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.COMMAND_FAILED,
                serviceStatus = after,
                report = report,
                command = command,
                error = "Full rollback command exited unsuccessfully",
            )
        }
        if (!report.satisfiesCompleteContract) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.VERIFICATION_FAILED,
                serviceStatus = after,
                report = report,
                command = command,
                error = "Full rollback reported complete without all required safety invariants",
            )
        }
        if (!after.fullyStopped) {
            return FullRollbackResult(
                outcome = FullRollbackOutcome.VERIFICATION_FAILED,
                serviceStatus = after,
                report = report,
                command = command,
                error = "Full rollback completed, but the verified service status is not fully stopped",
            )
        }
        return FullRollbackResult(
            outcome = FullRollbackOutcome.COMPLETE,
            serviceStatus = after,
            report = report,
            command = command,
        )
    }

    private suspend fun perform(action: Action): LifecycleResult {
        lifecycleMutex.lock()
        return try {
            if (appUpdateInProgress.get() || fullRollbackInProgress.get() ||
                ModulePurgeController.isInProgress()
            ) {
                val status = getStatusLocked()
                return LifecycleResult(
                    success = false,
                    action = action,
                    status = status,
                    error = "Service lifecycle is disabled while an update or full rollback is in progress"
                )
            }
            // Start/stop need a leading observation for their idempotent no-op
            // contract. Restart always executes a replacement transaction, so
            // a separate status process here only duplicates lifecycle-owned
            // verification and adds seconds to every preset application.
            val before = if (action == Action.RESTART) null else getStatusLocked()
            if (before != null && !before.rootGranted) {
                return LifecycleResult(false, action, before, error = before.error ?: "Root access is required")
            }

            if (action == Action.START && before?.healthy == true) {
                return LifecycleResult(true, action, before)
            }
            if (action == Action.STOP && before?.fullyStopped == true) {
                return LifecycleResult(true, action, before)
            }

            val script = when (action) {
                Action.START -> START_SCRIPT
                Action.STOP -> STOP_SCRIPT
                Action.RESTART -> RESTART_SCRIPT
            }
            val expectedRunning = action != Action.STOP
            val expectedOwnerGeneration = ModuleMutationCoordinator.currentLifecycleToken()
            val result = withContext(NonCancellable) {
                val commandResult = executeRoot(
                    ModuleMutationCoordinator.inheritLifecycleLock(
                        "ZAPRET2_EMIT_STATUS_V6=1 sh \"$script\"",
                    ),
                    RootCommandPolicy.LIFECYCLE,
                )
                if (!commandResult.success) {
                    val status = getStatusLocked()
                    if (indeterminateLifecycleCommitMatches(
                            commandResult,
                            status,
                            expectedRunning,
                            expectedOwnerGeneration,
                        )
                    ) {
                        return@withContext LifecycleResult(
                            success = true,
                            action = action,
                            status = status,
                            command = commandResult,
                        )
                    }
                    return@withContext LifecycleResult(
                        success = false,
                        action = action,
                        status = status,
                        command = commandResult,
                        error = "${action.displayName()} command failed"
                    )
                }

                parseLifecycleReceipt(commandResult)
                    ?.takeIf { it.matchesExpectedState(expectedRunning) }
                    ?.let { committed ->
                        return@withContext LifecycleResult(
                            success = true,
                            action = action,
                            status = committed,
                            command = commandResult,
                        )
                    }
                var status = getStatusLocked()
                repeat(4) {
                    if (status.matchesExpectedState(expectedRunning)) {
                        return@withContext LifecycleResult(true, action, status, commandResult)
                    }
                    delay(250)
                    status = getStatusLocked()
                }

                LifecycleResult(
                    success = false,
                    action = action,
                    status = status,
                    command = commandResult,
                    error = if (expectedRunning) {
                        "Service command completed, but nfqws2 and its NFQUEUE rules were not both active"
                    } else {
                        "Stop command completed, but an nfqws2 process or stale NFQUEUE rules remain"
                    }
                )
            }
            currentCoroutineContext().ensureActive()
            result
        } finally {
            lifecycleMutex.unlock()
        }
    }

    internal fun indeterminateLifecycleCommitMatches(
        command: CommandResult,
        status: ServiceStatus,
        expectedRunning: Boolean,
        expectedOwnerGeneration: String?,
    ): Boolean =
        command.indeterminate &&
            expectedRunning &&
            !expectedOwnerGeneration.isNullOrEmpty() &&
            status.ownerGeneration == expectedOwnerGeneration &&
            status.matchesExpectedState(expectedRunning)

    private fun Action.displayName(): String = name.lowercase()

    private fun ServiceStatus.matchesExpectedState(expectedRunning: Boolean): Boolean {
        return if (expectedRunning) healthy else fullyStopped
    }

    private suspend fun getStatusLocked(): ServiceStatus {
        val current = executeRoot(
            ModuleMutationCoordinator.inheritLifecycleObservation(buildStatusCommand(version = 6)),
        )
        val topologyCompatible = if (current.isUnsupportedMachineProtocol()) {
            executeRoot(buildStatusCommand(version = 5))
        } else {
            current
        }
        val compatible = if (topologyCompatible.isUnsupportedMachineProtocol()) {
            executeRoot(buildStatusCommand(version = 4))
        } else {
            topologyCompatible
        }
        val legacy = if (compatible.isUnsupportedMachineProtocol()) {
            executeRoot(buildStatusCommand(version = 3))
        } else {
            compatible
        }
        val result = if (legacy.isUnsupportedMachineProtocol()) {
            executeRoot(buildStatusCommand(version = 1))
        } else {
            legacy
        }
        return parseStatusCommandResult(result)
    }

    private fun CommandResult.isUnsupportedMachineProtocol(): Boolean =
        rootGranted && exitCode == 2 && stdout.none { it.startsWith("Z2_") }

    /**
     * Consumes the complete machine payload and the status script's 0/1/2 exit contract together.
     * Update and recovery callers use this same gate so neither can accept a mismatched payload.
     */
    internal fun parseStatusCommandResult(result: CommandResult): ServiceStatus {
        if (!result.rootGranted) {
            return ServiceStatus(
                rootGranted = false,
                rootAccessState = result.rootAccessState ?: RootAccessState.SHELL_FAILURE,
                processRunning = false,
                error = result.error,
                lifecycleError = result.lifecycleError,
            )
        }
        val hasMachineStatus = result.stdout.any { it.startsWith("Z2_STATUS=") }
        if (!hasMachineStatus) {
            return ServiceStatus(
                rootGranted = true,
                processRunning = false,
                error = result.error ?: result.stderr.joinToString("\n")
                    .ifBlank { "Unable to query service status" },
                lifecycleError = result.lifecycleError,
            )
        }

        val parsed = parseStatusOutput(result.stdout)
        if (!parsed.metadataComplete) return parsed
        val expectedExitCode = statusExitCode(parsed.declaredStatus)
        if (expectedExitCode == null || result.exitCode != expectedExitCode) {
            return parsed.copy(
                hasOwnedState = true,
                declaredStatus = "unknown",
                metadataComplete = false,
                error = "Zapret2 machine status payload does not match its exit code",
            )
        }
        return parsed
    }

    /**
     * Lifecycle scripts exit zero after both mutation and verification, even
     * when the committed state is "stopped". Normalize only a complete v6
     * receipt through the same strict parser used by status observation.
     */
    internal fun parseLifecycleReceipt(result: CommandResult): ServiceStatus? {
        if (!result.success || !result.rootGranted) return null
        if (result.stdout.count { it == "Z2_PROTOCOL=6" } != 1) return null
        val declared = result.stdout
            .filter { it.startsWith("Z2_STATUS=") }
            .singleOrNull()
            ?.substringAfter('=')
            ?: return null
        val normalizedExitCode = statusExitCode(declared) ?: return null
        val parsed = parseStatusCommandResult(
            result.copy(
                success = normalizedExitCode == 0,
                exitCode = normalizedExitCode,
            ),
        )
        return parsed.takeIf { it.metadataComplete }
    }

    internal fun statusExitCode(status: String): Int? = when (status.lowercase()) {
        "ok" -> 0
        "stopped" -> 1
        "degraded" -> 2
        else -> null
    }

    /** Pure parser kept internal so lifecycle status semantics can be covered by local unit tests. */
    internal fun parseStatusOutput(lines: List<String>): ServiceStatus {
        val machineLines = lines.filter { it.startsWith("Z2_") }
        val machineLikeLines = lines.filter { it.trimStart().startsWith("Z2_") }
        val parsedPairs = machineLines.mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        val values = parsedPairs.toMap()
        val legacyRequiredFields = setOf(
            "Z2_STATUS",
            "Z2_OWNED",
            "Z2_PROCESS",
            "Z2_ACTIVE",
            "Z2_PID",
            "Z2_PID_VERIFIED",
            "Z2_PID_STARTTIME",
            "Z2_OWNER_GENERATION",
            "Z2_OWNER_METADATA_VERIFIED",
            "Z2_QNUM",
            "Z2_IPV4",
            "Z2_IPV6",
            "Z2_RULES",
            "Z2_EXPECTED_RULES",
            "Z2_IPV4_RULES",
            "Z2_IPV6_RULES",
            "Z2_RULESET_VERIFIED",
            "Z2_NFQUEUE",
            "Z2_QUEUE_BYPASS",
            "Z2_UPDATE_BLOCKED",
            "Z2_UNINSTALL_TOMBSTONE"
        )
        val protocolVersion = values["Z2_PROTOCOL"]
        val versionedProtocol = protocolVersion in setOf(
            LifecycleErrorContract.LEGACY_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.LEGACY_LIFECYCLE_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.LEGACY_TOPOLOGY_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.STATUS_PROTOCOL_VERSION,
        )
        val lifecycleProtocol = protocolVersion in setOf(
            LifecycleErrorContract.LEGACY_LIFECYCLE_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.LEGACY_TOPOLOGY_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.STATUS_PROTOCOL_VERSION,
        )
        val topologyProtocol = protocolVersion in setOf(
            LifecycleErrorContract.LEGACY_TOPOLOGY_STATUS_PROTOCOL_VERSION,
            LifecycleErrorContract.STATUS_PROTOCOL_VERSION,
        )
        val requiredFields = when {
            topologyProtocol -> legacyRequiredFields +
                setOf(
                    "Z2_PROTOCOL",
                    "Z2_LIFECYCLE_STATE",
                    "Z2_LIFECYCLE_OWNER_KIND",
                    "Z2_CHAINS",
                    "Z2_ANCHORS",
                ) +
                LifecycleErrorContract.wireFields
            lifecycleProtocol -> legacyRequiredFields +
                setOf("Z2_PROTOCOL", "Z2_LIFECYCLE_STATE", "Z2_LIFECYCLE_OWNER_KIND") +
                LifecycleErrorContract.wireFields
            versionedProtocol -> legacyRequiredFields + "Z2_PROTOCOL" + LifecycleErrorContract.wireFields
            else -> legacyRequiredFields
        }
        val completionField = "Z2_COMPLETE"
        val protocolFields = requiredFields + completionField
        val fieldCounts = parsedPairs.groupingBy { it.first }.eachCount()
        val structurallyComplete =
            machineLikeLines.size == machineLines.size &&
            machineLines.lastOrNull() == "$completionField=1" &&
                machineLines.size == protocolFields.size &&
                parsedPairs.size == protocolFields.size &&
                fieldCounts.keys == protocolFields &&
                protocolFields.all { fieldCounts[it] == 1 } &&
                values[completionField] == "1"
        val booleanFields = setOf(
            "Z2_OWNED", "Z2_PROCESS", "Z2_ACTIVE", "Z2_PID_VERIFIED",
            "Z2_OWNER_METADATA_VERIFIED", "Z2_IPV4", "Z2_IPV6", "Z2_RULESET_VERIFIED",
            "Z2_NFQUEUE", "Z2_QUEUE_BYPASS", "Z2_UPDATE_BLOCKED", "Z2_UNINSTALL_TOMBSTONE",
            completionField,
        )
        val booleansValid = booleanFields.all { values[it] == "0" || values[it] == "1" }
        val integerFields = buildSet {
            addAll(setOf("Z2_RULES", "Z2_EXPECTED_RULES", "Z2_IPV4_RULES", "Z2_IPV6_RULES"))
            if (topologyProtocol) addAll(setOf("Z2_CHAINS", "Z2_ANCHORS"))
        }
        val integers = integerFields.associateWith { values[it].parseNonNegativeInt() }
        val integersValid = integers.values.all { it != null }
        val pid = values["Z2_PID"].parseOptionalPositiveDecimal()
        val pidStarttime = values["Z2_PID_STARTTIME"].parseOptionalStartTicks()
        val qnum = values["Z2_QNUM"].parseOptionalPositiveDecimal()?.takeIf { it in 1..65535 }
        val optionalNumbersValid = values["Z2_PID"].orEmpty().isEmpty() || pid != null
        val optionalStartValid = values["Z2_PID_STARTTIME"].orEmpty().isEmpty() || pidStarttime != null
        val optionalQnumValid = values["Z2_QNUM"].orEmpty().isEmpty() || qnum != null
        val generation = values["Z2_OWNER_GENERATION"].orEmpty()
        val generationValid = generation.isEmpty() || generation.matches(Regex("[A-Za-z0-9._-]+"))
        val lifecycleState = when (values["Z2_LIFECYCLE_STATE"]) {
            "idle" -> LifecycleState.IDLE
            "recovered" -> LifecycleState.RECOVERED
            "owned" -> LifecycleState.OWNED
            "active" -> LifecycleState.ACTIVE
            "ambiguous" -> LifecycleState.AMBIGUOUS
            "recovery_failed" -> LifecycleState.RECOVERY_FAILED
            else -> LifecycleState.UNKNOWN
        }
        val lifecycleOwnerKind = values["Z2_LIFECYCLE_OWNER_KIND"].orEmpty()
        val lifecycleValid = !lifecycleProtocol || (
            lifecycleState != LifecycleState.UNKNOWN &&
                (lifecycleState != LifecycleState.OWNED ||
                    protocolVersion == LifecycleErrorContract.STATUS_PROTOCOL_VERSION) &&
                lifecycleOwnerKind in setOf("none", "shell", "android-mutation", "unknown") &&
                lifecyclePayloadSemanticsAreValid(
                    lifecycleState,
                    lifecycleOwnerKind,
                    values["Z2_UPDATE_BLOCKED"] == "1",
                )
            )
        val reportedStatus = values["Z2_STATUS"] ?: "unknown"
        val semanticValuesValid = structurallyComplete && booleansValid && integersValid &&
            optionalNumbersValid && optionalStartValid && optionalQnumValid && generationValid &&
            lifecycleValid &&
            statusPayloadSemanticsAreValid(values, integers, pid, pidStarttime, qnum, reportedStatus)
        val lifecycleError = if (versionedProtocol) {
            LifecycleErrorContract.parseValues(values)
        } else {
            null
        }
        val errorContractValid = !versionedProtocol || lifecycleError != null
        val machineOutputComplete = semanticValuesValid && errorContractValid
        val declaredStatus = if (machineOutputComplete) reportedStatus else "unknown"
        val rules = integers["Z2_RULES"] ?: 0
        val reportsOwnedState = !machineOutputComplete ||
            values["Z2_OWNED"] == "1" ||
            values["Z2_PROCESS"] == "1" ||
            values["Z2_ACTIVE"] == "1" ||
            rules > 0 ||
            reportedStatus != "stopped"
        return ServiceStatus(
            rootGranted = true,
            processRunning = values["Z2_PROCESS"] == "1" && (pid != null || machineOutputComplete),
            pid = pid?.toString().orEmpty(),
            nfqueueRulesCount = rules,
            iptablesActive = values["Z2_ACTIVE"] == "1",
            hasOwnedState = reportsOwnedState,
            declaredStatus = declaredStatus,
            pidVerified = values["Z2_PID_VERIFIED"] == "1",
            pidStarttime = pidStarttime.orEmpty(),
            ownerGeneration = generation,
            qnum = qnum,
            ipv4Active = values["Z2_IPV4"] == "1",
            ipv6Active = values["Z2_IPV6"] == "1",
            expectedRulesCount = integers["Z2_EXPECTED_RULES"] ?: 0,
            ipv4RulesCount = integers["Z2_IPV4_RULES"] ?: 0,
            ipv6RulesCount = integers["Z2_IPV6_RULES"] ?: 0,
            chainsCount = integers["Z2_CHAINS"] ?: 0,
            anchorsCount = integers["Z2_ANCHORS"] ?: 0,
            nfqueueSupported = values["Z2_NFQUEUE"] == "1",
            queueBypassSupported = values["Z2_QUEUE_BYPASS"] == "1",
            rulesetVerified = values["Z2_RULESET_VERIFIED"] == "1",
            ownerMetadataVerified = values["Z2_OWNER_METADATA_VERIFIED"] == "1",
            updateBlocked = values["Z2_UPDATE_BLOCKED"] == "1",
            uninstallTombstone = values["Z2_UNINSTALL_TOMBSTONE"] == "1",
            lifecycleState = if (lifecycleProtocol) lifecycleState else LifecycleState.UNKNOWN,
            lifecycleOwnerKind = if (lifecycleProtocol) lifecycleOwnerKind else "unknown",
            metadataComplete = machineOutputComplete,
            error = if (machineOutputComplete) null else "Invalid or incomplete Zapret2 machine status output",
            lifecycleError = lifecycleError?.takeUnless { it.isNone },
        )
    }

    private fun String?.parseNonNegativeInt(): Int? = this
        ?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }
        ?.toIntOrNull()

    private fun String?.parseOptionalPositiveDecimal(): Int? = this
        ?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
        ?.toIntOrNull()

    private fun String?.parseOptionalStartTicks(): String? = this
        ?.takeIf(ProtocolDecimal::isCanonicalNonNegativeLong)

    private fun lifecyclePayloadSemanticsAreValid(
        state: LifecycleState,
        ownerKind: String,
        updateBlocked: Boolean,
    ): Boolean = when (state) {
        LifecycleState.IDLE,
        LifecycleState.RECOVERED,
        -> ownerKind == "none" && !updateBlocked
        LifecycleState.OWNED -> ownerKind == "android-mutation" && !updateBlocked
        LifecycleState.ACTIVE ->
            ownerKind in setOf("shell", "android-mutation") && updateBlocked
        LifecycleState.AMBIGUOUS -> ownerKind == "unknown" && updateBlocked
        LifecycleState.RECOVERY_FAILED ->
            ownerKind in setOf("none", "shell", "android-mutation", "unknown") && updateBlocked
        LifecycleState.UNKNOWN -> false
    }

    private fun statusPayloadSemanticsAreValid(
        values: Map<String, String>,
        integers: Map<String, Int?>,
        pid: Int?,
        pidStarttime: String?,
        qnum: Int?,
        status: String,
    ): Boolean {
        if (status !in setOf("ok", "stopped", "degraded")) return false
        fun flag(name: String) = values[name] == "1"
        val owned = flag("Z2_OWNED")
        val process = flag("Z2_PROCESS")
        val active = flag("Z2_ACTIVE")
        val pidVerified = flag("Z2_PID_VERIFIED")
        val ownerVerified = flag("Z2_OWNER_METADATA_VERIFIED")
        val ipv4 = flag("Z2_IPV4")
        val ipv6 = flag("Z2_IPV6")
        val rulesVerified = flag("Z2_RULESET_VERIFIED")
        val generation = values["Z2_OWNER_GENERATION"].orEmpty()
        val rules = integers["Z2_RULES"] ?: return false
        val expected = integers["Z2_EXPECTED_RULES"] ?: return false
        val ipv4Rules = integers["Z2_IPV4_RULES"] ?: return false
        val ipv6Rules = integers["Z2_IPV6_RULES"] ?: return false
        val chains = integers["Z2_CHAINS"]
        val anchors = integers["Z2_ANCHORS"]
        val ruleSum = ipv4Rules.toLong() + ipv6Rules.toLong()
        if (ruleSum != rules.toLong()) return false
        if ((chains == null) != (anchors == null)) return false
        if (chains != null && anchors != null) {
            if (anchors > chains) return false
            if (status == "ok" && (chains == 0 || anchors == 0)) return false
            if (status == "stopped" && (chains != 0 || anchors != 0)) return false
        }
        if (process && !owned) return false
        if (!process && (pid != null || pidStarttime != null)) return false
        if (pid == null && pidStarttime != null) return false
        if (pidVerified && (!process || pidStarttime == null)) return false
        if (ownerVerified != pidVerified) return false
        if (ownerVerified && (generation.isEmpty() || qnum == null)) return false
        if (!ownerVerified && generation.isNotEmpty()) return false
        if ((active || ipv4 || ipv6 || rules > 0 || flag("Z2_UNINSTALL_TOMBSTONE")) && !owned) return false
        if (rulesVerified && rules != expected) return false
        if (status != "stopped" && rulesVerified && !ownerVerified) return false
        if (flag("Z2_UPDATE_BLOCKED") && status != "degraded") return false
        return when (status) {
            "ok" -> owned && process && active && pidVerified && ownerVerified &&
                qnum != null && ipv4 && flag("Z2_NFQUEUE") && flag("Z2_QUEUE_BYPASS") &&
                rulesVerified && expected > 0 && ipv4Rules > 0 && rules == expected &&
                !flag("Z2_UPDATE_BLOCKED") && !flag("Z2_UNINSTALL_TOMBSTONE")
            "stopped" -> !owned && !process && !active && pid == null && !pidVerified &&
                pidStarttime == null && values["Z2_OWNER_GENERATION"].orEmpty().isEmpty() &&
                !ownerVerified && !ipv4 && !ipv6 && rules == 0 && expected == 0 &&
                ipv4Rules == 0 && ipv6Rules == 0 && rulesVerified &&
                !flag("Z2_NFQUEUE") && !flag("Z2_QUEUE_BYPASS") &&
                !flag("Z2_UPDATE_BLOCKED") && !flag("Z2_UNINSTALL_TOMBSTONE")
            else -> owned && !active
        }
    }

    /** Strict parser for the exact ten-field full-rollback machine protocol. */
    internal fun parseFullRollbackOutput(lines: List<String>): FullRollbackParseResult {
        val protocolLines = lines.map(String::trim).filter(String::isNotEmpty)
        val fields = listOf(
            "Z2_RB_STATUS",
            "Z2_RB_PROCESS_CLEAN",
            "Z2_RB_FIREWALL_CLEAN",
            "Z2_RB_ROLLBACK_ARMED",
            "Z2_RB_HOSTS_PRESERVED",
            "Z2_RB_REBOOT_REQUIRED",
            "Z2_RB_USER_DATA_PRESERVED",
            "Z2_RB_LEGACY_AMBIGUOUS",
            "Z2_RB_DIAGNOSTIC",
            "Z2_RB_COMPLETE",
        )
        if (protocolLines.size != fields.size) {
            return FullRollbackParseResult.Invalid("Expected exactly ten full rollback protocol fields")
        }
        val pairs = protocolLines.mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        if (pairs.size != fields.size) {
            return FullRollbackParseResult.Invalid("Malformed full rollback protocol field")
        }
        val counts = pairs.groupingBy { it.first }.eachCount()
        if (counts.keys != fields.toSet() || fields.any { counts[it] != 1 }) {
            return FullRollbackParseResult.Invalid("Full rollback protocol has unknown, duplicate, or missing fields")
        }
        if (protocolLines.last() != "Z2_RB_COMPLETE=1") {
            return FullRollbackParseResult.Invalid("Full rollback completion sentinel is missing or non-terminal")
        }
        val values = pairs.toMap()
        val status = values.getValue("Z2_RB_STATUS")
            .let(FullRollbackStatus::fromWireValue)
            ?: return FullRollbackParseResult.Invalid("Unknown full rollback status")
        fun boolean(name: String): Boolean? = when (values.getValue(name)) {
            "0" -> false
            "1" -> true
            else -> null
        }
        val booleanFields = fields.filter {
            it != "Z2_RB_STATUS" && it != "Z2_RB_DIAGNOSTIC"
        }
        if (booleanFields.any { boolean(it) == null }) {
            return FullRollbackParseResult.Invalid("Full rollback protocol contains an invalid boolean")
        }
        return FullRollbackParseResult.Valid(
            FullRollbackReport(
                status = status,
                processClean = boolean("Z2_RB_PROCESS_CLEAN") == true,
                firewallClean = boolean("Z2_RB_FIREWALL_CLEAN") == true,
                rollbackArmed = boolean("Z2_RB_ROLLBACK_ARMED") == true,
                hostsPreserved = boolean("Z2_RB_HOSTS_PRESERVED") == true,
                rebootRequired = boolean("Z2_RB_REBOOT_REQUIRED") == true,
                userDataPreserved = boolean("Z2_RB_USER_DATA_PRESERVED") == true,
                legacyAmbiguous = boolean("Z2_RB_LEGACY_AMBIGUOUS") == true,
                diagnostic = values.getValue("Z2_RB_DIAGNOSTIC"),
            ),
        )
    }

    private suspend fun executeRaw(
        command: String,
        requireRoot: Boolean,
        policy: RootCommandPolicy = RootCommandPolicy.OBSERVATION,
    ): CommandResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val result = executeShell(command, policy)
        if (!requireRoot) return@withContext result
        result.copy(
            rootGranted = result.rootAccessState !in setOf(
                RootAccessState.BUSY,
                RootAccessState.SHELL_FAILURE,
            ),
        )
    }

    private fun executeShell(
        command: String,
        policy: RootCommandPolicy,
    ): CommandResult {
        return try {
            val result = RootCommandExecutor.execute(command, policy)
            val lifecycleError = LifecycleErrorContract.parseLines(result.out + result.err)
            CommandResult(
                success = result.isSuccess,
                stdout = result.out,
                stderr = result.err,
                error = when (result.failure) {
                    RootCommandFailure.QUEUE_BUSY -> "Another root command is still running"
                    RootCommandFailure.COMMAND_TIMEOUT -> "Root command timed out"
                    RootCommandFailure.TRANSPORT_TIMEOUT -> "Root transport timed out"
                    RootCommandFailure.SHELL_UNAVAILABLE -> "Root shell is unavailable"
                    RootCommandFailure.SHELL_DIED -> "Root shell disconnected"
                    null -> if (result.isSuccess) null else "Root command exited unsuccessfully"
                },
                exitCode = result.code,
                rootAccessState = when (result.failure) {
                    RootCommandFailure.QUEUE_BUSY -> RootAccessState.BUSY
                    RootCommandFailure.COMMAND_TIMEOUT,
                    RootCommandFailure.TRANSPORT_TIMEOUT,
                    -> RootAccessState.TIMEOUT
                    RootCommandFailure.SHELL_UNAVAILABLE,
                    RootCommandFailure.SHELL_DIED,
                    -> RootAccessState.SHELL_FAILURE
                    null -> null
                },
                lifecycleError = lifecycleError ?: when (result.failure) {
                    RootCommandFailure.QUEUE_BUSY -> LifecycleErrorContract.rootQueueBusy
                    RootCommandFailure.COMMAND_TIMEOUT,
                    RootCommandFailure.TRANSPORT_TIMEOUT,
                    -> LifecycleErrorContract.rootCommandTimeout(
                        stage = "ROOT_COMMAND",
                        detail = result.detail ?: "Root command timed out",
                    )
                    RootCommandFailure.SHELL_UNAVAILABLE,
                    RootCommandFailure.SHELL_DIED,
                    -> LifecycleErrorContract.rootAccess(
                        LifecycleErrorContract.ROOT_SHELL_FAILED,
                        result.detail ?: "Root shell disconnected",
                    )
                    null -> if (result.isSuccess) {
                        null
                    } else {
                        LifecycleErrorContract.rootCommandFailed("ROOT_COMMAND")
                    }
                },
                indeterminate = result.isIndeterminate,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CommandResult(
                success = false,
                rootGranted = false,
                rootAccessState = RootAccessState.SHELL_FAILURE,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun buildStatusCommand(version: Int): String {
        val argument = when (version) {
            6 -> "--machine-v6"
            5 -> "--machine-v5"
            4 -> "--machine-v4"
            3 -> "--machine-v3"
            else -> "--machine"
        }
        return "sh \"$STATUS_SCRIPT\" $argument"
    }
}
