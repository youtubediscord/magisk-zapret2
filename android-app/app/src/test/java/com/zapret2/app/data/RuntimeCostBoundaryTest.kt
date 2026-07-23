package com.zapret2.app.data

import java.io.File
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
        assertTrue(source.contains("hotSwapBootstrapFiles"))
        assertTrue(source.contains("magisk --install-module"))
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
        val fallback = source
            .substringAfter("A status poll is an observer, not a recovery audit.")
            .substringBefore("Z2_IPV4=0")

        assertTrue(fallback.contains("[ \"\$MACHINE\" != 1 ]"))
        assertTrue(fallback.contains("scan_exact_owned_nfqws"))
        assertTrue(source.contains("STATUS_FILE_OWNER_GENERATION"))
        assertTrue(source.contains("Z2_FAST_SNAPSHOT=1"))
    }

    @Test
    fun liveUpdateCompatibility_coversEveryMagiskFacingBootstrapFile() {
        assertTrue(ModulePackageContract.hotSwapBootstrapFiles.containsAll(
            ModulePackageContract.hotUpdateRootExecutables,
        ))
        assertTrue(ModulePackageContract.hotSwapBootstrapFiles.containsAll(
            ModulePackageContract.wrappers.map { it.relativePath },
        ))
        assertTrue(
            ModulePackageContract.LIFECYCLE_CONTRACT_PATH in
                ModulePackageContract.hotSwapBootstrapFiles,
        )
        assertFalse("module.prop" in ModulePackageContract.hotSwapBootstrapFiles)
        assertFalse(ModulePackageContract.COMMAND_BUILDER_SCRIPT_PATH in
            ModulePackageContract.hotSwapBootstrapFiles)
    }

    @Test
    fun recurringObservers_doNotRunMutationRecovery() {
        val repository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/Zapret2ModuleRepository.kt",
        ).readText()
        val reconcile = repository
            .substringAfter("internal suspend fun reconcileEnvironment(")
            .substringBefore("internal fun buildProbeCommand")
        val controller = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ServiceLifecycleController.kt",
        ).readText()
        val status = controller
            .substringAfter("suspend fun getStatus(): ServiceStatus")
            .substringBefore("suspend fun start()")

        assertTrue(reconcile.contains("if (recoverInterruptedUpdate)"))
        assertFalse(status.contains("recoverIfNeeded"))
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
