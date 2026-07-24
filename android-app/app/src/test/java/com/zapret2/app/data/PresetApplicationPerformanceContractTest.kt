package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetApplicationPerformanceContractTest {

    @Test
    fun presetCatalog_exitsBeforeLoadingLifecycleOrCompilerRuntime() {
        val builder = repositoryFile("zapret2/scripts/command-builder.sh").readText()
        val fastEntry = builder.indexOf("if [ \"\${1:-}\" = --list-presets-machine ]; then")
        val commonSource = builder.indexOf(". \"\$SCRIPT_DIR/common.sh\"")

        assertTrue(fastEntry >= 0)
        assertTrue(commonSource > fastEntry)
        assertTrue(builder.substring(fastEntry, commonSource).contains("Z2_PRESET_SUMMARY"))
    }

    @Test
    fun restart_consumesLifecycleCommitWithoutLeadingOrTrailingStatusProcess() {
        val controller = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ServiceLifecycleController.kt",
        ).readText()
        val perform = controller
            .substringAfter("private suspend fun perform(action: Action)")
            .substringBefore("private fun Action.displayName()")

        assertTrue(perform.contains("if (action == Action.RESTART) null else getStatusLocked()"))
        assertTrue(perform.contains("ZAPRET2_EMIT_STATUS_V6=1"))
        assertTrue(perform.contains("parseLifecycleReceipt(commandResult)"))
    }

    @Test
    fun lifecycleFirewall_usesOneAtomicTransitionAndOneCommitSnapshot() {
        val start = repositoryFile("zapret2/scripts/zapret-start.sh").readText()
        val reconciler = repositoryFile("zapret2/scripts/firewall-reconciler.sh").readText()
        val reconcile = reconciler
            .substringAfter("z2_fw_reconcile_family() {")
            .substringBeforeLast("}")
        val verify = reconciler
            .substringAfter("z2_fw_verify_family() {")
            .substringBefore("z2_fw_reconcile_family() {")

        assertTrue(start.contains("z2_fw_reconcile_family iptables audited"))
        assertTrue(start.contains("z2_fw_reconcile_family ip6tables audited"))
        assertTrue(reconcile.contains("z2_fw_verify_family"))
        assertFalse(start.contains("z2_fw_reconcile_family iptables precleaned"))
        assertFalse(reconciler.contains("z2_fw_delete_anchors"))
        assertFalse(reconciler.contains("z2_fw_drop_chain"))
        assertTrue(reconciler.contains("z2_fw_emit_baseline_cleanup"))
        assertTrue(verify.contains("\"${'$'}tool\" -t mangle -S"))
        assertFalse(verify.contains(" -C "))
        assertFalse(verify.contains("z2_fw_chain_rule_count"))
    }

    @Test
    fun restart_reusesOnlyAnAuthenticatedUnchangedFirewallGeneration() {
        val start = repositoryFile("zapret2/scripts/zapret-start.sh").readText()
        val selector = start
            .substringAfter("prepare_fast_replace_candidate() {")
            .substringBefore("fast_replace_health_ok() {")
        val fastPath = start
            .substringAfter("if prepare_fast_replace_candidate; then")
            .substringBefore("log_section \"Firewall transaction\"")

        assertTrue(selector.contains("OWNER_WRITE_FIREWALL_FINGERPRINT"))
        assertTrue(selector.contains("FAST_REPLACE_FIREWALL_FINGERPRINT"))
        assertTrue(fastPath.contains("stop_pidfile_process"))
        assertTrue(fastPath.contains("launch_nfqws2"))
        assertTrue(fastPath.contains("fast_replace_health_ok"))
        assertFalse(fastPath.contains("cleanup_owned_firewall"))
        assertFalse(fastPath.contains("z2_fw_reconcile_family"))
    }

    @Test
    fun transactionCandidates_deferDurabilityToTheirSingleCommitBoundary() {
        val presets = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/PresetRepository.kt",
        ).readText()
        val presetCandidate = presets
            .substringAfter("override suspend fun writeCandidate(")
            .substringBefore("override suspend fun replaceCandidate(")
        val runtime = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RuntimeConfigStore.kt",
        ).readText()
        val runtimeCandidate = runtime
            .substringAfter("private fun commitCandidate(")
            .substringBefore("private fun mutationWriteFailed(")

        assertTrue(presetCandidate.contains("durable = false"))
        assertTrue(runtimeCandidate.contains("durable = false"))
    }

    @Test
    fun packagedNfqwsContract_hasNoRuntimeHelpProbeOrFallbackLauncher() {
        val start = repositoryFile("zapret2/scripts/zapret-start.sh").readText()
        val prepare = start
            .substringAfter("prepare_options() {")
            .substringBefore("compiled_source_binding_current() {")
        val launch = start
            .substringAfter("launch_nfqws2() {")
            .substringBefore("stop_failed_fallback_launch() {")

        assertFalse(prepare.contains("--help"))
        assertTrue(prepare.contains("'--daemon'"))
        assertTrue(launch.contains("run_compiled_artifact \"\$COMPILED_ARGV_FILE\" daemon"))
        assertFalse(launch.contains("run_compiled_artifact \"\$COMPILED_ARGV_FILE\" background"))
    }

    @Test
    fun verifiedStop_keepsOriginalTimeoutWithFineGrainedExitPolling() {
        val common = repositoryFile("zapret2/scripts/common.sh").readText()
        val stop = common
            .substringAfter("stop_verified_nfqws_pid() {")
            .substringBefore("PROCESS_CLEANUP_PREFLIGHT_PROVEN=")

        assertTrue(stop.contains("[ \"\$n\" -lt 50 ]"))
        assertTrue(stop.contains("[ \"\$n\" -lt 30 ]"))
        assertTrue(stop.contains("sleep 0.1"))
        assertFalse(stop.contains("sleep 1"))
    }

    private fun repositoryFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository file: $relativePath")
    }
}
