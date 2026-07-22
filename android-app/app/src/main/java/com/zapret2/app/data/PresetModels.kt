package com.zapret2.app.data

data class PresetEntry(
    val fileName: String,
    val displayName: String = fileName.removeSuffix(".txt"),
)

enum class PresetIssue(val wireCode: String) {
    UNSAFE_PRESET_NAME("UNSAFE_PRESET_NAME"),
    PRESET_NOT_DIRECT_CHILD("PRESET_NOT_DIRECT_CHILD"),
    PRESET_MISSING("PRESET_MISSING"),
    PRESET_SYMLINK("PRESET_SYMLINK"),
    PRESET_EMPTY("PRESET_EMPTY"),
    PRESET_TOO_LARGE("PRESET_TOO_LARGE"),
    PRESET_UNREADABLE("PRESET_UNREADABLE"),
    UNSAFE_DEPENDENCY_PATH("UNSAFE_DEPENDENCY_PATH"),
    DEPENDENCY_NOT_DECLARED("DEPENDENCY_NOT_DECLARED"),
    DEPENDENCY_MISSING("DEPENDENCY_MISSING"),
    DEPENDENCY_SYMLINK("DEPENDENCY_SYMLINK"),
    DEPENDENCY_EMPTY("DEPENDENCY_EMPTY"),
    DEPENDENCY_UNREADABLE("DEPENDENCY_UNREADABLE"),
    FORBIDDEN_IPCACHE_OPTION("FORBIDDEN_IPCACHE_OPTION"),
    UNKNOWN_OPTION("UNKNOWN_OPTION"),
    WINDOWS_OPTION_FORBIDDEN("WINDOWS_OPTION_FORBIDDEN"),
    GLOBAL_OPTION_AFTER_PROFILE("GLOBAL_OPTION_AFTER_PROFILE"),
    EMPTY_PROFILE("EMPTY_PROFILE"),
    TRAILING_NEW("TRAILING_NEW"),
    PROFILE_NAME_MISSING("PROFILE_NAME_MISSING"),
    PROFILE_DUPLICATE_NAME("PROFILE_DUPLICATE_NAME"),
    PROFILE_DUPLICATE_SKIP("PROFILE_DUPLICATE_SKIP"),
    PROFILE_FILTER_MISSING("PROFILE_FILTER_MISSING"),
    PROFILE_STRATEGY_MISSING("PROFILE_STRATEGY_MISSING"),
    INVALID_FILTER("INVALID_FILTER"),
    INVALID_BLOB("INVALID_BLOB"),
    INVALID_OPTION_VALUE("INVALID_OPTION_VALUE"),
    UNSAFE_OPTION_VALUE("UNSAFE_OPTION_VALUE"),
    NO_ENABLED_PROFILE("NO_ENABLED_PROFILE"),
    NO_VALID_OPTIONS("NO_VALID_OPTIONS"),
    NFQWS_DRY_RUN_FAILED("NFQWS_DRY_RUN_FAILED"),
    MALFORMED_PROTOCOL("MALFORMED_PROTOCOL"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromWireCode(value: String): PresetIssue =
            entries.firstOrNull { it.wireCode == value.trim().uppercase() } ?: UNKNOWN
    }
}

data class PresetCommandPreview(
    val executable: String,
    val arguments: List<String>,
    val tcpPorts: String,
    val udpPorts: String,
) {
    val rendered: String
        get() = buildList {
            add(shellQuote(executable))
            addAll(arguments.map(::shellQuote))
        }.joinToString(" \\\n  ")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}

sealed interface PresetPreviewOutcome {
    data class Ready(val preview: PresetCommandPreview) : PresetPreviewOutcome
    data class Rejected(val issue: PresetIssue) : PresetPreviewOutcome
    data object Failed : PresetPreviewOutcome
    data object Blocked : PresetPreviewOutcome
}

internal object PresetContentPolicy {
    const val MAX_BYTES = 1024 * 1024

    fun isAllowed(content: String): Boolean =
        content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    fun normalizedForWrite(content: String): String =
        canonicalProtectedText(content) + "\n"

    fun isPersistable(content: String): Boolean = isAllowed(normalizedForWrite(content))
}

data class PresetDiscovery(
    val available: List<PresetEntry>,
    val quarantinedCount: Int,
    val issueCounts: Map<PresetIssue, Int>,
)

data class PresetSelection(
    val activePresetFile: String,
)

data class PresetCatalog(
    val discovery: PresetDiscovery,
    val selection: PresetSelection,
)

sealed interface PresetValidation {
    data object Compatible : PresetValidation
    data class Quarantined(val issue: PresetIssue) : PresetValidation
    data object ProtocolFailure : PresetValidation
}

enum class PresetDurableOutcome {
    APPLIED,
    SAVED,
    SAVED_AND_APPLIED,
    REJECTED,
    SOURCE_CHANGED,
    RESTART_FAILED_ROLLED_BACK,
    WRITE_FAILED_ROLLED_BACK,
    ROLLBACK_FAILED,
    IO_FAILED,
    BLOCKED,
}

sealed interface PresetMutationOutcome {
    val durable: PresetDurableOutcome

    data object Applied : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.APPLIED
    }

    data object Saved : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.SAVED
    }

    data object SavedAndApplied : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.SAVED_AND_APPLIED
    }

    data class Rejected(val issue: PresetIssue) : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.REJECTED
    }

    data object SourceChanged : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.SOURCE_CHANGED
    }

    data object RestartFailedRolledBack : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.RESTART_FAILED_ROLLED_BACK
    }

    data object WriteFailedRolledBack : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.WRITE_FAILED_ROLLED_BACK
    }

    data object RollbackFailed : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.ROLLBACK_FAILED
    }

    data object IoFailed : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.IO_FAILED
    }

    data object Blocked : PresetMutationOutcome {
        override val durable = PresetDurableOutcome.BLOCKED
    }
}

internal data class ActivePresetConfig(
    val presetFile: String,
)

internal sealed interface PresetFileSnapshot {
    data object Missing : PresetFileSnapshot
    data object Unsafe : PresetFileSnapshot
    data class Present(val content: String) : PresetFileSnapshot
}
