package com.zapret2.app

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RuntimeConfigStore {

    private const val moduleDir = "/data/adb/modules/zapret2"
    private const val moduleConfigPath = "$moduleDir/zapret2/config.sh"
    private const val runtimeConfigPath = "$moduleDir/zapret2/runtime.ini"
    private const val migrateScriptPath = "$moduleDir/zapret2/scripts/runtime-migrate.sh"
    private const val legacyUserConfigPath = "/data/local/tmp/zapret2-user.conf"

    data class CoreSettingsUpdate(
        val presetMode: String? = null,
        val presetFile: String? = null,
        val customCmdlineFile: String? = null,
        val logMode: String? = null,
        val pktOut: Int? = null,
        val pktIn: Int? = null,
        val autostart: Boolean? = null,
        val wifiOnly: Boolean? = null,
        val debug: Boolean? = null,
        val qnum: Int? = null,
        val desyncMark: String? = null,
        val portsTcp: String? = null,
        val portsUdp: String? = null,
        val strategyPreset: String? = null,
        val nfqwsUid: String? = null
    ) {
        fun toCorePairs(): Map<String, String> {
            val values = linkedMapOf<String, String>()
            presetMode?.let { values["preset_mode"] = it }
            presetFile?.let { values["preset_file"] = it }
            customCmdlineFile?.let { values["custom_cmdline_file"] = it }
            logMode?.let { values["log_mode"] = it }
            pktOut?.let { values["pkt_out"] = it.toString() }
            pktIn?.let { values["pkt_in"] = it.toString() }
            autostart?.let { values["autostart"] = if (it) "1" else "0" }
            wifiOnly?.let { values["wifi_only"] = if (it) "1" else "0" }
            debug?.let { values["debug"] = if (it) "1" else "0" }
            qnum?.let { values["qnum"] = it.toString() }
            desyncMark?.let { values["desync_mark"] = it }
            portsTcp?.let { values["ports_tcp"] = it }
            portsUdp?.let { values["ports_udp"] = it }
            strategyPreset?.let { values["strategy_preset"] = it }
            nfqwsUid?.let { values["nfqws_uid"] = it }
            return values
        }
    }

    data class CoreReadResult(
        val values: Map<String, String>,
        val usesRuntimeConfig: Boolean
    )

    suspend fun runtimeConfigExists(): Boolean = withContext(Dispatchers.IO) {
        readRuntimeConfigContent(regenerateIfNeeded = false) != null
    }

    suspend fun ensureRuntimeConfig(): Boolean = withContext(Dispatchers.IO) {
        ensureRuntimeConfigInternal()
    }

    suspend fun readCore(): Map<String, String> = withContext(Dispatchers.IO) {
        readCoreResultInternal().values
    }

    fun readCoreBlocking(): Map<String, String> {
        return readCoreResultInternal().values
    }

    suspend fun readCoreResult(): CoreReadResult = withContext(Dispatchers.IO) {
        readCoreResultInternal()
    }

    fun readCoreResultBlocking(): CoreReadResult {
        return readCoreResultInternal()
    }

    suspend fun readCoreValue(key: String): String? = withContext(Dispatchers.IO) {
        if (key.isBlank()) {
            return@withContext null
        }

        val values = readRuntimeConfigContent()?.let {
            parseSectionValues(it, "core")
        } else {
            emptyMap()
        }

        values[normalizeKey(key)]
    }

    fun readCoreValueBlocking(key: String): String? {
        if (key.isBlank()) {
            return null
        }

        return readCoreBlocking()[normalizeKey(key)]
    }

    fun readCoreValueCompatBlocking(
        runtimeKey: String,
        legacyKey: String,
        defaultValue: String? = null
    ): String? {
        val result = readCoreResultInternal()
        if (result.usesRuntimeConfig) {
            return result.values[normalizeKey(runtimeKey)]
                ?.takeIf { it.isNotEmpty() }
                ?: defaultValue
        }

        return readLegacyShellValue(legacyKey) ?: defaultValue
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
    ): Boolean = withContext(Dispatchers.IO) {
        if (values.isEmpty() && removeKeys.isEmpty()) {
            return@withContext true
        }

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
            return@withContext false
        }

        val currentContent = readRuntimeConfigContent() ?: return@withContext false
        val updatedContent = upsertSectionValues(currentContent, "core", normalized, normalizedRemoveKeys)
        writeFileAtomically(runtimeConfigPath, updatedContent)
    }

    suspend fun updateCoreSettings(
        update: CoreSettingsUpdate,
        removeKeys: Set<String> = emptySet()
    ): Boolean {
        return upsertCoreValues(update.toCorePairs(), removeKeys)
    }

    suspend fun setActiveModeValues(
        update: CoreSettingsUpdate,
        removeKeys: Set<String> = emptySet()
    ): Boolean {
        return updateCoreSettings(update, removeKeys)
    }

    private fun ensureRuntimeConfigInternal(): Boolean {
        return readRuntimeConfigContent() != null
    }

    private fun readCoreResultInternal(): CoreReadResult {
        readRuntimeConfigContent()?.let { content ->
            return CoreReadResult(
                values = parseSectionValues(content, "core"),
                usesRuntimeConfig = true
            )
        }

        return CoreReadResult(
            values = emptyMap(),
            usesRuntimeConfig = false
        )
    }

    private fun readLegacyShellValue(key: String): String? {
        if (key.isBlank()) {
            return null
        }

        return readShellConfigValue(legacyUserConfigPath, key)
            ?: readShellConfigValue(moduleConfigPath, key)
    }

    private fun readShellConfigValue(path: String, key: String): String? {
        val content = readFile(path) ?: return null
        return parseShellConfig(content)[key]
    }

    private fun readFile(path: String): String? {
        val command = "cat \"${escapeForDoubleQuotes(path)}\" 2>/dev/null"
        val result = Shell.cmd(command).exec()
        return if (result.isSuccess) result.out.joinToString("\n") else null
    }

    private fun readRuntimeConfigContent(regenerateIfNeeded: Boolean = true): String? {
        readFile(runtimeConfigPath)?.let { return it }
        if (!regenerateIfNeeded) {
            return null
        }

        val command = "sh \"${escapeForDoubleQuotes(migrateScriptPath)}\" \"${escapeForDoubleQuotes(runtimeConfigPath)}\""
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) {
            return null
        }

        return readFile(runtimeConfigPath)
    }

    private fun writeFileAtomically(path: String, content: String): Boolean {
        val normalizedContent = normalizeLineEndings(content).trimEnd('\n') + "\n"
        val tmpPath = "$path.tmp"
        var delimiter = "__ZAPRET_RUNTIME_CONFIG_EOF__"
        while (normalizedContent.contains(delimiter)) {
            delimiter += "_X"
        }

        val command = buildString {
            append("cat <<'")
            append(delimiter)
            append("' > \"")
            append(escapeForDoubleQuotes(tmpPath))
            append("\" && mv \"")
            append(escapeForDoubleQuotes(tmpPath))
            append("\" \"")
            append(escapeForDoubleQuotes(path))
            append("\"\n")
            append(normalizedContent)
            append(delimiter)
        }

        if (!Shell.cmd(command).exec().isSuccess) {
            return false
        }

        val persistedContent = readFile(path) ?: return false
        return normalizeLineEndings(persistedContent).trimEnd('\n') == normalizedContent.trimEnd('\n')
    }

    private fun parseSectionValues(content: String, sectionName: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val sectionHeader = sectionName.trim().lowercase()
        var currentSection = ""

        normalizeLineEndings(content).lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                return@forEach
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                return@forEach
            }

            if (currentSection != sectionHeader) {
                return@forEach
            }

            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) {
                return@forEach
            }

            val key = normalizeKey(line.substring(0, separatorIndex))
            if (key.isEmpty()) {
                return@forEach
            }

            val rawValue = line.substring(separatorIndex + 1).trim()
            values[key] = decodeIniValue(rawValue)
        }

        return values
    }

    private fun parseShellConfig(content: String): Map<String, String> {
        if (content.isBlank()) {
            return emptyMap()
        }

        val values = linkedMapOf<String, String>()
        val pattern = Regex("""^([A-Z0-9_]+)=[\"']?([^\"'\n]*)[\"']?$""")

        normalizeLineEndings(content).lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }

            val match = pattern.find(line) ?: return@forEach
            values[match.groupValues[1]] = match.groupValues[2].trim()
        }

        return values
    }

    private fun upsertSectionValues(
        content: String,
        sectionName: String,
        updates: Map<String, String>,
        removeKeys: Set<String> = emptySet()
    ): String {
        val lines = normalizeLineEndings(content).split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }

        val normalizedSectionName = sectionName.trim().lowercase()
        var sectionStart = -1
        var sectionEnd = lines.size

        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                continue
            }

            val currentSection = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
            if (sectionStart < 0 && currentSection == normalizedSectionName) {
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

    private fun normalizeKey(key: String): String {
        return key.trim().lowercase()
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

    private fun escapeForDoubleQuotes(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
