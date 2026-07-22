package com.zapret2.app.data

import android.os.Build
import javax.inject.Inject

internal data class ModuleEnvironmentSnapshot(
    val moduleState: ModuleInstallState,
    val nfqueueSupported: Boolean,
    val moduleVersion: String,
)

enum class ModuleInstallState {
    UNKNOWN,
    MISSING,
    READY,
    DISABLED,
    REMOVAL_PENDING,
    UPDATING,
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

internal data class RuntimeProcessMetrics(
    val memoryKb: String = "",
    val threads: String = "",
    val uptime: String = "",
)

/** Typed diagnostic probes used by Control; raw privileged commands stay out of ViewModels. */
class ControlDiagnosticsRepository @Inject constructor() {

    internal suspend fun readEnvironment(): ModuleEnvironmentSnapshot? {
        val moduleDir = RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)
        val disableMarker = RootFileIo.shellQuote("${ServiceLifecycleController.MODULE_DIR}/disable")
        val removeMarker = RootFileIo.shellQuote("${ServiceLifecycleController.MODULE_DIR}/remove")
        val packageContract = RootFileIo.shellQuote(
            "${ServiceLifecycleController.MODULE_DIR}/${ModulePackageContract.PACKAGE_CONTRACT_SCRIPT_PATH}",
        )
        val commandBuilder = RootFileIo.shellQuote(
            "${ServiceLifecycleController.MODULE_DIR}/${ModulePackageContract.COMMAND_BUILDER_SCRIPT_PATH}",
        )
        val zapretDirectory = RootFileIo.shellQuote("${ServiceLifecycleController.MODULE_DIR}/zapret2")
        val lifecycleScripts = ModulePackageContract.mandatoryRuntimeExecutables
        val binaryDirectory = ModulePackageContract.selectBinaryDirectory(Build.SUPPORTED_ABIS.toList())
        val executablePaths = buildList {
            addAll(ModulePackageContract.hotUpdateRootExecutables)
            addAll(lifecycleScripts)
            addAll(ModulePackageContract.wrappers.map { it.relativePath })
            add("zapret2/nfqws2")
            binaryDirectory?.let { add(ModulePackageContract.binaryRelativePath(it)) }
        }.distinct()
        val regularPaths = ModulePackageContract.mandatoryRuntimeRegularFiles
        val requiredRegularFiles = regularPaths.distinct().joinToString(" ") {
            RootFileIo.shellQuote("${ServiceLifecycleController.MODULE_DIR}/$it")
        }
        val requiredExecutables = executablePaths.joinToString(" ") {
            RootFileIo.shellQuote("${ServiceLifecycleController.MODULE_DIR}/$it")
        }
        val result = ServiceLifecycleController.executeRoot(
            """
                if [ ! -e $moduleDir ] && [ ! -L $moduleDir ]; then
                    z2_module_state=missing
                else
                    if ! { [ -d $moduleDir ] && [ ! -L $moduleDir ] &&
                        [ "${'$'}(stat -c %u $moduleDir 2>/dev/null)" = 0 ]; }; then
                        z2_module_state=unreadable
                    elif ! { z2_module_mode=${'$'}(stat -c %a $moduleDir 2>/dev/null) &&
                        case "${'$'}z2_module_mode" in 700|711|750|751|755) true ;; *) false ;; esac; }; then
                        z2_module_state=unreadable
                    elif [ -e ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)} ] ||
                        [ -L ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)} ] ||
                        [ -e ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)} ] ||
                        [ -L ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)} ] ||
                        [ -e ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_CLEANUP)} ] ||
                        [ -L ${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_CLEANUP)} ]; then
                        z2_module_state=updating
                    else
                        z2_module_state=ready
                        for z2_required in $requiredRegularFiles
                        do
                            if ! { [ -f "${'$'}z2_required" ] && [ ! -L "${'$'}z2_required" ] && [ -s "${'$'}z2_required" ] &&
                                [ "${'$'}(stat -c %u "${'$'}z2_required" 2>/dev/null)" = 0 ] &&
                                [ "${'$'}(stat -c %a "${'$'}z2_required" 2>/dev/null)" = 644 ] &&
                                [ "${'$'}(stat -c %h "${'$'}z2_required" 2>/dev/null)" = 1 ]; }; then
                                z2_module_state=partial
                                break
                            fi
                        done
                        if [ "${'$'}z2_module_state" = ready ]; then
                            for z2_script in $requiredExecutables
                            do
                                if ! { [ -f "${'$'}z2_script" ] && [ ! -L "${'$'}z2_script" ] && [ -s "${'$'}z2_script" ] &&
                                    [ "${'$'}(stat -c %u "${'$'}z2_script" 2>/dev/null)" = 0 ] &&
                                    [ "${'$'}(stat -c %a "${'$'}z2_script" 2>/dev/null)" = 755 ] &&
                                    [ "${'$'}(stat -c %h "${'$'}z2_script" 2>/dev/null)" = 1 ]; }; then
                                    z2_module_state=partial
                                    break
                                fi
                            done
                        fi
                        if [ "${'$'}z2_module_state" = ready ]; then
                            if ! . $packageContract ||
                                ! package_contract_validate_runtime_selection $moduleDir; then
                                z2_module_state=partial
                            fi
                        fi
                        if [ "${'$'}z2_module_state" = ready ]; then
                            z2_strategy_validation=${'$'}(/system/bin/sh $commandBuilder \
                                --validate-strategies-machine $zapretDirectory) || z2_module_state=partial
                            if [ "${'$'}z2_module_state" = ready ] &&
                                [ "${'$'}z2_strategy_validation" != "${'$'}(printf 'Z2_STRATEGIES\tOK')" ]; then
                                z2_module_state=partial
                            fi
                        fi
                        if [ "${'$'}z2_module_state" = ready ]; then
                            z2_active_preset=${'$'}(package_contract_runtime_core_value $moduleDir active_preset) ||
                                z2_module_state=partial
                            if [ "${'$'}z2_module_state" = ready ]; then
                                /system/bin/sh $commandBuilder --preflight-preset-machine $zapretDirectory \
                                    "$zapretDirectory/presets/${'$'}z2_active_preset" "${'$'}z2_active_preset" \
                                    >/dev/null 2>&1 || z2_module_state=partial
                            fi
                        fi
                        if [ -e $removeMarker ] || [ -L $removeMarker ]; then
                            z2_module_state=removal_pending
                        elif [ "${'$'}z2_module_state" = ready ] &&
                            { [ -e $disableMarker ] || [ -L $disableMarker ]; }; then
                            if [ -f $disableMarker ] && [ ! -L $disableMarker ] &&
                                [ "${'$'}(stat -c %u $disableMarker 2>/dev/null)" = 0 ] &&
                                [ "${'$'}(stat -c %a $disableMarker 2>/dev/null)" = 600 ] &&
                                [ "${'$'}(stat -c %s $disableMarker 2>/dev/null)" = 0 ] &&
                                [ "${'$'}(stat -c %h $disableMarker 2>/dev/null)" = 1 ]; then
                                z2_module_state=disabled
                            else
                                z2_module_state=partial
                            fi
                        fi
                    fi
                fi
                printf 'Z2_MODULE_STATE=%s\n' "${'$'}z2_module_state"
                if [ -f /proc/net/netfilter/nf_queue ] || grep -qs NFQUEUE /proc/net/ip_tables_targets /proc/net/ip6_tables_targets; then
                    echo Z2_NFQUEUE=1
                else
                    echo Z2_NFQUEUE=0
                fi
            """.trimIndent(),
        )
        if (!result.success) return null
        val environment = parseEnvironmentOutput(result.stdout) ?: return null
        if (!environment.moduleState.isPresent) return environment
        val moduleProp = RootFileIo.readSecureRegularText(
            "${ServiceLifecycleController.MODULE_DIR}/module.prop",
            ModulePackageContract.MAX_MODULE_PROP_BYTES,
        )
        val version = moduleProp?.let(::parseModulePropVersion)
        val verifiedEnvironment = if (version != null) {
            environment.copy(moduleVersion = version)
        } else if (environment.moduleState in setOf(
                ModuleInstallState.UPDATING,
                ModuleInstallState.REMOVAL_PENDING,
            )
        ) {
            environment
        } else {
            environment.copy(moduleState = ModuleInstallState.PARTIAL)
        }
        return if (
            binaryDirectory == null &&
            verifiedEnvironment.moduleState in setOf(ModuleInstallState.READY, ModuleInstallState.DISABLED)
        ) {
            verifiedEnvironment.copy(moduleState = ModuleInstallState.UNSUPPORTED_ABI)
        } else {
            verifiedEnvironment
        }
    }

