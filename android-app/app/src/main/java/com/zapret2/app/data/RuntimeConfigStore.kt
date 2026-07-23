package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

const val MAX_PACKET_COUNT = 999_999_999

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

object RuntimeConfigStore {

    private const val moduleDir = "/data/adb/modules/zapret2"
    private const val runtimeConfigPath = "$moduleDir/zapret2/runtime.ini"
    private const val runtimeConfigToolPath = "$moduleDir/zapret2/scripts/runtime-config.sh"
    private const val maxRuntimeBytes = 256 * 1024
    private val fileLock = Any()

    data class CoreSettingsUpdate(
        val activePreset: String? = null,
        val logMode: String? = null,
        val pktOut: Int? = null,
        val pktIn: Int? = null,
        val autostart: Boolean? = null,
        val wifiOnly: Boolean? = null,
        val desyncMark: String? = null,
        val nfqwsUid: String? = null
    ) {
        fun toCorePairs(): Map<String, String> {
            val values = linkedMapOf<String, String>()
            activePreset?.let { values["active_preset"] = it }
            logMode?.let { values["log_mode"] = it }
            pktOut?.let { values["pkt_out"] = it.toString() }
            pktIn?.let { values["pkt_in"] = it.toString() }
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
            val result = Shell.cmd(command).exec()
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
        val command = "sh ${RootFileIo.shellQuote(runtimeConfigToolPath)} --inspect-machine " +
            RootFileIo.shellQuote(runtimeConfigPath)
        val result = try {
            Shell.cmd(command).exec()
        } catch (error: Exception) {
            return unavailable("Runtime inspection failed: ${error.message ?: error.javaClass.simpleName}")
        }
        if (!result.isSuccess) {
            return unavailable(
                (result.err + result.out).joinToString(" ").ifBlank {
                    "runtime-config.sh exited unsuccessfully"
                },
            )
        }
        val envelope = LifecycleErrorContract.parseLines(result.out)
            ?: return unavailable("runtime-config.sh returned an invalid diagnostic envelope")
        if (!envelope.isNone) return classifyRuntimeError(envelope)

        val hashLines = result.out.filter { it.startsWith("Z2_RUNTIME_SHA256\t") }
        val expectedHash = hashLines.singleOrNull()
            ?.removePrefix("Z2_RUNTIME_SHA256\t")
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: return unavailable("runtime-config.sh returned an invalid runtime digest")
        val corePairs = result.out.filter { it.startsWith("Z2_RUNTIME_CORE\t") }.mapNotNull { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size == 3) fields[1] to fields[2] else null
        }
        val expectedKeys = setOf(
            "schema_version", "config_format", "runtime_source", "autostart", "wifi_only",
            "debug", "qnum", "desync_mark", "pkt_out", "pkt_in", "active_preset",
            "nfqws_uid", "log_mode",
        )
        val counts = corePairs.groupingBy { it.first }.eachCount()
        if (corePairs.size != expectedKeys.size ||
            counts.keys != expectedKeys ||
            expectedKeys.any { counts[it] != 1 }
        ) {
            return unavailable("runtime-config.sh returned an invalid core payload")
        }
        val content = RootFileIo.readSecureRegularText(runtimeConfigPath, maxRuntimeBytes)
            ?: return unavailable("runtime.ini could not be read after validation")
        if (sha256(content) != expectedHash) {
            return unavailable("runtime.ini changed after validation")
        }
        return RuntimeConfigReadResult.Valid(
            values = corePairs.toMap(linkedMapOf()),
            content = content,
            digest = expectedHash,
        )
    }

    private fun classifyRuntimeError(error: LifecycleError): RuntimeConfigReadResult = when (error.code) {
        "RUNTIME_MISSING" -> RuntimeConfigReadResult.Missing(error)
        "UNSAFE_RUNTIME_FILE" -> RuntimeConfigReadResult.UnsafeFile(error)
        "UNSUPPORTED_SCHEMA", "UNSUPPORTED_FORMAT" ->
            RuntimeConfigReadResult.UnsupportedSchema(error)
        "DUPLICATE_CORE", "INVALID_CORE_KEY", "INVALID_QUOTED_VALUE",
        "MALFORMED_CORE_LINE", "DUPLICATE_CORE_KEY", "INVALID_RUNTIME_SOURCE",
        "UNKNOWN_CORE_KEY", "MISSING_CORE", "INCOMPLETE_CORE", "INVALID_QNUM",
        "INVALID_CORE_VALUE", "CONFIG_INVALID",
        -> RuntimeConfigReadResult.Malformed(error)
        "RUNTIME_METADATA_UNAVAILABLE", "RUNTIME_READ_FAILED", "RUNTIME_CHANGED" ->
            RuntimeConfigReadResult.Unavailable(error)
        else -> RuntimeConfigReadResult.Failure(error)
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

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                (
                    content.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

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
            Shell.cmd(command).exec()
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

    internal fun positiveCountOrNull(value: String?): Int? =
        RuntimeConfigCodec.positiveCountOrNull(value)

}
