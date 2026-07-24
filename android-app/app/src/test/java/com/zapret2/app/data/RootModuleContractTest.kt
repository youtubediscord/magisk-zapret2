package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModuleContractTest {

    @Test
    fun paths_areOwnedByOneSharedContract() {
        assertEquals("/data/adb/modules/zapret2", RootModuleContract.ACTIVE_MODULE_DIR)
        assertEquals("/data/adb/modules_update/zapret2", RootModuleContract.PENDING_MODULE_DIR)
        assertEquals(
            "${RootModuleContract.ACTIVE_MODULE_DIR}/zapret2/scripts",
            RootModuleContract.SCRIPTS_DIR,
        )
    }

    @Test
    fun installerBackend_requiresOneExactWireRecord() {
        assertEquals(
            RootModuleContract.InstallerBackend.MAGISK,
            RootModuleContract.parseInstallerBackend(listOf("Z2_ROOT_INSTALLER=magisk")),
        )
        assertEquals(
            RootModuleContract.InstallerBackend.KERNEL_SU,
            RootModuleContract.parseInstallerBackend(listOf("Z2_ROOT_INSTALLER=kernelsu")),
        )
        assertEquals(
            RootModuleContract.InstallerBackend.APATCH,
            RootModuleContract.parseInstallerBackend(listOf("Z2_ROOT_INSTALLER=apatch")),
        )
        assertNull(RootModuleContract.parseInstallerBackend(listOf("Z2_ROOT_INSTALLER=unsupported")))
        assertNull(
            RootModuleContract.parseInstallerBackend(
                listOf("Z2_ROOT_INSTALLER=magisk", "Z2_ROOT_INSTALLER=apatch"),
            ),
        )
    }

    @Test
    fun installCommand_isBackendSpecificAndQuotesTheArchive() {
        val archive = "/data/user/0/com.zapret2.app/files/update module.zip"
        assertEquals(
            "magisk --install-module '$archive'",
            RootModuleContract.installCommand(RootModuleContract.InstallerBackend.MAGISK, archive),
        )
        assertEquals(
            "/data/adb/ksud module install '$archive'",
            RootModuleContract.installCommand(RootModuleContract.InstallerBackend.KERNEL_SU, archive),
        )
        assertEquals(
            "/data/adb/apd module install '$archive'",
            RootModuleContract.installCommand(RootModuleContract.InstallerBackend.APATCH, archive),
        )
        assertTrue(RootModuleContract.installerProbeCommand().contains("Z2_ROOT_INSTALLER"))
    }
}
