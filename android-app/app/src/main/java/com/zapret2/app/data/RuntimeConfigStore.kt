package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_PACKET_COUNT = 999_999_999

class RuntimeConfigRollbackException : IllegalStateException(
    "The previous runtime configuration could not be restored",
)

object RuntimeConfigStore {

    private const val moduleDir = "/data/adb/modules/zapret2"
    private const val runtimeConfigPath = "$moduleDir/zapret2/runtime.ini"
    private const val initScriptPath = "$moduleDir/zapret2/scripts/runtime-init.sh"
    private const val maxRuntimeBytes = 256 * 1024
    private val requiredCoreKeys = setOf(
        "schema_version",
        "config_format",
        "runtime_source",
        "autostart",
        "wifi_only",
        "debug",
        "qnum",
        "desync_mark",
        "pkt_out",
        "pkt_in",
        "active_preset",
        "nfqws_uid",
        "log_mode",
    )
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

    data class CoreReadResult(
        val values: Map<String, String>,
        val usesRuntimeConfig: Boolean
    )

    suspend fun runtimeConfigExists(): Boolean = withContext(Dispatchers.IO) {
        synchronized(fileLock) { readRuntimeConfigContent() != null }
    }

    suspend fun ensureRuntimeConfig(): Boolean = coordinatedMutation {
        synchronized(fileLock) { ensureRuntimeConfigInternal() }
    }

    suspend fun readCore(): Map<String, String> = readCoreResult().values

    suspend fun readCoreResult(): CoreReadResult {
        val content = readRuntimeConfigContentEnsuring()
        return if (content == null) {
            CoreReadResult(values = emptyMap(), usesRuntimeConfig = false)
        } else {
            CoreReadResult(
                values = withContext(Dispatchers.IO) { parseSectionValues(content, "core") },
                usesRuntimeConfig = true
            )
        }
    }

    suspend fun readCoreValue(key: String): String? {
        if (key.isBlank()) {
            return null
        }

        val content = readRuntimeConfigContentEnsuring() ?: return null
        return withContext(Dispatchers.IO) {
            parseSectionValues(content, "core")[normalizeKey(key)]
        }
    }

    suspend fun upsertCoreValue(
        key: String,
        value: String,
        removeKeys: Set<String> = emptySet()
    ): Boolean {
        return upsertCoreValues(mapOf(key to value), removeKeys)
    }

    suspend fun upsertCoreValues(
        values: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): Boolean = coordinatedMutation {
        if (values.isEmpty() && removeKeys.isEmpty()) {
            return@coordinatedMutation true
        }

        synchronized(fileLock) {
            val normalized = linkedMapOf<String, String>()
            values.forEach { (key, value) ->
                val normalizedKey = normalizeKey(key)
                if (normalizedKey.isNotEmpty()) {
                    normalized[normalizedKey] = sanitizeValue(value)
                }
            }

            val normalizedRemoveKeys = removeKeys
                .map(::normalizeKey)
                .filter { it.isNotEmpty() }
                .toSet()

            if (normalized.isEmpty() && normalizedRemoveKeys.isEmpty()) {
                return@synchronized false
            }

            val currentContent = readRuntimeConfigContentOrInitializeInternal() ?: return@synchronized false
            val updatedContent = upsertSectionValues(currentContent, "core", normalized, normalizedRemoveKeys)
            if (!hasCompleteRuntimeCore(updatedContent)) return@synchronized false
            writeFileAtomically(runtimeConfigPath, updatedContent, currentContent)
        }
    }

    suspend fun updateCoreSettings(
        update: CoreSettingsUpdate,
        removeKeys: Set<String> = emptySet()
    ): Boolean {
        return upsertCoreValues(update.toCorePairs(), removeKeys)
    }

    suspend fun readDnsManager(): Map<String, String>? {
        val content = readRuntimeConfigContentEnsuring() ?: return null
        return withContext(Dispatchers.IO) { parseSectionValuesOrNull(content, "dns_manager") }
    }

