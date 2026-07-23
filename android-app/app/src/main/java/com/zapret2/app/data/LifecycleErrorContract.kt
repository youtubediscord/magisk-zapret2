package com.zapret2.app.data

enum class LifecycleErrorDomain {
    NONE,
    ROOT,
    STATE,
    LIFECYCLE,
    CONFIG,
    FIREWALL,
    PROCESS,
    STATUS,
    UPDATE,
}

enum class LifecycleErrorCode {
    NONE,
    ROOT_DENIED,
    ROOT_MANAGER_UNAVAILABLE,
    ROOT_SHELL_FAILED,
    ROOT_COMMAND_QUEUE_BUSY,
    ROOT_COMMAND_TIMEOUT,
    ROOT_COMMAND_FAILED,
    STATE_UNAVAILABLE,
    LIFECYCLE_BUSY,
    UPDATE_BLOCKED,
    RECOVERY_BLOCKED,
    UNINSTALL_BLOCKED,
    MODULE_DISABLED,
    MODULE_REMOVAL_PENDING,
    CONFIG_INVALID,
    PREFLIGHT_FAILED,
    FIREWALL_PROBE_FAILED,
    FIREWALL_BUILD_FAILED,
    FIREWALL_CLEANUP_FAILED,
    FIREWALL_COMMIT_FAILED,
    PROCESS_LAUNCH_FAILED,
    PROCESS_STOP_FAILED,
    POSTCONDITION_FAILED,
    STATUS_DEGRADED,
    LIFECYCLE_FAILED,
}

data class LifecycleError(
    val domain: LifecycleErrorDomain,
    val code: LifecycleErrorCode,
    val stage: String,
    val retryable: Boolean,
) {
    init {
        require(stage.matches(Regex("[A-Z0-9_]+")))
        require(code.acceptsDomain(domain))
        require(
            if (code == LifecycleErrorCode.NONE) {
                domain == LifecycleErrorDomain.NONE && stage == "NONE" && !retryable
            } else {
                domain != LifecycleErrorDomain.NONE && stage != "NONE"
            },
        )
    }

    val isNone: Boolean get() = code == LifecycleErrorCode.NONE

    fun diagnosticText(): String =
        "${code.name} [${domain.name}/${stage}]${if (retryable) " (retryable)" else ""}"

    private fun LifecycleErrorCode.acceptsDomain(domain: LifecycleErrorDomain): Boolean = when (this) {
        LifecycleErrorCode.NONE -> domain == LifecycleErrorDomain.NONE
        LifecycleErrorCode.ROOT_DENIED,
        LifecycleErrorCode.ROOT_MANAGER_UNAVAILABLE,
        LifecycleErrorCode.ROOT_SHELL_FAILED,
        LifecycleErrorCode.ROOT_COMMAND_QUEUE_BUSY,
        LifecycleErrorCode.ROOT_COMMAND_TIMEOUT,
        LifecycleErrorCode.ROOT_COMMAND_FAILED,
        -> domain == LifecycleErrorDomain.ROOT
        LifecycleErrorCode.STATE_UNAVAILABLE -> domain == LifecycleErrorDomain.STATE
        LifecycleErrorCode.LIFECYCLE_BUSY,
        LifecycleErrorCode.RECOVERY_BLOCKED,
        LifecycleErrorCode.UNINSTALL_BLOCKED,
        LifecycleErrorCode.MODULE_DISABLED,
        LifecycleErrorCode.MODULE_REMOVAL_PENDING,
        LifecycleErrorCode.LIFECYCLE_FAILED,
        -> domain == LifecycleErrorDomain.LIFECYCLE
        LifecycleErrorCode.CONFIG_INVALID -> domain == LifecycleErrorDomain.CONFIG
        LifecycleErrorCode.PREFLIGHT_FAILED ->
            domain == LifecycleErrorDomain.CONFIG || domain == LifecycleErrorDomain.FIREWALL
        LifecycleErrorCode.FIREWALL_PROBE_FAILED,
        LifecycleErrorCode.FIREWALL_BUILD_FAILED,
        LifecycleErrorCode.FIREWALL_CLEANUP_FAILED,
        LifecycleErrorCode.FIREWALL_COMMIT_FAILED,
        -> domain == LifecycleErrorDomain.FIREWALL
        LifecycleErrorCode.PROCESS_LAUNCH_FAILED,
        LifecycleErrorCode.PROCESS_STOP_FAILED,
        -> domain == LifecycleErrorDomain.PROCESS
        LifecycleErrorCode.POSTCONDITION_FAILED ->
            domain == LifecycleErrorDomain.LIFECYCLE ||
                domain == LifecycleErrorDomain.FIREWALL ||
                domain == LifecycleErrorDomain.PROCESS
        LifecycleErrorCode.STATUS_DEGRADED -> domain == LifecycleErrorDomain.STATUS
        LifecycleErrorCode.UPDATE_BLOCKED -> domain == LifecycleErrorDomain.UPDATE
    }
}

internal object LifecycleErrorContract {
    const val SCHEMA_VERSION = "1"
    const val STATUS_PROTOCOL_VERSION = "2"

    private const val SCHEMA = "Z2_ERROR_SCHEMA"
    private const val DOMAIN = "Z2_ERROR_DOMAIN"
    private const val CODE = "Z2_ERROR_CODE"
    private const val STAGE = "Z2_ERROR_STAGE"
    private const val RETRYABLE = "Z2_ERROR_RETRYABLE"

    val wireFields: Set<String> = setOf(SCHEMA, DOMAIN, CODE, STAGE, RETRYABLE)

    val none = LifecycleError(
        domain = LifecycleErrorDomain.NONE,
        code = LifecycleErrorCode.NONE,
        stage = "NONE",
        retryable = false,
    )

    val rootQueueBusy = LifecycleError(
        domain = LifecycleErrorDomain.ROOT,
        code = LifecycleErrorCode.ROOT_COMMAND_QUEUE_BUSY,
        stage = "ROOT_QUEUE",
        retryable = true,
    )

    fun rootCommandFailed(stage: String) = LifecycleError(
        domain = LifecycleErrorDomain.ROOT,
        code = LifecycleErrorCode.ROOT_COMMAND_FAILED,
        stage = stage,
        retryable = true,
    )

    fun rootAccess(code: LifecycleErrorCode, retryable: Boolean) = LifecycleError(
        domain = LifecycleErrorDomain.ROOT,
        code = code,
        stage = "ROOT_ACQUIRE",
        retryable = retryable,
    )

    fun parseLines(lines: List<String>): LifecycleError? {
        val pairs = lines.mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.filter { it.first in wireFields }
        if (pairs.isEmpty()) return null
        val counts = pairs.groupingBy { it.first }.eachCount()
        if (counts.keys != wireFields || wireFields.any { counts[it] != 1 }) return null
        return parseValues(pairs.toMap())
    }

    fun parseValues(values: Map<String, String>): LifecycleError? {
        if (values[SCHEMA] != SCHEMA_VERSION) return null
        val domain = values[DOMAIN]?.let { runCatching { LifecycleErrorDomain.valueOf(it) }.getOrNull() }
            ?: return null
        val code = values[CODE]?.let { runCatching { LifecycleErrorCode.valueOf(it) }.getOrNull() }
            ?: return null
        val stage = values[STAGE]?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: return null
        val retryable = when (values[RETRYABLE]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        return runCatching { LifecycleError(domain, code, stage, retryable) }.getOrNull()
    }
}
