package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Zapret2ModuleRepositoryTest {

    private val repository = Zapret2ModuleRepository()

    @Test
    fun environmentParser_mapsPackageSlotsIndependently() {
        val activeStates = mapOf(
            "missing" to ModuleInstallState.MISSING,
            "ready" to ModuleInstallState.READY,
            "disabled" to ModuleInstallState.DISABLED,
            "removal_pending" to ModuleInstallState.REMOVAL_PENDING,
            "partial" to ModuleInstallState.PARTIAL,
            "unreadable" to ModuleInstallState.UNREADABLE,
        )
        val pendingStates = mapOf(
            "missing" to PendingModuleState.NONE,
            "ready" to PendingModuleState.READY,
            "partial" to PendingModuleState.PARTIAL,
            "unreadable" to PendingModuleState.UNREADABLE,
        )

        activeStates.forEach { (wireValue, expected) ->
            val parsed = repository.parseEnvironmentOutput(
                payload(active = wireValue),
            )
            assertEquals(expected, parsed?.activeState)
        }
        pendingStates.forEach { (wireValue, expected) ->
            val parsed = repository.parseEnvironmentOutput(
                payload(pending = wireValue),
            )
            assertEquals(expected, parsed?.pendingState)
        }
    }

    @Test
    fun environmentParser_rejectsMalformedDuplicateUnknownAndContradictoryPayloads() {
        val valid = payload()

        assertNull(repository.parseEnvironmentOutput(valid + "Z2_NFQUEUE=1"))
        assertNull(repository.parseEnvironmentOutput(valid.dropLast(1)))
        assertNull(repository.parseEnvironmentOutput(valid + "Z2_FUTURE=1"))
        assertNull(
            repository.parseEnvironmentOutput(
                valid.map { if (it == "Z2_NFQUEUE=1") "Z2_NFQUEUE=yes" else it },
            ),
        )
        assertNull(repository.parseEnvironmentOutput(payload(active = "broken")))
        assertNull(repository.parseEnvironmentOutput(payload(pending = "disabled")))
        assertNull(repository.parseEnvironmentOutput(valid + "Z2_MUTATION_STATE=in_progress"))
    }

    @Test
    fun installationProbe_ownsBothMagiskSlotsWithoutExecutingRuntimeStrategies() {
        val command = repository.buildProbeCommand("arm64-v8a")

        assertTrue(command.contains(Zapret2ModuleRepository.ACTIVE_MODULE_DIR))
        assertTrue(command.contains(Zapret2ModuleRepository.PENDING_MODULE_DIR))
        assertTrue(command.contains("zapret2/bin/arm64-v8a/nfqws2"))
        assertTrue(command.contains(ModulePackageContract.LIFECYCLE_CONTRACT_PATH))
        assertTrue(command.contains("= ${ModulePackageContract.LIFECYCLE_CONTRACT_VERSION} ]"))
        assertFalse(command.contains("package_contract_validate_runtime_selection"))
        assertFalse(command.contains("--validate-strategies-machine"))
        assertFalse(command.contains("--preflight-preset-machine"))
        assertFalse(command.contains("runtime-manifest.tsv"))
        assertFalse(command.contains("strategy-catalogs"))
        assertFalse(command.contains("zapret2/lua/"))
        assertTrue(command.contains(InstallGenerationMetadata.RELATIVE_PATH))
        assertFalse(command.contains("lifecycle.lock"))
    }

    @Test
    fun modulePropParser_requiresTheFullReleaseIdentityAndVersionBinding() {
        val valid = """
            id=zapret2
            name=Zapret2 DPI Bypass
            version=v2.0.0
            versionCode=2000000
            author=bol-van
            description=DPI bypass
            updateJson=https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json
        """.trimIndent() + "\n"

        assertEquals("v2.0.0", repository.parseModulePropVersion(valid))
        assertNull(repository.parseModulePropVersion(valid.replace("id=zapret2", "id=other")))
        assertNull(repository.parseModulePropVersion(valid.replace("versionCode=2000000", "versionCode=1999999")))
        assertNull(repository.parseModulePropVersion(valid.replace("version=v2.0.0", "version=v2.0.00")))
        assertNull(repository.parseModulePropVersion(valid + "webRoot=webroot\n"))
        assertNull(repository.parseModulePropVersion(valid + "id=zapret2\n"))
        assertNull(repository.parseModulePropVersion(valid.replace("version=v2.0.0", "version=bad\u0000value")))
        assertNull(repository.parseModulePropVersion(valid + "#${"x".repeat(64 * 1024)}\n"))
    }

    @Test
    fun moduleCapabilities_failClosedOutsideStableStates() {
        assertTrue(ModuleInstallState.READY.isOperational)
        assertTrue(ModuleInstallState.READY.allowsFullRollback)
        assertTrue(ModuleInstallState.DISABLED.allowsFullRollback)
        assertFalse(ModuleInstallState.DISABLED.isOperational)
        listOf(
            ModuleInstallState.MISSING,
            ModuleInstallState.READY,
            ModuleInstallState.DISABLED,
            ModuleInstallState.PARTIAL,
        ).forEach { assertTrue(it.allowsModuleUpdate) }

        listOf(
            ModuleInstallState.UNKNOWN,
            ModuleInstallState.MISSING,
            ModuleInstallState.REMOVAL_PENDING,
            ModuleInstallState.PARTIAL,
            ModuleInstallState.UNSUPPORTED_ABI,
            ModuleInstallState.UNREADABLE,
        ).forEach { state ->
            assertFalse(state.isOperational)
            assertFalse(state.allowsFullRollback)
            if (state !in setOf(ModuleInstallState.MISSING, ModuleInstallState.PARTIAL)) {
                assertFalse(state.allowsModuleUpdate)
            }
        }
    }

    private fun payload(
        active: String = "ready",
        pending: String = "missing",
    ) = listOf(
        "Z2_ACTIVE_STATE=$active",
        "Z2_PENDING_STATE=$pending",
        "Z2_NFQUEUE=1",
    )
}
