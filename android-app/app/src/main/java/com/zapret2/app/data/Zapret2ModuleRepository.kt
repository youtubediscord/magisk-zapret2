package com.zapret2.app.data

import android.os.Build
import javax.inject.Inject

enum class ModuleInstallState {
    UNKNOWN,
    MISSING,
    READY,
    DISABLED,
    REMOVAL_PENDING,
    PARTIAL,
    UNSUPPORTED_ABI,
    UNREADABLE;

    val isPresent: Boolean
        get() = this !in setOf(UNKNOWN, MISSING, UNREADABLE)

    val isOperational: Boolean
        get() = this == READY

    val allowsFullRollback: Boolean
        get() = this in setOf(READY, DISABLED)

    val allowsModuleUpdate: Boolean
        get() = this in setOf(MISSING, READY, DISABLED, PARTIAL)
}

enum class PendingModuleState {
    NONE,
    READY,
    PARTIAL,
    UNSUPPORTED_ABI,
    UNREADABLE,
}

enum class ModuleMutationState {
    IDLE,
    IN_PROGRESS,
    BLOCKED,
}

enum class ModuleServiceAccess {
    QUERY_ACTIVE,
    NOT_INSTALLED,
    REBOOT_REQUIRED,
    NOT_READY,
    UNKNOWN,
}

internal data class ModuleEnvironmentSnapshot(
    val activeState: ModuleInstallState,
    val pendingState: PendingModuleState,
    val nfqueueSupported: Boolean,
    val activeVersion: String = "",
    val pendingVersion: String = "",
) {
    val displayedVersion: String
        get() = activeVersion.ifEmpty { pendingVersion }

    val hasPendingReboot: Boolean
        get() = pendingState == PendingModuleState.READY ||
            activeState == ModuleInstallState.REMOVAL_PENDING

    /**
     * The installation authority decides whether the active status script is trusted and present.
     * Presentation code may map this result, but must not independently infer script availability.
     */
    val serviceAccess: ModuleServiceAccess
        get() = when {
            activeState in setOf(ModuleInstallState.READY, ModuleInstallState.DISABLED) ->
                ModuleServiceAccess.QUERY_ACTIVE
            pendingState == PendingModuleState.READY ->
                ModuleServiceAccess.REBOOT_REQUIRED
            activeState == ModuleInstallState.MISSING &&
                pendingState == PendingModuleState.NONE -> ModuleServiceAccess.NOT_INSTALLED
            activeState == ModuleInstallState.REMOVAL_PENDING ->
                ModuleServiceAccess.REBOOT_REQUIRED
            pendingState in setOf(
                PendingModuleState.PARTIAL,
                PendingModuleState.UNSUPPORTED_ABI,
                PendingModuleState.UNREADABLE,
            ) -> ModuleServiceAccess.NOT_READY
            activeState in setOf(
                ModuleInstallState.PARTIAL,
                ModuleInstallState.UNSUPPORTED_ABI,
                ModuleInstallState.UNREADABLE,
            ) -> ModuleServiceAccess.NOT_READY
            else -> ModuleServiceAccess.UNKNOWN
        }
}

internal data class RuntimeProcessMetrics(
    val memoryKb: String = "",
    val threads: String = "",
    val uptime: String = "",
)

/**
 * Single privileged source of truth for the root module installation.
 *
 * Package integrity and root-manager staging are detected here. Lifecycle ownership deliberately does
 * not participate in installation detection: it belongs to ServiceLifecycleController's typed,
 * serialized status boundary. Runtime configuration and strategy preflight likewise belong to
 * the service start/configuration boundary.
 */
class Zapret2ModuleRepository @Inject constructor() {

