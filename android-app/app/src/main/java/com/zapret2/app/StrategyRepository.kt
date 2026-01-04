package com.zapret2.app

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for managing DPI bypass strategies.
 * Reads strategy definitions from strategies.sh and categories.txt from the Magisk module.
 */
object StrategyRepository {

    private const val MODDIR = "/data/adb/modules/zapret2"
    private const val STRATEGIES_FILE = "$MODDIR/zapret2/strategies.sh"
    private const val CATEGORIES_FILE = "$MODDIR/zapret2/categories.txt"

    // Cached strategies
    private var tcpStrategies: List<StrategyInfo>? = null
    private var udpStrategies: List<StrategyInfo>? = null

    data class StrategyInfo(
        val id: String,           // Internal ID (e.g., "syndata_2_tls_7")
        val displayName: String,  // Display name (e.g., "Syndata 2 tls 7")
        val index: Int            // 0-based index (0 = disabled)
    )

    data class CategoryConfig(
        val key: String,          // e.g., "youtube"
        val protocol: String,     // "tcp", "udp", or "stun"
        val filterMode: String,   // "none", "hostlist", "ipset"
        val hostlistFile: String, // e.g., "youtube.txt"
        val strategyName: String  // Strategy name (e.g., "syndata_multisplit_tls_google_700" or "disabled")
    )

