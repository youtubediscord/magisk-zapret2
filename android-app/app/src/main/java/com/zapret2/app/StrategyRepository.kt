package com.zapret2.app

import com.topjohnwu.superuser.Shell
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

    // Cached strategies
    private var tcpStrategies: List<StrategyInfo>? = null
    private var udpStrategies: List<StrategyInfo>? = null
    private var stunStrategies: List<StrategyInfo>? = null

    data class StrategyInfo(
        val id: String,           // Internal ID (e.g., "syndata_2_tls_7")
        val displayName: String,  // Display name (e.g., "Syndata 2 tls 7")
        val index: Int            // 0-based index (0 = disabled)
    )

    /**
     * Category configuration from INI file.
     * INI format:
     * [category_name]
     * protocol=tcp
     * enabled=true
     * filter=--filter-tcp=80,443
     * hostlist=--hostlist=lists/youtube.txt
     * strategy=syndata_multisplit_tls_google_700
     * command=--filter-tcp=80,443 --hostlist=lists/youtube.txt --lua-desync=...
     */
    data class CategoryConfig(
        val key: String,          // e.g., "youtube" (section name)
        val protocol: String,     // "tcp", "udp", or "stun"
        val enabled: Boolean,     // true if category is enabled
        val filter: String,       // e.g., "--filter-tcp=80,443"
        val hostlist: String,     // e.g., "--hostlist=lists/youtube.txt" or "--ipset=lists/ipset-discord.txt"
        val strategy: String,     // Strategy name (e.g., "syndata_multisplit_tls_google_700" or "disabled")
        val command: String       // Full nfqws command for this category
    ) {
        // Backward compatibility properties
        val strategyName: String get() = strategy
        val filterMode: String get() = when {
            hostlist.contains("--ipset=") -> "ipset"
            hostlist.contains("--hostlist=") -> "hostlist"
            else -> "none"
        }
        val hostlistFile: String get() {
            val regex = Regex("""(?:--hostlist|--ipset)=(?:lists/)?([^\s]+)""")
            return regex.find(hostlist)?.groupValues?.get(1) ?: ""
        }
    }

    /**
     * Load TCP strategies from strategies-tcp.ini
     */
    suspend fun getTcpStrategies(): List<StrategyInfo> {
        if (tcpStrategies != null) return tcpStrategies!!

        return withContext(Dispatchers.IO) {
            val strategies = mutableListOf<StrategyInfo>()

            // Add "Disabled" as first option
            strategies.add(StrategyInfo("disabled", "Disabled", 0))

            val result = Shell.cmd("cat $TCP_STRATEGIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                val ids = parseStrategiesFromIni(result.out)
                ids.forEachIndexed { index, id ->
                    strategies.add(StrategyInfo(
                        id = id,
                        displayName = formatDisplayName(id),
                        index = index + 1 // 1-based, 0 is "disabled"
                    ))
                }
            }

            tcpStrategies = strategies
            strategies
        }
    }

    /**
     * Load UDP strategies from strategies-udp.ini
     */
    suspend fun getUdpStrategies(): List<StrategyInfo> {
        if (udpStrategies != null) return udpStrategies!!

        return withContext(Dispatchers.IO) {
            val strategies = mutableListOf<StrategyInfo>()

            // Add "Disabled" as first option
            strategies.add(StrategyInfo("disabled", "Disabled", 0))

            val result = Shell.cmd("cat $UDP_STRATEGIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                val ids = parseStrategiesFromIni(result.out)
                ids.forEachIndexed { index, id ->
                    strategies.add(StrategyInfo(
                        id = id,
                        displayName = formatDisplayName(id),
                        index = index + 1
                    ))
                }
            }

            udpStrategies = strategies
            strategies
        }
    }

    /**
     * Load STUN strategies from strategies-stun.ini
     * Used for voice/video calls (Discord, Telegram, etc.)
     */
    suspend fun getStunStrategies(): List<StrategyInfo> {
        if (stunStrategies != null) return stunStrategies!!

        return withContext(Dispatchers.IO) {
            val strategies = mutableListOf<StrategyInfo>()

            // Add "Disabled" as first option
            strategies.add(StrategyInfo("disabled", "Disabled", 0))

            val result = Shell.cmd("cat $STUN_STRATEGIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                val ids = parseStrategiesFromIni(result.out)
                ids.forEachIndexed { index, id ->
                    strategies.add(StrategyInfo(
                        id = id,
                        displayName = formatDisplayName(id),
                        index = index + 1
                    ))
                }
            }

            stunStrategies = strategies
            strategies
        }
    }

    /**
     * Get strategy by index (0 = disabled, 1+ = actual strategy)
     */
    suspend fun getStrategyByIndex(isTcp: Boolean, index: Int): StrategyInfo? {
        val strategies = if (isTcp) getTcpStrategies() else getUdpStrategies()
        return strategies.getOrNull(index)
    }

    /**
     * Get strategy index by ID
     */
    suspend fun getIndexById(isTcp: Boolean, id: String): Int {
        val strategies = if (isTcp) getTcpStrategies() else getUdpStrategies()
        return strategies.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }

    /**
     * Read all category configurations from categories.ini
     * INI format:
     * [section_name]
     * protocol=tcp
     * enabled=true
     * filter=--filter-tcp=80,443
     * hostlist=--hostlist=lists/youtube.txt
     * strategy=syndata_multisplit_tls_google_700
     * command=--filter-tcp=80,443 --hostlist=lists/youtube.txt --lua-desync=...
     */
    suspend fun readCategories(): Map<String, CategoryConfig> {
        return withContext(Dispatchers.IO) {
            val categories = mutableMapOf<String, CategoryConfig>()

            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext categories

            var currentSection = ""
            var protocol = "tcp"
            var enabled = false
            var filter = ""
            var hostlist = ""
            var strategy = "disabled"
            var command = ""

            for (line in result.out) {
                val trimmed = line.trim()
                // Skip empty lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

                // Section header [name]
                val sectionMatch = Regex("""^\[([a-zA-Z0-9_]+)\]$""").find(trimmed)
                if (sectionMatch != null) {
                    // Save previous section if exists
                    if (currentSection.isNotEmpty()) {
                        categories[currentSection] = CategoryConfig(
                            key = currentSection,
                            protocol = protocol,
                            enabled = enabled,
                            filter = filter,
                            hostlist = hostlist,
                            strategy = strategy,
                            command = command
                        )
                    }
                    // Start new section with defaults
                    currentSection = sectionMatch.groupValues[1]
                    protocol = "tcp"
                    enabled = false
                    filter = ""
                    hostlist = ""
                    strategy = "disabled"
                    command = ""
                    continue
                }

                // Key=value pairs
                val keyValueMatch = Regex("""^([a-z_]+)=(.*)$""").find(trimmed)
                if (keyValueMatch != null) {
                    val key = keyValueMatch.groupValues[1]
                    val value = keyValueMatch.groupValues[2]
                    when (key) {
                        "protocol" -> protocol = value.ifEmpty { "tcp" }
                        "enabled" -> enabled = value == "true"
                        "filter" -> filter = value
                        "hostlist" -> hostlist = value
                        "strategy" -> strategy = value.ifEmpty { "disabled" }
                        "command" -> command = value
                    }
                }
            }

            // Don't forget the last section
            if (currentSection.isNotEmpty()) {
                categories[currentSection] = CategoryConfig(
                    key = currentSection,
                    protocol = protocol,
                    enabled = enabled,
                    filter = filter,
                    hostlist = hostlist,
                    strategy = strategy,
                    command = command
                )
            }

            categories
        }
    }

    /**
     * Get strategy index for a specific category
     * Returns the index of the strategy in the strategy list (0 = disabled)
     */
    suspend fun getCategoryStrategyIndex(categoryKey: String): Int {
        val categories = readCategories()
        val category = categories[categoryKey] ?: return 0

        val strategyName = category.strategyName
        if (strategyName == "disabled" || strategyName.isEmpty()) {
            return 0
        }

        // Determine if TCP or UDP based on protocol
        val isTcp = category.protocol != "udp"
        return getIndexById(isTcp, strategyName)
    }

    /**
     * Update strategy for a category in categories.ini by strategy NAME.
     * Updates both the 'strategy' and 'enabled' fields in the INI section.
     *
     * @param categoryKey The category key (e.g., "youtube")
     * @param newStrategyName The strategy name (e.g., "syndata_multisplit_tls_google_700" or "disabled")
     */
    suspend fun updateCategoryStrategyByName(categoryKey: String, newStrategyName: String): Boolean {
        return withContext(Dispatchers.IO) {
            // Read current file
            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext false

            val lines = result.out.toMutableList()
            var inTargetSection = false
            var sectionFound = false
            var strategyUpdated = false
            var enabledUpdated = false
            val isEnabled = newStrategyName.isNotEmpty() && newStrategyName != "disabled"

            for (i in lines.indices) {
                val line = lines[i]
                val trimmed = line.trim()

                // Check for section header
                val sectionMatch = Regex("""^\[([a-zA-Z0-9_]+)\]$""").find(trimmed)
                if (sectionMatch != null) {
                    // If we were in target section and leaving, break
                    if (inTargetSection) break
                    // Check if this is our target section
                    inTargetSection = sectionMatch.groupValues[1] == categoryKey
                    if (inTargetSection) sectionFound = true
                    continue
                }

                // If in target section, update strategy and enabled fields
                if (inTargetSection) {
                    if (trimmed.startsWith("strategy=")) {
                        lines[i] = "strategy=${newStrategyName.ifEmpty { "disabled" }}"
                        strategyUpdated = true
                    } else if (trimmed.startsWith("enabled=")) {
                        lines[i] = "enabled=$isEnabled"
                        enabledUpdated = true
                    }
                }
            }

            if (!sectionFound) return@withContext false

            // Write back preserving original formatting
            val newContent = lines.joinToString("\n")
            val escaped = newContent.replace("'", "'\\''")
            val writeResult = Shell.cmd("echo '$escaped' > $CATEGORIES_FILE").exec()
            writeResult.isSuccess
        }
    }

    /**
     * Batch update strategies for multiple categories in a single file operation.
     * Much more efficient than calling updateCategoryStrategyByName() multiple times.
     * Reduces many shell commands to just 2 (one read, one write).
     *
     * Updates both 'strategy' and 'enabled' fields for each category in the INI file.
     *
     * @param updates Map of category key to strategy name (e.g., "youtube" to "syndata_multisplit_tls_google_700")
     */
    suspend fun updateAllCategoryStrategies(updates: Map<String, String>): Boolean {
        return withContext(Dispatchers.IO) {
            // Read file ONCE
            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext false

            val lines = result.out.toMutableList()
            var currentSection = ""

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
                    continue
                }

                // If current section is in updates map, update strategy and enabled
                updates[currentSection]?.let { newStrategyName ->
                    val isEnabled = newStrategyName.isNotEmpty() && newStrategyName != "disabled"

                    if (trimmed.startsWith("strategy=")) {
                        lines[i] = "strategy=${newStrategyName.ifEmpty { "disabled" }}"
                    } else if (trimmed.startsWith("enabled=")) {
                        lines[i] = "enabled=$isEnabled"
                    }
                }
            }

            // Write file ONCE preserving original formatting
            val newContent = lines.joinToString("\n")
            val escaped = newContent.replace("'", "'\\''")
            val writeResult = Shell.cmd("echo '$escaped' > $CATEGORIES_FILE").exec()
            writeResult.isSuccess
        }
    }

    /**
     * Update strategy for a category in categories.ini by strategy INDEX.
     * Converts index to strategy name and delegates to updateCategoryStrategyByName.
     *
     * @param categoryKey The category key (e.g., "youtube")
     * @param newStrategyIndex The index in the strategy list (0 = disabled)
     * @deprecated Use updateCategoryStrategyByName instead for better clarity
     */
    suspend fun updateCategoryStrategy(categoryKey: String, newStrategyIndex: Int): Boolean {
        return withContext(Dispatchers.IO) {
            // First read categories to get the protocol for this category
            val categories = readCategories()
            val category = categories[categoryKey] ?: return@withContext false

            val isTcp = category.protocol != "udp" && category.protocol != "stun"
            val isStun = category.protocol == "stun"

            // Get the strategy name from index
            val strategies = when {
                isStun -> stunStrategies ?: getStunStrategiesSync()
                isTcp -> tcpStrategies ?: getTcpStrategiesSync()
                else -> udpStrategies ?: getUdpStrategiesSync()
            }

            // Get strategy name (index 0 = "disabled")
            val strategyName = strategies.getOrNull(newStrategyIndex)?.id ?: "disabled"

            // Delegate to updateCategoryStrategyByName
            updateCategoryStrategyByName(categoryKey, strategyName)
        }
    }

    /**
     * Synchronous version of getTcpStrategies for use within IO context
     */
    private fun getTcpStrategiesSync(): List<StrategyInfo> {
        val strategies = mutableListOf<StrategyInfo>()
        strategies.add(StrategyInfo("disabled", "Disabled", 0))

        val result = Shell.cmd("cat $TCP_STRATEGIES_FILE 2>/dev/null").exec()
        if (result.isSuccess) {
            val ids = parseStrategiesFromIni(result.out)
            ids.forEachIndexed { index, id ->
                strategies.add(StrategyInfo(
                    id = id,
                    displayName = formatDisplayName(id),
                    index = index + 1
                ))
            }
        }

        tcpStrategies = strategies
        return strategies
    }

    /**
     * Synchronous version of getUdpStrategies for use within IO context
     */
    private fun getUdpStrategiesSync(): List<StrategyInfo> {
        val strategies = mutableListOf<StrategyInfo>()
        strategies.add(StrategyInfo("disabled", "Disabled", 0))

        val result = Shell.cmd("cat $UDP_STRATEGIES_FILE 2>/dev/null").exec()
        if (result.isSuccess) {
            val ids = parseStrategiesFromIni(result.out)
            ids.forEachIndexed { index, id ->
                strategies.add(StrategyInfo(
                    id = id,
                    displayName = formatDisplayName(id),
                    index = index + 1
                ))
            }
        }

        udpStrategies = strategies
        return strategies
    }

    /**
     * Synchronous version of getStunStrategies for use within IO context
     */
    private fun getStunStrategiesSync(): List<StrategyInfo> {
        val strategies = mutableListOf<StrategyInfo>()
        strategies.add(StrategyInfo("disabled", "Disabled", 0))

        val result = Shell.cmd("cat $STUN_STRATEGIES_FILE 2>/dev/null").exec()
        if (result.isSuccess) {
            val ids = parseStrategiesFromIni(result.out)
            ids.forEachIndexed { index, id ->
                strategies.add(StrategyInfo(
                    id = id,
                    displayName = formatDisplayName(id),
                    index = index + 1
                ))
            }
        }

        stunStrategies = strategies
        return strategies
    }

    /**
     * Check if a category is enabled.
     * Uses the 'enabled' field from INI (true/false).
     */
    suspend fun isCategoryEnabled(categoryKey: String): Boolean {
        val categories = readCategories()
        val category = categories[categoryKey] ?: return false
        // Use the enabled field directly from INI
        return category.enabled
    }

    /**
     * Clear cached strategies (call when module is updated)
     */
    fun clearCache() {
        tcpStrategies = null
        udpStrategies = null
        stunStrategies = null
    }

    // Helper functions

    /**
     * Parse strategy names from INI file content.
     * Extracts section names [strategy_name] from the file.
     * Skips 'default' and 'disabled' sections as they are handled separately.
     */
    private fun parseStrategiesFromIni(lines: List<String>): List<String> {
        val ids = mutableListOf<String>()
        val sectionPattern = Regex("""^\[([a-zA-Z0-9_]+)\]$""")

        for (line in lines) {
            val trimmed = line.trim()
            val match = sectionPattern.find(trimmed)
            if (match != null) {
                val sectionName = match.groupValues[1]
                // Skip special sections - they are added manually as first items
                if (sectionName != "default" && sectionName != "disabled") {
                    ids.add(sectionName)
                }
            }
        }

        return ids
    }

    private fun formatDisplayName(id: String): String {
        return id.split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