    internal suspend fun reconcileEnvironment(): ModuleEnvironmentSnapshot? {
        val binaryDirectory = ModulePackageContract.selectBinaryDirectory(
            Build.SUPPORTED_ABIS.toList(),
        )
        val result = ServiceLifecycleController.executeRoot(
            buildProbeCommand(),
        )
        if (!result.success) return null

        var environment = parseEnvironmentOutput(result.stdout) ?: return null
        if (
            binaryDirectory == null &&
            environment.activeState in setOf(ModuleInstallState.READY, ModuleInstallState.DISABLED)
        ) {
            environment = environment.copy(activeState = ModuleInstallState.UNSUPPORTED_ABI)
        }
        if (binaryDirectory == null && environment.pendingState == PendingModuleState.READY) {
            environment = environment.copy(pendingState = PendingModuleState.UNSUPPORTED_ABI)
        }

        val activeVersion = if (
            environment.activeState in setOf(ModuleInstallState.READY, ModuleInstallState.DISABLED)
        ) {
            readVerifiedVersion(ACTIVE_MODULE_DIR)
        } else {
            null
        }
        if (
            activeVersion == null &&
            environment.activeState in setOf(ModuleInstallState.READY, ModuleInstallState.DISABLED)
        ) {
            environment = environment.copy(activeState = ModuleInstallState.PARTIAL)
        }

        val pendingVersion = if (environment.pendingState == PendingModuleState.READY) {
            readVerifiedVersion(PENDING_MODULE_DIR)
        } else {
            null
        }
        if (pendingVersion == null && environment.pendingState == PendingModuleState.READY) {
            environment = environment.copy(pendingState = PendingModuleState.PARTIAL)
        }

        return environment.copy(
            activeVersion = activeVersion.orEmpty(),
            pendingVersion = pendingVersion.orEmpty(),
        )
    }

    internal fun buildProbeCommand(): String {
        val activeDir = RootFileIo.shellQuote(ACTIVE_MODULE_DIR)
        val pendingDir = RootFileIo.shellQuote(PENDING_MODULE_DIR)
        val disableMarker = ModulePackageContract.DISABLE_MARKER
        return """
            z2_probe_slot() {
                z2_slot=${'$'}1
                z2_kind=${'$'}2
                if [ ! -e "${'$'}z2_slot" ] && [ ! -L "${'$'}z2_slot" ]; then
                    printf '%s\n' missing
                    return
                fi
                if ! { [ -d "${'$'}z2_slot" ] && [ ! -L "${'$'}z2_slot" ] &&
                    [ "${'$'}(stat -c %u "${'$'}z2_slot" 2>/dev/null)" = 0 ]; }; then
                    printf '%s\n' unreadable
                    return
                fi
                z2_slot_mode=${'$'}(stat -c %a "${'$'}z2_slot" 2>/dev/null)
                case "${'$'}z2_slot_mode" in
                    700|711|750|751|755) ;;
                    *) printf '%s\n' unreadable; return ;;
                esac

                # Package bytes were exhaustively qualified before this generation was published.
                # Runtime observation trusts its bounded publication receipt instead of rescanning
                # scripts, binaries, catalogs and Lua assets on every status request.
                z2_state=ready
                ${InstallGenerationMetadata.shellValidator(
                    "\"${'$'}z2_slot/${InstallGenerationMetadata.RELATIVE_PATH}\"",
                    required = true,
                )} || z2_state=partial
                if [ "${'$'}z2_kind" = active ]; then
                    z2_remove="${'$'}z2_slot/remove"
                    z2_disable="${'$'}z2_slot/$disableMarker"
                    if [ -e "${'$'}z2_remove" ] || [ -L "${'$'}z2_remove" ]; then
                        z2_state=removal_pending
                    elif [ -e "${'$'}z2_disable" ] || [ -L "${'$'}z2_disable" ]; then
                        if [ "${'$'}z2_state" = ready ] &&
                            [ -f "${'$'}z2_disable" ] && [ ! -L "${'$'}z2_disable" ] &&
                            [ "${'$'}(stat -c %u "${'$'}z2_disable" 2>/dev/null)" = 0 ] &&
                            [ "${'$'}(stat -c %a "${'$'}z2_disable" 2>/dev/null)" = 600 ] &&
                            [ "${'$'}(stat -c %s "${'$'}z2_disable" 2>/dev/null)" = 0 ] &&
                            [ "${'$'}(stat -c %h "${'$'}z2_disable" 2>/dev/null)" = 1 ]; then
                            z2_state=disabled
                        else
                            z2_state=partial
                        fi
                    fi
                fi
                printf '%s\n' "${'$'}z2_state"
            }

            z2_active_state=${'$'}(z2_probe_slot $activeDir active) || exit 1
            z2_pending_state=${'$'}(z2_probe_slot $pendingDir pending) || exit 1
            printf 'Z2_ACTIVE_STATE=%s\n' "${'$'}z2_active_state"
            printf 'Z2_PENDING_STATE=%s\n' "${'$'}z2_pending_state"
            if [ -f /proc/net/netfilter/nf_queue ] ||
                grep -qs NFQUEUE /proc/net/ip_tables_targets /proc/net/ip6_tables_targets; then
                echo Z2_NFQUEUE=1
            else
                echo Z2_NFQUEUE=0
            fi
        """.trimIndent()
    }

