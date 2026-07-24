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
        assertTrue(source.contains("magisk --install-module"))
    }

    @Test
    fun moduleUpdate_neverReplacesTheActiveMagiskTree() {
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
        assertTrue(install.contains("magisk --install-module"))
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
        assertTrue(source.contains("STATUS_FILE_OWNER_GENERATION"))
        assertTrue(source.contains("Z2_FAST_SNAPSHOT=1"))
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
    fun canonicalStagingRelease_forcesLegacyApkIntoItsMagiskFallback() {
        assertEquals("5", ModulePackageContract.LIFECYCLE_CONTRACT_VERSION)
        assertTrue(
            repositoryFile("service.sh").readText()
                .contains("Module package generations are activated only by Magisk at boot."),
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

        assertFalse(repository.contains("lifecycle.lock"))
        assertFalse(repository.contains("Z2_MUTATION_STATE"))
        assertTrue(status.contains("--machine-v5"))
        assertTrue(status.contains("classify_lifecycle_lock"))
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
