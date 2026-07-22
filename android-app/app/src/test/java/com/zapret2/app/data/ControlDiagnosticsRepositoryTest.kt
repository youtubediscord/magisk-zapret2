package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDiagnosticsRepositoryTest {

    private val repository = ControlDiagnosticsRepository()

    @Test
    fun environmentParser_mapsEveryExactModuleState() {
        val states = mapOf(
            "missing" to ModuleInstallState.MISSING,
            "ready" to ModuleInstallState.READY,
            "disabled" to ModuleInstallState.DISABLED,
            "removal_pending" to ModuleInstallState.REMOVAL_PENDING,
            "updating" to ModuleInstallState.UPDATING,
            "partial" to ModuleInstallState.PARTIAL,
            "unsupported_abi" to ModuleInstallState.UNSUPPORTED_ABI,
            "unreadable" to ModuleInstallState.UNREADABLE,
        )

        states.forEach { (wireValue, expected) ->
            val parsed = repository.parseEnvironmentOutput(
                listOf(
                    "Z2_MODULE_STATE=$wireValue",
                    "Z2_NFQUEUE=1",
                ),
            )
            assertEquals(expected, parsed?.moduleState)
        }
    }

    @Test
    fun environmentParser_rejectsMalformedDuplicateUnknownAndContradictoryPayloads() {
        val valid = listOf(
            "Z2_MODULE_STATE=ready",
            "Z2_NFQUEUE=1",
        )

        assertNull(repository.parseEnvironmentOutput(valid + "Z2_NFQUEUE=1"))
        assertNull(repository.parseEnvironmentOutput(valid.dropLast(1)))
        assertNull(repository.parseEnvironmentOutput(valid + "Z2_FUTURE=1"))
        assertNull(repository.parseEnvironmentOutput(valid.map { if (it == "Z2_NFQUEUE=1") "Z2_NFQUEUE=yes" else it }))
        assertNull(repository.parseEnvironmentOutput(valid.map { if (it.startsWith("Z2_MODULE_STATE=")) "Z2_MODULE_STATE=broken" else it }))
    }

    @Test
    fun modulePropParser_requiresTheFullReleaseIdentityAndVersionBinding() {
        val valid = """
            id=zapret2
            name=Zapret2 DPI Bypass
            version=v2.0.100
            versionCode=100
            author=bol-van
            description=DPI bypass
            updateJson=https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json
        """.trimIndent() + "\n"

        assertEquals(
            "v2.0.100",
            repository.parseModulePropVersion(valid),
        )
        assertNull(repository.parseModulePropVersion(valid.replace("id=zapret2", "id=other")))
        assertNull(repository.parseModulePropVersion(valid.replace("versionCode=100", "versionCode=99")))
        assertNull(repository.parseModulePropVersion(valid.replace("version=v2.0.100", "version=v2.0.0100")))
        assertNull(repository.parseModulePropVersion(valid + "webRoot=webroot\n"))
        assertNull(repository.parseModulePropVersion(valid + "id=zapret2\n"))
        assertNull(repository.parseModulePropVersion(valid.replace("version=v2.0.100", "version=bad\u0000value")))
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
            ModuleInstallState.UPDATING,
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
}
