package com.zapret2.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleControllerTest {

    @Test
    fun boundedCommandGate_timesOutQueuedCallerAndRecoversAfterOwnerFinishes() = runBlocking {
        val gate = BoundedCommandGate(queueTimeoutMillis = 100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            gate.run(onTimeout = { error("holder unexpectedly timed out") }) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val queued = gate.run(onTimeout = { "timeout" }) { "acquired" }
        assertEquals("timeout", queued)

        release.complete(Unit)
        holder.join()
        assertEquals("acquired", gate.run(onTimeout = { "timeout" }) { "acquired" })
    }

    @Test
    fun rootAccessClassifier_distinguishesGrantedDeniedMissingTimeoutAndShellFailure() {
        val granted = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(success = true),
            uid = "0",
        )
        val deniedByUid = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(success = true),
            uid = "2000",
        )
        val deniedByManager = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                stderr = listOf("Permission denied by root manager"),
            ),
            uid = null,
        )
        val missingManager = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                stderr = listOf("su: not found"),
            ),
            uid = null,
        )
        val timedOut = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                error = "Root request timed out",
            ),
            uid = null,
        )
        val shellFailure = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                error = "Root transport disconnected",
            ),
            uid = null,
        )
        val deniedWithoutShellDiagnostic = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                error = "Root command exited unsuccessfully",
            ),
            uid = null,
            appGrantedRoot = false,
        )
        val failedGrantedShell = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                error = "Root shell disconnected",
            ),
            uid = null,
            appGrantedRoot = true,
        )
        val malformedUid = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(success = true),
            uid = "root",
        )
        val busy = ServiceLifecycleController.classifyRootAccess(
            ServiceLifecycleController.CommandResult(
                success = false,
                lifecycleError = LifecycleErrorContract.rootQueueBusy,
            ),
            uid = null,
        )

        assertEquals(ServiceLifecycleController.RootAccessState.GRANTED, granted.state)
        assertEquals(ServiceLifecycleController.RootAccessState.DENIED, deniedByUid.state)
        assertEquals(ServiceLifecycleController.RootAccessState.DENIED, deniedByManager.state)
        assertEquals(
            ServiceLifecycleController.RootAccessState.MANAGER_UNAVAILABLE,
            missingManager.state,
        )
        assertEquals(ServiceLifecycleController.RootAccessState.TIMEOUT, timedOut.state)
        assertEquals(LifecycleErrorContract.ROOT_COMMAND_TIMEOUT, timedOut.lifecycleError?.code)
        assertEquals(ServiceLifecycleController.RootAccessState.SHELL_FAILURE, shellFailure.state)
        assertEquals(LifecycleErrorContract.ROOT_SHELL_FAILED, shellFailure.lifecycleError?.code)
        assertEquals(
            ServiceLifecycleController.RootAccessState.DENIED,
            deniedWithoutShellDiagnostic.state,
        )
        assertEquals(
            "Root access was not granted by the root manager",
            deniedWithoutShellDiagnostic.error,
        )
        assertEquals(LifecycleErrorContract.ROOT_DENIED, deniedWithoutShellDiagnostic.lifecycleError?.code)
        assertEquals(
            ServiceLifecycleController.RootAccessState.SHELL_FAILURE,
            failedGrantedShell.state,
        )
        assertEquals(ServiceLifecycleController.RootAccessState.SHELL_FAILURE, malformedUid.state)
        assertEquals(ServiceLifecycleController.RootAccessState.BUSY, busy.state)
        assertEquals(LifecycleErrorContract.ROOT_COMMAND_QUEUE_BUSY, busy.lifecycleError?.code)
    }

    @Test
    fun statusCommandResult_requiresExactPayloadExitCodeMapping() {
        val healthy = ServiceLifecycleController.parseStatusCommandResult(
            ServiceLifecycleController.CommandResult(
                success = true,
                stdout = healthyStatusLines(),
                exitCode = 0,
            )
        )
        val stopped = ServiceLifecycleController.parseStatusCommandResult(
            ServiceLifecycleController.CommandResult(
                success = false,
                stdout = stoppedStatusLines(),
                exitCode = 1,
            )
        )
        val mismatched = ServiceLifecycleController.parseStatusCommandResult(
            ServiceLifecycleController.CommandResult(
                success = true,
                stdout = stoppedStatusLines(),
                exitCode = 0,
            )
        )

        assertTrue(healthy.healthy)
        assertTrue(stopped.fullyStopped)
        assertFalse(mismatched.metadataComplete)
        assertEquals("unknown", mismatched.declaredStatus)
        assertEquals(0, ServiceLifecycleController.statusExitCode("ok"))
        assertEquals(1, ServiceLifecycleController.statusExitCode("stopped"))
        assertEquals(2, ServiceLifecycleController.statusExitCode("degraded"))
    }

    @Test
    fun ownerStateSchema_acceptsOnlyCurrentVersionSeven() {
        assertEquals(7, OwnerStateSchema.VERSION)
        assertTrue(OwnerStateSchema.accepts("7"))
        assertFalse(OwnerStateSchema.accepts("6"))
        assertFalse(OwnerStateSchema.accepts("5"))
        assertFalse(OwnerStateSchema.accepts("4"))
        assertFalse(OwnerStateSchema.accepts("3"))
        assertFalse(OwnerStateSchema.accepts("1"))
        assertFalse(OwnerStateSchema.accepts("03"))
        assertEquals(33, OwnerStateSchema.fields.size)
        assertEquals("argv_sha256", OwnerStateSchema.currentFields[3])
        assertEquals("boot_id", OwnerStateSchema.currentFields[7])
        assertEquals(listOf("firewall_tag", "out_chain", "in_chain"), OwnerStateSchema.currentFields.subList(11, 14))
        assertTrue("install_generation" in OwnerStateSchema.fields)
        assertTrue("firewall_fingerprint" in OwnerStateSchema.fields)
    }

    @Test
    fun parseStatusOutput_acceptsOnlyCompleteVerifiedHealthyState() {
        val status = ServiceLifecycleController.parseStatusOutput(healthyStatusLines())

        assertTrue(status.healthy)
        assertFalse(status.fullyStopped)
        assertTrue(status.metadataComplete)
        assertTrue(status.hasOwnedState)
        assertEquals("4242", status.pid)
        assertEquals(3, status.nfqueueRulesCount)
        assertEquals(200, status.qnum)
        assertNull(status.error)
    }

    @Test
    fun parseStatusOutput_acceptsVersionThreeAndCarriesOpaqueModuleError() {
        val lines = stoppedStatusLines().toMutableList().apply {
            add(0, "Z2_PROTOCOL=3")
            add(lastIndex, "Z2_ERROR_SCHEMA=1")
            add(lastIndex, "Z2_ERROR_STATUS=ERROR")
            add(lastIndex, "Z2_ERROR_DOMAIN=FIREWALL")
            add(lastIndex, "Z2_ERROR_STAGE=START_IPV4_BUILD_RULE")
            add(lastIndex, "Z2_ERROR_CODE=FUTURE_FIREWALL_FAILURE")
            add(lastIndex, "Z2_ERROR_DETAIL=iptables rejected the future rule")
        }

        val status = ServiceLifecycleController.parseStatusOutput(lines)

        assertTrue(status.metadataComplete)
        assertTrue(status.fullyStopped)
        assertEquals("FIREWALL", status.lifecycleError?.domain)
        assertEquals("FUTURE_FIREWALL_FAILURE", status.lifecycleError?.code)
        assertEquals("START_IPV4_BUILD_RULE", status.lifecycleError?.stage)
        assertEquals("iptables rejected the future rule", status.lifecycleError?.detail)
    }

    @Test
    fun parseStatusOutput_rejectsPartialButAcceptsUnknownVersionThreeIdentity() {
        val valid = stoppedStatusLines().toMutableList().apply {
            add(0, "Z2_PROTOCOL=3")
            add(lastIndex, "Z2_ERROR_SCHEMA=1")
            add(lastIndex, "Z2_ERROR_STATUS=OK")
            add(lastIndex, "Z2_ERROR_DOMAIN=NONE")
            add(lastIndex, "Z2_ERROR_STAGE=NONE")
            add(lastIndex, "Z2_ERROR_CODE=NONE")
            add(lastIndex, "Z2_ERROR_DETAIL=")
        }
        val partial = valid.filterNot { it.startsWith("Z2_ERROR_STAGE=") }
        val unknown = valid.map {
            when {
                it == "Z2_ERROR_STATUS=OK" -> "Z2_ERROR_STATUS=ERROR"
                it == "Z2_ERROR_DOMAIN=NONE" -> "Z2_ERROR_DOMAIN=FUTURE"
                it == "Z2_ERROR_STAGE=NONE" -> "Z2_ERROR_STAGE=FUTURE_STAGE"
                it == "Z2_ERROR_CODE=NONE" -> "Z2_ERROR_CODE=FUTURE_FAILURE"
                it == "Z2_ERROR_DETAIL=" -> "Z2_ERROR_DETAIL=future detail"
                else -> it
            }
        }

        assertFalse(ServiceLifecycleController.parseStatusOutput(partial).metadataComplete)
        assertTrue(ServiceLifecycleController.parseStatusOutput(unknown).metadataComplete)
        assertEquals(
            "FUTURE_FAILURE",
            ServiceLifecycleController.parseStatusOutput(unknown).lifecycleError?.code,
        )
    }

    @Test
    fun parseStatusOutput_rejectsApparentlyRunningStatusWithMissingMetadata() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().filterNot { it.startsWith("Z2_OWNER_METADATA_VERIFIED=") },
        )

        assertTrue(status.processRunning)
        assertFalse(status.metadataComplete)
        assertFalse(status.healthy)
    }

    @Test
    fun parseStatusOutput_rejectsRuleCountMismatchAndInvalidPid() {
        val lines = healthyStatusLines().map { line ->
            when {
                line.startsWith("Z2_PID=") -> "Z2_PID=not-a-pid"
                line.startsWith("Z2_RULES=") -> "Z2_RULES=2"
                else -> line
            }
        }
        val status = ServiceLifecycleController.parseStatusOutput(lines)

        assertEquals("", status.pid)
        assertFalse(status.processRunning)
        assertEquals(2, status.nfqueueRulesCount)
        assertFalse(status.healthy)
    }

    @Test
    fun parseStatusOutput_identifiesCleanStoppedState() {
        val status = ServiceLifecycleController.parseStatusOutput(
            stoppedStatusLines(),
        )

        assertTrue(status.fullyStopped)
        assertFalse(status.healthy)
        assertFalse(status.hasOwnedState)
    }

    @Test
    fun parseStatusOutput_neverTreatsTruncatedStoppedRecordAsFullyStopped() {
        val status = ServiceLifecycleController.parseStatusOutput(
            stoppedStatusLines().filterNot { it.startsWith("Z2_OWNER_GENERATION=") },
        )

        assertFalse(status.metadataComplete)
        assertFalse(status.fullyStopped)
        assertTrue(status.hasOwnedState)
        assertEquals("unknown", status.declaredStatus)
    }

    @Test
    fun parseStatusOutput_rejectsRecordMissingRequiredTerminalField() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().dropLast(1),
        )

        assertFalse(status.metadataComplete)
        assertFalse(status.healthy)
        assertEquals("unknown", status.declaredStatus)
    }

    @Test
    fun parseStatusOutput_rejectsDuplicateOrNonTerminalProtocolFields() {
        val duplicate = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().toMutableList().apply { add(size - 1, "Z2_PID=4242") },
        )
        val nonTerminal = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines() + "Z2_STATUS=ok",
        )
        val duplicateCompletion = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines() + "Z2_COMPLETE=1",
        )
        assertFalse(duplicate.metadataComplete)
        assertFalse(nonTerminal.metadataComplete)
        assertFalse(duplicateCompletion.metadataComplete)
    }

    @Test
    fun parseStatusOutput_rejectsUnknownMachineField() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().toMutableList().apply { add(size - 1, "Z2_FUTURE_FIELD=1") },
        )

        assertFalse(status.metadataComplete)
        assertFalse(status.healthy)
        assertEquals("unknown", status.declaredStatus)
    }

    @Test
    fun parseStatusOutput_exposesUpdateAndUninstallGatesFromRealRecord() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().map {
                when {
                    it.startsWith("Z2_STATUS=") -> "Z2_STATUS=degraded"
                    it.startsWith("Z2_ACTIVE=") -> "Z2_ACTIVE=0"
                    it.startsWith("Z2_UPDATE_BLOCKED=") -> "Z2_UPDATE_BLOCKED=1"
                    it.startsWith("Z2_UNINSTALL_TOMBSTONE=") -> "Z2_UNINSTALL_TOMBSTONE=1"
                    else -> it
                }
            },
        )

        assertTrue(status.metadataComplete)
        assertTrue(status.updateBlocked)
        assertTrue(status.uninstallTombstone)
        assertFalse(status.healthy)
    }

    @Test
    fun parseStatusOutput_acceptsValidDegradedMultipleOrphanShape() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().map { line ->
                when {
                    line.startsWith("Z2_STATUS=") -> "Z2_STATUS=degraded"
                    line.startsWith("Z2_ACTIVE=") -> "Z2_ACTIVE=0"
                    line.startsWith("Z2_PID=") -> "Z2_PID="
                    line.startsWith("Z2_PID_VERIFIED=") -> "Z2_PID_VERIFIED=0"
                    line.startsWith("Z2_PID_STARTTIME=") -> "Z2_PID_STARTTIME="
                    line.startsWith("Z2_OWNER_GENERATION=") -> "Z2_OWNER_GENERATION="
                    line.startsWith("Z2_OWNER_METADATA_VERIFIED=") -> "Z2_OWNER_METADATA_VERIFIED=0"
                    line.startsWith("Z2_RULESET_VERIFIED=") -> "Z2_RULESET_VERIFIED=0"
                    else -> line
                }
            },
        )

        assertTrue(status.metadataComplete)
        assertTrue(status.processRunning)
        assertEquals("", status.pid)
        assertFalse(status.healthy)
    }

    @Test
    fun parseStatusOutput_enforcesQueueNumberBoundaries() {
        listOf("1", "65535").forEach { value ->
            val status = ServiceLifecycleController.parseStatusOutput(
                healthyStatusLines().map { if (it.startsWith("Z2_QNUM=")) "Z2_QNUM=$value" else it },
            )
            assertEquals(value.toInt(), status.qnum)
            assertTrue(status.healthy)
        }

        listOf("0", "-1", "65536", "1.0", "999999999999999999999").forEach { value ->
            val status = ServiceLifecycleController.parseStatusOutput(
                healthyStatusLines().map { if (it.startsWith("Z2_QNUM=")) "Z2_QNUM=$value" else it },
            )
            assertNull(status.qnum)
            assertFalse(status.healthy)
        }
    }

    @Test
    fun parseStatusOutput_acceptsCanonicalNonnegative64BitStartTicks() {
        listOf("0", "2147483648", "9223372036854775807").forEach { ticks ->
            val status = ServiceLifecycleController.parseStatusOutput(
                healthyStatusLines().map {
                    if (it.startsWith("Z2_PID_STARTTIME=")) "Z2_PID_STARTTIME=$ticks" else it
                },
            )
            assertTrue("start ticks $ticks must remain valid", status.metadataComplete)
            assertEquals(ticks, status.pidStarttime)
            assertTrue(status.healthy)
        }

        listOf("-1", "+1", "01", "9223372036854775808", "18446744073709551615").forEach { ticks ->
            val status = ServiceLifecycleController.parseStatusOutput(
                healthyStatusLines().map {
                    if (it.startsWith("Z2_PID_STARTTIME=")) "Z2_PID_STARTTIME=$ticks" else it
                },
            )
            assertFalse("start ticks $ticks must fail closed", status.metadataComplete)
            assertFalse(status.healthy)
        }
    }

    @Test
    fun parseStatusOutput_rejectsNegativeCountsAndUnknownStatusValue() {
        val status = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().map { line ->
                when {
                    line.startsWith("Z2_STATUS=") -> "Z2_STATUS=error=iptables"
                    line.startsWith("Z2_RULES=") -> "Z2_RULES=-7"
                    line.startsWith("Z2_EXPECTED_RULES=") -> "Z2_EXPECTED_RULES=-2"
                    line.startsWith("Z2_IPV4_RULES=") -> "Z2_IPV4_RULES=-1"
                    line.startsWith("Z2_IPV6_RULES=") -> "Z2_IPV6_RULES=-3"
                    else -> line
                }
            },
        )

        assertEquals("unknown", status.declaredStatus)
        assertEquals(0, status.nfqueueRulesCount)
        assertEquals(0, status.expectedRulesCount)
        assertEquals(0, status.ipv4RulesCount)
        assertEquals(0, status.ipv6RulesCount)
        assertTrue(status.hasOwnedState)
        assertFalse(status.metadataComplete)
    }

    @Test
    fun parseStatusOutput_rejectsEveryNonCanonicalBooleanEncoding() {
        val booleanFields = listOf(
            "Z2_OWNED", "Z2_PROCESS", "Z2_ACTIVE", "Z2_PID_VERIFIED",
            "Z2_OWNER_METADATA_VERIFIED", "Z2_IPV4", "Z2_IPV6", "Z2_RULESET_VERIFIED",
            "Z2_NFQUEUE", "Z2_QUEUE_BYPASS", "Z2_UPDATE_BLOCKED", "Z2_UNINSTALL_TOMBSTONE",
            "Z2_COMPLETE",
        )
        booleanFields.forEach { field ->
            listOf("2", "true", "", "01").forEach { invalid ->
                val status = ServiceLifecycleController.parseStatusOutput(
                    healthyStatusLines().map { if (it.startsWith("$field=")) "$field=$invalid" else it },
                )
                assertFalse("$field=$invalid must fail closed", status.metadataComplete)
                assertFalse("$field=$invalid must not authorize healthy state", status.healthy)
            }
        }
    }

    @Test
    fun parseStatusOutput_rejectsNegativeNonCanonicalAndOverflowingCounts() {
        val integerFields = listOf(
            "Z2_RULES", "Z2_EXPECTED_RULES", "Z2_IPV4_RULES", "Z2_IPV6_RULES",
        )
        integerFields.forEach { field ->
            listOf("-1", "+1", "01", "2147483648", "999999999999999999999", "").forEach { invalid ->
                val status = ServiceLifecycleController.parseStatusOutput(
                    healthyStatusLines().map { if (it.startsWith("$field=")) "$field=$invalid" else it },
                )
                assertFalse("$field=$invalid must fail closed", status.metadataComplete)
                assertEquals("unknown", status.declaredStatus)
            }
        }
    }

    @Test
    fun parseStatusOutput_rejectsCrossContractContradictions() {
        val contradictoryRecords = listOf(
            mapOf("Z2_STATUS" to "stopped"),
            mapOf("Z2_OWNED" to "0"),
            mapOf("Z2_PROCESS" to "0"),
            mapOf("Z2_PID_VERIFIED" to "0"),
            mapOf("Z2_OWNER_METADATA_VERIFIED" to "0"),
            mapOf("Z2_RULESET_VERIFIED" to "0"),
            mapOf("Z2_RULES" to "4"),
            mapOf("Z2_IPV4_RULES" to "3"),
            mapOf("Z2_UPDATE_BLOCKED" to "1"),
        )
        contradictoryRecords.forEach { overrides ->
            val status = ServiceLifecycleController.parseStatusOutput(
                healthyStatusLines().map { line ->
                    val key = line.substringBefore('=')
                    overrides[key]?.let { "$key=$it" } ?: line
                },
            )
            assertFalse("Contradiction $overrides must fail closed", status.metadataComplete)
            assertFalse(status.healthy)
        }
    }

    @Test
    fun parseStatusOutput_rejectsWhitespaceAndPrefixKeyInjection() {
        val whitespaceKey = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().map { if (it.startsWith("Z2_OWNED=")) "Z2_OWNED =1" else it },
        )
        val prefixedKey = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().toMutableList().apply { add(size - 1, "Z2_OWNED_EXTRA=1") },
        )
        val whitespaceValue = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines().map { if (it.startsWith("Z2_OWNED=")) "Z2_OWNED=1 " else it },
        )
        val leadingWhitespaceInjection = ServiceLifecycleController.parseStatusOutput(
            healthyStatusLines() + " Z2_OWNED=0",
        )

        assertFalse(whitespaceKey.metadataComplete)
        assertFalse(prefixedKey.metadataComplete)
        assertFalse(whitespaceValue.metadataComplete)
        assertFalse(leadingWhitespaceInjection.metadataComplete)
    }

    @Test
    fun commandDiagnostics_collectsUsefulUniqueMessagesOnly() {
        val result = ServiceLifecycleController.CommandResult(
            success = false,
            stdout = listOf("ordinary output", "DIAGNOSTIC: queue unavailable", "DIAGNOSTIC: queue unavailable"),
            stderr = listOf("", "iptables failed"),
            error = "root command failed",
        )

        assertEquals(
            "root command failed\niptables failed\nqueue unavailable",
            result.diagnosticText(),
        )
    }

    @Test
    fun commandDiagnostics_includeStableErrorCodeBeforeShellDetail() {
        val result = ServiceLifecycleController.CommandResult(
            success = false,
            stdout = listOf(
                "Z2_ERROR_CODE=FIREWALL_BUILD_FAILED",
                "ERROR: cannot build detached IPv4 chains; iptables BUILD_RULE failed: xtables lock",
            ),
            lifecycleError = LifecycleError(
                status = "ERROR",
                domain = "FIREWALL",
                stage = "START_IPV4_BUILD_RULE",
                code = "FIREWALL_BUILD_FAILED",
                detail = "iptables BUILD_RULE failed: xtables lock",
            ),
        )

        assertEquals(
            "schema=1\nstatus=ERROR\ndomain=FIREWALL\nstage=START_IPV4_BUILD_RULE\n" +
                "code=FIREWALL_BUILD_FAILED\ndetail=iptables BUILD_RULE failed: xtables lock\n" +
                "cannot build detached IPv4 chains; iptables BUILD_RULE failed: xtables lock",
            result.diagnosticText(),
        )
    }

    @Test
    fun parseFullRollbackOutput_acceptsExactCompleteProtocolAndPropagatesReboot() {
        val parsed = ServiceLifecycleController.parseFullRollbackOutput(fullRollbackLines())

        assertTrue(parsed is ServiceLifecycleController.FullRollbackParseResult.Valid)
        val report = (parsed as ServiceLifecycleController.FullRollbackParseResult.Valid).report
        assertEquals(ServiceLifecycleController.FullRollbackStatus.COMPLETE, report.status)
        assertTrue(report.satisfiesCompleteContract)
        assertTrue(report.rebootRequired)
        assertEquals("full rollback complete; reboot required", report.diagnostic)
    }

    @Test
    fun parseFullRollbackOutput_rejectsDuplicateMissingAndUnknownFields() {
        val duplicate = fullRollbackLines().toMutableList().apply {
            add(size - 1, "Z2_RB_PROCESS_CLEAN=1")
        }
        val missing = fullRollbackLines().filterNot { it.startsWith("Z2_RB_HOSTS_PRESERVED=") }
        val unknown = fullRollbackLines().toMutableList().apply {
            add(size - 1, "Z2_RB_FUTURE=1")
            removeAt(indexOfFirst { it.startsWith("Z2_RB_HOSTS_PRESERVED=") })
        }

        listOf(duplicate, missing, unknown).forEach { lines ->
            assertTrue(
                ServiceLifecycleController.parseFullRollbackOutput(lines) is
                    ServiceLifecycleController.FullRollbackParseResult.Invalid,
            )
        }
    }

    @Test
    fun parseFullRollbackOutput_requiresCompleteOneAsFinalField() {
        val nonTerminal = fullRollbackLines().toMutableList().apply {
            add(0, removeAt(lastIndex))
        }
        val badSentinel = fullRollbackLines().dropLast(1) + "Z2_RB_COMPLETE=0"

        assertTrue(
            ServiceLifecycleController.parseFullRollbackOutput(nonTerminal) is
                ServiceLifecycleController.FullRollbackParseResult.Invalid,
        )
        assertTrue(
            ServiceLifecycleController.parseFullRollbackOutput(badSentinel) is
                ServiceLifecycleController.FullRollbackParseResult.Invalid,
        )
    }

    @Test
    fun parseFullRollbackOutput_rejectsBadBooleanAndUnknownStatus() {
        val badBoolean = fullRollbackLines(
            overrides = mapOf("Z2_RB_PROCESS_CLEAN" to "true"),
        )
        val badStatus = fullRollbackLines(status = "success")

        assertTrue(
            ServiceLifecycleController.parseFullRollbackOutput(badBoolean) is
                ServiceLifecycleController.FullRollbackParseResult.Invalid,
        )
        assertTrue(
            ServiceLifecycleController.parseFullRollbackOutput(badStatus) is
                ServiceLifecycleController.FullRollbackParseResult.Invalid,
        )
    }

    @Test
    fun parseFullRollbackOutput_keepsPartialBlockedAndErrorTyped() {
        listOf(
            "partial" to ServiceLifecycleController.FullRollbackStatus.PARTIAL,
            "blocked" to ServiceLifecycleController.FullRollbackStatus.BLOCKED,
            "error" to ServiceLifecycleController.FullRollbackStatus.ERROR,
        ).forEach { (wireStatus, expected) ->
            val parsed = ServiceLifecycleController.parseFullRollbackOutput(
                fullRollbackLines(
                    status = wireStatus,
                    overrides = mapOf(
                        "Z2_RB_PROCESS_CLEAN" to "0",
                        "Z2_RB_REBOOT_REQUIRED" to if (wireStatus == "blocked") "0" else "1",
                    ),
                ),
            ) as ServiceLifecycleController.FullRollbackParseResult.Valid

            assertEquals(expected, parsed.report.status)
            assertEquals(wireStatus != "blocked", parsed.report.rebootRequired)
            assertFalse(parsed.report.satisfiesCompleteContract)
        }
    }

    @Test
    fun parseFullRollbackOutput_completeStatusFailsClosedOnInconsistentInvariants() {
        listOf(
            "Z2_RB_PROCESS_CLEAN",
            "Z2_RB_FIREWALL_CLEAN",
            "Z2_RB_ROLLBACK_ARMED",
            "Z2_RB_HOSTS_PRESERVED",
            "Z2_RB_REBOOT_REQUIRED",
            "Z2_RB_USER_DATA_PRESERVED",
        ).forEach { field ->
            val parsed = ServiceLifecycleController.parseFullRollbackOutput(
                fullRollbackLines(overrides = mapOf(field to "0")),
            ) as ServiceLifecycleController.FullRollbackParseResult.Valid
            assertFalse(field, parsed.report.satisfiesCompleteContract)
        }
        val legacyAmbiguous = ServiceLifecycleController.parseFullRollbackOutput(
            fullRollbackLines(overrides = mapOf("Z2_RB_LEGACY_AMBIGUOUS" to "1")),
        ) as ServiceLifecycleController.FullRollbackParseResult.Valid
        assertFalse(legacyAmbiguous.report.satisfiesCompleteContract)
    }

    private fun fullRollbackLines(
        status: String = "complete",
        overrides: Map<String, String> = emptyMap(),
    ): List<String> {
        val values = linkedMapOf(
            "Z2_RB_STATUS" to status,
            "Z2_RB_PROCESS_CLEAN" to "1",
            "Z2_RB_FIREWALL_CLEAN" to "1",
            "Z2_RB_ROLLBACK_ARMED" to "1",
            "Z2_RB_HOSTS_PRESERVED" to "1",
            "Z2_RB_REBOOT_REQUIRED" to "1",
            "Z2_RB_USER_DATA_PRESERVED" to "1",
            "Z2_RB_LEGACY_AMBIGUOUS" to "0",
            "Z2_RB_DIAGNOSTIC" to "full rollback complete; reboot required",
            "Z2_RB_COMPLETE" to "1",
        )
        overrides.forEach { (key, value) -> values[key] = value }
        return values.map { (key, value) -> "$key=$value" }
    }

    private fun healthyStatusLines(): List<String> = listOf(
        "Z2_STATUS=ok",
        "Z2_OWNED=1",
        "Z2_PROCESS=1",
        "Z2_ACTIVE=1",
        "Z2_PID=4242",
        "Z2_PID_VERIFIED=1",
        "Z2_PID_STARTTIME=98765",
        "Z2_OWNER_GENERATION=generation-1",
        "Z2_OWNER_METADATA_VERIFIED=1",
        "Z2_QNUM=200",
        "Z2_IPV4=1",
        "Z2_IPV6=1",
        "Z2_RULES=3",
        "Z2_EXPECTED_RULES=3",
        "Z2_IPV4_RULES=2",
        "Z2_IPV6_RULES=1",
        "Z2_RULESET_VERIFIED=1",
        "Z2_NFQUEUE=1",
        "Z2_QUEUE_BYPASS=1",
        "Z2_UPDATE_BLOCKED=0",
        "Z2_UNINSTALL_TOMBSTONE=0",
        "Z2_COMPLETE=1",
    )

    private fun stoppedStatusLines(): List<String> = listOf(
        "Z2_STATUS=stopped",
        "Z2_OWNED=0",
        "Z2_PROCESS=0",
        "Z2_ACTIVE=0",
        "Z2_PID=",
        "Z2_PID_VERIFIED=0",
        "Z2_PID_STARTTIME=",
        "Z2_OWNER_GENERATION=",
        "Z2_OWNER_METADATA_VERIFIED=0",
        "Z2_QNUM=200",
        "Z2_IPV4=0",
        "Z2_IPV6=0",
        "Z2_RULES=0",
        "Z2_EXPECTED_RULES=0",
        "Z2_IPV4_RULES=0",
        "Z2_IPV6_RULES=0",
        "Z2_RULESET_VERIFIED=1",
        "Z2_NFQUEUE=0",
        "Z2_QUEUE_BYPASS=0",
        "Z2_UPDATE_BLOCKED=0",
        "Z2_UNINSTALL_TOMBSTONE=0",
        "Z2_COMPLETE=1",
    )
}
