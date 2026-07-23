package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatsManagerTest {
    private val identity = OwnedFirewallIdentity(
        tag = "Ab12Cd34Ef",
        outChain = "Z2O_Ab12Cd34Ef",
        inChain = "Z2I_Ab12Cd34Ef",
    )
    private val contract = OwnedFirewallFamilyContract(
        identity = identity,
        active = true,
        qnum = 200,
        portsTcp = "80",
        portsUdp = "443,3478,5349,19302",
        pktOut = 20,
        pktIn = 10,
        desyncMark = "0x40000000",
        connbytes = false,
        multiport = false,
        markCapability = false,
        expectedRuleCount = 10,
    )

    @Test
    fun watchdog_reusesMatchingSnapshotUntilRareBackgroundDeadline() {
        var now = 1_000L
        val gate = FirewallWatchdogGate(intervalMillis = 60_000L) { now }

        assertTrue(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = false))
        now += 59_999L
        assertFalse(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = true))
        now += 1L
        assertTrue(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = true))
    }

    @Test
    fun watchdog_runsForRequestsForcingSnapshotChangesAndDegradedTransitions() {
        var now = 1_000L
        val gate = FirewallWatchdogGate(intervalMillis = 60_000L) { now }

        assertTrue(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = false))
        assertFalse(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = true))

        gate.request()
        assertTrue(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = true))
        assertTrue(gate.shouldRun(force = true, shellDegraded = false, cachedSnapshotMatches = true))
        assertTrue(gate.shouldRun(force = false, shellDegraded = false, cachedSnapshotMatches = false))

        assertTrue(gate.shouldRun(force = false, shellDegraded = true, cachedSnapshotMatches = true))
        assertFalse(gate.shouldRun(force = false, shellDegraded = true, cachedSnapshotMatches = true))
        gate.noteNotRequired()
        assertTrue(gate.shouldRun(force = false, shellDegraded = true, cachedSnapshotMatches = true))
    }

    @Test
    fun ownerMetadata_enforcesQueueNumberBoundaries() {
        fun detail(qnum: Int?) = NetworkStatsManager.IptablesDetail(
            ownPid = "4242",
            pidVerified = true,
            ownPidStarttime = "98765",
            ownerGeneration = "generation-1",
            qnum = qnum,
            ownerMetadataVerified = true,
            metadataComplete = true,
        )

        assertTrue(detail(1).hasValidOwnerMetadata)
        assertTrue(detail(65535).hasValidOwnerMetadata)
        assertFalse(detail(0).hasValidOwnerMetadata)
        assertFalse(detail(65536).hasValidOwnerMetadata)
        assertFalse(detail(null).hasValidOwnerMetadata)
    }

    @Test
    fun topology_acceptsExactV6GenerationAndReportsDynamicCounts() {
        val result = verify(validTopology())

        assertTrue(result.topologyVerified)
        assertTrue(result.hasState)
        assertEquals(12, result.chainCount)
        assertEquals(2, result.anchorCount)
        assertEquals(10, result.mainJumpCount)
        assertEquals(10, result.nfqueueRulesCount)
        assertEquals(5, result.outNfqueueCount)
        assertEquals(5, result.inNfqueueCount)
    }

    @Test
    fun topology_rejectsMissingExtraAndDuplicateObjects() {
        val valid = validTopology()
        val cases = listOf(
            valid - "-N Z2R_Ab12Cd34Ef_O3",
            valid + "-N Z2R_Ab12Cd34Ef_O6",
            valid + "-N Z2R_Ab12Cd34Ef_O1",
            valid + "unexpected stdout noise",
            valid + "-P INPUT ACCEPT extra",
            valid + "-A Z2O_Ab12Cd34Ef -j Z2R_Ab12Cd34Ef_O1",
            valid + "-A Z2R_Ab12Cd34Ef_O1 -p tcp --dport 80 -j NFQUEUE --queue-num 200 --queue-bypass",
        )

        cases.forEach { assertFalse(verify(it).topologyVerified) }
    }

    @Test
    fun topology_rejectsWrongTagSideOrdinalAndDirectMainPayload() {
        val cases = listOf(
            validTopology().map { it.replace("Z2R_Ab12Cd34Ef_O3", "Z2R_Zz98Yy76Xx_O3") },
            validTopology().map { it.replace("Z2R_Ab12Cd34Ef_O3", "Z2R_Ab12Cd34Ef_I3") },
            validTopology().map { it.replace("Z2R_Ab12Cd34Ef_O3", "Z2R_Ab12Cd34Ef_O6") },
            validTopology() + "-A Z2O_Ab12Cd34Ef -j NFQUEUE --queue-num 200 --queue-bypass",
        )

        cases.forEach { assertFalse(verify(it).topologyVerified) }
    }

    @Test
    fun topology_ignoresDetachedFailedGenerationButRejectsAnyCrossGenerationAnchor() {
        val detached = listOf(
            "-N Z2O_Zz98Yy76Xx",
            "-N Z2I_Zz98Yy76Xx",
            "-N Z2R_Zz98Yy76Xx_O1",
            "-A Z2O_Zz98Yy76Xx -j Z2R_Zz98Yy76Xx_O1",
            "-A Z2R_Zz98Yy76Xx_O1 -p tcp --dport 443 -j NFQUEUE --queue-num 200 --queue-bypass",
        )
        assertTrue(verify(validTopology() + detached).topologyVerified)
        assertFalse(
            verify(validTopology() + detached + "-A OUTPUT -j Z2O_Zz98Yy76Xx").topologyVerified,
        )
    }

    @Test
    fun topology_rejectsWrongOrDuplicateQueueOptions() {
        val cases = listOf(
            validTopology().map { it.replace("--queue-num 200", "--queue-num 201") },
            validTopology().map { it.replace("--queue-bypass", "") },
            validTopology().map { it.replace("--queue-bypass", "--queue-bypass --queue-bypass") },
            validTopology().map { it.replace("-j NFQUEUE", "-j NFQUEUE -j NFQUEUE") },
        )

        cases.forEach { assertFalse(verify(it).topologyVerified) }
    }

    @Test
    fun topology_rejectsProtocolPortDirectionAndUnknownMatchDrift() {
        val valid = validTopology()
        val cases = listOf(
            valid.map { it.replace("-p tcp --dport 80", "-p udp --dport 80") },
            valid.map { it.replace("-p tcp --dport 80", "-p tcp --sport 80") },
            valid.map { it.replace("-p tcp --dport 80", "-p tcp --dport 81") },
            valid.map { it.replace("-p tcp --dport 80", "-p tcp -m comment --dport 80") },
        )

        cases.forEach { assertFalse(verify(it).topologyVerified) }
    }

    @Test
    fun topology_bindsMultiportConnbytesAndMarkPayloads() {
        val capableContract = contract.copy(
            portsTcp = "80,443",
            portsUdp = "443,3478,5349,19302",
            connbytes = true,
            multiport = true,
            markCapability = true,
            expectedRuleCount = 4,
        )
        val valid = capableTopology()
        assertTrue(verify(valid, capableContract).topologyVerified)

        val cases = listOf(
            valid.map { it.replace("--connbytes 1:20", "--connbytes 1:19") },
            valid.map { it.replace("--connbytes-dir original", "--connbytes-dir reply") },
            valid.map { it.replace("--dports 80,443", "--dports 80") },
            valid.map { it.replace(" -m multiport", "") },
            valid.map {
                it.replace(
                    " -m mark ! --mark 0x40000000/0x40000000",
                    " -m mark ! --mark 0x40000001/0x40000000",
                )
            },
            valid.map { it.replace(" -m mark ! --mark 0x40000000/0x40000000", "") },
        )
        cases.forEach { assertFalse(verify(it, capableContract).topologyVerified) }
    }

    @Test
    fun topology_rejectsOwnerCardinalityThatDoesNotMatchItsPayloadSpec() {
        assertFalse(verify(validTopology(), contract.copy(expectedRuleCount = 4)).topologyVerified)
    }

    @Test
    fun topology_neverTreatsLegacyV5ChainsAsActive() {
        val legacy = listOf(
            "-N ZAPRET2_OUT",
            "-N ZAPRET2_IN",
            "-A OUTPUT -j ZAPRET2_OUT",
            "-A INPUT -j ZAPRET2_IN",
            "-A ZAPRET2_OUT -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A ZAPRET2_IN -j NFQUEUE --queue-num 200 --queue-bypass",
        )

        val result = verify(legacy)
        assertFalse(result.topologyVerified)
        assertTrue(result.hasState)
        assertEquals(0, result.nfqueueRulesCount)
    }

    @Test
    fun topology_acceptsAbsentInactiveFamilyButRejectsOwnedResidue() {
        val inactive = contract.copy(active = false, expectedRuleCount = 0)
        assertTrue(verify(emptyList(), inactive).topologyVerified)
        assertFalse(verify(validTopology(), inactive).topologyVerified)
    }

    private fun verify(
        lines: List<String>,
        expected: OwnedFirewallFamilyContract = contract,
    ): OwnedIptablesFamily = OwnedIptablesTopologyVerifier.verify(lines, expected)

    private fun validTopology(): List<String> = topology(
        listOf(
            "-A Z2R_Ab12Cd34Ef_O1 -p tcp --dport 80 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_O2 -p udp --dport 443 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_O3 -p udp --dport 3478 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_O4 -p udp --dport 5349 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_O5 -p udp --dport 19302 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_I1 -p tcp --sport 80 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_I2 -p udp --sport 443 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_I3 -p udp --sport 3478 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_I4 -p udp --sport 5349 -j NFQUEUE --queue-num 200 --queue-bypass",
            "-A Z2R_Ab12Cd34Ef_I5 -p udp --sport 19302 -j NFQUEUE --queue-num 200 --queue-bypass",
        ),
    )

    private fun capableTopology(): List<String> = topology(
        listOf(
            capablePayload("O1", "tcp", "--dports", "80,443", 20, "original"),
            capablePayload("O2", "udp", "--dports", "443,3478,5349,19302", 20, "original"),
            capablePayload("I1", "tcp", "--sports", "80,443", 10, "reply"),
            capablePayload("I2", "udp", "--sports", "443,3478,5349,19302", 10, "reply"),
        ),
    )

    private fun capablePayload(
        sideAndOrdinal: String,
        protocol: String,
        portOption: String,
        ports: String,
        packetCount: Int,
        connbytesDirection: String,
    ): String = "-A Z2R_Ab12Cd34Ef_$sideAndOrdinal -p $protocol -m $protocol " +
        "-m multiport $portOption $ports -m connbytes --connbytes 1:$packetCount " +
        "--connbytes-dir $connbytesDirection --connbytes-mode packets " +
        "-m mark ! --mark 0x40000000/0x40000000 " +
        "-j NFQUEUE --queue-num 200 --queue-bypass"

    private fun topology(payloads: List<String>): List<String> = buildList {
        val perSide = payloads.size / 2
        add("-P INPUT ACCEPT")
        add("-P OUTPUT ACCEPT")
        add("-N Z2O_Ab12Cd34Ef")
        add("-N Z2I_Ab12Cd34Ef")
        (1..perSide).forEach { add("-N Z2R_Ab12Cd34Ef_O$it") }
        (1..perSide).forEach { add("-N Z2R_Ab12Cd34Ef_I$it") }
        add("-A OUTPUT -j Z2O_Ab12Cd34Ef")
        add("-A INPUT -j Z2I_Ab12Cd34Ef")
        (1..perSide).forEach { add("-A Z2O_Ab12Cd34Ef -j Z2R_Ab12Cd34Ef_O$it") }
        (1..perSide).forEach { add("-A Z2I_Ab12Cd34Ef -j Z2R_Ab12Cd34Ef_I$it") }
        addAll(payloads)
    }
}
