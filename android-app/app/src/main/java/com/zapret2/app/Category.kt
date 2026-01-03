package com.zapret2.app

/**
 * Data class representing a category from categories.txt
 * Format: CATEGORY|ENABLED|FILTER_MODE|HOSTLIST_FILE|STRATEGY
 */
data class Category(
    val name: String,
    var enabled: Boolean,
    var filterMode: FilterMode,
    var hostlistFile: String,
    var strategy: Int,
    val section: String = ""
) {
    enum class FilterMode(val value: String) {
        NONE("none"),
        HOSTLIST("hostlist"),
        IPSET("ipset");

        companion object {
            fun fromString(value: String): FilterMode {
                return entries.find { it.value.equals(value, ignoreCase = true) } ?: NONE
            }
        }
    }

    /**
     * Convert category back to line format for saving
     */
    fun toLine(): String {
        return "$name|${if (enabled) 1 else 0}|${filterMode.value}|$hostlistFile|$strategy"
    }

    /**
     * Get display name (formatted from category name)
     */
    fun getDisplayName(): String {
        return name
            .replace("_tcp", " TCP")
            .replace("_udp", " UDP")
            .replace("_http", " HTTP")
            .split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    companion object {
        /**
         * Parse a single line from categories.txt
         * Returns null for comments and empty lines
         */
        fun fromLine(line: String, section: String = ""): Category? {
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return null
            }

            val parts = trimmed.split("|")
            if (parts.size < 5) {
                return null
            }

            return try {
                Category(
                    name = parts[0],
                    enabled = parts[1] == "1",
                    filterMode = FilterMode.fromString(parts[2]),
                    hostlistFile = parts[3],
                    strategy = parts[4].toIntOrNull() ?: 1,
                    section = section
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Parse section name from comment line
         * Example: "# ===============================\n# YouTube & Google Video"
         */
        fun parseSectionName(line: String): String? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#")) return null

            val content = trimmed.removePrefix("#").trim()

            // Skip separator lines
            if (content.isEmpty() || content.all { it == '=' }) {
                return null
            }

            // Skip format comments
            if (content.startsWith("Format:") ||
                content.startsWith("FILTER_MODE:") ||
                content.startsWith("STRATEGY:") ||
                content.startsWith("By default") ||
                content.startsWith("Each category") ||
                content.startsWith("Zapret2")) {
                return null
            }

            return content
        }
    }
}
