package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatsManagerTest {

    @Test
    fun uiFirewallDetail_isPureProjectionOfMachineStatusContract() {
        val status = ServiceLifecycleController.ServiceStatus(
            rootGranted = true,
            processRunning = true,
            pid = "18479",
            nfqueueRulesCount = 4,
            iptablesActive = true,
            hasOwnedState = true,
            declaredStatus = "ok",
            pidVerified = true,
            pidStarttime = "20218",
            ownerGeneration = "generation-1",
            qnum = 200,
            ipv4Active = true,
            ipv6Active = true,
            expectedRulesCount = 4,
            ipv4RulesCount = 2,
            ipv6RulesCount = 2,
            chainsCount = 2,
            anchorsCount = 2,
            nfqueueSupported = true,
            queueBypassSupported = true,
            rulesetVerified = true,
            ownerMetadataVerified = true,
            metadataComplete = true,
        )

        val detail = projectIptablesDetail(status)

        assertEquals(4, detail.rulesOk)
        assertEquals(0, detail.rulesFail)
        assertEquals(4, detail.rulesTotal)
        assertEquals(2, detail.chains)
        assertEquals(2, detail.anchors)
        assertTrue(detail.ipv4Active)
        assertTrue(detail.ipv6Active)
        assertTrue(detail.rulesetVerified)
        assertTrue(detail.metadataComplete)
    }

    @Test
    fun connbytesFallbackCountsRemainAuthoritativeWithoutAppTopologyInference() {
        val status = ServiceLifecycleController.ServiceStatus(
            rootGranted = true,
            processRunning = true,
            nfqueueRulesCount = 2,
            iptablesActive = true,
            hasOwnedState = true,
            declaredStatus = "ok",
            pidVerified = true,
            pid = "4242",
            pidStarttime = "98765",
            ownerGeneration = "fallback-generation",
            qnum = 200,
            ipv4Active = true,
            expectedRulesCount = 2,
            ipv4RulesCount = 2,
            chainsCount = 1,
            anchorsCount = 1,
            nfqueueSupported = true,
            queueBypassSupported = true,
            rulesetVerified = true,
            ownerMetadataVerified = true,
            metadataComplete = true,
        )

        val detail = projectIptablesDetail(status)

        assertEquals(2, detail.rulesTotal)
        assertEquals(1, detail.chains)
        assertEquals(1, detail.anchors)
        assertTrue(status.healthy)
    }
}
