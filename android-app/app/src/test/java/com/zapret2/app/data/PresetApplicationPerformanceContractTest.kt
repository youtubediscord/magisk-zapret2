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
