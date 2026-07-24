package com.zapret2.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

sealed interface RuntimeConfigReadResult {
    data class Valid(
        val values: Map<String, String>,
        internal val content: String,
        internal val digest: String,
    ) : RuntimeConfigReadResult

    data class Missing(val error: LifecycleError) : RuntimeConfigReadResult
    data class UnsafeFile(val error: LifecycleError) : RuntimeConfigReadResult
    data class Unavailable(val error: LifecycleError) : RuntimeConfigReadResult
    data class Malformed(val error: LifecycleError) : RuntimeConfigReadResult
    data class UnsupportedSchema(val error: LifecycleError) : RuntimeConfigReadResult
    data class Failure(val error: LifecycleError) : RuntimeConfigReadResult
}

sealed interface RuntimeConfigSectionReadResult {
    data class Valid(val values: Map<String, String>) : RuntimeConfigSectionReadResult
    data class ConfigUnavailable(val config: RuntimeConfigReadResult) : RuntimeConfigSectionReadResult
    data class Malformed(val sectionName: String) : RuntimeConfigSectionReadResult
}

sealed interface RuntimeConfigMutationResult {
    data object Applied : RuntimeConfigMutationResult
    data class InvalidInput(val error: LifecycleError? = null) : RuntimeConfigMutationResult
    data class WriteFailed(val error: LifecycleError) : RuntimeConfigMutationResult
    data class ConfigUnavailable(val config: RuntimeConfigReadResult) : RuntimeConfigMutationResult

    val isSuccess: Boolean
        get() = this === Applied
}

fun RuntimeConfigMutationResult.diagnosticTextOrNull(): String? = when (this) {
    RuntimeConfigMutationResult.Applied -> null
    is RuntimeConfigMutationResult.InvalidInput -> error?.diagnosticText()
    is RuntimeConfigMutationResult.WriteFailed -> error.diagnosticText()
    is RuntimeConfigMutationResult.ConfigUnavailable -> config.diagnosticText()
}

sealed interface RuntimeConfigRepairResult {
    data class AlreadyValid(val config: RuntimeConfigReadResult.Valid) : RuntimeConfigRepairResult
    data class Repaired(val config: RuntimeConfigReadResult.Valid) : RuntimeConfigRepairResult
    data class Failed(val config: RuntimeConfigReadResult) : RuntimeConfigRepairResult
}

fun RuntimeConfigReadResult.diagnosticText(): String = when (this) {
    is RuntimeConfigReadResult.Valid -> "runtime.ini: VALID"
    is RuntimeConfigReadResult.Missing -> error.diagnosticText()
    is RuntimeConfigReadResult.UnsafeFile -> error.diagnosticText()
    is RuntimeConfigReadResult.Unavailable -> error.diagnosticText()
    is RuntimeConfigReadResult.Malformed -> error.diagnosticText()
    is RuntimeConfigReadResult.UnsupportedSchema -> error.diagnosticText()
    is RuntimeConfigReadResult.Failure -> error.diagnosticText()
}

