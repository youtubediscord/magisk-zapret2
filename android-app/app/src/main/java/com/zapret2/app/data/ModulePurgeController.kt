package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Canonical APK boundary for the same one-shot purge protocol used by Magisk Action. */
object ModulePurgeController {

    private const val PURGE_SCRIPT =
        "${ServiceLifecycleController.MODULE_DIR}/${ModulePackageContract.PURGE_SCRIPT_PATH}"
    private val purgeInProgress = AtomicBoolean(false)

    enum class Status(val wireValue: String) {
        COMPLETE("complete"),
        PARTIAL("partial"),
        BLOCKED("blocked"),
        ERROR("error");

        companion object {
            fun fromWireValue(value: String): Status? = entries.firstOrNull { it.wireValue == value }
        }
    }

    data class PrepareReport(
        val token: String,
        val diagnostic: String,
    )

    data class Report(
        val status: Status,
        val processClean: Boolean,
        val firewallClean: Boolean,
        val moduleRemoved: Boolean,
        val stateRemoved: Boolean,
        val externalRemoved: Boolean,
        val apkTouched: Boolean,
        val rebootRequired: Boolean,
        val diagnostic: String,
    ) {
        val satisfiesCompleteContract: Boolean
            get() = status == Status.COMPLETE && processClean && firewallClean && moduleRemoved &&
                stateRemoved && externalRemoved && !apkTouched && rebootRequired
    }

    sealed interface ParseResult<out T> {
        data class Valid<T>(val value: T) : ParseResult<T>
        data class Invalid(val error: String) : ParseResult<Nothing>
    }

    enum class Outcome {
        COMPLETE,
        PARTIAL,
        BLOCKED,
        ERROR,
        INVALID_PROTOCOL,
        COMMAND_FAILED,
    }

    data class Result(
        val outcome: Outcome,
        val report: Report? = null,
        val command: ServiceLifecycleController.CommandResult? = null,
        val error: String? = null,
    ) {
        val success: Boolean get() = outcome == Outcome.COMPLETE
        val rebootRequired: Boolean get() = report?.rebootRequired == true

        fun diagnosticText(): String = listOfNotNull(
            error?.takeIf(String::isNotBlank),
            report?.diagnostic?.takeIf(String::isNotBlank),
            command?.diagnosticText()?.takeIf(String::isNotBlank),
        ).distinct().joinToString("\n")
    }

    fun isInProgress(): Boolean = purgeInProgress.get()

    suspend fun purge(appDataCleaner: ModulePurgeAppDataCleaner): Result {
        if (ServiceLifecycleController.isAppUpdateInProgress() ||
            ServiceLifecycleController.isFullRollbackInProgress() ||
            !purgeInProgress.compareAndSet(false, true)
        ) {
            return Result(Outcome.BLOCKED, error = "Another module mutation is already in progress")
        }
        if (ServiceLifecycleController.isAppUpdateInProgress() ||
            ServiceLifecycleController.isFullRollbackInProgress()
        ) {
            purgeInProgress.set(false)
            return Result(Outcome.BLOCKED, error = "Another module mutation is already in progress")
        }

        return try {
            ModuleMutationCoordinator.withLifecycleScript {
                ServiceLifecycleController.runExclusiveLifecycleTask {
                    withContext(NonCancellable) {
                        val moduleResult = purgeInsideExclusiveTask()
                        if (moduleResult.success && !appDataCleaner.clear()) {
                            moduleResult.copy(
                                outcome = Outcome.PARTIAL,
                                error = "Module data was removed, but APK-private state could not be cleared",
                            )
                        } else {
                            moduleResult
                        }
                    }
                }
            }
        } catch (blocked: ModuleMutationCoordinator.MutationBlockedException) {
            Result(Outcome.BLOCKED, error = blocked.message ?: "Module purge is blocked")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result(Outcome.ERROR, error = error.message ?: error.javaClass.simpleName)
        } finally {
            purgeInProgress.set(false)
        }
    }

