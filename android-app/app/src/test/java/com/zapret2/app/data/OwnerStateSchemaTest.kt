package com.zapret2.app.data

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerStateSchemaTest {
    private val bootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun currentV7_requiresCanonicalShapeCurrentBootAndBoundChains() {
        val current = linesFor(OwnerStateSchema.currentFields, OwnerStateSchema.VERSION.toString(), bootId)

        val result = OwnerStateSchema.reconcile(current, bootId)

        assertEquals(OwnerStateSchema.Disposition.CURRENT, result.disposition)
        assertTrue(result.isHealthyCandidate)
        assertFalse(result.recoveryRequired)
        assertEquals(OwnerStateSchema.CURRENT_FIELD_COUNT, OwnerStateSchema.currentFields.size)
        assertEquals(OwnerStateSchema.CURRENT_FIELD_COUNT, OwnerStateSchema.fields.size)
        assertEquals(OwnerStateSchema.CURRENT_FIELD_COUNT, result.values.size)
    }

    @Test
    fun currentV7_acceptsUpstreamKeepaliveTopologyWithoutConnbytes() {
        val canonical = linesFor(OwnerStateSchema.currentFields, OwnerStateSchema.VERSION.toString(), bootId)
        val fallbackSpec = "family:ipv4;active:1;tag:Ab12Cd34Ef;outchain:Z2O_Ab12Cd34Ef;inchain:Z2I_Ab12Cd34Ef;qnum:200;tcp:80,443;udp:443;stun:0;out:20;in:10;mark:0x40000000;connbytes:0;multiport:1;markcap:1;rules:2"
        val ipv6Spec = canonical.first { it.startsWith("ipv6_spec=") }.substringAfter('=')
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$fallbackSpec\n$ipv6Spec\n".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val fallback = canonical.map {
            when {
                it.startsWith("ipv4_connbytes=") -> "ipv4_connbytes=0"
                it.startsWith("ipv4_rules=") -> "ipv4_rules=2"
                it.startsWith("ipv4_spec=") -> "ipv4_spec=$fallbackSpec"
                it.startsWith("firewall_fingerprint=") -> "firewall_fingerprint=$fingerprint"
                else -> it
            }
        }

        val result = OwnerStateSchema.reconcile(fallback, bootId)

        assertEquals(OwnerStateSchema.Disposition.CURRENT, result.disposition)
        assertTrue(result.isHealthyCandidate)
    }

    @Test
    fun ownerFileBound_coversOnlyCompactMetadata() {
        assertEquals(1024 * 1024, OwnerStateSchema.MAX_FILE_BYTES)
        assertEquals(64 * 1024, OwnerStateSchema.MAX_CURRENT_FILE_BYTES)
    }

    @Test
    fun legacyV3ThroughV5RequireRecoveryWhileSameBootV6IsReadOnlyCompatible() {
        val v3 = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV3Fields, "3"),
            bootId,
        )
        val v4 = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV4Fields, "4"),
            bootId,
        )
        val v5SameBoot = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV5Fields, "5", bootId), bootId,
        )
        val v5CrossBoot = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV5Fields, "5", bootId),
            "22222222-2222-2222-2222-222222222222",
        )
        val v5WithV6Identity = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV5Fields, "5", bootId) +
                listOf("firewall_tag=abcdefghij", "out_chain=Z2O_abcdefghij", "in_chain=Z2I_abcdefghij"),
            bootId,
        )
        val v6 = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV6Fields, "6", bootId), bootId,
        )
        val v6CrossBoot = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV6Fields, "6", bootId),
            "22222222-2222-2222-2222-222222222222",
        )
        val malformedV6 = OwnerStateSchema.reconcile(
            linesFor(OwnerStateSchema.legacyV6Fields, "6", bootId).map {
                if (it.startsWith("argv_hex=")) "argv_hex=not-hex" else it
            },
            bootId,
        )

        assertEquals(OwnerStateSchema.Disposition.LEGACY_RECOVERY_REQUIRED, v3.disposition)
        assertEquals(OwnerStateSchema.Disposition.LEGACY_RECOVERY_REQUIRED, v4.disposition)
        assertEquals(OwnerStateSchema.Disposition.LEGACY_RECOVERY_REQUIRED, v5SameBoot.disposition)
        assertEquals(OwnerStateSchema.Disposition.LEGACY_RECOVERY_REQUIRED, v5CrossBoot.disposition)
        assertEquals(OwnerStateSchema.Disposition.COMPATIBLE_READ_ONLY, v6.disposition)
        assertEquals(OwnerStateSchema.Disposition.LEGACY_RECOVERY_REQUIRED, v6CrossBoot.disposition)
        assertEquals(OwnerStateSchema.Disposition.INVALID_RECOVERY_REQUIRED, malformedV6.disposition)
        assertEquals(OwnerStateSchema.Disposition.INVALID_RECOVERY_REQUIRED, v5WithV6Identity.disposition)
        assertFalse(v3.isHealthyCandidate)
        assertFalse(v4.isHealthyCandidate)
        assertTrue(v3.recoveryRequired)
        assertTrue(v4.recoveryRequired)
        assertTrue(v6.isHealthyCandidate)
        assertFalse(v6.recoveryRequired)
    }

    @Test
    fun currentV7_rejectsMissingMalformedDuplicateUnknownReorderedCrossBootAndBadChains() {
        val canonical = linesFor(OwnerStateSchema.currentFields, OwnerStateSchema.VERSION.toString(), bootId)
        val mutations = listOf(
            canonical.filterNot { it.startsWith("boot_id=") },
            canonical.map { if (it.startsWith("boot_id=")) "boot_id=NOT-A-UUID" else it },
            canonical + "boot_id=$bootId",
            canonical + "future=value",
            canonical.toMutableList().apply { add(8, removeAt(7)) },
            canonical.map { if (it.startsWith("firewall_tag=")) "firewall_tag=short" else it },
            canonical.map { if (it.startsWith("firewall_tag=")) "firewall_tag=abcde-1234" else it },
            canonical.map { if (it.startsWith("out_chain=")) "out_chain=Z2O_wrongchain" else it },
            canonical.map { if (it.startsWith("in_chain=")) "in_chain=Z2I_wrongchain" else it },
            canonical.map { if (it.startsWith("qnum=")) "qnum=0" else it },
            canonical.map { if (it.startsWith("qnum=")) "qnum=0200" else it },
            canonical.map { if (it.startsWith("ipv4_rules=")) "ipv4_rules=5" else it },
            canonical.map { if (it.startsWith("ipv4_rules=")) "ipv4_rules=06" else it },
            canonical.map { if (it.startsWith("firewall_fingerprint=")) "firewall_fingerprint=${"0".repeat(64)}" else it },
            canonical.map {
                if (it.startsWith("generation=")) "generation=${"a".repeat(OwnerStateSchema.MAX_CURRENT_FILE_BYTES)}" else it
            },
            canonical,
        )
        val currentBoots = List(mutations.size - 1) { bootId } +
            "22222222-2222-2222-2222-222222222222"

        mutations.zip(currentBoots).forEach { (lines, currentBoot) ->
            val result = OwnerStateSchema.reconcile(lines, currentBoot)
            assertEquals(OwnerStateSchema.Disposition.INVALID_RECOVERY_REQUIRED, result.disposition)
            assertFalse(result.isHealthyCandidate)
            assertTrue(result.recoveryRequired)
        }
    }

    @Test
    fun bootId_isStrictLowercaseUuid() {
        assertTrue(OwnerStateSchema.isValidBootId(bootId))
        assertFalse(OwnerStateSchema.isValidBootId(bootId.uppercase()))
        assertFalse(OwnerStateSchema.isValidBootId("11111111-1111-1111-1111-11111111111"))
        assertFalse(OwnerStateSchema.isValidBootId(""))
    }

    @Test
    fun netfilterMark_isCanonicalUnsigned32Bit() {
        assertEquals("0x0", ProtocolMark.canonicalOrNull("0"))
        assertEquals("0x40000000", ProtocolMark.canonicalOrNull("0X40000000"))
        assertEquals("0xffffffff", ProtocolMark.canonicalOrNull("4294967295"))
        assertNull(ProtocolMark.canonicalOrNull("4294967296"))
        assertNull(ProtocolMark.canonicalOrNull("0x100000000"))
        assertNull(ProtocolMark.canonicalOrNull("-1"))
    }

    private fun linesFor(fields: List<String>, version: String, boot: String = ""): List<String> {
        val tag = "Ab12Cd34Ef"
        val outChain = "Z2O_$tag"
        val inChain = "Z2I_$tag"
        val ipv4Spec = "family:ipv4;active:1;tag:$tag;outchain:$outChain;inchain:$inChain;qnum:200;tcp:80,443;udp:443;stun:0;out:20;in:10;mark:0x40000000;connbytes:1;multiport:1;markcap:1;rules:4"
        val ipv6Spec = "family:ipv6;active:0;tag:$tag;outchain:$outChain;inchain:$inChain;qnum:200;tcp:80,443;udp:443;stun:0;out:20;in:10;mark:0x40000000;connbytes:1;multiport:1;markcap:1;rules:0"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$ipv4Spec\n$ipv6Spec\n".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val values = mapOf(
            "version" to version,
            "pid" to "1234",
            "starttime" to "5678",
            "argv_hex" to "2f62696e2f6e667177733200",
            "argv_sha256" to "f".repeat(64),
            "qnum" to "200",
            "exe" to "/data/adb/modules/zapret2/zapret2/nfqws2",
            "generation" to "generation-1",
            "boot_id" to boot,
            "phase" to "active",
            "install_generation" to "install-1",
            "install_archive_sha256" to "a".repeat(64),
            "firewall_tag" to tag,
            "out_chain" to outChain,
            "in_chain" to inChain,
            "ports_tcp" to "80,443",
            "ports_udp" to "443",
            "stun_ports" to "0",
            "pkt_out" to "20",
            "pkt_in" to "10",
            "desync_mark" to "0x40000000",
            "ipv4_active" to "1",
            "ipv6_active" to "0",
            "ipv4_connbytes" to "1",
            "ipv4_multiport" to "1",
            "ipv4_mark" to "1",
            "ipv6_connbytes" to "1",
            "ipv6_multiport" to "1",
            "ipv6_mark" to "1",
            "ipv4_rules" to "4",
            "ipv6_rules" to "0",
            "ipv4_spec" to ipv4Spec,
            "ipv6_spec" to ipv6Spec,
            "firewall_fingerprint" to fingerprint,
        )
        return fields.map { field -> "$field=${values.getValue(field)}" }
    }
}