internal fun parseRuntimeConfigSnapshot(content: String): RuntimeConfigReadResult {
    val section = RuntimeConfigCodec.parseSection(content, "core")
    if (section !is RuntimeConfigSectionResult.Valid) {
        return RuntimeConfigReadResult.Malformed(
            runtimeConfigError("CONFIG_INVALID", "RUNTIME_PARSE", "runtime.ini [core] is malformed"),
        )
    }
    val raw = section.values
    val expectedKeys = setOf(
        "schema_version", "config_format", "runtime_source", "autostart", "wifi_only",
        "debug", "qnum", "desync_mark", "active_preset", "nfqws_uid", "log_mode",
    )
    if (raw.keys != expectedKeys) {
        return RuntimeConfigReadResult.Malformed(
            runtimeConfigError(
                "CONFIG_INVALID",
                "RUNTIME_PARSE",
                "runtime.ini [core] is incomplete or contains unsupported keys",
            ),
        )
    }
    if (raw["schema_version"] != "1") {
        return RuntimeConfigReadResult.UnsupportedSchema(
            runtimeConfigError("UNSUPPORTED_SCHEMA", "RUNTIME_PARSE", "unsupported runtime.ini schema_version"),
        )
    }
    if (raw["config_format"] != "runtime-v1") {
        return RuntimeConfigReadResult.UnsupportedSchema(
            runtimeConfigError("UNSUPPORTED_FORMAT", "RUNTIME_PARSE", "unsupported runtime.ini config_format"),
        )
    }

    val runtimeSource = raw.getValue("runtime_source")
        .takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
        ?: return malformedRuntimeValue("runtime_source")
    val autostart = raw.getValue("autostart").takeIf(::isRuntimeBoolean)
        ?: return malformedRuntimeValue("autostart")
    val wifiOnly = raw.getValue("wifi_only").takeIf(::isRuntimeBoolean)
        ?: return malformedRuntimeValue("wifi_only")
    val debug = raw.getValue("debug").takeIf(::isRuntimeBoolean)
        ?: return malformedRuntimeValue("debug")
    val qnum = raw.getValue("qnum")
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.trimStart('0')
        ?.ifEmpty { "0" }
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?.toString()
        ?: return malformedRuntimeValue("qnum", "INVALID_QNUM")
    val desyncMark = canonicalRuntimeMark(raw.getValue("desync_mark"))
        ?: return malformedRuntimeValue("desync_mark")
    val activePreset = raw.getValue("active_preset").takeIf(PresetNamePolicy::isValid)
        ?: return malformedRuntimeValue("active_preset")
    val nfqwsUid = raw.getValue("nfqws_uid").takeIf(::isCanonicalRuntimeUidPair)
        ?: return malformedRuntimeValue("nfqws_uid")
    val logMode = raw.getValue("log_mode").takeIf { it in setOf("android", "file", "syslog", "none") }
        ?: return malformedRuntimeValue("log_mode")

    return RuntimeConfigReadResult.Valid(
        values = linkedMapOf(
            "schema_version" to "1",
            "config_format" to "runtime-v1",
            "runtime_source" to runtimeSource,
            "autostart" to autostart,
            "wifi_only" to wifiOnly,
            "debug" to debug,
            "qnum" to qnum,
            "desync_mark" to desyncMark,
            "active_preset" to activePreset,
            "nfqws_uid" to nfqwsUid,
            "log_mode" to logMode,
        ),
        content = content,
        digest = runtimeConfigDigest(content),
    )
}

private fun isRuntimeBoolean(value: String): Boolean = value == "0" || value == "1"

private fun canonicalRuntimeMark(value: String): String? {
    val hexadecimal = value.startsWith("0x", ignoreCase = true)
    val digits = if (hexadecimal) value.substring(2) else value
    if (digits.isEmpty()) return null
    val radix = if (hexadecimal) 16 else 10
    val parsed = digits.toULongOrNull(radix)?.takeIf { it <= UInt.MAX_VALUE.toULong() } ?: return null
    return "0x${parsed.toString(16)}"
}

private fun isCanonicalRuntimeUidPair(value: String): Boolean {
    val fields = value.split(':')
    if (fields.size != 2) return false
    return fields.all { field ->
        field == "0" ||
            (field.isNotEmpty() && !field.startsWith('0') && field.all(Char::isDigit) &&
                field.toLongOrNull() in 1..Int.MAX_VALUE.toLong())
    }
}

private fun malformedRuntimeValue(
    key: String,
    code: String = "INVALID_CORE_VALUE",
): RuntimeConfigReadResult.Malformed =
    RuntimeConfigReadResult.Malformed(
        runtimeConfigError(code, "RUNTIME_PARSE", "runtime.ini [core] $key is invalid"),
    )