    internal fun parseEnvironmentOutput(lines: List<String>): ModuleEnvironmentSnapshot? {
        val values = parseExactKeyValues(
            lines,
            setOf("Z2_ACTIVE_STATE", "Z2_PENDING_STATE", "Z2_NFQUEUE"),
        ) ?: return null
        val activeState = when (values["Z2_ACTIVE_STATE"]) {
            "missing" -> ModuleInstallState.MISSING
            "ready" -> ModuleInstallState.READY
            "disabled" -> ModuleInstallState.DISABLED
            "removal_pending" -> ModuleInstallState.REMOVAL_PENDING
            "partial" -> ModuleInstallState.PARTIAL
            "unreadable" -> ModuleInstallState.UNREADABLE
            else -> return null
        }
        val pendingState = when (values["Z2_PENDING_STATE"]) {
            "missing" -> PendingModuleState.NONE
            "ready" -> PendingModuleState.READY
            "partial" -> PendingModuleState.PARTIAL
            "unreadable" -> PendingModuleState.UNREADABLE
            else -> return null
        }
        return ModuleEnvironmentSnapshot(
            activeState = activeState,
            pendingState = pendingState,
            nfqueueSupported = values["Z2_NFQUEUE"] == "1",
        ).takeIf {
            values["Z2_NFQUEUE"] in setOf("0", "1")
        }
    }

    internal fun parseModulePropVersion(content: String): String? =
        ModulePackageContract.validatedInstalledVersion(content)

    internal suspend fun readProcessMetrics(pid: String): RuntimeProcessMetrics {
        if (!pid.matches(Regex("[1-9][0-9]*")) || pid.toIntOrNull() == null) {
            return RuntimeProcessMetrics()
        }
        val result = ServiceLifecycleController.executeRoot(
            """
                [ -d /proc/$pid ] || exit 1
                z2_mem=${'$'}(awk '/^VmRSS:/ { print ${'$'}2; exit }' /proc/$pid/status 2>/dev/null)
                z2_threads=${'$'}(awk '/^Threads:/ { print ${'$'}2; exit }' /proc/$pid/status 2>/dev/null)
                z2_uptime=${'$'}(ps -o etime= -p $pid 2>/dev/null | head -n 1)
                printf 'Z2_MEM=%s\nZ2_THREADS=%s\nZ2_UPTIME=%s\n' "${'$'}z2_mem" "${'$'}z2_threads" "${'$'}z2_uptime"
            """.trimIndent(),
        )
        if (!result.success) return RuntimeProcessMetrics()
        val values = parseExactKeyValues(
            result.stdout,
            setOf("Z2_MEM", "Z2_THREADS", "Z2_UPTIME"),
        ) ?: return RuntimeProcessMetrics()
        val memory = values.getValue("Z2_MEM").takeIf(::canonicalDecimal).orEmpty()
        val threads = values.getValue("Z2_THREADS").takeIf(::canonicalDecimal).orEmpty()
        val uptime = values.getValue("Z2_UPTIME").trim().takeIf {
            it.length <= 64 && it.none { character -> character.isISOControl() }
        }.orEmpty()
        return RuntimeProcessMetrics(memory, threads, uptime)
    }

    private suspend fun readVerifiedVersion(directory: String): String? {
        val moduleProp = RootFileIo.readSecureRegularText(
            "$directory/module.prop",
            ModulePackageContract.MAX_MODULE_PROP_BYTES,
        ) ?: return null
        return parseModulePropVersion(moduleProp)
    }

    private fun parseExactKeyValues(
        lines: List<String>,
        expected: Set<String>,
    ): Map<String, String>? {
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        if (pairs.size != expected.size || pairs.map { it.first }.toSet() != expected) return null
        return pairs.toMap().takeIf { it.size == pairs.size }
    }

    private fun canonicalDecimal(value: String): Boolean =
        value.matches(Regex("0|[1-9][0-9]*")) && value.toLongOrNull() != null

    companion object {
        const val ACTIVE_MODULE_DIR = RootModuleContract.ACTIVE_MODULE_DIR
        const val PENDING_MODULE_DIR = RootModuleContract.PENDING_MODULE_DIR
    }
}
