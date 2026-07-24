package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCostBoundaryTest {

    @Test
    fun updateRuntime_doesNotRepeatTheCompletePackageAudit() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt",
        ).readText()

        assertFalse(source.contains("package_contract_validate_all"))
        assertFalse(source.contains("package_contract_compare_release"))
        assertTrue(source.contains("RootModuleContract.installCommand"))
    }

    @Test
    fun moduleUpdate_neverReplacesTheActiveModuleTree() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt",
        ).readText()
        val stagingBoundary = source
            .substringAfter("private suspend fun installValidatedModule(")
            .substringBefore("private suspend fun installModuleLocked(")
        val install = source
            .substringAfter("private suspend fun installModuleLocked(")
            .substringBefore("private suspend fun verifyStandardModuleInstall(")

        assertFalse(stagingBoundary.contains("runExclusiveLifecycleTask"))
        assertTrue(install.contains("RootModuleContract.installCommand"))
        assertTrue(install.contains("needsReboot = true"))
        assertFalse(install.contains("validateModuleArchive"))
        assertFalse(install.contains("rollbackHotUpdate"))
        assertFalse(install.contains(".zapret2-update-"))
        assertFalse(install.contains(".zapret2-backup-"))
        assertFalse(install.contains("mv -f"))
        assertFalse(install.contains("cp -R"))
    }

    @Test
    fun shellContract_keepsCatalogExecutionOutsideTheRuntimeValidator() {
        val source = repositoryFile("zapret2/scripts/package-contract.sh").readText()
        val runtimeValidator = source
            .substringAfter("package_contract_validate_all() {")
            .substringBefore("package_contract_validate_release_all() {")
        val releaseValidator = source.substringAfter("package_contract_validate_release_all() {")

        assertFalse(runtimeValidator.contains("package_contract_validate_catalog"))
        assertTrue(releaseValidator.contains("package_contract_validate_catalog"))
    }

    @Test
    fun apkCatalogListsQualifiedPresetsWithoutRunningTheReleaseScanner() {
        val repository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/PresetRepository.kt",
        ).readText()
        val runner = repository
            .substringAfter("override suspend fun listPresets()")
            .substringBefore("override suspend fun validatePreset(")
        val read = repository
            .substringAfter("override suspend fun readCompatible(")
            .substringBefore("override suspend fun preview(")
        val apply = repository
            .substringAfter("override suspend fun apply(")
            .substringBefore("override suspend fun save(")
        val profiles = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ProfileRepository.kt",
        ).readText()
        val strategies = profiles
            .substringAfter("override suspend fun loadStrategies(")
            .substringBefore("override suspend fun loadListEntries(")

        assertTrue(runner.contains("--list-presets-machine"))
        assertFalse(runner.contains("--scan-presets-machine"))
        assertFalse(read.contains("validatePreset"))
        assertFalse(apply.contains("validatePreset"))
        assertTrue(repository.substringAfter("override suspend fun save(").contains("validatePreset"))
        assertTrue(strategies.contains("readPublishedRegularText"))
        assertFalse(strategies.contains("validate-strategies-machine"))
    }

    @Test
    fun profileRead_usesOnlyActiveSelectionAndNeverEnumeratesThePresetCatalog() {
        val profiles = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ProfileRepository.kt",
        ).readText()
        val loadActive = profiles
            .substringAfter("override suspend fun loadActive()")
            .substringBefore("override suspend fun loadStrategies(")

        assertTrue(loadActive.contains("presets.readActive()"))
        assertFalse(loadActive.contains("loadCatalog"))
        assertFalse(loadActive.contains("listPresets"))
    }

    @Test
    fun runtimeAndPresetReads_consumeOneAtomicSnapshotWithoutRepeatedHashes() {
        val runtime = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RuntimeConfigStore.kt",
        ).readText()
        val inspect = runtime
            .substringAfter("private fun inspectRuntimeConfig()")
            .substringBefore("private fun unavailable(")
        val presets = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/PresetRepository.kt",
        ).readText()
        val snapshot = presets
            .substringAfter("override suspend fun snapshotFile(")
            .substringBefore("override suspend fun writeCandidate(")
        val rootIo = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RootFileIo.kt",
        ).readText()
        val atomicRead = rootIo
            .substringAfter("fun readAtomicMutableText(")
            .substringBefore("/** Reads a stable protected text file")

        assertTrue(inspect.contains("readAtomicMutableText"))
        assertFalse(inspect.contains("runtime-config.sh"))
        assertFalse(inspect.contains("readSecureRegularText"))
        assertTrue(snapshot.contains("readAtomicMutableText"))
        assertFalse(snapshot.contains("executeRoot"))
        assertFalse(snapshot.contains("readSecureRegularText"))
        assertEquals(1, atomicRead.split("cat ${'$'}quoted").size - 1)
        assertFalse(atomicRead.contains("sha256sum"))
    }

    @Test
    fun successfulPresetAndProfileMutations_projectVerifiedResultsWithoutCatalogReloads() {
        val presets = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/viewmodel/PresetsViewModel.kt",
        ).readText()
        val apply = presets
            .substringAfter("internal suspend fun applyPresetNow(")
            .substringBefore("fun openPresetEditor(")
        val save = presets
            .substringAfter("internal suspend fun savePresetNow(")
            .substringBefore("private fun launchOperation(")
        val profiles = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/viewmodel/ProfilesViewModel.kt",
        ).readText()
        val mutate = profiles
            .substringAfter("private fun mutate(")
            .substringBefore("private fun outcomeMessage(")

        assertFalse(apply.contains("loadPresetsNow()"))
        assertFalse(save.contains("loadPresetsNow()"))
        assertTrue(mutate.contains("result.publishedDocument"))
        assertTrue(mutate.contains("outcome == PresetMutationOutcome.SourceChanged -> repository.loadActive()"))
    }

    @Test
    fun ordinaryServiceStatus_reusesPublishedEnvironmentAndTypedSnapshot() {
        val control = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/viewmodel/ControlViewModel.kt",
        ).readText()
        val refresh = control
            .substringAfter("private suspend fun refreshStatus()")
            .substringBefore("fun refreshStatusManually()")

        assertTrue(refresh.contains("ServiceLifecycleController.getStatus()"))
        assertFalse(refresh.contains("reconcileEnvironment()"))
        assertFalse(refresh.contains("RuntimeConfigStore.readCore()"))
        assertFalse(refresh.contains("checkRootAccess()"))
    }

    @Test
    fun rootTransport_doesNotSerializeAnIdProbeBeforeEveryCommand() {
        val controller = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ServiceLifecycleController.kt",
        ).readText()
        val executeRaw = controller
            .substringAfter("private suspend fun executeRaw(")
            .substringBefore("private fun executeShell(")

        assertFalse(executeRaw.contains("executeShell(\"id -u\""))
        assertTrue(executeRaw.contains("val result = executeShell(command, policy)"))
    }

    @Test
    fun installationObservation_trustsOneGenerationReceiptInsteadOfPackagePathScans() {
        val repository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/Zapret2ModuleRepository.kt",
        ).readText()
        val probe = repository
            .substringAfter("internal fun buildProbeCommand()")
            .substringBefore("internal fun parseEnvironmentOutput(")

        assertTrue(probe.contains("InstallGenerationMetadata.shellValidator"))
        assertFalse(probe.contains("runtimeReadinessRegularFiles"))
        assertFalse(probe.contains("runtimeReadinessExecutables"))
        assertFalse(probe.contains("for z2_relative"))
    }

    @Test
    fun shellContract_keepsExhaustiveByteComparisonOutsidePublication() {
        val source = repositoryFile("zapret2/scripts/package-contract.sh").readText()
        val runtimeComparison = source
            .substringAfter("package_contract_compare_release() {")
            .substringBefore("package_contract_compare_release_all() {")
        val exhaustiveComparison =
            source.substringAfter("package_contract_compare_release_all() {")

        assertFalse(runtimeComparison.contains("package_contract_for_each_path"))
        assertTrue(exhaustiveComparison.contains("package_contract_for_each_path"))
    }

    @Test
    fun machineStatus_usesBoundOwnerSnapshotAndNeverFallsBackToGlobalProcScan() {
        val source = repositoryFile("zapret2/scripts/zapret-status.sh").readText()

        assertFalse(source.contains("scan_exact_owned_nfqws"))
        assertFalse(source.contains("owned_family_present"))
        assertFalse(source.contains("owner_family_generation_healthy"))
        assertFalse(source.contains("read_owner_state"))
        assertFalse(source.contains("read_verified_pidfile"))
        assertFalse(source.contains("read_install_generation_meta"))
        assertTrue(source.contains("verify_status_snapshot_pid"))
        assertTrue(source.contains("STATUS_FILE_OWNER_GENERATION"))
        assertTrue(source.contains("Z2_FAST_SNAPSHOT=1"))
    }

    @Test
    fun lifecycleStart_reusesTheCompiledPresetReceiptUntilItsInputsChange() {
        val start = repositoryFile("zapret2/scripts/zapret-start.sh").readText()
        val prepare = start
            .substringAfter("prepare_options() {")
            .substringBefore("compiled_source_binding_current() {")

        assertTrue(prepare.contains("ensure_compiled_artifact"))
        assertTrue(prepare.contains("compiled_validation_receipt_current"))
        assertTrue(prepare.contains("write_compiled_validation_receipt"))
        assertFalse(prepare.contains("compile_preset_artifact"))
    }

    @Test
    fun bootStart_hasNoNetworkWaitAndUsesFineGrainedBootBarrier() {
        val service = repositoryFile("service.sh").readText()

        assertFalse(service.contains("ip route show default"))
        assertFalse(service.contains("net.dns1"))
        assertFalse(service.contains("Network wait timeout"))
        assertFalse(service.contains("sleep 5"))
        assertTrue(service.contains("sleep 1"))
    }

    @Test
    fun lifecyclePublication_doesNotReverifyOrRewriteTheOwnerPhase() {
        val start = repositoryFile("zapret2/scripts/zapret-start.sh").readText()
        val launch = start
            .substringAfter("launch_nfqws2() {")
            .substringBefore("stop_failed_fallback_launch() {")
        val fastCommit = start
            .substringAfter("if prepare_fast_replace_candidate; then")
            .substringBefore("log_section \"Firewall transaction\"")
        val fullCommit = start
            .substringAfterLast("log_section \"nfqws2 launch\"")
            .substringBefore("TOTAL_RULES=")

        assertFalse(launch.contains("verify_nfqws_pid"))
        assertTrue(launch.contains("publish_nfqws_owner"))
        assertFalse(fastCommit.contains("set_owner_phase active"))
        assertTrue(fastCommit.contains("fast_replace_health_ok"))
        assertFalse(fullCommit.contains("set_owner_phase active"))
        assertFalse(fullCommit.contains("normal_health_ok"))
        assertTrue(fullCommit.contains("PUBLISHED_PID"))
        assertTrue(fullCommit.contains("PUBLISHED_GENERATION"))
    }

    @Test
    fun packageContract_hasNoActiveTreeHotSwapSurface() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ModulePackageContract.kt",
        ).readText()

        assertTrue(ModulePackageContract.moduleRootExecutables.containsAll(
            listOf("service.sh", "uninstall.sh", "action.sh"),
        ))
        assertFalse(source.contains("hotSwapBootstrapFiles"))
        assertFalse(source.contains("hotUpdateRootExecutables"))
    }

    @Test
    fun canonicalStagingRelease_keepsActivationOwnedByTheRootManager() {
        assertEquals("7", ModulePackageContract.LIFECYCLE_CONTRACT_VERSION)
        assertTrue(
            repositoryFile("service.sh").readText()
                .contains("Module package generations are activated only by the root manager at boot."),
        )
    }

    @Test
    fun recurringObservers_haveNoLegacyMutationRecovery() {
        val repository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/Zapret2ModuleRepository.kt",
        ).readText()
        val controller = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ServiceLifecycleController.kt",
        ).readText()
        val status = controller
            .substringAfter("suspend fun getStatus(): ServiceStatus")
            .substringBefore("suspend fun start()")

        assertFalse(repository.contains("ModuleUpdateRecovery"))
        assertFalse(status.contains("recoverIfNeeded"))
        assertFalse(controller.contains("ModuleUpdateRecovery"))
    }

    @Test
    fun lifecycleObservation_hasOneTypedSerializedAuthority() {
        val repository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/Zapret2ModuleRepository.kt",
        ).readText()
        val status = repositoryFile("zapret2/scripts/zapret-status.sh").readText()
        val common = repositoryFile("zapret2/scripts/common.sh").readText()
        val controller = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ServiceLifecycleController.kt",
        ).readText()

        assertFalse(repository.contains("lifecycle.lock"))
        assertFalse(repository.contains("Z2_MUTATION_STATE"))
        assertTrue(status.contains("--machine-v6"))
        assertTrue(status.contains("classify_lifecycle_lock"))
        assertTrue(status.contains("lifecycle_lock_is_owned_by_caller"))
        assertTrue(common.contains("lifecycle_lock_is_owned_by_caller"))
        assertTrue(
            controller.contains(
                "inheritLifecycleObservation(buildStatusCommand(version = 6))",
            ),
        )
        assertFalse(status.contains("acquire_lifecycle_lock"))
        assertFalse(status.contains("release_lifecycle_lock"))
        assertFalse(status.contains("scan_exact_owned_nfqws"))
        assertFalse(status.contains("owned_family_present"))
        assertFalse(status.contains("owner_family_generation_healthy"))
        assertTrue(status.contains("Z2_LIFECYCLE_STATE"))
        assertTrue(
            status.contains(
                "if [ \"\$Z2_FAST_SNAPSHOT\" = 0 ] && " +
                    "[ \"\$STATUS_FILE_IPV6_ACTIVE\" = 1 ]; then",
            ),
        )
        val networkStats = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/NetworkStatsManager.kt",
        ).readText()
        assertFalse(networkStats.contains("owner.meta"))
        assertFalse(networkStats.contains("iptables -t mangle -S"))
        assertFalse(networkStats.contains("OwnedIptablesTopologyVerifier"))
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