private fun runtimeConfigError(code: String, stage: String, detail: String): LifecycleError =
    LifecycleErrorContract.error(
        domain = "CONFIG",
        code = code,
        stage = stage,
        detail = detail,
    )

private fun runtimeConfigDigest(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(
            (
                content.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
                ).toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it) }

object RuntimeConfigStore {

    private const val runtimeConfigPath = "${RootModuleContract.RUNTIME_DIR}/runtime.ini"
    private const val runtimeConfigToolPath =
        "${RootModuleContract.SCRIPTS_DIR}/runtime-config.sh"
    private const val maxRuntimeBytes = 256 * 1024
    private val fileLock = Any()

    data class CoreSettingsUpdate(
        val activePreset: String? = null,
        val logMode: String? = null,
        val autostart: Boolean? = null,
        val wifiOnly: Boolean? = null,
        val desyncMark: String? = null,
        val nfqwsUid: String? = null
    ) {
        fun toCorePairs(): Map<String, String> {
            val values = linkedMapOf<String, String>()
            activePreset?.let { values["active_preset"] = it }
            logMode?.let { values["log_mode"] = it }
            autostart?.let { values["autostart"] = if (it) "1" else "0" }
            wifiOnly?.let { values["wifi_only"] = if (it) "1" else "0" }
            desyncMark?.let { values["desync_mark"] = it }
            nfqwsUid?.let { values["nfqws_uid"] = it }
            return values
        }
    }

    suspend fun inspect(): RuntimeConfigReadResult = withContext(Dispatchers.IO) {
        synchronized(fileLock) { inspectRuntimeConfig() }
    }

    suspend fun readCore(): RuntimeConfigReadResult = inspect()

    suspend fun upsertCoreValue(
        key: String,
        value: String,
        removeKeys: Set<String> = emptySet()
    ): RuntimeConfigMutationResult {
        return upsertCoreValues(mapOf(key to value), removeKeys)
    }

    suspend fun upsertCoreValues(
        values: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): RuntimeConfigMutationResult = coordinatedMutation {
        if (values.isEmpty() && removeKeys.isEmpty()) {
            return@coordinatedMutation RuntimeConfigMutationResult.Applied
        }

        synchronized(fileLock) {
            val normalized = linkedMapOf<String, String>()
            values.forEach { (key, value) ->
                val normalizedKey = RuntimeConfigCodec.normalizeKey(key)
                if (normalizedKey.isNotEmpty()) {
                    normalized[normalizedKey] = RuntimeConfigCodec.sanitizeValue(value)
                }
            }

            val normalizedRemoveKeys = removeKeys
                .map(RuntimeConfigCodec::normalizeKey)
                .filter { it.isNotEmpty() }
                .toSet()

            if (normalized.isEmpty() && normalizedRemoveKeys.isEmpty()) {
                return@synchronized RuntimeConfigMutationResult.InvalidInput()
            }

            val current = inspectRuntimeConfig()
            if (current !is RuntimeConfigReadResult.Valid) {
                return@synchronized RuntimeConfigMutationResult.ConfigUnavailable(current)
            }
            val currentContent = current.content
            val updatedContent = RuntimeConfigCodec.upsertSectionValues(
                currentContent,
                "core",
                normalized,
                normalizedRemoveKeys,
            )
            commitCandidate(updatedContent, current.digest)
        }
    }

    suspend fun updateCoreSettings(
        update: CoreSettingsUpdate,
        removeKeys: Set<String> = emptySet()
    ): RuntimeConfigMutationResult {
        return upsertCoreValues(update.toCorePairs(), removeKeys)
    }