    private suspend fun purgeInsideExclusiveTask(): Result {
        val prepareCommand = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(PURGE_SCRIPT)} --prepare app --machine",
        )
        val prepared = parsePrepareOutput(prepareCommand.stdout)
        if (prepared is ParseResult.Invalid) {
            return Result(
                outcome = if (prepareCommand.success) Outcome.INVALID_PROTOCOL else Outcome.COMMAND_FAILED,
                command = prepareCommand,
                error = prepared.error,
            )
        }
        val token = (prepared as ParseResult.Valid).value.token
        val commitCommand = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(PURGE_SCRIPT)} --commit app " +
                "${RootFileIo.shellQuote(token)} --machine",
        )
        val parsed = parseReportOutput(commitCommand.stdout)
        if (parsed is ParseResult.Invalid) {
            return Result(
                outcome = if (commitCommand.success) Outcome.INVALID_PROTOCOL else Outcome.COMMAND_FAILED,
                command = commitCommand,
                error = parsed.error,
            )
        }
        val report = (parsed as ParseResult.Valid).value
        val outcome = when {
            report.status == Status.COMPLETE && report.satisfiesCompleteContract && commitCommand.success ->
                Outcome.COMPLETE
            report.status == Status.COMPLETE -> Outcome.INVALID_PROTOCOL
            report.status == Status.PARTIAL -> Outcome.PARTIAL
            report.status == Status.BLOCKED -> Outcome.BLOCKED
            else -> Outcome.ERROR
        }
        return Result(outcome = outcome, report = report, command = commitCommand)
    }

    internal fun parsePrepareOutput(lines: List<String>): ParseResult<PrepareReport> {
        val values = parseExactRecord(
            lines = lines,
            expectedKeys = setOf(
                "Z2_PURGE_PREPARE_VERSION",
                "Z2_PURGE_PREPARE_STATUS",
                "Z2_PURGE_PREPARE_TOKEN",
                "Z2_PURGE_PREPARE_DIAGNOSTIC",
                "Z2_PURGE_PREPARE_COMPLETE",
            ),
            terminal = "Z2_PURGE_PREPARE_COMPLETE=1",
        ) ?: return ParseResult.Invalid("Purge prepare protocol is incomplete or malformed")
        val token = values.getValue("Z2_PURGE_PREPARE_TOKEN")
        if (values["Z2_PURGE_PREPARE_VERSION"] != "1" ||
            values["Z2_PURGE_PREPARE_STATUS"] != "armed" ||
            !token.matches(Regex("[A-Za-z0-9._-]{1,128}"))
        ) {
            return ParseResult.Invalid("Purge prepare protocol rejected the one-time confirmation")
        }
        return ParseResult.Valid(PrepareReport(token, values.getValue("Z2_PURGE_PREPARE_DIAGNOSTIC")))
    }

    internal fun parseReportOutput(lines: List<String>): ParseResult<Report> {
        val booleanKeys = setOf(
            "Z2_PURGE_PROCESS_CLEAN",
            "Z2_PURGE_FIREWALL_CLEAN",
            "Z2_PURGE_MODULE_REMOVED",
            "Z2_PURGE_STATE_REMOVED",
            "Z2_PURGE_EXTERNAL_REMOVED",
            "Z2_PURGE_APK_TOUCHED",
            "Z2_PURGE_REBOOT_REQUIRED",
        )
        val expected = booleanKeys + setOf(
            "Z2_PURGE_VERSION",
            "Z2_PURGE_STATUS",
            "Z2_PURGE_DIAGNOSTIC",
            "Z2_PURGE_COMPLETE",
        )
        val values = parseExactRecord(lines, expected, "Z2_PURGE_COMPLETE=1")
            ?: return ParseResult.Invalid("Purge result protocol is incomplete or malformed")
        if (values["Z2_PURGE_VERSION"] != "1" || booleanKeys.any { values[it] !in setOf("0", "1") }) {
            return ParseResult.Invalid("Purge result protocol contains invalid values")
        }
        val status = Status.fromWireValue(values.getValue("Z2_PURGE_STATUS"))
            ?: return ParseResult.Invalid("Purge result protocol contains an unknown status")
        fun flag(key: String) = values.getValue(key) == "1"
        return ParseResult.Valid(
            Report(
                status = status,
                processClean = flag("Z2_PURGE_PROCESS_CLEAN"),
                firewallClean = flag("Z2_PURGE_FIREWALL_CLEAN"),
                moduleRemoved = flag("Z2_PURGE_MODULE_REMOVED"),
                stateRemoved = flag("Z2_PURGE_STATE_REMOVED"),
                externalRemoved = flag("Z2_PURGE_EXTERNAL_REMOVED"),
                apkTouched = flag("Z2_PURGE_APK_TOUCHED"),
                rebootRequired = flag("Z2_PURGE_REBOOT_REQUIRED"),
                diagnostic = values.getValue("Z2_PURGE_DIAGNOSTIC"),
            ),
        )
    }

    private fun parseExactRecord(
        lines: List<String>,
        expectedKeys: Set<String>,
        terminal: String,
    ): Map<String, String>? {
        if (lines.size != expectedKeys.size || lines.lastOrNull() != terminal) return null
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val counts = pairs.groupingBy { it.first }.eachCount()
        if (counts.keys != expectedKeys || expectedKeys.any { counts[it] != 1 }) return null
        return pairs.toMap()
    }
}