    suspend fun upsertDnsManagerValues(
        values: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): Boolean = coordinatedMutation {
        if (values.isEmpty() && removeKeys.isEmpty()) {
            return@coordinatedMutation true
        }

        synchronized(fileLock) {
            val normalized = values.mapNotNull { (key, value) ->
                normalizeKey(key).takeIf { it.isNotEmpty() }?.let { it to sanitizeValue(value) }
            }.toMap(linkedMapOf())
            val normalizedRemoveKeys = removeKeys.map(::normalizeKey).filter { it.isNotEmpty() }.toSet()
            if (normalized.isEmpty() && normalizedRemoveKeys.isEmpty()) return@synchronized false
            val currentContent = readRuntimeConfigContentOrInitializeInternal() ?: return@synchronized false
            val updatedContent = upsertSectionValues(currentContent, "dns_manager", normalized, normalizedRemoveKeys)
            writeFileAtomically(runtimeConfigPath, updatedContent, currentContent)
        }
    }

    private fun ensureRuntimeConfigInternal(): Boolean {
        return readRuntimeConfigContentOrInitializeInternal() != null
    }

    private suspend fun coordinatedMutation(block: suspend () -> Boolean): Boolean {
        return ModuleMutationCoordinator.withMutation {
            withContext(Dispatchers.IO) { block() }
        }
    }