    suspend fun readDnsManager(): RuntimeConfigSectionReadResult {
        return when (val config = inspect()) {
            is RuntimeConfigReadResult.Valid -> {
                when (val section = RuntimeConfigCodec.parseSection(config.content, "dns_manager")) {
                    is RuntimeConfigSectionResult.Valid ->
                        RuntimeConfigSectionReadResult.Valid(section.values)
                    is RuntimeConfigSectionResult.Malformed ->
                        RuntimeConfigSectionReadResult.Malformed(section.sectionName)
                }
            }
            else -> RuntimeConfigSectionReadResult.ConfigUnavailable(config)
        }
    }

    suspend fun upsertDnsManagerValues(
        values: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): RuntimeConfigMutationResult = coordinatedMutation {
        if (values.isEmpty() && removeKeys.isEmpty()) {
            return@coordinatedMutation RuntimeConfigMutationResult.Applied
        }

        synchronized(fileLock) {
            val normalized = values.mapNotNull { (key, value) ->
                RuntimeConfigCodec.normalizeKey(key)
                    .takeIf { it.isNotEmpty() }
                    ?.let { it to RuntimeConfigCodec.sanitizeValue(value) }
            }.toMap(linkedMapOf())
            val normalizedRemoveKeys = removeKeys
                .map(RuntimeConfigCodec::normalizeKey)
                .filter { it.isNotEmpty() }
                .toSet()
            if (normalized.isEmpty() && normalizedRemoveKeys.isEmpty()) {
                return@synchronized RuntimeConfigMutationResult.InvalidInput()
            }
            val current = inspectRuntimeConfig()
            if (current !is RuntimeConfigReadResult.Valid) {
                return@synchronized RuntimeConfigMutationResult.ConfigUnavailable(current)
            }
            val currentContent = current.content
            val updatedContent = try {
                RuntimeConfigCodec.upsertSectionValues(
                    currentContent,
                    "dns_manager",
                    normalized,
                    normalizedRemoveKeys,
                )
            } catch (_: IllegalArgumentException) {
                return@synchronized RuntimeConfigMutationResult.InvalidInput()
            }
            commitCandidate(updatedContent, current.digest)
        }
    }

