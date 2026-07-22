package com.zapret2.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetRepositoryTest {

    @Test
    fun machineDiscoveryFixture_exposes20AndQuarantines49WithTypedCounts() {
        val lines = buildList {
            repeat(20) { index -> add("Z2_PRESET\tVALID\tOK\tvalid-$index.txt") }
            repeat(30) { index -> add("Z2_PRESET\tQUARANTINED\tDEPENDENCY_MISSING\tmissing-$index.txt") }
            repeat(19) { index -> add("Z2_PRESET\tQUARANTINED\tUNSAFE_DEPENDENCY_PATH\tunsafe-$index.txt") }
            add("Z2_PRESET_SUMMARY\t1\tvalid=20\tquarantined=49\ttotal=69")
        }

        val discovery = requireNotNull(PresetMachineProtocol.parseDiscovery(lines))

        assertEquals(20, discovery.available.size)
        assertEquals(49, discovery.quarantinedCount)
        assertEquals(30, discovery.issueCounts[PresetIssue.DEPENDENCY_MISSING])
        assertEquals(19, discovery.issueCounts[PresetIssue.UNSAFE_DEPENDENCY_PATH])
    }

    @Test
    fun machineProtocol_failsClosedOnCountMismatchDuplicateOrUnexpectedLine() {
        assertNull(
            PresetMachineProtocol.parseDiscovery(
                listOf(
                    "Z2_PRESET\tVALID\tOK\tone.txt",
                    "Z2_PRESET_SUMMARY\t1\tvalid=2\tquarantined=0\ttotal=2",
                ),
            ),
        )
        assertNull(
            PresetMachineProtocol.parseDiscovery(
                listOf(
                    "Z2_PRESET\tVALID\tOK\tone.txt",
                    "Z2_PRESET\tVALID\tOK\tone.txt",
                    "Z2_PRESET_SUMMARY\t1\tvalid=2\tquarantined=0\ttotal=2",
                ),
            ),
        )
        assertNull(PresetMachineProtocol.parseDiscovery(listOf("diagnostic noise")))
    }

    @Test
    fun validationProtocol_acceptsExactLogicalNameAndTypedReason() {
        assertEquals(
            PresetValidation.Compatible,
            PresetMachineProtocol.parseValidation(
                listOf("Z2_PRESET_VALIDATION\t1\tOK\tgood.txt"),
                "good.txt",
            ),
        )
        assertEquals(
            PresetValidation.Quarantined(PresetIssue.DEPENDENCY_SYMLINK),
            PresetMachineProtocol.parseValidation(
                listOf("Z2_PRESET_VALIDATION\t0\tDEPENDENCY_SYMLINK\tgood.txt"),
                "good.txt",
            ),
        )
        assertEquals(
            PresetValidation.ProtocolFailure,
            PresetMachineProtocol.parseValidation(
                listOf("Z2_PRESET_VALIDATION\t1\tOK\tother.txt"),
                "good.txt",
            ),
        )
    }

    @Test
    fun apply_revalidatesInsideMutationAndDoesNotMutateRejectedPreset() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Quarantined(PresetIssue.DEPENDENCY_MISSING))
        val gate = RecordingGate()
        val repository = TransactionalPresetRepository(runner, gate)

        val result = repository.apply("broken.txt")

        assertEquals(PresetMutationOutcome.Rejected(PresetIssue.DEPENDENCY_MISSING), result)
        assertEquals(1, gate.calls)
        assertEquals(1, runner.validationCalls)
        assertEquals(0, runner.configWrites)
        assertEquals(0, runner.restartCalls)
    }

    @Test
    fun save_rejectsCandidateBeforeAtomicReplaceAndLeavesTargetUntouched() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Quarantined(PresetIssue.NO_VALID_OPTIONS))
        runner.files["custom.txt"] = "old"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old", "invalid", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.Rejected(PresetIssue.NO_VALID_OPTIONS), result)
        assertEquals("old", runner.files["custom.txt"])
        assertEquals(0, runner.replaceCalls)
        assertEquals(0, runner.configWrites)
        assertEquals(listOf("snapshot-file", "snapshot-config", "write-candidate", "validate", "remove"), runner.events)
    }

    @Test
    fun save_rejectsOversizedContentBeforeSnapshotOrWrite() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Compatible)
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save(
            "custom.txt",
            expectedContent = null,
            content = "a".repeat(PresetContentPolicy.MAX_BYTES + 1),
            applyAfterSave = false,
        )

        assertEquals(PresetMutationOutcome.Rejected(PresetIssue.PRESET_TOO_LARGE), result)
        assertTrue(runner.events.isEmpty())
    }

    @Test
    fun saveAndApply_restartFailureRestoresConfigAndOldFileContent() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Compatible, restartSucceeds = false)
        runner.config = ActivePresetConfig("categories", "old.txt")
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.RestartFailedRolledBack, result)
        assertEquals(ActivePresetConfig("categories", "old.txt"), runner.config)
        assertEquals("old content", runner.files["custom.txt"])
        assertEquals(2, runner.configWrites)
        assertEquals(1, runner.restartCalls)
        assertTrue(runner.events.indexOf("validate") < runner.events.indexOf("replace"))
    }

    @Test
    fun apply_restartExceptionRestoresPreviousConfig() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            restartFailure = IllegalStateException("restart failed"),
        )
        runner.config = ActivePresetConfig("categories", "old.txt")
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.apply("good.txt")

        assertEquals(PresetMutationOutcome.RestartFailedRolledBack, result)
        assertEquals(ActivePresetConfig("categories", "old.txt"), runner.config)
        assertEquals(2, runner.configWrites)
    }

    @Test
    fun saveAndApply_restartExceptionRestoresConfigAndOldFileContent() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            restartFailure = IllegalStateException("restart failed"),
        )
        runner.config = ActivePresetConfig("categories", "old.txt")
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.RestartFailedRolledBack, result)
        assertEquals(ActivePresetConfig("categories", "old.txt"), runner.config)
        assertEquals("old content", runner.files["custom.txt"])
    }

    @Test
    fun switchToCategories_restartExceptionRestoresPreviousConfig() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            restartFailure = IllegalStateException("restart failed"),
        )
        runner.config = ActivePresetConfig("file", "active.txt")
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.switchToCategories()

        assertEquals(PresetMutationOutcome.RestartFailedRolledBack, result)
        assertEquals(ActivePresetConfig("file", "active.txt"), runner.config)
        assertEquals(2, runner.configWrites)
    }

    @Test
    fun saveAndApply_newFileRestartFailureRestoresNonExistence() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Compatible, restartSucceeds = false)
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("new.txt", null, "valid", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.RestartFailedRolledBack, result)
        assertFalse("new file must be removed by rollback", runner.files.containsKey("new.txt"))
    }

    @Test
    fun ambiguousReplace_restoresPreviousPresetInsteadOfReportingPlainIoFailure() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            replaceReturns = false,
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.WriteFailedRolledBack, result)
        assertEquals("old content", runner.files["custom.txt"])
    }

    @Test
    fun ambiguousReplace_reportsRollbackFailureWhenPreviousPresetCannotBeRestored() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            replaceReturns = false,
            restoreSucceeds = false,
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.RollbackFailed, result)
        assertEquals("new content", runner.files["custom.txt"])
    }

    @Test
    fun candidateWriteException_removesAmbiguousCandidateAndPreservesTarget() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            writeCandidateFailure = IllegalStateException("write result lost"),
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.IoFailed, result)
        assertEquals("old content", runner.files["custom.txt"])
        assertFalse(runner.files.keys.any { it.startsWith("_") })
    }

    @Test
    fun candidateValidationException_removesCandidateAndPreservesTarget() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            validationFailure = IllegalStateException("validator unavailable"),
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.IoFailed, result)
        assertEquals("old content", runner.files["custom.txt"])
        assertFalse(runner.files.keys.any { it.startsWith("_") })
    }

    @Test
    fun replaceException_restoresPreviousPresetInsteadOfEscapingCleanup() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            replaceFailure = IllegalStateException("replace result lost"),
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.WriteFailedRolledBack, result)
        assertEquals("old content", runner.files["custom.txt"])
    }

    @Test
    fun postReplaceSnapshotException_restoresPreviousPreset() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            snapshotFileFailureOnCall = 2,
        )
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = false)

        assertEquals(PresetMutationOutcome.WriteFailedRolledBack, result)
        assertEquals("old content", runner.files["custom.txt"])
    }

    @Test
    fun activeConfigWriteException_restoresPreviousConfigAndPreset() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            configWriteFailureOnCall = 1,
        )
        runner.config = ActivePresetConfig("categories", "old.txt")
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.WriteFailedRolledBack, result)
        assertEquals(ActivePresetConfig("categories", "old.txt"), runner.config)
        assertEquals("old content", runner.files["custom.txt"])
    }

    @Test
    fun configRollbackException_doesNotSkipPresetFileRestore() = runBlocking {
        val runner = FakePresetRunner(
            validation = PresetValidation.Compatible,
            restartSucceeds = false,
            configWriteFailureOnCall = 2,
        )
        runner.config = ActivePresetConfig("categories", "old.txt")
        runner.files["custom.txt"] = "old content"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save("custom.txt", "old content", "new content", applyAfterSave = true)

        assertEquals(PresetMutationOutcome.RollbackFailed, result)
        assertEquals("old content", runner.files["custom.txt"])
        assertTrue(runner.events.contains("restore-file"))
    }

    @Test
    fun save_rejectsChangedSourceBeforeCandidatePublication() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Compatible)
        runner.files["custom.txt"] = "changed externally"
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.save(
            fileName = "custom.txt",
            expectedContent = "editor baseline",
            content = "draft",
            applyAfterSave = false,
        )

        assertEquals(PresetMutationOutcome.SourceChanged, result)
        assertEquals("changed externally", runner.files["custom.txt"])
        assertEquals(0, runner.replaceCalls)
    }

    @Test
    fun successfulApplyPersistsOnlyAfterRevalidation() = runBlocking {
        val runner = FakePresetRunner(validation = PresetValidation.Compatible, restartSucceeds = true)
        val repository = TransactionalPresetRepository(runner, RecordingGate())

        val result = repository.apply("good.txt")

        assertEquals(PresetMutationOutcome.Applied, result)
        assertEquals(ActivePresetConfig("file", "good.txt"), runner.config)
        assertEquals(listOf("validate", "snapshot-config", "write-config", "restart"), runner.events)
    }

    private class RecordingGate : PresetMutationGate {
        var calls = 0
        override suspend fun <T> mutate(block: suspend () -> T): T {
            calls++
            return block()
        }
    }

    private class FakePresetRunner(
        var validation: PresetValidation,
        private val restartSucceeds: Boolean = true,
        private val restartFailure: Exception? = null,
        private val replaceReturns: Boolean = true,
        private val restoreSucceeds: Boolean = true,
        private val validationFailure: Exception? = null,
        private val writeCandidateFailure: Exception? = null,
        private val replaceFailure: Exception? = null,
        private val snapshotFileFailureOnCall: Int? = null,
        private val configWriteFailureOnCall: Int? = null,
    ) : PresetRunner {
        var config = ActivePresetConfig("categories", "old.txt")
        val files = linkedMapOf<String, String>()
        val events = mutableListOf<String>()
        var validationCalls = 0
        var configWrites = 0
        var restartCalls = 0
        var replaceCalls = 0
        var snapshotFileCalls = 0

        override suspend fun scanPresets(): List<String>? = null

        override suspend fun validatePreset(candidateFileName: String, logicalFileName: String): PresetValidation {
            events += "validate"
            validationCalls++
            validationFailure?.let { throw it }
            return validation
        }

        override suspend fun loadSelection(): PresetSelection =
            PresetSelection(config.presetMode ?: "categories", config.presetFile.orEmpty(), "cmdline.txt")

        override suspend fun snapshotActiveConfig(): ActivePresetConfig {
            events += "snapshot-config"
            return config.copy()
        }

        override suspend fun writeActiveConfig(config: ActivePresetConfig): Boolean {
            events += "write-config"
            configWrites++
            this.config = config
            if (configWrites == configWriteFailureOnCall) {
                throw IllegalStateException("config write result lost")
            }
            return true
        }

        override suspend fun snapshotFile(fileName: String): PresetFileSnapshot {
            events += "snapshot-file"
            snapshotFileCalls++
            if (snapshotFileCalls == snapshotFileFailureOnCall) {
                throw IllegalStateException("snapshot unavailable")
            }
            return files[fileName]?.let(PresetFileSnapshot::Present) ?: PresetFileSnapshot.Missing
        }

        override suspend fun writeCandidate(fileName: String, content: String): Boolean {
            events += "write-candidate"
            files[fileName] = content
            writeCandidateFailure?.let { throw it }
            return true
        }

        override suspend fun replaceCandidate(candidateFileName: String, targetFileName: String): Boolean {
            events += "replace"
            replaceCalls++
            val content = files.remove(candidateFileName) ?: return false
            files[targetFileName] = content
            replaceFailure?.let { throw it }
            return replaceReturns
        }

        override suspend fun restoreFile(fileName: String, snapshot: PresetFileSnapshot): Boolean {
            events += "restore-file"
            if (!restoreSucceeds) return false
            when (snapshot) {
                PresetFileSnapshot.Missing -> files.remove(fileName)
                is PresetFileSnapshot.Present -> files[fileName] = snapshot.content
                PresetFileSnapshot.Unsafe -> return false
            }
            return true
        }

        override suspend fun removeFile(fileName: String): Boolean {
            events += "remove"
            files.remove(fileName)
            return true
        }

        override suspend fun restart(): Boolean {
            events += "restart"
            restartCalls++
            restartFailure?.let { throw it }
            return restartSucceeds
        }
    }
}
