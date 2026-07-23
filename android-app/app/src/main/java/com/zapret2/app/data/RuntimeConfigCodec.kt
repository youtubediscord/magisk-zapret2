package com.zapret2.app.data

internal sealed interface RuntimeConfigSectionResult {
    data class Valid(val values: Map<String, String>) : RuntimeConfigSectionResult
    data class Malformed(val sectionName: String) : RuntimeConfigSectionResult
}

/**
 * Text editor for app-owned runtime sections. It does not validate runtime-v1:
 * the module parser validates every candidate before atomic publication.
 */
internal object RuntimeConfigCodec {

    fun parseSection(content: String, sectionName: String): RuntimeConfigSectionResult {
        if (countExactSections(content, sectionName) > 1) {
            return RuntimeConfigSectionResult.Malformed(sectionName)
        }
        val values = linkedMapOf<String, String>()
        var currentSection = ""

        for (rawLine in normalizeLineEndings(content).lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1)
                continue
            }
            if (currentSection != sectionName) continue
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return RuntimeConfigSectionResult.Malformed(sectionName)
            val key = normalizeKey(line.substring(0, separatorIndex))
            if (key.isEmpty() || key in values) {
                return RuntimeConfigSectionResult.Malformed(sectionName)
            }
            val rawValue = line.substring(separatorIndex + 1).trim()
            if (!hasValidIniScalarSyntax(rawValue)) {
                return RuntimeConfigSectionResult.Malformed(sectionName)
            }
            values[key] = decodeIniValue(rawValue)
        }
        return RuntimeConfigSectionResult.Valid(values)
    }

    fun upsertSectionValues(
        content: String,
        sectionName: String,
        updates: Map<String, String>,
        removeKeys: Set<String> = emptySet(),
    ): String {
        require(parseSection(content, sectionName) is RuntimeConfigSectionResult.Valid) {
            "Malformed or ambiguous [$sectionName] section"
        }
        val lines = normalizeLineEndings(content).split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)

        var sectionStart = -1
        var sectionEnd = lines.size
        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) continue
            val currentSection = trimmed.substring(1, trimmed.length - 1)
            if (sectionStart < 0 && currentSection == sectionName) {
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
                return lines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }
            }
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[$sectionName]")
            updates.forEach { (key, value) -> lines.add("$key=${encodeIniValue(value)}") }
            return lines.joinToString("\n") + "\n"
        }

        val existingKeys = mutableMapOf<String, Int>()
        for (index in (sectionStart + 1) until sectionEnd) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex <= 0) continue
            val key = normalizeKey(trimmed.substring(0, separatorIndex))
            if (key.isNotEmpty()) {
                require(key !in existingKeys) { "Duplicate [$sectionName] key is ambiguous: $key" }
                existingKeys[key] = index
            }
        }

        val indicesToRemove = removeKeys.mapNotNull(existingKeys::get).sortedDescending()
        indicesToRemove.forEach { removeIndex ->
            lines.removeAt(removeIndex)
            existingKeys.entries.removeAll { it.value == removeIndex }
            existingKeys.replaceAll { _, value -> if (value > removeIndex) value - 1 else value }
            if (sectionEnd > removeIndex) sectionEnd -= 1
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

    fun normalizeKey(key: String): String {
        val normalized = key.trim().lowercase()
        return normalized.takeIf { it.matches(Regex("[a-z0-9_-]+")) } ?: ""
    }

    fun sanitizeValue(value: String): String =
        normalizeLineEndings(value).replace('\n', ' ').trim()

    fun positiveCountOrNull(value: String?): Int? {
        val canonical = value?.takeIf { it.matches(Regex("[1-9][0-9]{0,8}")) } ?: return null
        return canonical.toIntOrNull()
    }

    private fun countExactSections(content: String, sectionName: String): Int =
        normalizeLineEndings(content).lineSequence().count { rawLine ->
            val line = rawLine.trim()
            line.startsWith("[") && line.endsWith("]") &&
                line.substring(1, line.length - 1) == sectionName
        }

    private fun encodeIniValue(value: String): String {
        val sanitized = sanitizeValue(value)
        if (sanitized.isEmpty()) return ""
        val needsQuotes = sanitized.firstOrNull()?.isWhitespace() == true ||
            sanitized.lastOrNull()?.isWhitespace() == true ||
            sanitized.any { it.isWhitespace() || it == '#' || it == ';' || it == '"' }
        if (!needsQuotes) return sanitized
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

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
