package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedProtocolParityPolicyTest {

    @Test
    fun mandatoryRuntimeManifestMinimum_matchesTheShellVerifierExactly() {
        val shell = repositoryFile("zapret2/scripts/package-contract.sh").readText()
        val commandBuilder = repositoryFile("zapret2/scripts/command-builder.sh").readText()
        val requiredBlock = shell
            .substringAfter("    for required_entry in \\\n")
            .substringBefore("\n    do")
        val shellRequired = Regex("\\\"([^\\\"]+)\\\"")
            .findAll(requiredBlock)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(shellRequired.size, shellRequired.toSet().size)
        assertEquals(ModulePackageContract.mandatoryRuntimeManifestLines.toSet(), shellRequired.toSet())
        assertEquals(
            ModulePackageContract.MAX_RUNTIME_MANIFEST_BYTES.toString(),
            shellValue(shell, "PACKAGE_CONTRACT_MAX_MANIFEST_BYTES"),
        )
        assertEquals(
            ModulePackageContract.MAX_MODULE_PROP_BYTES.toString(),
            shellValue(shell, "PACKAGE_CONTRACT_MAX_MODULE_PROP_BYTES"),
        )
        assertEquals(
            ModulePackageContract.MAX_SHELL_EXEC_BYTES.toString(),
            shellValue(shell, "PACKAGE_CONTRACT_MAX_SHELL_EXEC_BYTES"),
        )
        assertEquals(
            ModulePackageContract.MAX_PRESERVED_COMMAND_LINE_BYTES.toString(),
            shellValue(shell, "PACKAGE_CONTRACT_MAX_CMDLINE_BYTES"),
        )
        assertEquals(
            ModulePackageContract.MAX_PRESERVED_COMMAND_LINE_BYTES.toString(),
            shellValue(commandBuilder, "CUSTOM_CMDLINE_MAX_BYTES"),
        )
        assertEquals(ModulePackageContract.MODULE_UPDATE_JSON, shellValue(shell, "PACKAGE_CONTRACT_UPDATE_JSON"))
        assertTrue(shell.contains("package_contract_validate_module_prop \"\$root\""))
        assertTrue(shell.contains("case \"\$file_mode\" in\n        600|644) ;;"))
        assertTrue(commandBuilder.contains("stat -c '%d:%i:%u:%a:%h:%s' \"\$file\""))
        assertTrue(commandBuilder.contains("[ \"\$uid\" = 0 ] && [ \"\$links\" = 1 ]"))
        assertTrue(commandBuilder.contains("[ \"\$after\" = \"\$before\" ]"))
        assertTrue(commandBuilder.contains("[ \"\$after_sha\" = \"\$before_sha\" ]"))
        assertTrue(commandBuilder.contains("Custom cmdline contains an empty file reference"))
        assertTrue(commandBuilder.contains("Custom cmdline contains a traversing file reference"))
        assertTrue(commandBuilder.contains("reference_root=\"\$ZAPRET_DIR/lua\""))
        assertTrue(commandBuilder.contains("reference_root=\"\$ZAPRET_DIR/lists\""))
        assertTrue(commandBuilder.contains("reference_root=\"\$ZAPRET_DIR/bin\""))
        assertTrue(commandBuilder.contains("Custom cmdline file reference is outside its approved root"))
        assertTrue(commandBuilder.contains("Lua reference must be a direct .lua file"))
        assertTrue(commandBuilder.contains("list reference must be a direct .txt file"))
        assertTrue(commandBuilder.contains("reference_child=\"\${resolved#\"\$reference_root\"/}\""))
        assertTrue(commandBuilder.contains("if ! is_readable_file \"\$resolved\"; then"))
        assertTrue(commandBuilder.contains("if [ \"\$COMMAND_BUILDER_CLI_MODE\" -eq 0 ]; then"))
        assertTrue(commandBuilder.contains("load_effective_core_config_readonly"))
        assertTrue(commandBuilder.contains("LOG_MODE=none"))
        assertTrue(commandBuilder.contains("Z2_CMDLINE_ERROR\\tRUNTIME_UNAVAILABLE"))
        assertTrue(commandBuilder.contains("Z2_CMDLINE_ERROR\\tBINARY_UNAVAILABLE"))
        assertTrue(commandBuilder.contains("Z2_CMDLINE_ERROR\\tINTEGRITY_UNAVAILABLE"))
        assertTrue(commandBuilder.contains("Z2_CMDLINE_ERROR\\tBUILD_UNAVAILABLE"))
        assertTrue(commandBuilder.contains("command -v sha256sum"))
        assertTrue(commandBuilder.contains("command -v awk"))
        assertTrue(commandBuilder.contains("[ \"\$nfqws_meta\" != 0:755:1 ]"))
        assertTrue(commandBuilder.contains("\"\$NFQWS2\" --dry-run \$built_options"))
    }

    @Test
    fun androidConstantsAndOwnerFields_matchTheShellWireContract() {
        val common = repositoryFile("zapret2/scripts/common.sh").readText()

        assertEquals(OwnerStateSchema.VERSION.toString(), shellValue(common, "OWNER_STATE_VERSION"))
        assertEquals(OwnerStateSchema.legacyV3Fields, shellValue(common, "OWNER_STATE_V3_FIELD_SEQUENCE").split('|'))
        assertEquals(OwnerStateSchema.legacyV4Fields, shellValue(common, "OWNER_STATE_V4_FIELD_SEQUENCE").split('|'))
        assertEquals(OwnerStateSchema.legacyV5Fields, shellValue(common, "OWNER_STATE_V5_FIELD_SEQUENCE").split('|'))
        assertEquals(OwnerStateSchema.legacyV6Fields, shellValue(common, "OWNER_STATE_V6_FIELD_SEQUENCE").split('|'))
        assertEquals(OwnerStateSchema.currentFields, shellValue(common, "OWNER_STATE_V7_FIELD_SEQUENCE").split('|'))
        assertEquals(33, OwnerStateSchema.currentFields.size)
        assertEquals(
            OwnerStateSchema.MAX_FILE_BYTES.toString(),
            shellValue(common, "OWNER_STATE_MAX_BYTES"),
        )
        assertEquals(
            OwnerStateSchema.MAX_CURRENT_FILE_BYTES.toString(),
            shellValue(common, "OWNER_STATE_CURRENT_MAX_BYTES"),
        )

        assertEquals(UpdateLockProtocol.VERSION, shellValue(common, "UPDATE_LOCK_VERSION"))
        assertEquals(UpdateLockProtocol.LEGACY_VERSION, shellValue(common, "UPDATE_LOCK_LEGACY_VERSION"))
        assertEquals(UpdateTransactionProtocol.TRANSACTION_VERSION, shellValue(common, "UPDATE_TRANSACTION_VERSION"))
        assertEquals(UpdateTransactionProtocol.LEGACY_TRANSACTION_VERSION, shellValue(common, "UPDATE_TRANSACTION_LEGACY_VERSION"))
        assertEquals(UpdateTransactionProtocol.CLEANUP_VERSION, shellValue(common, "UPDATE_CLEANUP_VERSION"))
        assertEquals(UpdateTransactionProtocol.LEGACY_CLEANUP_VERSION, shellValue(common, "UPDATE_CLEANUP_LEGACY_VERSION"))
    }

    @Test
    fun onlyCurrentBootBoundVersions_areWritable() {
        val updateManager = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val ownerWriter = repositoryFile("zapret2/scripts/common.sh").readText()

        assertTrue(updateManager.contains("UpdateTransactionProtocol.TRANSACTION_VERSION"))
        assertTrue(updateManager.contains("owner_boot_id"))
        assertTrue(ownerWriter.contains("printf 'version=%s\\n' \"\$OWNER_STATE_VERSION\""))
        assertTrue(ownerWriter.contains("printf 'firewall_tag=%s\\nout_chain=%s\\nin_chain=%s\\n'"))
    }

    @Test
    fun privilegedLifecycleArguments_areTypedAndIndividuallyShellQuoted() {
        listOf("UpdateManager.kt", "ModuleUpdateRecovery.kt").forEach { fileName ->
            val source = repositoryFile(
                "android-app/app/src/main/java/com/zapret2/app/data/$fileName"
            ).readText()

            assertTrue(source.contains("arguments: List<String> = emptyList()"))
            assertTrue(source.contains("RootFileIo.shellQuote(argument)"))
            assertTrue(!source.contains("arguments: String"))
            assertTrue(!source.contains("+ \" --machine\""))
        }
    }

    @Test
    fun combinedUpdate_preflightsBothArtifactsBeforeModuleMutation() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val updateStart = source.indexOf("internal suspend fun updateAll(")
        val moduleDownload = source.indexOf("preparedModule = result.file", updateStart)
        val apkDownload = source.indexOf("preparedApk = result.file", moduleDownload)
        val moduleValidation = source.indexOf("validateModuleArchive(moduleFile", apkDownload)
        val firstApkValidation = source.indexOf("validateApkArtifact(", moduleValidation)
        val handoffDeclaration = source.indexOf("suspend fun handoffPreparedApk(", firstApkValidation)
        val handoffApkValidation = source.indexOf("validateApkArtifact(", handoffDeclaration)
        val apkInstaller = source.indexOf("installApk(apkFile)", handoffApkValidation)
        val moduleInstall = source.indexOf("installModule(", apkInstaller)
        val committedHandoff = source.indexOf(
            "handoffPreparedApk(apkFile, committedModule = true)",
            moduleInstall,
        )

        assertTrue(updateStart >= 0)
        assertTrue(moduleDownload > updateStart && moduleDownload < apkDownload)
        assertTrue(apkDownload < moduleValidation)
        assertTrue(moduleValidation < firstApkValidation)
        assertTrue(firstApkValidation < handoffDeclaration)
        assertTrue(handoffDeclaration < handoffApkValidation)
        assertTrue(handoffApkValidation < apkInstaller)
        assertTrue(apkInstaller < moduleInstall)
        assertTrue(moduleInstall < committedHandoff)
    }

    @Test
    fun completedDownload_remainsCleanupOwnedUntilSuccessIsReturned() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val downloadStart = source.indexOf("private suspend fun downloadFile(")
        val publication = source.indexOf("finalizedFile = outputFile", downloadStart)
        val terminalProgress = source.indexOf("progress(100)", publication)
        val ownershipRelease = source.indexOf("finalizedFile = null", terminalProgress)
        val cleanup = source.indexOf("deleteFileBestEffort(finalizedFile)", ownershipRelease)

        assertTrue(downloadStart >= 0)
        assertTrue(publication > downloadStart)
        assertTrue(terminalProgress > publication)
        assertTrue(ownershipRelease > terminalProgress)
        assertTrue(cleanup > ownershipRelease)
    }

    @Test
    fun committedModuleUpdate_survivesPromptCancellationThroughApkHandoff() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val installStart = source.indexOf("suspend fun installModule(")
        val capturedResult = source.indexOf("var completedResult:", installStart)
        val standardCommit = source.indexOf("recordCommitted(result)", capturedResult)
        val hotCommit = source.indexOf("recordCommitted(Pair(true, false))", standardCommit)
        val cancellationDelivery = source.indexOf(
            "completedResult?.takeIf { it.first } ?: throw cancelled",
            capturedResult,
        )
        val updateStart = source.indexOf("internal suspend fun updateAll(")
        val moduleInstalled = source.indexOf("ModuleArtifactOutcome.Installed", updateStart)
        val nonCancellableFinish = source.indexOf("withContext(NonCancellable)", moduleInstalled)
        val committedHandoff = source.indexOf(
            "handoffPreparedApk(apkFile, committedModule = true)",
            nonCancellableFinish,
        )

        assertTrue(installStart >= 0)
        assertTrue(capturedResult > installStart)
        assertTrue(standardCommit > capturedResult)
        assertTrue(hotCommit > standardCommit)
        assertTrue(cancellationDelivery > capturedResult)
        assertTrue(moduleInstalled > updateStart)
        assertTrue(nonCancellableFinish > moduleInstalled)
        assertTrue(committedHandoff > nonCancellableFinish)
    }

    @Test
    fun standardModuleInstall_verifiesPublicationBeforeReportingCommit() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val lockedInstall = source.indexOf("private suspend fun installModuleLocked(")
        val pendingFence = source.indexOf("Z2_MODULE_LAYOUT=", lockedInstall)
        val cancellationFence = source.indexOf("CancellationSafeTerminalCommit.run(", pendingFence)
        val standardInstall = source.indexOf("magisk --install-module", cancellationFence)
        val publicationVerification = source.indexOf("verifyStandardModuleInstall(", standardInstall)
        val commit = source.indexOf("recordCommitted(result)", publicationVerification)
        val verifier = source.indexOf("private suspend fun verifyStandardModuleInstall(", commit)
        val generationBinding = source.indexOf("standardInstallPublicationMatches(", verifier)

        assertTrue(lockedInstall >= 0)
        assertTrue(pendingFence > lockedInstall)
        assertTrue(cancellationFence > pendingFence)
        assertTrue(standardInstall > cancellationFence)
        assertTrue(publicationVerification > standardInstall)
        assertTrue(commit > publicationVerification)
        assertTrue(verifier > commit)
        assertTrue(generationBinding > verifier)
        assertTrue(source.contains("ModulePackageContract.mandatoryRuntimeRegularFiles"))
        assertTrue(source.contains("ModulePackageContract.mandatoryRuntimeExecutables"))
        assertTrue(source.contains("ModulePackageContract.binaryRelativePath(binaryDirectory)"))
    }

    @Test
    fun hotUpdate_appliesAndVerifiesFullInstalledManifestModesBeforeCandidateReady() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val lockedInstall = source.indexOf("private suspend fun installModuleLocked(")
        val preparationGuard = source.indexOf("val prepareGuard = requireNotNull", lockedInstall)
        val candidatePreparation = source.indexOf("val prepareCommand = buildString", preparationGuard)
        val applyModes = source.indexOf("package_contract_apply_modes", candidatePreparation)
        val validateModes = source.indexOf("package_contract_validate_modes", applyModes)
        val executePreparation = source.indexOf("executeRoot(prepareCommand)", validateModes)
        val candidateReady = source.indexOf("phase = \"candidate_ready\"", executePreparation)

        assertTrue(lockedInstall >= 0)
        assertTrue(preparationGuard > lockedInstall)
        assertTrue(candidatePreparation > preparationGuard)
        assertTrue(applyModes > candidatePreparation)
        assertTrue(validateModes > applyModes)
        assertTrue(executePreparation > validateModes)
        assertTrue(candidateReady > executePreparation)
        val preparation = source.substring(preparationGuard, executePreparation)
        assertTrue(preparation.contains("buildOwnerTransactionGuard("))
        assertTrue(preparation.contains("freshWorkspacePredicate"))
        assertTrue(preparation.contains("siblingWorkspacePredicate"))
        assertTrue(preparation.contains("safeRootDirectoryPredicate(updateDir)"))
        assertTrue(preparation.contains("moduleIntegrityPredicate(updateDir"))
        assertTrue(preparation.contains("safeRootDirectoryOrAbsentPredicate(updateDir)"))
        assertFalse(preparation.contains("append(\"rm -rf \""))
    }

    @Test
    fun readyDiagnostics_requireInstalledBinaryAndExactCriticalModes() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ControlDiagnosticsRepository.kt"
        ).readText()

        assertTrue(source.contains("ModulePackageContract.mandatoryRuntimeExecutables"))
        assertTrue(source.contains("ModulePackageContract.mandatoryRuntimeRegularFiles"))
        assertTrue(source.contains("add(\"zapret2/nfqws2\")"))
        assertTrue(source.contains("z2_required\" 2>/dev/null)\" = 644"))
        assertTrue(source.contains("z2_script\" 2>/dev/null)\" = 755"))
        assertTrue(source.contains("ModulePackageContract.PACKAGE_CONTRACT_SCRIPT_PATH"))
        assertTrue(source.contains("package_contract_validate_runtime_selection"))
        assertTrue(source.contains("ModulePackageContract.COMMAND_BUILDER_SCRIPT_PATH"))
        assertTrue(source.contains("--validate-categories-machine"))
        assertTrue(source.contains("Z2_CATEGORIES\\tOK"))
        assertTrue(source.contains("disableMarker 2>/dev/null)\" = 600"))
        assertTrue(source.contains("disableMarker 2>/dev/null)\" = 0"))
    }

    @Test
    fun moduleInstall_rechecksVersionUnderLockBeforePublishingUpdateState() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val lockedInstall = source.indexOf("private suspend fun installModuleLocked(")
        val installedRead = source.indexOf("RootFileIo.readSecureRegularText(", lockedInstall)
        val fullIdentity = source.indexOf("validatedInstalledVersion", installedRead)
        val versionPolicy = source.indexOf("moduleVersionAllowsInstall(", fullIdentity)
        val rejection = source.indexOf("ModuleInstallRejectedException(", versionPolicy)
        val markerPublication = source.indexOf("acquireUpdateMarker()", rejection)
        val updateStart = source.indexOf("internal suspend fun updateAll(")
        val repairFlag = source.indexOf("release.allowSameVersionModuleRepair", updateStart)

        assertTrue(lockedInstall >= 0)
        assertTrue(installedRead > lockedInstall)
        assertTrue(fullIdentity > installedRead)
        assertTrue(versionPolicy > fullIdentity)
        assertTrue(rejection > versionPolicy)
        assertTrue(markerPublication > rejection)
        assertTrue(repairFlag > updateStart)
    }

    @Test
    fun androidOwnerRead_isBoundToSecureStateAndStableFileIdentity() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/NetworkStatsManager.kt"
        ).readText()

        assertTrue(source.contains("RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)"))
        assertTrue(source.contains("RootFileIo.shellQuote(OwnerStateSchema.OWNER_FILE)"))
        assertTrue(source.contains("[ ! -L \$ownerFile ]"))
        assertTrue(source.contains("[ \"\${'\$'}z2_owner_uid\" = 0 ]"))
        assertTrue(source.contains("[ \"\${'\$'}z2_owner_mode\" = 600 ]"))
        assertTrue(source.contains("[ \"\${'\$'}z2_owner_links\" = 1 ]"))
        assertTrue(source.contains("OwnerStateSchema.MAX_FILE_BYTES"))
        assertFalse(source.contains("-le 4096"))
        assertTrue(source.contains("z2_owner_digest_before="))
        assertTrue(source.contains("z2_owner_digest_after="))
        assertTrue(source.contains("[ \"\${'\$'}z2_owner_after\" = \"\${'\$'}z2_owner_meta\" ]"))
        assertTrue(source.contains("z2_owner_digest_after\" = \"\${'\$'}z2_owner_digest_before"))
    }

    @Test
    fun androidFirewallProof_bindsEveryLivePayloadFieldToOwnerV6() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/NetworkStatsManager.kt"
        ).readText()

        listOf(
            "portsTcp = values.getValue(\"ports_tcp\")",
            "portsUdp = values.getValue(\"ports_udp\")",
            "stunPorts = values.getValue(\"stun_ports\")",
            "pktOut = values.getValue(\"pkt_out\").toInt()",
            "pktIn = values.getValue(\"pkt_in\").toInt()",
            "desyncMark = values.getValue(\"desync_mark\")",
            "connbytes = values.getValue(\"\${prefix}_connbytes\") == \"1\"",
            "multiport = values.getValue(\"\${prefix}_multiport\") == \"1\"",
            "markCapability = values.getValue(\"\${prefix}_mark\") == \"1\"",
            "expectedRuleCount = values.getValue(\"\${prefix}_rules\").toInt()",
        ).forEach { binding -> assertTrue(source.contains(binding)) }
        assertTrue(source.contains("private fun payloadMatches("))
        assertTrue(source.contains("connbytesRange == \"1:\${expected.packetCount}\""))
        assertTrue(source.contains("normalizedMarkPair(mark)"))
        assertTrue(source.contains("ports == expected.ports"))
        assertTrue(source.contains("queueNum == contract.qnum.toString()"))
    }

    @Test
    fun protectedTextRead_provesStableBytesAcrossTheRead() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RootFileIo.kt"
        ).readText()
        val reader = source.indexOf("fun readSecureRegularText(")
        val digestBefore = source.indexOf("z2_digest_before=", reader)
        val contentRead = source.indexOf("cat \$quoted", digestBefore)
        val digestAfter = source.indexOf("z2_digest_after=", contentRead)
        val metadataCas = source.indexOf("z2_after", digestAfter)
        val digestCas = source.indexOf("z2_digest_after\" = \"\${'\$'}z2_digest_before", metadataCas)

        assertTrue(reader >= 0)
        assertTrue(digestBefore > reader)
        assertTrue(contentRead > digestBefore)
        assertTrue(digestAfter > contentRead)
        assertTrue(metadataCas > digestAfter)
        assertTrue(digestCas > metadataCas)
        assertTrue(source.contains("\\u0000"))
    }

    @Test
    fun recoveryJournalReads_bindParsedContentToOneStableDigest() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ModuleUpdateRecovery.kt"
        ).readText()

        assertFalse(source.contains("buildTerminalCommitCommand"))
        assertTrue(source.contains("internal fun buildTerminalCommitPlan("))

        listOf("readTransaction", "readCleanupPending").forEach { readerName ->
            val reader = source.indexOf("fun $readerName(")
            val nextReader = source.indexOf("\n    private ", reader + 1).let {
                if (it >= 0) it else source.length
            }
            val body = source.substring(reader, nextReader)
            val digestBefore = body.indexOf("digest_before=")
            val contentRead = body.indexOf("content=\${'$'}(cat")
            val capturedDigest = body.indexOf("captured_digest=", contentRead)
            val capturedCas = body.indexOf("captured_digest\" = \"\${'$'}digest_before", capturedDigest)
            val digestAfter = body.indexOf("digest_after=", capturedCas)
            val digestCas = body.indexOf("digest_after\" = \"\${'$'}digest_before", digestAfter)
            val protocolOutput = body.indexOf("_SHA256=\${'$'}digest_before", digestCas)

            assertTrue("$readerName lacks a pre-read digest", digestBefore >= 0)
            assertTrue("$readerName reads content before its digest", contentRead > digestBefore)
            assertTrue("$readerName does not bind the shell-captured bytes", capturedDigest > contentRead)
            assertTrue("$readerName accepts altered trailing bytes", capturedCas > capturedDigest)
            assertTrue("$readerName lacks a post-read digest", digestAfter > capturedCas)
            assertTrue("$readerName lacks a digest CAS", digestCas > digestAfter)
            assertTrue("$readerName publishes an unverified digest", protocolOutput > digestCas)
        }
    }

    @Test
    fun recoveryLifecycleAndTerminalCommit_rejectUnsafeActiveTreesAndUnboundRebindBytes() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ModuleUpdateRecovery.kt"
        ).readText()
        val transaction = ModuleUpdateRecovery.Transaction(
            transactionId = "policy-probe",
            phase = "staged",
            createdEpoch = "1",
            preUpdateState = ModuleUpdateStatePolicy.VerifiedState.STOPPED,
            disableMarkerExpectation = ModuleUpdatePreservation.DisableMarkerExpectation.ABSENT,
            ownerPid = "1",
            ownerStarttime = "1",
            ownerCreatedEpoch = "1",
            ownerBootId = "00000000-0000-0000-0000-000000000001",
            updateDir = "/data/adb/modules/.zapret2-update-policy-probe",
            backupDir = "/data/adb/modules/.zapret2-backup-policy-probe",
            failedDir = "/data/adb/modules/.zapret2-failed-policy-probe",
        )
        val probe = ModuleUpdateRecovery.buildDirectoryProbe(transaction)
        val rebind = requireNotNull(
            ModuleUpdateRecovery.buildOwnerRebindPlan(
                transaction = transaction,
                expectedSha256 = "a".repeat(64),
                ownerPid = 2,
                ownerStarttime = "2",
                ownerCreatedEpoch = "2",
                ownerBootId = "00000000-0000-0000-0000-000000000002",
                ownerToken = "policy-token",
            ),
        ).command

        assertTrue(source.contains("internal fun buildDirectoryProbe("))
        assertTrue(probe.contains("[ ! -L \"${'$'}z2_probe_path\" ]"))
        assertTrue(probe.contains("stat -c %u \"${'$'}z2_probe_path\""))
        assertTrue(probe.contains("case \"${'$'}z2_probe_mode\" in 700|711|750|751|755"))
        assertTrue(source.contains("internal fun activeModuleIntegrityPredicate("))
        assertTrue(source.contains("internal fun moduleIntegrityPredicate("))
        assertTrue(source.contains("secureRootRegularFilePredicate"))
        assertTrue(source.contains("\"common.sh\""))
        assertTrue(source.contains("\"command-builder.sh\""))
        assertTrue(source.contains("buildOwnerTransactionGuard("))
        assertTrue(source.contains("moduleIntegrityPredicate(transaction.backupDir"))
        assertTrue(source.contains("moduleIntegrityPredicate(transaction.recoveryDir"))
        assertTrue(source.contains("val reboundDigest = UpdateTransactionProtocol.sha256(reboundContent)"))
        assertTrue(rebind.contains("[ \"${'$'}new_digest\" = "))

        val terminal = source.indexOf("private fun recoveryTerminalPrerequisite(")
        val integrity = source.indexOf("activeModuleIntegrityPredicate(", terminal)
        val disable = source.indexOf("expectedDisableMarkerPredicate", integrity)
        val status = source.indexOf("z2_terminal_status=0", disable)
        assertTrue(terminal >= 0)
        assertTrue(integrity > terminal)
        assertTrue(disable > integrity)
        assertTrue(status > disable)
    }

    @Test
    fun candidatePromotion_isOwnerAndJournalBoundInsteadOfUsingARawMove() {
        val manager = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val protocol = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateTransactionProtocol.kt"
        ).readText()

        assertTrue(manager.contains("UpdateTransactionProtocol.buildCandidatePromotion("))
        assertTrue(protocol.contains("fun buildOwnerGuard("))
        assertTrue(manager.contains("sourcePrerequisite ="))
        assertTrue(manager.contains("movedPrerequisite ="))
        assertTrue(manager.contains("candidatePrerequisite ="))
        assertTrue(manager.contains("promotedPrerequisite ="))
        assertFalse(manager.contains(
            "\"mv ${'$'}{RootFileIo.shellQuote(updateDir)} ${'$'}{RootFileIo.shellQuote(MODULE_DIR)} && sync\""
        ))
        val builder = protocol.indexOf("fun buildCandidatePromotion(")
        val move = protocol.indexOf("append(\"mv ${'$'}update ${'$'}module || exit 1", builder)
        val ownerCas = protocol.lastIndexOf("appendOwnerCas(owner", move)
        val journalCas = protocol.lastIndexOf("appendPriorCas(expectedDigest", move)
        val sourceCheck = protocol.lastIndexOf("append(updatePredicate)", move)
        val fullCandidateCheck = protocol.lastIndexOf("append(candidatePrerequisite)", move)
        val promotedCheck = protocol.indexOf("append(promotedPrerequisite)", move)
        assertTrue(builder >= 0)
        assertTrue(ownerCas in builder until move)
        assertTrue(journalCas in ownerCas until move)
        assertTrue(sourceCheck in journalCas until move)
        assertTrue(fullCandidateCheck in sourceCheck until move)
        assertTrue(promotedCheck > move)

        val rollback = manager.indexOf("private suspend fun rollbackHotUpdate(")
        val rollbackEnd = manager.indexOf("private suspend fun incompleteRollback(", rollback)
        val rollbackSource = manager.substring(rollback, rollbackEnd)
        assertTrue(rollbackSource.contains("buildOwnerTransactionGuard("))
        assertTrue(rollbackSource.contains("activeModuleIntegrityPredicate("))
        assertTrue(rollbackSource.contains("moduleIntegrityPredicate(backupDir"))
        assertTrue(rollbackSource.contains("moduleIntegrityPredicate(failedDir"))
        assertTrue(rollbackSource.contains("verifiedUpdateLifecycle("))
        assertTrue(rollbackSource.contains("verifyServiceState("))
        assertTrue(rollbackSource.contains("expectedTransactionDigest = retainedTransactionDigest"))
        assertFalse(rollbackSource.contains("authorizedUpdateLifecycle(marker, stopScript)"))

        val ordinaryFailure = manager
            .substringAfter("var transactionDigest: String? = null")
            .substringAfter("} catch (error: Exception) {")
            .substringBefore("} finally {")
        val rollbackFence = ordinaryFailure.indexOf("val rollback = withContext(NonCancellable)")
        val restoreFence = ordinaryFailure.indexOf("val restored = withContext(NonCancellable)")
        val cancellationExit = ordinaryFailure.lastIndexOf("currentCoroutineContext().ensureActive()")
        val failedReturn = ordinaryFailure.indexOf("return Pair(false, false)")
        assertTrue(rollbackFence >= 0)
        assertTrue(restoreFence > rollbackFence)
        assertTrue(cancellationExit > restoreFence)
        assertTrue(failedReturn > cancellationExit)
        assertTrue(
            Regex("currentCoroutineContext\\(\\)\\.ensureActive\\(\\)")
                .findAll(ordinaryFailure)
                .count() == 3,
        )

        val incomplete = manager.substring(
            manager.indexOf("private suspend fun incompleteRollback("),
            manager.indexOf("private fun validateModuleArchive("),
        )
        assertTrue(incomplete.contains("transactionRetained"))
        assertTrue(incomplete.contains("stat -c %h ${'$'}transaction"))
        assertTrue(incomplete.contains("sha256sum ${'$'}transaction"))
        assertTrue(incomplete.contains("live update owner lock will be released"))
        assertFalse(manager.contains("update marker and recovery artifacts were retained"))
    }

    @Test
    fun runtimeRollbackAmbiguity_isVerifiedAndSurfacedByEveryMutationOwner() {
        val store = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RuntimeConfigStore.kt"
        ).readText()
        val writeBoundary = store.substringAfter("private fun writeFileAtomically(")
            .substringBefore("private fun runtimeContentsEquivalent(")
        assertTrue(writeBoundary.contains("val written = try {"))
        assertTrue(writeBoundary.contains("val current = try {"))
        assertTrue(writeBoundary.contains("\"Z2_RUNTIME_ROLLBACK\""))
        assertTrue(writeBoundary.contains("val restored = try {"))
        assertTrue(store.contains("if (restored == null || !runtimeContentsEquivalent(restored, previousContent))"))
        assertTrue(store.contains("throw RuntimeConfigRollbackException()"))

        listOf(
            "data/PresetRepository.kt",
            "viewmodel/ConfigEditorViewModel.kt",
            "viewmodel/ControlViewModel.kt",
            "viewmodel/DnsManagerViewModel.kt",
            "viewmodel/StrategiesViewModel.kt",
        ).forEach { relative ->
            val source = repositoryFile(
                "android-app/app/src/main/java/com/zapret2/app/$relative"
            ).readText()
            assertTrue("$relative masks runtime rollback ambiguity", source.contains("RuntimeConfigRollbackException"))
        }

        val presets = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/PresetRepository.kt"
        ).readText()
        assertTrue(presets.contains("restoreFileOrFalse(fileName, oldFile)"))
        assertTrue(presets.contains("writeConfigResult(config) == true"))
        assertTrue(presets.contains("catch (_: RuntimeConfigRollbackException)"))
        assertTrue(presets.contains("val fileRestored = restoreFileOrFalse(fileName, oldFile)"))
    }

    @Test
    fun updateCacheCleanup_cannotPreemptOwnerReleaseOrSiblingCleanup() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val lockedInstall = source.indexOf("private suspend fun installModuleLocked(")
        val ordinaryFailure = source.indexOf("} catch (error: Exception) {", lockedInstall)
        val installFinally = source.indexOf("} finally {", ordinaryFailure)
        val installEnd = source.indexOf("private suspend fun verifyStandardModuleInstall(", installFinally)
        val terminalCleanup = source.substring(installFinally, installEnd)
        val ownerRelease = terminalCleanup.indexOf("withContext(NonCancellable)")
        val stagingCleanup = terminalCleanup.indexOf("deleteTreeBestEffort(stagingDir)")

        assertTrue(lockedInstall >= 0)
        assertTrue(ordinaryFailure > lockedInstall)
        assertTrue(installFinally > ordinaryFailure)
        assertTrue(installEnd > installFinally)
        assertTrue(ownerRelease >= 0)
        assertTrue(stagingCleanup > ownerRelease)
        assertTrue(terminalCleanup.contains("} finally {\n                deleteTreeBestEffort(stagingDir)"))

        val postExtractionValidation = source.substring(
            source.indexOf("val stagingViolation = try {", lockedInstall),
            source.indexOf("val transactionId =", lockedInstall),
        )
        assertTrue(postExtractionValidation.contains("catch (error: Exception)"))
        assertTrue(postExtractionValidation.contains("deleteTreeBestEffort(stagingDir)\n            throw error"))
        assertTrue(postExtractionValidation.contains("if (stagingViolation != null)"))

        val download = source.substring(
            source.indexOf("private suspend fun downloadFile("),
            source.indexOf("private fun installApk("),
        )
        assertTrue(download.contains("deleteFileBestEffort(partialFile)"))
        assertTrue(download.contains("deleteFileBestEffort(finalizedFile)"))
        assertTrue(download.contains("disconnectBestEffort(connection)"))
        assertFalse(download.contains("partialFile?.delete()"))
        assertFalse(download.contains("finalizedFile?.delete()"))
        assertFalse(download.contains("connection?.disconnect()"))

        val cleanupHelpers = source.substringAfter("private fun deleteFileBestEffort(")
            .substringBefore("private fun setExactMode(")
        assertTrue(cleanupHelpers.contains("private fun deleteTreeBestEffort("))
        assertTrue(cleanupHelpers.contains("private fun disconnectBestEffort("))
        assertTrue(Regex("catch \\(_: Exception\\)").findAll(cleanupHelpers).count() == 3)
        assertEquals(1, Regex("connection\\?\\.disconnect\\(\\)").findAll(source).count())
        assertTrue(source.contains("finally {\n            disconnectBestEffort(connection)"))
    }

    @Test
    fun nonCancellableOwnerRelease_restoresLateCancellationBeforeNormalReturn() {
        val lease = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/CancellationSafeMutationLease.kt"
        ).readText()
        val blockResult = lease.indexOf("val blockResult = try {")
        val release = lease.indexOf("withContext(NonCancellable) { release(owned) }", blockResult)
        val primaryResult = lease.indexOf("val value = blockResult.getOrThrow()", release)
        val cancellationFence = lease.indexOf("currentCoroutineContext().ensureActive()", primaryResult)
        val normalReturn = lease.indexOf("return value", cancellationFence)

        assertTrue(blockResult >= 0)
        assertTrue(release > blockResult)
        assertTrue(primaryResult > release)
        assertTrue(cancellationFence > primaryResult)
        assertTrue(normalReturn > cancellationFence)

        val recovery = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ModuleUpdateRecovery.kt"
        ).readText()
        val boundary = recovery.substringAfter("private suspend fun withRecoveryMarker(")
            .substringBefore("internal fun parseTransaction(")
        val recoveryRelease = boundary.indexOf("releaseRecoveryMarker(ownedMarker)")
        val recoveryCancellationFence = boundary.indexOf(
            "currentCoroutineContext().ensureActive()",
            recoveryRelease,
        )
        val recoveryReturn = boundary.indexOf("return outcome", recoveryCancellationFence)
        assertTrue(recoveryRelease >= 0)
        assertTrue(recoveryCancellationFence > recoveryRelease)
        assertTrue(recoveryReturn > recoveryCancellationFence)
        assertFalse(boundary.contains("marker ?: return outcome"))
    }

    @Test
    fun fileNamePolicies_matchShellControlSuffixAndReservedContracts() {
        val rootFileIo = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RootFileIo.kt"
        ).readText()
        val runtimeConfig = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/RuntimeConfigStore.kt"
        ).readText()
        val presetRepository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/PresetRepository.kt"
        ).readText()
        val hostlistRepository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/HostlistRepository.kt"
        ).readText()
        val strategyRepository = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/StrategyRepository.kt"
        ).readText()
        val modulePackageContract = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/ModulePackageContract.kt"
        ).readText()
        val updateManager = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/data/UpdateManager.kt"
        ).readText()
        val common = repositoryFile("zapret2/scripts/common.sh").readText()
        val commandBuilder = repositoryFile("zapret2/scripts/command-builder.sh").readText()
        val packageContract = repositoryFile("zapret2/scripts/package-contract.sh").readText()

        assertTrue(rootFileIo.contains("it.isISOControl()"))
        assertTrue(rootFileIo.contains("fileName.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES"))
        assertTrue(runtimeConfig.contains("RootFileIo.isSimpleFileName(value)"))
        assertTrue(common.substringAfter("is_safe_runtime_file_name() {").contains("[[:cntrl:]]"))
        assertTrue(common.contains("is_safe_file_name_byte_length \"\$value\""))
        assertTrue(
            common.substringAfter("is_safe_file_name_byte_length() {")
                .substringBefore("is_safe_runtime_file_name() {")
                .contains("local LC_ALL=C")
        )
        assertTrue(
            packageContract.substringAfter("package_contract_safe_cmdline_name() {")
                .contains("[[:cntrl:]]")
        )
        assertTrue(packageContract.contains("package_contract_safe_file_name_byte_length \"\$value\""))
        assertTrue(
            packageContract.substringAfter("package_contract_safe_file_name_byte_length() {")
                .substringBefore("package_contract_safe_path_component_lengths() {")
                .contains("local LC_ALL=C")
        )
        assertTrue(packageContract.contains("package_contract_safe_path_component_lengths \"\$path\""))
        assertTrue(packageContract.contains("package_contract_safe_preset_name_syntax \"\${path##*/}\""))
        assertTrue(packageContract.contains("MANIFEST_CONTROL_CHARACTER"))
        assertTrue(packageContract.contains("package_contract_safe_preset_name \"\$preset\""))
        assertTrue(commandBuilder.contains("command_builder_safe_file_name_byte_length \"\$name\""))
        assertTrue(
            commandBuilder.substringAfter("command_builder_safe_file_name_byte_length() {")
                .substringBefore("is_safe_preset_file_name() {")
                .contains("local LC_ALL=C")
        )
        assertTrue(modulePackageContract.contains("PresetNamePolicy.isValid(presetName)"))
        assertTrue(modulePackageContract.contains("size > MAX_PATH_COMPONENT_BYTES"))
        assertTrue(modulePackageContract.contains("internal fun archivePathTopologyIsValid("))
        assertTrue(modulePackageContract.contains("expandedPaths.size != expandedPaths.toSet().size"))
        assertTrue(updateManager.contains("size > MAX_ARCHIVE_PATH_COMPONENT_BYTES"))
        assertTrue(updateManager.contains("ModulePackageContract.archivePathTopologyIsValid(archivePaths)"))
        assertTrue(packageContract.contains("package_contract_validate_manifest_paths_file"))
        assertTrue(packageContract.contains("package_contract_validate_zip_topology_file"))
        val zipValidator = packageContract
            .substringAfter("package_contract_validate_zip_names() {")
            .substringBefore("package_contract_apply_modes() {")
        assertFalse(zipValidator.contains("grep -Fxc"))
        assertFalse(zipValidator.contains("package_contract_archive_path_collides"))
        assertFalse(RootFileIo.isSimpleFileName("delete\u007fname.txt", ".txt"))
        assertFalse(RuntimeConfigStore.isSafeCommandLineFileName("delete\u007fname.txt"))

        val androidReserved = runtimeConfig
            .substringAfter("private val reservedCommandLineNames = setOf(")
            .substringBefore("\n    )")
            .let { block ->
                Regex("\"([^\"]+)\"")
                    .findAll(block)
                    .map { it.groupValues[1] }
                    .toSet()
            }
        val commonReserved = shellReservedCommandLineNames(common, "is_safe_cmdline_file_name() {")
        val packageReserved = shellReservedCommandLineNames(
            packageContract,
            "package_contract_safe_cmdline_name() {",
        )
        assertEquals(16, androidReserved.size)
        assertEquals(androidReserved, commonReserved)
        assertEquals(androidReserved, packageReserved)
        androidReserved.forEach { reserved ->
            assertFalse(RuntimeConfigStore.isSafeCommandLineFileName(reserved))
            assertFalse(RuntimeConfigStore.isSafeCommandLineFileName(reserved.uppercase()))
        }

        assertTrue(rootFileIo.contains("endsWith(requiredSuffix, ignoreCase = true)"))
        assertTrue(presetRepository.contains("fileName.endsWith(\".txt\")"))
        assertTrue(hostlistRepository.contains("fileName.endsWith(\".txt\", ignoreCase = true)"))
        assertTrue(strategyRepository.contains("RootFileIo.isSimpleFileName(hostlist, \".txt\")"))
        assertTrue(strategyRepository.contains("RootFileIo.isSimpleFileName(ipset, \".txt\")"))
        val presetValidator = commandBuilder.substringAfter("is_safe_preset_file_name() {")
            .substringBefore("is_safe_dependency_name() {")
        assertTrue(presetValidator.contains("*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT"))
        assertTrue(presetValidator.contains("*.txt) ;;"))
        val packagePresetValidator = packageContract
            .substringAfter("package_contract_safe_preset_name_syntax() {")
            .substringBefore("package_contract_safe_preset_name() {")
        assertTrue(packagePresetValidator.contains("*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT"))
        assertTrue(packagePresetValidator.contains("*.txt) ;;"))
        val categoryValidator = commandBuilder.substringAfter("is_safe_category_txt_name() {")
            .substringBefore("process_category_section() {")
        assertTrue(categoryValidator.contains("tr '[:upper:]' '[:lower:]'"))
        assertTrue(categoryValidator.contains("case \"\$normalized\" in *.txt)"))
    }

    private fun shellReservedCommandLineNames(source: String, functionHeader: String): Set<String> {
        val block = source.substringAfter(functionHeader).substringBefore("return 0")
        val names = Regex(
            "(?m)^\\s*([a-z0-9.-]+(?:\\|[a-z0-9.-]+)*)\\) return 1",
        ).find(block)?.groupValues?.get(1) ?: error("Missing reserved command-line name case")
        return names.split('|').toSet()
    }

    private fun shellValue(text: String, name: String): String {
        val match = Regex("(?m)^${Regex.escape(name)}=(?:\\\"([^\\\"]*)\\\"|([^\\r\\n# ]+))$")
            .find(text)
        return match?.groupValues?.let { it[1].ifEmpty { it[2] } }
            ?: error("Missing shell protocol constant: $name")
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