    internal fun parseEnvironmentOutput(lines: List<String>): ModuleEnvironmentSnapshot? {
        val values = parseExactKeyValues(
            lines,
            setOf("Z2_MODULE_STATE", "Z2_NFQUEUE"),
        ) ?: return null
        val moduleState = when (values["Z2_MODULE_STATE"]) {
            "missing" -> ModuleInstallState.MISSING
            "ready" -> ModuleInstallState.READY
            "disabled" -> ModuleInstallState.DISABLED
            "removal_pending" -> ModuleInstallState.REMOVAL_PENDING
            "updating" -> ModuleInstallState.UPDATING
            "partial" -> ModuleInstallState.PARTIAL
            "unsupported_abi" -> ModuleInstallState.UNSUPPORTED_ABI
            "unreadable" -> ModuleInstallState.UNREADABLE
            else -> return null
        }
        return ModuleEnvironmentSnapshot(
            moduleState = moduleState,
            nfqueueSupported = values["Z2_NFQUEUE"] == "1",
            moduleVersion = "",
        ).takeIf {
            values["Z2_NFQUEUE"] in setOf("0", "1")
        }
    }

    internal fun parseModulePropVersion(content: String): String? {
        return ModulePackageContract.validatedInstalledVersion(content)
    }

    internal suspend fun readProcessMetrics(pid: String): RuntimeProcessMetrics {
        if (!pid.matches(Regex("[1-9][0-9]*")) || pid.toIntOrNull() == null) return RuntimeProcessMetrics()
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
        val values = parseExactKeyValues(result.stdout, setOf("Z2_MEM", "Z2_THREADS", "Z2_UPTIME"))
            ?: return RuntimeProcessMetrics()
        val memory = values.getValue("Z2_MEM").takeIf(::canonicalDecimal).orEmpty()
        val threads = values.getValue("Z2_THREADS").takeIf(::canonicalDecimal).orEmpty()
        val uptime = values.getValue("Z2_UPTIME").trim().takeIf {
            it.length <= 64 && it.none { character -> character.isISOControl() }
        }.orEmpty()
        return RuntimeProcessMetrics(memory, threads, uptime)
    }

    private fun parseExactKeyValues(lines: List<String>, expected: Set<String>): Map<String, String>? {
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

}
