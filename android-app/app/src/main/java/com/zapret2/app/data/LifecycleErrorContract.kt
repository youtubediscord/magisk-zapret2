package com.zapret2.app.data

/**
 * Opaque module diagnostic envelope.
 *
 * The app validates only the transport shape and bounds. Domain, stage and code
 * deliberately remain strings so a newer module can report a new failure to an
 * older APK without teaching the APK new enums.
 */
data class LifecycleError(
    val status: String,
    val domain: String,
    val stage: String,
    val code: String,
    val detail: String,
) {
    init {
        require(status == LifecycleErrorContract.STATUS_OK ||
            status == LifecycleErrorContract.STATUS_ERROR)
        require(LifecycleErrorContract.isToken(domain))
        require(LifecycleErrorContract.isToken(stage))
        require(LifecycleErrorContract.isToken(code))
        require(LifecycleErrorContract.isBoundedSingleLineDetail(detail))
        require(
            if (status == LifecycleErrorContract.STATUS_OK) {
                domain == LifecycleErrorContract.NONE &&
                    stage == LifecycleErrorContract.NONE &&
                    code == LifecycleErrorContract.NONE &&
                    detail.isEmpty()
            } else {
                domain != LifecycleErrorContract.NONE &&
                    stage != LifecycleErrorContract.NONE &&
                    code != LifecycleErrorContract.NONE &&
                    detail.isNotEmpty()
            },
        )
    }

    val isNone: Boolean get() = status == LifecycleErrorContract.STATUS_OK

    fun diagnosticText(): String = buildString {
        append("schema=").append(LifecycleErrorContract.SCHEMA_VERSION).append('\n')
        append("status=").append(status).append('\n')
        append("domain=").append(domain).append('\n')
        append("stage=").append(stage).append('\n')
        append("code=").append(code).append('\n')
        append("detail=").append(detail)
    }
}

internal object LifecycleErrorContract {
    const val SCHEMA_VERSION = "1"
    const val STATUS_PROTOCOL_VERSION = "4"
    const val LEGACY_STATUS_PROTOCOL_VERSION = "3"
    const val STATUS_OK = "OK"
    const val STATUS_ERROR = "ERROR"
    const val NONE = "NONE"
    const val MAX_TOKEN_LENGTH = 64
    const val MAX_DETAIL_BYTES = 512

    const val ROOT = "ROOT"
    const val ROOT_DENIED = "ROOT_DENIED"
    const val ROOT_MANAGER_UNAVAILABLE = "ROOT_MANAGER_UNAVAILABLE"
    const val ROOT_SHELL_FAILED = "ROOT_SHELL_FAILED"
    const val ROOT_COMMAND_QUEUE_BUSY = "ROOT_COMMAND_QUEUE_BUSY"
    const val ROOT_COMMAND_TIMEOUT = "ROOT_COMMAND_TIMEOUT"
    const val ROOT_COMMAND_FAILED = "ROOT_COMMAND_FAILED"

    private const val SCHEMA = "Z2_ERROR_SCHEMA"
    private const val STATUS = "Z2_ERROR_STATUS"
    private const val DOMAIN = "Z2_ERROR_DOMAIN"
    private const val STAGE = "Z2_ERROR_STAGE"
    private const val CODE = "Z2_ERROR_CODE"
    private const val DETAIL = "Z2_ERROR_DETAIL"

    val wireFields: Set<String> = setOf(SCHEMA, STATUS, DOMAIN, STAGE, CODE, DETAIL)

    val none = LifecycleError(
        status = STATUS_OK,
        domain = NONE,
        stage = NONE,
        code = NONE,
        detail = "",
    )

    val rootQueueBusy = error(
        domain = ROOT,
        code = ROOT_COMMAND_QUEUE_BUSY,
        stage = "ROOT_QUEUE",
        detail = "Another root command is still running",
    )

    fun rootCommandFailed(stage: String) = error(
        domain = ROOT,
        code = ROOT_COMMAND_FAILED,
        stage = stage,
        detail = "Root command exited unsuccessfully",
    )

    fun rootCommandTimeout(stage: String, detail: String) = error(
        domain = ROOT,
        code = ROOT_COMMAND_TIMEOUT,
        stage = stage,
        detail = detail,
    )

    fun rootAccess(code: String, detail: String) = error(
        domain = ROOT,
        code = code,
        stage = "ROOT_ACQUIRE",
        detail = detail,
    )

    fun error(domain: String, code: String, stage: String, detail: String): LifecycleError =
        LifecycleError(
            status = STATUS_ERROR,
            domain = domain,
            stage = stage,
            code = code,
            detail = boundedSingleLineDetail(detail),
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
        val status = values[STATUS] ?: return null
        val domain = values[DOMAIN] ?: return null
        val stage = values[STAGE] ?: return null
        val code = values[CODE] ?: return null
        val detail = values[DETAIL] ?: return null
        return runCatching { LifecycleError(status, domain, stage, code, detail) }.getOrNull()
    }

    fun boundedSingleLineDetail(value: String): String {
        val normalized = value
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .filterNot { it == '\u0000' || (it.isISOControl() && it != ' ') }
            .trim()
        if (normalized.toByteArray(Charsets.UTF_8).size <= MAX_DETAIL_BYTES) return normalized
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val width = text.toByteArray(Charsets.UTF_8).size
            if (bytes + width > MAX_DETAIL_BYTES) break
            result.append(text)
            bytes += width
            index += Character.charCount(codePoint)
        }
        return result.toString().trimEnd()
    }

    internal fun isToken(value: String): Boolean =
        value.length in 1..MAX_TOKEN_LENGTH && value.matches(Regex("[A-Z0-9_]+"))

    internal fun isBoundedSingleLineDetail(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_DETAIL_BYTES &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || it == '\t' || it.isISOControl() }
}
