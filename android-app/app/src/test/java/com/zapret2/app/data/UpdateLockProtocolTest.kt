package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateLockProtocolTest {

    private val bootId = "01234567-89ab-4def-8abc-0123456789ab"

    @Test
    fun parse_acceptsExactSevenUniqueFieldsInAnyOrder() {
        val parsed = UpdateLockProtocol.parse(validRecord().reversed())

        assertNotNull(parsed)
        assertEquals("4242", parsed?.pid)
        assertEquals("98765", parsed?.starttime)
        assertEquals(bootId, parsed?.bootId)
        assertEquals("token-1", parsed?.token)
    }

    @Test
    fun parse_rejectsMissingDuplicateAndUnknownFields() {
        assertNull(UpdateLockProtocol.parse(validRecord().dropLast(1)))
        assertNull(UpdateLockProtocol.parse(validRecord().filterNot { it.startsWith("boot_id=") }))
        assertNull(UpdateLockProtocol.parse(validRecord() + "pid=4242"))
        assertNull(UpdateLockProtocol.parse(validRecord().dropLast(1) + "future=value"))
    }

    @Test
    fun parse_rejectsMalformedValuesAndForeignModule() {
        assertNull(UpdateLockProtocol.parse(validRecord().replace("pid=4242", "pid=not-a-pid")))
        assertNull(UpdateLockProtocol.parse(validRecord().replace("token=token-1", "token=bad token")))
        assertNull(UpdateLockProtocol.parse(validRecord().replace("boot_id=$bootId", "boot_id=not-a-boot-id")))
        listOf("01", "-1", "9223372036854775808", "18446744073709551615").forEach { invalid ->
            assertNull(UpdateLockProtocol.parse(validRecord().replace("starttime=98765", "starttime=$invalid")))
        }
        assertNull(UpdateLockProtocol.parse(validRecord().replace(
            "module_dir=/data/adb/modules/zapret2",
            "module_dir=/data/adb/modules/other",
        )))
    }

    @Test
    fun startTicks_acceptCanonicalNonnegativeSigned64BeyondIntRange() {
        listOf("0", "2147483648", "9223372036854775807").forEach { ticks ->
            assertEquals(ticks, UpdateLockProtocol.parse(
                validRecord().replace("starttime=98765", "starttime=$ticks"),
            )?.starttime)
        }
        val acquire = listOf(
            "Z2_LOCK_START=9223372036854775807",
            "Z2_LOCK_CREATED=1234567890",
            "Z2_LOCK_BOOT=$bootId",
            "Z2_LOCK_COMPLETE=1",
        )
        assertEquals(
            "9223372036854775807",
            UpdateLockProtocol.parseAcquireOutput(acquire, 4242, "token-1")?.starttime,
        )
        assertNull(UpdateLockProtocol.parseAcquireOutput(
            acquire.map { if (it.startsWith("Z2_LOCK_START=")) "Z2_LOCK_START=9223372036854775808" else it },
            4242,
            "token-1",
        ))
    }

    @Test
    fun shellParser_coversTheSameExactFieldSet() {
        val parser = UpdateLockProtocol.shellParser()

        UpdateLockProtocol.fields.forEach { field ->
            assertEquals(1, Regex("\\n\\s*$field\\)").findAll(parser).count())
        }
        assertEquals(true, parser.contains("z2_valid_nonnegative_i64"))
        assertEquals(true, parser.contains("223372036854775807"))
        assertEquals(true, parser.contains("z2_lock_legacy=1"))
        assertEquals(true, parser.contains("z2_lock_owner_state=ambiguous"))
    }

    @Test
    fun guardAcquireCommand_matchesLifecycleSerializedShellContract() {
        assertEquals(
            "/system/bin/sh '/data/adb/modules/zapret2/zapret2/scripts/zapret-update-guard.sh' acquire " +
                "--pid 4242 --token 'token-1' --module-dir '/data/adb/modules/zapret2'",
            UpdateLockProtocol.buildGuardAcquireCommand(4242, "token-1"),
        )
        assertNull(UpdateLockProtocol.buildGuardAcquireCommand(0, "token-1"))
        assertNull(UpdateLockProtocol.buildGuardAcquireCommand(4242, "bad token"))
    }

    @Test
    fun parseAcquireOutput_requiresExactUniqueTerminalRecord() {
        val valid = listOf(
            "Z2_LOCK_START=98765",
            "Z2_LOCK_CREATED=1234567890",
            "Z2_LOCK_BOOT=$bootId",
            "Z2_LOCK_COMPLETE=1",
        )

        assertNotNull(UpdateLockProtocol.parseAcquireOutput(valid, 4242, "token-1"))
        assertNull(UpdateLockProtocol.parseAcquireOutput(valid.dropLast(1), 4242, "token-1"))
        assertNull(UpdateLockProtocol.parseAcquireOutput(valid + "Z2_LOCK_COMPLETE=1", 4242, "token-1"))
        assertNull(UpdateLockProtocol.parseAcquireOutput(
            valid.toMutableList().apply { add(size - 1, "Z2_LOCK_FUTURE=1") },
            4242,
            "token-1",
        ))
    }

    private fun validRecord(): List<String> = listOf(
        "version=2",
        "pid=4242",
        "starttime=98765",
        "created_epoch=1234567890",
        "boot_id=$bootId",
        "token=token-1",
        "module_dir=/data/adb/modules/zapret2",
    )

    private fun List<String>.replace(old: String, new: String): List<String> =
        map { if (it == old) new else it }
}
