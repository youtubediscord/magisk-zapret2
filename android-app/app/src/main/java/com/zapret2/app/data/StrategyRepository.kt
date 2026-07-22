package com.zapret2.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for managing DPI bypass strategies.
 * Reads strategy definitions from INI files:
 * - strategies-tcp.ini (TCP strategies)
 * - strategies-udp.ini (UDP/QUIC strategies)
 * - strategies-stun.ini (STUN/voice strategies)
 * - categories.ini (category configurations)
 */
object StrategyRepository {

    private const val MODDIR = "/data/adb/modules/zapret2"
    private const val TCP_STRATEGIES_FILE = "$MODDIR/zapret2/strategies-tcp.ini"
    private const val UDP_STRATEGIES_FILE = "$MODDIR/zapret2/strategies-udp.ini"
    private const val STUN_STRATEGIES_FILE = "$MODDIR/zapret2/strategies-stun.ini"
    private const val CATEGORIES_FILE = "$MODDIR/zapret2/categories.ini"
    private const val COMMAND_BUILDER = "$MODDIR/zapret2/scripts/command-builder.sh"
    private const val ZAPRET_DIR = "$MODDIR/zapret2"
    private const val MAX_STRATEGY_FILE_BYTES = 1024 * 1024
    private const val MAX_STRATEGY_ARGS_CHARS = 64 * 1024
    private const val MAX_CATEGORIES_FILE_BYTES = 1024 * 1024
    private val categoriesLock = Any()
    private val identifierPattern = Regex("[a-zA-Z0-9_]+")
    private val allowedFilterModes = setOf("ipset", "hostlist", "hostlist-domains", "none")

    data class StrategyDetail(
        val id: String,
        val displayName: String,
        val description: String,
        val args: String
    )

    /** Stable sentinel; presentation owns its localized user-facing copy. */
    private fun disabledStrategyDetail() = StrategyDetail("disabled", "", "", "")

    /**
     * Category configuration from INI file.
     * INI format:
     * [category_name]
     * protocol=tcp
     * hostlist=youtube.txt
     * ipset=ipset-youtube.txt
     * filter_mode=ipset
     * strategy=syndata_multisplit_tls_google_700
     */
    data class CategoryConfig(
        val key: String,          // e.g., "youtube" (section name)
        val protocol: String,     // "tcp", "udp", or "stun"
        val hostlistFile: String, // Hostlist filename (e.g., "youtube.txt")
        val ipsetFile: String,    // IP set filename (e.g., "ipset-youtube.txt")
        val hostlistDomains: String, // Inline domains (e.g., "googlevideo.com")
        val filterMode: String,   // "ipset", "hostlist", "hostlist-domains", or "none"
        val strategy: String      // Strategy name (e.g., "syndata_multisplit_tls_google_700" or "disabled")
    ) {
        // Check if filter mode switching is available (both files exist)
        val canSwitchFilterMode: Boolean get() = hostlistFile.isNotEmpty() && ipsetFile.isNotEmpty()
    }

    /**
     * Read full strategy details (id, desc, args) from an INI file.
     * Parses all sections with their key-value pairs.
     */
    suspend fun getStrategyDetails(type: String): List<StrategyDetail> = withContext(Dispatchers.IO) {
        val filePath = when (type) {
            "tcp" -> TCP_STRATEGIES_FILE
            "udp" -> UDP_STRATEGIES_FILE
            "voice", "stun" -> STUN_STRATEGIES_FILE
            else -> return@withContext listOf(disabledStrategyDetail())
        }

        val content = RootFileIo.readSecureRegularText(filePath, MAX_STRATEGY_FILE_BYTES)
        if (content == null) return@withContext listOf(
            disabledStrategyDetail()
        )
        parseStrategyDetailsContent(content) ?: listOf(disabledStrategyDetail())
    }