    suspend fun repairRuntimeConfig(): RuntimeConfigRepairResult = coordinatedMutation {
        synchronized(fileLock) {
            val before = inspectRuntimeConfig()
            if (before is RuntimeConfigReadResult.Valid) {
                return@synchronized RuntimeConfigRepairResult.AlreadyValid(before)
            }
            if (before is RuntimeConfigReadResult.UnsafeFile ||
                before is RuntimeConfigReadResult.Unavailable ||
                before is RuntimeConfigReadResult.Failure
            ) {
                return@synchronized RuntimeConfigRepairResult.Failed(before)
            }
            val repairFlag = if (before is RuntimeConfigReadResult.Missing) "" else " --repair"
            val command = "sh ${RootFileIo.shellQuote(runtimeConfigToolPath)}$repairFlag " +
                RootFileIo.shellQuote(runtimeConfigPath)
            val result = RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION)
            if (!result.isSuccess) {
                return@synchronized RuntimeConfigRepairResult.Failed(inspectRuntimeConfig())
            }
            when (val after = inspectRuntimeConfig()) {
                is RuntimeConfigReadResult.Valid -> RuntimeConfigRepairResult.Repaired(after)
                else -> RuntimeConfigRepairResult.Failed(after)
            }
        }
    }

    private suspend fun <T> coordinatedMutation(block: suspend () -> T): T {
        return ModuleMutationCoordinator.withMutation {
            withContext(Dispatchers.IO) { block() }
        }
    }

    private fun inspectRuntimeConfig(): RuntimeConfigReadResult {
        return when (val snapshot = RootFileIo.readAtomicMutableText(runtimeConfigPath, maxRuntimeBytes)) {
            AtomicTextSnapshot.Missing ->
                RuntimeConfigReadResult.Missing(
                    runtimeConfigError("RUNTIME_MISSING", "RUNTIME_OPEN", "runtime.ini is missing"),
                )
            AtomicTextSnapshot.Unsafe ->
                RuntimeConfigReadResult.UnsafeFile(
                    runtimeConfigError(
                        "UNSAFE_RUNTIME_FILE",
                        "RUNTIME_OPEN",
                        "runtime.ini is not a safe root-owned regular file",
                    ),
                )
            AtomicTextSnapshot.Failed -> unavailable("runtime.ini snapshot could not be read")
            is AtomicTextSnapshot.Present -> parseRuntimeConfigSnapshot(snapshot.content)
        }
    }

    private fun unavailable(detail: String): RuntimeConfigReadResult.Unavailable =
        RuntimeConfigReadResult.Unavailable(
            LifecycleErrorContract.error(
                domain = "CONFIG",
                code = "RUNTIME_INSPECT_FAILED",
                stage = "RUNTIME_INSPECT",
                detail = detail,
            ),
        )

    private fun commitCandidate(
        content: String,
        expectedCurrentDigest: String,
    ): RuntimeConfigMutationResult {
        val candidatePath =
            "$runtimeConfigPath.candidate.${android.os.Process.myPid()}.${System.nanoTime()}"
        val staged = try {
            RootFileIo.writeTextAtomically(
                candidatePath,
                content,
                "Z2_RUNTIME_CANDIDATE",
                fileMode = "0644",
                durable = false,
            )
        } catch (_: Exception) {
            false
        }
        if (!staged) return mutationWriteFailed("runtime candidate could not be staged")
        val command = "sh ${RootFileIo.shellQuote(runtimeConfigToolPath)} --commit-candidate " +
            "${RootFileIo.shellQuote(candidatePath)} " +
            "${RootFileIo.shellQuote(expectedCurrentDigest)} " +
            RootFileIo.shellQuote(runtimeConfigPath)
        val result = try {
            RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION)
        } catch (_: Exception) {
            RootFileIo.removeFile(candidatePath)
            return mutationWriteFailed("runtime candidate commit command failed")
        }
        if (!result.isSuccess) {
            RootFileIo.removeFile(candidatePath)
            val error = LifecycleErrorContract.parseLines(result.out + result.err)
            return if (error?.domain == "CONFIG" &&
                error.stage == "RUNTIME_COMMIT" &&
                error.code in setOf("CONFIG_INVALID", "UNSAFE_RUNTIME_CANDIDATE")
            ) {
                RuntimeConfigMutationResult.InvalidInput(error)
            } else {
                RuntimeConfigMutationResult.WriteFailed(
                    error ?: LifecycleErrorContract.error(
                        domain = "CONFIG",
                        code = "RUNTIME_COMMIT_FAILED",
                        stage = "RUNTIME_COMMIT",
                        detail = "runtime candidate commit failed without a valid envelope",
                    ),
                )
            }
        }
        val envelope = LifecycleErrorContract.parseLines(result.out)
        if (envelope?.isNone != true) {
            return mutationWriteFailed("runtime candidate commit returned an invalid envelope")
        }
        val published = inspectRuntimeConfig()
        return if (published is RuntimeConfigReadResult.Valid &&
            runtimeContentsEquivalent(published.content, content)
        ) {
            RuntimeConfigMutationResult.Applied
        } else {
            mutationWriteFailed("published runtime did not pass post-commit verification")
        }
    }

    private fun mutationWriteFailed(detail: String): RuntimeConfigMutationResult.WriteFailed =
        RuntimeConfigMutationResult.WriteFailed(
            LifecycleErrorContract.error(
                domain = "CONFIG",
                code = "RUNTIME_COMMIT_FAILED",
                stage = "RUNTIME_COMMIT",
                detail = detail,
            ),
        )

    private fun runtimeContentsEquivalent(first: String, second: String): Boolean =
        normalizeLineEndings(first).trimEnd('\n') == normalizeLineEndings(second).trimEnd('\n')

    private fun normalizeLineEndings(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

}