    /**
     * A missing, partial, or invalid runtime.ini requires a defaults-only repair, which is a module
     * write. Reads may trigger that repair only while holding the same coordinator as every writer.
     */
    private suspend fun readRuntimeConfigContentEnsuring(): String? {
        val existing = withContext(Dispatchers.IO) {
            synchronized(fileLock) { readRuntimeConfigContent() }
        }
        if (existing != null) return existing

        return try {
            ModuleMutationCoordinator.withMutation {
                withContext(Dispatchers.IO) {
                    synchronized(fileLock) { readRuntimeConfigContentOrInitializeInternal() }
                }
            }
        } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
            // The read remains non-mutating while an update/recovery owns module state.
            null
        }
    }

    private fun readRuntimeConfigContent(): String? =
        RootFileIo.readSecureRegularText(runtimeConfigPath, maxRuntimeBytes)
            ?.takeIf(::hasCompleteRuntimeCore)

    /** Caller must already own [ModuleMutationCoordinator] and [fileLock]. */
    private fun readRuntimeConfigContentOrInitializeInternal(): String? {
        readRuntimeConfigContent()?.let { return it }

        val command = "sh ${RootFileIo.shellQuote(initScriptPath)} ${RootFileIo.shellQuote(runtimeConfigPath)}"
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) {
            return null
        }

        return readRuntimeConfigContent()
    }

    private fun writeFileAtomically(path: String, content: String, previousContent: String): Boolean {
        val written = try {
            RootFileIo.writeTextAtomically(
                path,
                content,
                "Z2_RUNTIME_CONFIG",
                fileMode = "0644",
            )
        } catch (_: Exception) {
            false
        }
        if (written) return true

        val current = try {
            RootFileIo.readSecureRegularText(path, maxRuntimeBytes)
        } catch (_: Exception) {
            null
        }
        if (current != null && runtimeContentsEquivalent(current, previousContent)) return false
        try {
            RootFileIo.writeTextAtomically(
                path,
                previousContent,
                "Z2_RUNTIME_ROLLBACK",
                fileMode = "0644",
            )
        } catch (_: Exception) {
            // The exact reread below remains the only rollback-success authority.
        }
        val restored = try {
            RootFileIo.readSecureRegularText(path, maxRuntimeBytes)
        } catch (_: Exception) {
            null
        }
        if (restored == null || !runtimeContentsEquivalent(restored, previousContent)) {
            throw RuntimeConfigRollbackException()
        }
        return false
    }

    private fun runtimeContentsEquivalent(first: String, second: String): Boolean =
        normalizeLineEndings(first).trimEnd('\n') == normalizeLineEndings(second).trimEnd('\n')

    internal fun parseSectionValues(content: String, sectionName: String): Map<String, String> =
        parseSectionValuesOrNull(content, sectionName).orEmpty()

    private fun parseSectionValuesOrNull(content: String, sectionName: String): Map<String, String>? {
        if (countExactSections(content, sectionName) > 1) return null
        val values = linkedMapOf<String, String>()
        val sectionHeader = sectionName
        var currentSection = ""

        normalizeLineEndings(content).lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                return@forEach
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1)
                return@forEach
            }

            if (currentSection != sectionHeader) {
                return@forEach
            }

            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) {
                return null
            }

            val key = normalizeKey(line.substring(0, separatorIndex))
            if (key.isEmpty()) {
                return null
            }
            if (key in values) return null

            val rawValue = line.substring(separatorIndex + 1).trim()
            if (!hasValidIniScalarSyntax(rawValue)) return null
            values[key] = decodeIniValue(rawValue)
        }

        return values
    }

    internal fun upsertSectionValues(
        content: String,
        sectionName: String,
        updates: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): String {
        require(parseSectionValuesOrNull(content, sectionName) != null) {
            "Malformed or ambiguous [$sectionName] section"
        }
        val lines = normalizeLineEndings(content).split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        val targetSectionName = sectionName
        var sectionStart = -1
        var sectionEnd = lines.size

        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                continue
            }

            val currentSection = trimmed.substring(1, trimmed.length - 1)
            if (sectionStart < 0 && currentSection == targetSectionName) {
                sectionStart = index
                continue
            }

            if (sectionStart >= 0) {
                sectionEnd = index
                break
            }
        }

        if (sectionStart < 0) {
            if (updates.isEmpty()) {
                return lines.joinToString("\n").let {
                    if (it.isEmpty()) "" else "$it\n"
                }
            }
            if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                lines.add("")
            }
            lines.add("[$sectionName]")
            updates.forEach { (key, value) ->
                lines.add("$key=${encodeIniValue(value)}")
            }
            return lines.joinToString("\n") + "\n"
        }

        val existingKeys = mutableMapOf<String, Int>()
        for (index in (sectionStart + 1) until sectionEnd) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue
            }

            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex <= 0) {
                continue
            }

            val key = normalizeKey(trimmed.substring(0, separatorIndex))
            if (key.isNotEmpty()) {
                require(key !in existingKeys) {
                    "Duplicate [$sectionName] key is ambiguous: $key"
                }
                existingKeys[key] = index
            }
        }

        val indicesToRemove = removeKeys.mapNotNull(existingKeys::get).sortedDescending()
        indicesToRemove.forEach { removeIndex ->
            lines.removeAt(removeIndex)
            existingKeys.entries.removeAll { it.value == removeIndex }
            existingKeys.replaceAll { _, value ->
                if (value > removeIndex) value - 1 else value
            }
            if (sectionEnd > removeIndex) {
                sectionEnd -= 1
            }
        }

        var insertionIndex = sectionEnd
        updates.forEach { (key, value) ->
            val encodedLine = "$key=${encodeIniValue(value)}"
            val existingIndex = existingKeys[key]
            if (existingIndex != null) {
                lines[existingIndex] = encodedLine
            } else {
                lines.add(insertionIndex, encodedLine)
                insertionIndex += 1
            }
        }

        return lines.joinToString("\n") + "\n"
    }

    private fun countExactSections(content: String, sectionName: String): Int =
        normalizeLineEndings(content).lineSequence().count { rawLine ->
            val line = rawLine.trim()
            line.startsWith("[") && line.endsWith("]") &&
                line.substring(1, line.length - 1) == sectionName
        }

    private fun normalizeKey(key: String): String {
        val normalized = key.trim().lowercase()
        return normalized.takeIf { it.matches(Regex("[a-z0-9_-]+")) } ?: ""
    }

    private fun sanitizeValue(value: String): String {
        return normalizeLineEndings(value).replace('\n', ' ').trim()
    }

    private fun encodeIniValue(value: String): String {
        val sanitized = sanitizeValue(value)
        if (sanitized.isEmpty()) {
            return ""
        }

        val needsQuotes = sanitized.firstOrNull()?.isWhitespace() == true ||
            sanitized.lastOrNull()?.isWhitespace() == true ||
            sanitized.any { it.isWhitespace() || it == '#' || it == ';' || it == '"' }

        if (!needsQuotes) {
            return sanitized
        }

        return buildString {
            append('"')
            sanitized.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

    private fun decodeIniValue(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1)
        }

        return value
    }

    private fun normalizeLineEndings(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    internal fun hasCompleteRuntimeCore(content: String): Boolean {
        val seen = linkedMapOf<String, String>()
        var currentSection = ""
        var coreSections = 0
        for (rawLine in normalizeLineEndings(content).lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1)
                if (currentSection == "core") coreSections += 1
                if (coreSections > 1) return false
                continue
            }
            if (currentSection != "core") continue
            val separator = line.indexOf('=')
            if (separator <= 0) return false
            val key = line.substring(0, separator).trim()
            if (!key.matches(Regex("[a-z0-9_-]+")) || seen.containsKey(key)) return false
            val rawValue = line.substring(separator + 1).trim()
            if (!hasValidIniScalarSyntax(rawValue)) return false
            seen[key] = decodeIniValue(rawValue)
        }
        if (coreSections != 1 || seen.keys != requiredCoreKeys) return false
        if (seen["schema_version"] != "1" || seen["config_format"] != "runtime-v1") return false
        val runtimeSource = seen["runtime_source"].orEmpty()
        if (!runtimeSource.matches(Regex("[A-Za-z0-9._-]+"))) return false
        if (seen["autostart"] !in setOf("0", "1") || seen["wifi_only"] !in setOf("0", "1") ||
            seen["debug"] !in setOf("0", "1")
        ) {
            return false
        }
        val qnum = seen["qnum"]?.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        if (qnum == null || qnum !in 1..65535) return false
        if (ProtocolMark.canonicalOrNull(seen["desync_mark"].orEmpty()) == null) return false
        if (positiveCountOrNull(seen["pkt_out"]) == null || positiveCountOrNull(seen["pkt_in"]) == null) return false
        val activePreset = seen["active_preset"].orEmpty()
        if (!isSafeRuntimeFileName(activePreset) || !activePreset.endsWith(".txt") || activePreset.startsWith("_")) return false
        if (!isValidNfqwsIdentity(seen["nfqws_uid"].orEmpty())) return false
        return seen["log_mode"] in setOf("android", "file", "syslog", "none")
    }

    internal fun positiveCountOrNull(value: String?): Int? {
        val canonical = value?.takeIf { it.matches(Regex("[1-9][0-9]{0,8}")) } ?: return null
        return canonical.toIntOrNull()
    }

    private fun hasValidIniScalarSyntax(value: String): Boolean {
        if (value.any { it.isISOControl() }) return false
        if (value.isEmpty()) return true
        val first = value.first()
        val last = value.last()
        return when {
            first == '"' || first == '\'' -> value.length >= 2 && last == first
            last == '"' || last == '\'' -> false
            else -> true
        }
    }

    private fun isValidNfqwsIdentity(value: String): Boolean {
        val components = value.split(':')
        if (components.size != 2) return false
        return components.all { component ->
            component == "0" || (
                component.matches(Regex("[1-9][0-9]{0,9}")) &&
                    component.toLongOrNull() in 1L..2_147_483_647L
                )
        }
    }

    private fun isSafeRuntimeFileName(value: String): Boolean =
        RootFileIo.isSimpleFileName(value)

}
