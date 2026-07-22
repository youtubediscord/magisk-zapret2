package com.zapret2.app.data

import java.security.MessageDigest

enum class StrategyCatalogScope(val fileName: String) {
    TCP("tcp.txt"),
    UDP("udp.txt"),
    VOICE("voice.txt"),
    HTTP80("http80.txt"),
}

data class PresetProfile(
    val index: Int,
    val stableId: String,
    val name: String,
    val enabled: Boolean,
    val filters: List<String>,
    val selectors: List<String>,
    val strategies: List<String>,
    val catalogScope: StrategyCatalogScope?,
)

data class PresetProfileDocument(
    val fileName: String,
    val sourceText: String,
    val sourceSha256: String,
    val declaredBlobs: Set<String>,
    val profiles: List<PresetProfile>,
)

data class StrategyCatalogEntry(
    val id: String,
    val name: String,
    val author: String = "",
    val label: String = "",
    val description: String = "",
    val arguments: List<String>,
    val requiredBlobs: Set<String> = emptySet(),
)

data class ProfileListEntry(
    val fileName: String,
    val relativePath: String,
)

internal object PresetProfileParser {
    fun parse(fileName: String, source: String): PresetProfileDocument? {
        val canonical = canonicalProtectedText(source) + "\n"
        val lines = canonical.trimEnd('\n').split('\n')
        val starts = lines.mapIndexedNotNull { index, line -> index.takeIf { line.startsWith("--name=") } }
        if (starts.isEmpty()) return null
        val declaredBlobs = lines.subList(0, starts.first()).mapNotNull { line ->
            line.takeIf { it.startsWith("--blob=") }
                ?.removePrefix("--blob=")
                ?.substringBefore(':')
                ?.takeIf { it.matches(BLOB_NAME) }
        }.toSet()
        val separators = lines.mapIndexedNotNull { index, line -> index.takeIf { line == "--new" } }
        if (separators.size != starts.size - 1) return null
        val blockOccurrences = mutableMapOf<String, Int>()
        val profiles = starts.mapIndexed { profileIndex, start ->
            val end = separators.getOrNull(profileIndex) ?: lines.size
            if (end <= start) return null
            val block = lines.subList(start, end)
            val names = block.filter { it.startsWith("--name=") }
            if (names.size != 1 || names.single().removePrefix("--name=").isBlank()) return null
            val filters = block.filter { it.startsWith("--filter-") }
            val strategies = block.filter { it.startsWith("--lua-desync=") }
            if (filters.isEmpty() || strategies.isEmpty()) return null
            val selectors = block.filter(::isFileSelector)
            val blockHash = sha256(block.joinToString("\n"))
            val blockOccurrence = blockOccurrences.getOrDefault(blockHash, 0)
            blockOccurrences[blockHash] = blockOccurrence + 1
            PresetProfile(
                index = profileIndex,
                stableId = "$blockHash:$blockOccurrence",
                name = names.single().removePrefix("--name="),
                enabled = block.none { it == "--skip" },
                filters = filters,
                selectors = selectors,
                strategies = strategies,
                catalogScope = classify(filters, selectors).takeIf { isReplaceableStrategyTail(block) },
            )
        }
        return PresetProfileDocument(fileName, canonical, sha256(canonical), declaredBlobs, profiles)
    }

    fun setEnabled(document: PresetProfileDocument, profileIndex: Int, enabled: Boolean): String? =
        editBlock(document, profileIndex) { block ->
            val skipIndices = block.indices.filter { block[it] == "--skip" }
            if (skipIndices.size > 1) return@editBlock null
            if (enabled) block.filterIndexed { index, _ -> index !in skipIndices }
            else if (skipIndices.isNotEmpty()) block
            else {
                val nameIndex = block.indexOfFirst { it.startsWith("--name=") }
                if (nameIndex < 0) null else block.toMutableList().apply { add(nameIndex + 1, "--skip") }
            }
        }

