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
        val filterMode: String,   // "ipset", "hostlist", or "none"
        val strategy: String      // Strategy name (e.g., "syndata_multisplit_tls_google_700" or "disabled")
    ) {
        // Backward compatibility properties
        val strategyName: String get() = strategy
        val enabled: Boolean get() = strategy.isNotEmpty() && strategy != "disabled"

        // Check if filter mode switching is available (both files exist)
        val canSwitchFilterMode: Boolean get() = hostlistFile.isNotEmpty() && ipsetFile.isNotEmpty()
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
     * hostlist=youtube.txt
     * ipset=ipset-youtube.txt
     * filter_mode=ipset
     * strategy=syndata_multisplit_tls_google_700
     */
    suspend fun readCategories(): Map<String, CategoryConfig> {
        return withContext(Dispatchers.IO) {
            val categories = mutableMapOf<String, CategoryConfig>()

            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext categories

            var currentSection = ""
            var protocol = "tcp"
            var hostlistFile = ""
            var ipsetFile = ""
            var filterMode = "none"
            var strategy = "disabled"

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
                            hostlistFile = hostlistFile,
                            ipsetFile = ipsetFile,
                            filterMode = filterMode,
                            strategy = strategy
                        )
                    }
                    // Start new section with defaults
                    currentSection = sectionMatch.groupValues[1]
                    protocol = "tcp"
                    hostlistFile = ""
                    ipsetFile = ""
                    filterMode = "none"
                    strategy = "disabled"
                    continue
                }

                // Key=value pairs
                val keyValueMatch = Regex("""^([a-z_]+)=(.*)$""").find(trimmed)
                if (keyValueMatch != null) {
                    val key = keyValueMatch.groupValues[1]
                    val value = keyValueMatch.groupValues[2]
                    when (key) {
                        "protocol" -> protocol = value.ifEmpty { "tcp" }
                        "hostlist" -> hostlistFile = value
                        "ipset" -> ipsetFile = value
                        "filter_mode" -> filterMode = value.ifEmpty { "none" }
                        "strategy" -> strategy = value.ifEmpty { "disabled" }
                    }
                }
            }

            // Don't forget the last section
            if (currentSection.isNotEmpty()) {
                categories[currentSection] = CategoryConfig(
                    key = currentSection,
                    protocol = protocol,
                    hostlistFile = hostlistFile,
                    ipsetFile = ipsetFile,
                    filterMode = filterMode,
                    strategy = strategy
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

                // If in target section, update strategy field
                if (inTargetSection && trimmed.startsWith("strategy=")) {
                    lines[i] = "strategy=${newStrategyName.ifEmpty { "disabled" }}"
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
     * Update filter_mode for a category in categories.ini.
     *
     * @param categoryKey The category key (e.g., "youtube")
     * @param newFilterMode The filter mode ("ipset", "hostlist", or "none")
     */
    suspend fun updateCategoryFilterMode(categoryKey: String, newFilterMode: String): Boolean {
        return withContext(Dispatchers.IO) {
            // Read current file
            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext false

            val lines = result.out.toMutableList()
            var inTargetSection = false
            var sectionFound = false

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

                // If in target section, update filter_mode field
                if (inTargetSection && trimmed.startsWith("filter_mode=")) {
                    lines[i] = "filter_mode=$newFilterMode"
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
     * Batch update strategies and filter modes for multiple categories in a single file operation.
     * Much more efficient than calling updateCategoryStrategyByName() multiple times.
     * Reduces many shell commands to just 2 (one read, one write).
     *
     * @param strategyUpdates Map of category key to strategy name (e.g., "youtube" to "syndata_multisplit_tls_google_700")
     * @param filterModeUpdates Optional map of category key to filter mode ("ipset", "hostlist", or "none")
     */
    suspend fun updateAllCategoryStrategies(
        strategyUpdates: Map<String, String>,
        filterModeUpdates: Map<String, String>? = null
    ): Boolean {
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

                // Update strategy if current section is in strategyUpdates map
                strategyUpdates[currentSection]?.let { newStrategyName ->
                    if (trimmed.startsWith("strategy=")) {
                        lines[i] = "strategy=${newStrategyName.ifEmpty { "disabled" }}"
                    }
                }

                // Update filter_mode if current section is in filterModeUpdates map
                filterModeUpdates?.get(currentSection)?.let { newFilterMode ->
                    if (trimmed.startsWith("filter_mode=")) {
                        lines[i] = "filter_mode=$newFilterMode"
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