    internal fun parseStrategyDetailsContent(content: String): List<StrategyDetail>? {
        val details = mutableListOf<StrategyDetail>()
        details.add(disabledStrategyDetail())
        val seenSections = mutableSetOf<String>()
        var currentId: String? = null
        var currentValues = linkedMapOf<String, String>()
        val sectionPattern = Regex("""^\[([a-zA-Z0-9_]+)\]$""")

        fun flushSection(): Boolean {
            val id = currentId ?: return currentValues.isEmpty()
            val args = currentValues["args"] ?: return false
            if (id == "disabled") return args.isEmpty()
            if (args.isBlank() || args.length > MAX_STRATEGY_ARGS_CHARS) return false
            details.add(
                StrategyDetail(
                    id = id,
                    displayName = formatDisplayName(id),
                    description = currentValues["desc"].orEmpty(),
                    args = args,
                ),
            )
            return true
        }

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

            val sectionMatch = sectionPattern.matchEntire(trimmed)
            if (sectionMatch != null) {
                if (!flushSection()) return null
                val id = sectionMatch.groupValues[1]
                if (!seenSections.add(id)) return null
                currentId = id
                currentValues = linkedMapOf()
                continue
            }

            val eqIdx = trimmed.indexOf('=')
            if (currentId == null || eqIdx <= 0) return null
            val key = trimmed.substring(0, eqIdx).trim()
            val value = decodeConfigScalar(trimmed.substring(eqIdx + 1)) ?: return null
            if (!key.matches(Regex("[a-zA-Z0-9_-]+")) || key in currentValues) return null
            currentValues[key] = value
        }
        if (!flushSection() || details.size <= 1) return null
        return details
    }

    /**
     * Read all category configurations from categories.ini
     * INI format:
     * [section_name]
     * protocol=tcp
     * hostlist=youtube.txt
     * ipset=ipset-youtube.txt
     * filter_mode=ipset
     * strategy=syndata_multisplit_tls_google_700
     */
    suspend fun readCategories(): Map<String, CategoryConfig>? = withContext(Dispatchers.IO) {
        val content = snapshotCategoriesContent() ?: return@withContext null
        parseCategoriesContent(content)
    }

    internal suspend fun snapshotCategoriesContent(): String? = withContext(Dispatchers.IO) {
        RootFileIo.readSecureRegularText(CATEGORIES_FILE, MAX_CATEGORIES_FILE_BYTES)
            ?.takeIf { parseCategoriesContent(it) != null }
    }

    internal suspend fun restoreCategoriesContent(content: String): Boolean = withContext(Dispatchers.IO) {
        if (parseCategoriesContent(content) == null) return@withContext false
        RootFileIo.writeTextAtomically(
            CATEGORIES_FILE,
            content,
            "Z2_CATEGORIES_RESTORE",
            fileMode = "0644",
        )
    }

    internal suspend fun validateCategoriesWithRuntimeBuilder(): Boolean = withContext(Dispatchers.IO) {
        val result = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(COMMAND_BUILDER)} --validate-categories-machine " +
                RootFileIo.shellQuote(ZAPRET_DIR),
        )
        result.success && parseCategoryValidation(result.stdout)
    }

    internal fun parseCategoryValidation(lines: List<String>): Boolean =
        lines == listOf("Z2_CATEGORIES\tOK")

    internal fun parseCategoriesContent(content: String): Map<String, CategoryConfig>? {
        val categories = linkedMapOf<String, CategoryConfig>()
        var currentSection: String? = null
        var currentValues = linkedMapOf<String, String>()

        fun flushSection(): Boolean {
            val key = currentSection ?: return currentValues.isEmpty()
            val protocol = currentValues["protocol"] ?: return false
            val filterMode = currentValues["filter_mode"] ?: return false
            val strategy = currentValues["strategy"] ?: return false
            val hostlist = currentValues["hostlist"].orEmpty()
            val ipset = currentValues["ipset"].orEmpty()
            val inlineDomains = currentValues["hostlist-domains"].orEmpty()
            if (protocol !in setOf("tcp", "udp", "stun")) return false
            if (filterMode !in allowedFilterModes) return false
            if (!identifierPattern.matches(strategy)) return false
            if (hostlist.isNotEmpty() && !RootFileIo.isSimpleFileName(hostlist, ".txt")) return false
            if (ipset.isNotEmpty() && !RootFileIo.isSimpleFileName(ipset, ".txt")) return false
            if (!isValidInlineDomains(inlineDomains)) return false
            val activeBindingExists = when (filterMode) {
                "hostlist" -> hostlist.isNotEmpty()
                "ipset" -> ipset.isNotEmpty()
                "hostlist-domains" -> inlineDomains.isNotEmpty()
                "none" -> true
                else -> false
            }
            if (!activeBindingExists) return false
            categories[key] = CategoryConfig(
                key = key,
                protocol = protocol,
                hostlistFile = hostlist,
                ipsetFile = ipset,
                hostlistDomains = inlineDomains,
                filterMode = filterMode,
                strategy = strategy,
            )
            return true
        }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val section = Regex("""^\[([a-zA-Z0-9_]+)\]$""").matchEntire(line)
            if (section != null) {
                if (!flushSection()) return null
                val key = section.groupValues[1]
                if (key in categories || key == currentSection) return null
                currentSection = key
                currentValues = linkedMapOf()
                continue
            }
            val separator = line.indexOf('=')
            if (currentSection == null || separator <= 0) return null
            val rawKey = line.substring(0, separator).trim()
            val key = if (rawKey == "hostlist_domains") "hostlist-domains" else rawKey
            val value = decodeConfigScalar(line.substring(separator + 1)) ?: return null
            if (key !in setOf("protocol", "hostlist", "ipset", "hostlist-domains", "filter_mode", "strategy") ||
                key in currentValues
            ) return null
            currentValues[key] = value
        }
        if (!flushSection() || categories.isEmpty()) return null
        return categories
    }

    /**
     * Batch update strategies and filter modes for multiple categories in a single file operation.
     * Writes every selected strategy and filter mode in one atomic operation.
     * Reduces many shell commands to just 2 (one read, one write).
     *
     * @param strategyUpdates Map of category key to strategy name (e.g., "youtube" to "syndata_multisplit_tls_google_700")
     * @param filterModeUpdates Optional map of category key to filter mode ("ipset", "hostlist", "hostlist-domains", or "none")
     */
    suspend fun updateAllCategoryStrategies(
        strategyUpdates: Map<String, String>,
        filterModeUpdates: Map<String, String>? = null
    ): Boolean {
        return ModuleMutationCoordinator.withMutation { withContext(Dispatchers.IO) {
            if (strategyUpdates.any { (key, value) ->
                    !identifierPattern.matches(key) || !identifierPattern.matches(value)
                } || filterModeUpdates.orEmpty().any { (key, value) ->
                    !identifierPattern.matches(key) || value !in allowedFilterModes
                }
            ) return@withContext false

            synchronized(categoriesLock) {
            val content = RootFileIo.readSecureRegularText(
                CATEGORIES_FILE,
                MAX_CATEGORIES_FILE_BYTES,
            ) ?: return@synchronized false
            if (parseCategoriesContent(content) == null) return@synchronized false

            val lines = content.split('\n').toMutableList()
            var currentSection = ""
            val seenSections = mutableSetOf<String>()
            val remainingStrategies = strategyUpdates.keys.toMutableSet()
            val remainingFilterModes = filterModeUpdates.orEmpty().keys.toMutableSet()

            // Update all matching sections in memory
            for (i in lines.indices) {
                val line = lines[i]
                val trimmed = line.trim()

                // Skip comments and empty lines (preserve them)
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

                // Check for section header
                val sectionMatch = Regex("""^\[([a-zA-Z0-9_]+)\]$""").find(trimmed)
                if (sectionMatch != null) {
                    currentSection = sectionMatch.groupValues[1]
                    if (!seenSections.add(currentSection)) return@synchronized false
                    continue
                }
                if (trimmed.startsWith("[") || trimmed.endsWith("]")) {
                    currentSection = ""
                    continue
                }
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@synchronized false
                val configKey = trimmed.substring(0, separator).trim()

                // Update strategy if current section is in strategyUpdates map
                strategyUpdates[currentSection]?.let { newStrategyName ->
                    if (configKey == "strategy") {
                        if (!remainingStrategies.remove(currentSection)) return@synchronized false
                        lines[i] = "strategy=$newStrategyName"
                    }
                }

                // Update filter_mode if current section is in filterModeUpdates map
                filterModeUpdates?.get(currentSection)?.let { newFilterMode ->
                    if (configKey == "filter_mode") {
                        if (!remainingFilterModes.remove(currentSection)) return@synchronized false
                        lines[i] = "filter_mode=$newFilterMode"
                    }
                }
            }

            if (remainingStrategies.isNotEmpty() || remainingFilterModes.isNotEmpty()) {
                return@synchronized false
            }

            val newContent = lines.joinToString("\n")
            if (parseCategoriesContent(newContent) == null) return@synchronized false
            val written = RootFileIo.writeTextAtomically(
                CATEGORIES_FILE,
                newContent,
                "Z2_CATEGORIES",
                fileMode = "0644",
            )
            if (written) return@synchronized true
            val current = RootFileIo.readSecureRegularText(
                CATEGORIES_FILE,
                MAX_CATEGORIES_FILE_BYTES,
            )
            if (current != content) {
                RootFileIo.writeTextAtomically(
                    CATEGORIES_FILE,
                    content,
                    "Z2_CATEGORIES_ROLLBACK",
                    fileMode = "0644",
                )
            }
            false
            }
        } }
    }

    private fun isValidInlineDomains(value: String): Boolean {
        if (value.length > 4096 || value.any { it.isISOControl() }) return false
        if (value.isEmpty()) return true
        if (value.startsWith(',') || value.endsWith(',') || ",," in value) return false
        return value.matches(Regex("[A-Za-z0-9.,_-]+"))
    }

    private fun decodeConfigScalar(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.any { it.isISOControl() }) return null
        val first = value.first()
        val last = value.last()
        return when {
            first == '"' || first == '\'' -> {
                if (value.length < 2 || last != first) null else value.substring(1, value.lastIndex)
            }
            last == '"' || last == '\'' -> null
            else -> value
        }
    }

    /**
     * Read saved strategy order from runtime.ini [strategy_order] section.
     * Returns list of IDs in saved order, or null if no custom order exists.
     */
    suspend fun getSavedOrder(type: String): List<String>? = withContext(Dispatchers.IO) {
        val key = when (type) {
            "tcp" -> "tcp"
            "udp" -> "udp"
            "voice", "stun" -> "stun"
            else -> return@withContext null
        }
        RuntimeConfigStore.readStrategyOrder()[key]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { identifierPattern.matches(it) }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Save custom strategy order to runtime.ini [strategy_order] section.
     * Only saves non-disabled strategy IDs (disabled is always first).
     */
    suspend fun saveOrder(
        type: String,
        ids: List<String>,
        expectedOrder: List<String>?,
    ) = withContext(Dispatchers.IO) {
        val key = when (type) {
            "tcp" -> "tcp"
            "udp" -> "udp"
            "voice", "stun" -> "stun"
            else -> return@withContext false
        }
        val filtered = ids.filter { it != "disabled" && identifierPattern.matches(it) }.distinct()
        if (filtered.isEmpty()) return@withContext false
        val value = filtered.joinToString(",")
        val expectedValue = expectedOrder
            ?.filter { it != "disabled" && identifierPattern.matches(it) }
            ?.distinct()
            ?.joinToString(",")
        RuntimeConfigStore.upsertStrategyOrderValue(key, value, expectedValue)
    }

    /**
     * Apply saved order to a list of strategy details.
     * Strategies in saved order come first, new strategies come after.
     * "disabled" always stays first.
     */
    fun applyOrder(details: List<StrategyDetail>, savedOrder: List<String>?): List<StrategyDetail> {
        if (savedOrder.isNullOrEmpty()) return details

        val disabled = details.firstOrNull { it.id == "disabled" }
        val rest = details.filter { it.id != "disabled" }
        val byId = rest.associateBy { it.id }

        val ordered = mutableListOf<StrategyDetail>()
        if (disabled != null) ordered.add(disabled)

        // Add in saved order
        for (id in savedOrder) {
            byId[id]?.let { ordered.add(it) }
        }

        // Add any new strategies not in saved order
        for (detail in rest) {
            if (detail.id !in savedOrder) {
                ordered.add(detail)
            }
        }

        return ordered
    }

    private fun formatDisplayName(id: String): String {
        return id.split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