    fun rename(document: PresetProfileDocument, profileIndex: Int, name: String): String? {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.any { it == '\r' || it == '\n' || it.isISOControl() }) return null
        return editBlock(document, profileIndex) { block ->
            val names = block.indices.filter { block[it].startsWith("--name=") }
            if (names.size != 1) null else block.toMutableList().apply {
                this[names.single()] = "--name=$normalized"
            }
        }
    }

    fun replaceSelector(
        document: PresetProfileDocument,
        profileIndex: Int,
        selectorIndex: Int,
        relativePath: String,
    ): String? {
        if (!relativePath.matches(Regex("lists/[A-Za-z0-9._-]+\\.txt"))) return null
        return editBlock(document, profileIndex) { block ->
            val selectorLines = block.indices.filter { isFileSelector(block[it]) }
            val lineIndex = selectorLines.getOrNull(selectorIndex) ?: return@editBlock null
            val option = block[lineIndex].substringBefore('=')
            block.toMutableList().apply { this[lineIndex] = "$option=$relativePath" }
        }
    }

    fun replaceStrategy(
        document: PresetProfileDocument,
        profileIndex: Int,
        arguments: List<String>,
    ): String? {
        if (arguments.isEmpty() || arguments.any { !it.startsWith("--lua-desync=") }) return null
        return editBlock(document, profileIndex) { block ->
            if (!isReplaceableStrategyTail(block)) return@editBlock null
            val first = block.indexOfFirst { it.startsWith("--lua-desync=") }
            if (first < 0) return@editBlock null
            block.filterNot { it.startsWith("--lua-desync=") }.toMutableList().apply {
                addAll(first.coerceAtMost(size), arguments)
            }
        }
    }

    fun move(document: PresetProfileDocument, fromIndex: Int, toIndex: Int): String? {
        if (fromIndex !in document.profiles.indices || toIndex !in document.profiles.indices) return null
        if (fromIndex == toIndex) return document.sourceText
        val lines = document.sourceText.trimEnd('\n').split('\n')
        val firstName = lines.indexOfFirst { it.startsWith("--name=") }
        if (firstName < 0) return null
        val global = lines.subList(0, firstName).dropLastWhile(String::isBlank)
        val profileText = lines.subList(firstName, lines.size).joinToString("\n")
        val blocks = profileText.split(Regex("\n[ \\t]*--new[ \\t]*\n"))
            .map { it.trim('\n') }
            .toMutableList()
        if (blocks.size != document.profiles.size) return null
        val moved = blocks.removeAt(fromIndex)
        blocks.add(toIndex, moved)
        return buildString {
            append(global.joinToString("\n"))
            append("\n\n")
            append(blocks.joinToString("\n\n--new\n\n"))
            append('\n')
        }
    }

    private fun editBlock(
        document: PresetProfileDocument,
        profileIndex: Int,
        transform: (List<String>) -> List<String>?,
    ): String? {
        if (profileIndex !in document.profiles.indices) return null
        val lines = document.sourceText.trimEnd('\n').split('\n').toMutableList()
        val starts = lines.mapIndexedNotNull { index, line -> index.takeIf { line.startsWith("--name=") } }
        val start = starts.getOrNull(profileIndex) ?: return null
        val end = lines.indexOfFirst(start) { it == "--new" }.let { if (it < 0) lines.size else it }
        val replacement = transform(lines.subList(start, end).toList()) ?: return null
        repeat(end - start) { lines.removeAt(start) }
        lines.addAll(start, replacement)
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    private fun classify(filters: List<String>, selectors: List<String>): StrategyCatalogScope? {
        val tcp = filters.filter { it.startsWith("--filter-tcp=") }
        val udp = filters.filter { it.startsWith("--filter-udp=") }
        val l7 = filters.filter { it.startsWith("--filter-l7=") }
        val voiceL7 = l7.any { value ->
            value.removePrefix("--filter-l7=").split(',').any { it == "stun" || it == "discord" }
        }
        val discordSelector = selectors.any {
            (it.startsWith("--hostlist=") || it.startsWith("--ipset=")) &&
                it.substringAfter('=').substringAfterLast('/').contains("discord", ignoreCase = true)
        }
        if (tcp.isNotEmpty() && (udp.isNotEmpty() || l7.isNotEmpty())) return null
        if (voiceL7 || (udp.isNotEmpty() && discordSelector)) return StrategyCatalogScope.VOICE
        if (tcp.isNotEmpty() && udp.isEmpty() && l7.isEmpty()) {
            val ports = tcp.flatMap { it.substringAfter('=').split(',') }.toSet()
            if (ports == setOf("80")) return StrategyCatalogScope.HTTP80
            return StrategyCatalogScope.TCP
        }
        if (udp.isNotEmpty() && tcp.isEmpty() && l7.isEmpty()) return StrategyCatalogScope.UDP
        return null
    }

    private fun isFileSelector(line: String): Boolean =
        line.startsWith("--hostlist=") || line.startsWith("--hostlist-exclude=") ||
            line.startsWith("--ipset=") || line.startsWith("--ipset-exclude=")

    private fun isReplaceableStrategyTail(block: List<String>): Boolean {
        val firstStrategy = block.indexOfFirst { it.startsWith("--lua-desync=") }
        if (firstStrategy < 0) return false
        return block.drop(firstStrategy).all { line ->
            line.isBlank() || line.startsWith("#") || line.startsWith(";") || line.startsWith("--lua-desync=")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val BLOB_NAME = Regex("[A-Za-z0-9_.-]+")
}

private inline fun <T> List<T>.indexOfFirst(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}
