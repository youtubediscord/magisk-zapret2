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
        val enabled: Int,         // 0 or 1
        val filterMode: String,   // "none", "hostlist", "ipset"
        val hostlistFile: String, // e.g., "youtube.txt"
        val strategyIndex: Int    // 1-based strategy index (1 = first strategy)
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

                // Parse TCP strategies (between "# ==================== TCP STRATEGIES ====================" and "# ==================== UDP STRATEGIES ====================")
                val tcpSection = extractSection(content, "TCP STRATEGIES", "UDP STRATEGIES")
                val ids = parseStrategyIds(tcpSection)

                ids.forEachIndexed { index, id ->
                    strategies.add(StrategyInfo(
                        id = id,
                        displayName = formatDisplayName(id),
                        index = index + 1 // 1-based, 0 is "disabled"
                    ))
                }
            }

            // Fallback to hardcoded list if file reading fails
            if (strategies.size == 1) {
                strategies.addAll(getHardcodedTcpStrategies())
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

                // Parse UDP strategies (after "# ==================== UDP STRATEGIES ====================")
                val udpSection = extractSection(content, "UDP STRATEGIES", null)
                val ids = parseStrategyIds(udpSection)

                ids.forEachIndexed { index, id ->
                    strategies.add(StrategyInfo(
                        id = id,
                        displayName = formatDisplayName(id),
                        index = index + 1
                    ))
                }
            }

            // Fallback to hardcoded list if file reading fails
            if (strategies.size == 1) {
                strategies.addAll(getHardcodedUdpStrategies())
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
                                enabled = parts[1].toIntOrNull() ?: 0,
                                filterMode = parts[2],
                                hostlistFile = parts[3],
                                strategyIndex = parts[4].toIntOrNull() ?: 1
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
     */
    suspend fun getCategoryStrategyIndex(categoryKey: String): Int {
        val categories = readCategories()
        return categories[categoryKey]?.strategyIndex ?: 0
    }

    /**
     * Update strategy for a category in categories.txt
     */
    suspend fun updateCategoryStrategy(categoryKey: String, newStrategyIndex: Int): Boolean {
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
                        // Update strategy index (parts[4]) and enable if strategy > 0
                        parts[4] = newStrategyIndex.toString()
                        if (newStrategyIndex > 0) {
                            parts[1] = "1" // Enable category
                        } else {
                            parts[1] = "0" // Disable category
                        }
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
     * Check if a category is enabled
     */
    suspend fun isCategoryEnabled(categoryKey: String): Boolean {
        val categories = readCategories()
        return categories[categoryKey]?.enabled == 1
    }

    /**
     * Clear cached strategies (call when module is updated)
     */
    fun clearCache() {
        tcpStrategies = null
        udpStrategies = null
    }

    // Helper functions

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

    // Hardcoded fallbacks (in case file reading fails)

    private fun getHardcodedTcpStrategies(): List<StrategyInfo> {
        return listOf(
            StrategyInfo("syndata_2_tls_7", "Syndata 2 tls 7", 1),
            StrategyInfo("syndata_3_tls_google", "Syndata 3 tls google", 2),
            StrategyInfo("syndata_7_n3", "Syndata 7 n3", 3),
            StrategyInfo("syndata_multisplit_tls_google_700", "Syndata multisplit tls google 700", 4),
            StrategyInfo("syndata_multidisorder_tls_google_700", "Syndata multidisorder tls google 700", 5),
            StrategyInfo("syndata_7_tls_google_multisplit_midsld", "Syndata 7 tls google multisplit midsld", 6),
            StrategyInfo("censorliber_google_syndata", "Censorliber google syndata", 7),
            StrategyInfo("censorliber_google_syndata_v2", "Censorliber google syndata v2", 8),
            StrategyInfo("multidisorder_legacy_midsld", "Multidisorder legacy midsld", 9),
            StrategyInfo("multidisorder_midsld", "Multidisorder midsld", 10),
            StrategyInfo("tls_aggressive", "Tls aggressive", 11),
            StrategyInfo("syndata", "Syndata", 12)
        )
    }

    private fun getHardcodedUdpStrategies(): List<StrategyInfo> {
        return listOf(
            StrategyInfo("fake_6_google_quic", "Fake 6 google quic", 1),
            StrategyInfo("fake_4_google_quic", "Fake 4 google quic", 2),
            StrategyInfo("fake_11_quic_bin", "Fake 11 quic bin", 3),
            StrategyInfo("fake_udplen_25_10", "Fake udplen 25 10", 4),
            StrategyInfo("fake_ipfrag2_quic5", "Fake ipfrag2 quic5", 5),
            StrategyInfo("ipset_fake_12_n3", "Ipset fake 12 n3", 6)
        )
    }
}