    /**
     * Load TCP strategies from strategies.sh
     */
    suspend fun getTcpStrategies(): List<StrategyInfo> {
        if (tcpStrategies != null) return tcpStrategies!!

        return withContext(Dispatchers.IO) {
            val strategies = mutableListOf<StrategyInfo>()

            // Add "Disabled" as first option
            strategies.add(StrategyInfo("disabled", "Disabled", 0))

            val result = Shell.cmd("cat $STRATEGIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                val content = result.out.joinToString("\n")

                // Primary: Parse from list_tcp_strategies() function (most reliable)
                var ids = parseStrategiesFromListFunction(content, "list_tcp_strategies")

                // Fallback: Parse from case statement if list function not found
                if (ids.isEmpty()) {
                    val tcpSection = extractSection(content, "TCP STRATEGIES", "UDP STRATEGIES")
                    ids = parseStrategyIds(tcpSection)
                }

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
     * Load UDP strategies from strategies.sh
     */
    suspend fun getUdpStrategies(): List<StrategyInfo> {
        if (udpStrategies != null) return udpStrategies!!

        return withContext(Dispatchers.IO) {
            val strategies = mutableListOf<StrategyInfo>()

            // Add "Disabled" as first option
            strategies.add(StrategyInfo("disabled", "Disabled", 0))

            val result = Shell.cmd("cat $STRATEGIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                val content = result.out.joinToString("\n")

                // Primary: Parse from list_udp_strategies() function (most reliable)
                var ids = parseStrategiesFromListFunction(content, "list_udp_strategies")

                // Fallback: Parse from case statement if list function not found
                if (ids.isEmpty()) {
                    val udpSection = extractSection(content, "UDP STRATEGIES", null)
                    ids = parseStrategyIds(udpSection)
                }

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
     * Read all category configurations from categories.txt
     * Format: CATEGORY|PROTOCOL|FILTER_MODE|HOSTLIST_FILE|STRATEGY_NAME
     */
    suspend fun readCategories(): Map<String, CategoryConfig> {
        return withContext(Dispatchers.IO) {
            val categories = mutableMapOf<String, CategoryConfig>()

            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                result.out.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("|")
                        if (parts.size >= 5) {
                            val key = parts[0]
                            categories[key] = CategoryConfig(
                                key = key,
                                protocol = parts[1].ifEmpty { "tcp" },
                                filterMode = parts[2].ifEmpty { "none" },
                                hostlistFile = parts[3],
                                strategyName = parts[4].ifEmpty { "disabled" }
                            )
                        }
                    }
                }
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
     * Update strategy for a category in categories.txt by strategy NAME
     * Format: CATEGORY|PROTOCOL|FILTER_MODE|HOSTLIST_FILE|STRATEGY_NAME
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
            var found = false

            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#") && line.startsWith("$categoryKey|")) {
                    val parts = line.split("|").toMutableList()
                    if (parts.size >= 5) {
                        // Update strategy name (parts[4])
                        parts[4] = newStrategyName.ifEmpty { "disabled" }
                        lines[i] = parts.joinToString("|")
                        found = true
                        break
                    }
                }
            }

            if (!found) return@withContext false

            // Write back
            val newContent = lines.joinToString("\n")
            val escaped = newContent.replace("'", "'\\''")
            val writeResult = Shell.cmd("echo '$escaped' > $CATEGORIES_FILE").exec()
            writeResult.isSuccess
        }
    }

    /**
     * Update strategy for a category in categories.txt by strategy INDEX
     * Format: CATEGORY|PROTOCOL|FILTER_MODE|HOSTLIST_FILE|STRATEGY_NAME
     *
     * @param categoryKey The category key (e.g., "youtube")
     * @param newStrategyIndex The index in the strategy list (0 = disabled)
     * @deprecated Use updateCategoryStrategyByName instead for better clarity
     */
    suspend fun updateCategoryStrategy(categoryKey: String, newStrategyIndex: Int): Boolean {
        return withContext(Dispatchers.IO) {
            // Read current file to get the protocol
            val result = Shell.cmd("cat $CATEGORIES_FILE 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext false

            val lines = result.out.toMutableList()
            var found = false

            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#") && line.startsWith("$categoryKey|")) {
                    val parts = line.split("|").toMutableList()
                    if (parts.size >= 5) {
                        // Get protocol from the category line (parts[1])
                        val protocol = parts[1].ifEmpty { "tcp" }
                        val isTcp = protocol != "udp"

                        // Get the strategy name from index
                        val strategies = if (isTcp) {
                            tcpStrategies ?: getTcpStrategiesSync()
                        } else {
                            udpStrategies ?: getUdpStrategiesSync()
                        }

                        // Get strategy name (index 0 = "disabled")
                        val strategyName = strategies.getOrNull(newStrategyIndex)?.id ?: "disabled"

                        // Update strategy name (parts[4])
                        parts[4] = strategyName
                        lines[i] = parts.joinToString("|")
                        found = true
                        break
                    }
                }
            }

            if (!found) return@withContext false

            // Write back
            val newContent = lines.joinToString("\n")
            val escaped = newContent.replace("'", "'\\''")
            val writeResult = Shell.cmd("echo '$escaped' > $CATEGORIES_FILE").exec()
            writeResult.isSuccess
        }
    }

    /**
     * Synchronous version of getTcpStrategies for use within IO context
     */
    private fun getTcpStrategiesSync(): List<StrategyInfo> {
        val strategies = mutableListOf<StrategyInfo>()
        strategies.add(StrategyInfo("disabled", "Disabled", 0))

        val result = Shell.cmd("cat $STRATEGIES_FILE 2>/dev/null").exec()
        if (result.isSuccess) {
            val content = result.out.joinToString("\n")

            // Primary: Parse from list_tcp_strategies() function (most reliable)
            var ids = parseStrategiesFromListFunction(content, "list_tcp_strategies")

            // Fallback: Parse from case statement if list function not found
            if (ids.isEmpty()) {
                val tcpSection = extractSection(content, "TCP STRATEGIES", "UDP STRATEGIES")
                ids = parseStrategyIds(tcpSection)
            }

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

        val result = Shell.cmd("cat $STRATEGIES_FILE 2>/dev/null").exec()
        if (result.isSuccess) {
            val content = result.out.joinToString("\n")

            // Primary: Parse from list_udp_strategies() function (most reliable)
            var ids = parseStrategiesFromListFunction(content, "list_udp_strategies")

            // Fallback: Parse from case statement if list function not found
            if (ids.isEmpty()) {
                val udpSection = extractSection(content, "UDP STRATEGIES", null)
                ids = parseStrategyIds(udpSection)
            }

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
     * Check if a category is enabled (has a non-disabled strategy)
     */
    suspend fun isCategoryEnabled(categoryKey: String): Boolean {
        val categories = readCategories()
        val strategyName = categories[categoryKey]?.strategyName ?: "disabled"
        return strategyName != "disabled" && strategyName.isNotEmpty()
    }

    /**
     * Clear cached strategies (call when module is updated)
     */
    fun clearCache() {
        tcpStrategies = null
        udpStrategies = null
    }

    // Helper functions

    /**
     * Extract strategy names from list_*_strategies() function in strategies.sh
     * These functions contain all strategy names in a single echo line:
     * list_tcp_strategies() {
     *     echo "strategy1 strategy2 strategy3..."
     * }
     */
    private fun parseStrategiesFromListFunction(content: String, functionName: String): List<String> {
        // Match: list_tcp_strategies() { echo "strategy1 strategy2..." }
        // or: list_udp_strategies() { echo "..." }
        val pattern = Regex("""$functionName\s*\(\s*\)\s*\{\s*\n\s*echo\s+"([^"]+)"""")
        val match = pattern.find(content)

        return if (match != null) {
            val strategiesString = match.groupValues[1]
            strategiesString.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    private fun extractSection(content: String, startMarker: String, endMarker: String?): String {
        val startPattern = "# =+ $startMarker =+"
        val startIdx = content.indexOf(startMarker)
        if (startIdx == -1) return ""

        val endIdx = if (endMarker != null) {
            val idx = content.indexOf(endMarker, startIdx)
            if (idx == -1) content.length else idx
        } else {
            content.length
        }

        return content.substring(startIdx, endIdx)
    }

    private fun parseStrategyIds(section: String): List<String> {
        val ids = mutableListOf<String>()
        // Match lines like "        strategy_name)" - strategy IDs in case statements
        val pattern = Regex("""^\s{8}(\w+)\)\s*$""", RegexOption.MULTILINE)

        pattern.findAll(section).forEach { match ->
            val id = match.groupValues[1]
            // Skip the catch-all case and other non-strategy patterns
            if (id != "*" && id != "esac" && !id.startsWith("_")) {
                ids.add(id)
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
