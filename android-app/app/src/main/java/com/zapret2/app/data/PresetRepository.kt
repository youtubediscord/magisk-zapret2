package com.zapret2.app.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface PresetRepository {
    suspend fun loadCatalog(): PresetCatalog?
    suspend fun readCompatible(fileName: String): String?
    suspend fun apply(fileName: String): PresetMutationOutcome
    suspend fun save(
        fileName: String,
        expectedContent: String?,
        content: String,
        applyAfterSave: Boolean,
    ): PresetMutationOutcome
    suspend fun switchToCategories(): PresetMutationOutcome
}

object PresetNamePolicy {
    fun isValid(fileName: String): Boolean =
        RootFileIo.isSimpleFileName(fileName) &&
            fileName.endsWith(".txt") &&
            !fileName.startsWith("_")
}

internal object PresetMachineProtocol {
    private const val RECORD = "Z2_PRESET"
    private const val SUMMARY = "Z2_PRESET_SUMMARY"
    private const val VALIDATION = "Z2_PRESET_VALIDATION"

    fun parseDiscovery(lines: List<String>): PresetDiscovery? {
        val records = mutableListOf<ScanRecord>()
        var summary: ScanSummary? = null
        for (line in lines.filter(String::isNotBlank)) {
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                RECORD -> {
                    if (fields.size != 4 || summary != null) return null
                    val status = fields[1]
                    val reason = fields[2]
                    val fileName = fields[3]
                    if (!PresetNamePolicy.isValid(fileName)) return null
                    val record = when {
                        status == "VALID" && reason == "OK" -> ScanRecord(fileName, null)
                        status == "QUARANTINED" && reason != "OK" ->
                            ScanRecord(fileName, PresetIssue.fromWireCode(reason))
                        else -> return null
                    }
                    records += record
                }

                SUMMARY -> {
                    if (fields.size != 5 || fields[1] != "1" || summary != null) return null
                    val values = fields.drop(2).mapNotNull { field ->
                        val separator = field.indexOf('=')
                        if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
                    }.toMap()
                    summary = ScanSummary(
                        valid = values["valid"]?.toIntOrNull() ?: return null,
                        quarantined = values["quarantined"]?.toIntOrNull() ?: return null,
                        total = values["total"]?.toIntOrNull() ?: return null,
                    )
                }

                else -> return null
            }
        }

        val finalSummary = summary ?: return null
        if (records.map(ScanRecord::fileName).distinct().size != records.size) return null
        if (finalSummary.valid < 0 || finalSummary.quarantined < 0 || finalSummary.total < 0) return null
        if (finalSummary.total != records.size) return null
        if (finalSummary.valid != records.count { it.issue == null }) return null
        if (finalSummary.quarantined != records.count { it.issue != null }) return null

        val available = records
            .filter { it.issue == null }
            .map { PresetEntry(it.fileName) }
            .sortedBy { it.fileName.lowercase() }
        val issueCounts = records
            .mapNotNull(ScanRecord::issue)
            .groupingBy { it }
            .eachCount()
        return PresetDiscovery(available, finalSummary.quarantined, issueCounts)
    }

    fun parseValidation(lines: List<String>, expectedLogicalName: String): PresetValidation {
        val records = lines.filter(String::isNotBlank)
        if (records.size != 1) return PresetValidation.ProtocolFailure
        val fields = records.single().split('\t')
        if (fields.size != 4 || fields[0] != VALIDATION || fields[3] != expectedLogicalName) {
            return PresetValidation.ProtocolFailure
        }
        return when {
            fields[1] == "1" && fields[2] == "OK" -> PresetValidation.Compatible
            fields[1] == "0" && fields[2] != "OK" ->
                PresetValidation.Quarantined(PresetIssue.fromWireCode(fields[2]))
            else -> PresetValidation.ProtocolFailure
        }
    }

    private data class ScanRecord(val fileName: String, val issue: PresetIssue?)
    private data class ScanSummary(val valid: Int, val quarantined: Int, val total: Int)
}

internal interface PresetMutationGate {
    suspend fun <T> mutate(block: suspend () -> T): T
}

internal interface PresetRunner {
    suspend fun scanPresets(): List<String>?
    suspend fun validatePreset(candidateFileName: String, logicalFileName: String): PresetValidation
    suspend fun loadSelection(): PresetSelection?
    suspend fun snapshotActiveConfig(): ActivePresetConfig?
    suspend fun writeActiveConfig(config: ActivePresetConfig): Boolean
    suspend fun snapshotFile(fileName: String): PresetFileSnapshot
    suspend fun writeCandidate(fileName: String, content: String): Boolean
    suspend fun replaceCandidate(candidateFileName: String, targetFileName: String): Boolean
    suspend fun restoreFile(fileName: String, snapshot: PresetFileSnapshot): Boolean
    suspend fun removeFile(fileName: String): Boolean
    suspend fun restart(): Boolean
}

@Singleton
internal class ModulePresetMutationGate @Inject constructor() : PresetMutationGate {
    override suspend fun <T> mutate(block: suspend () -> T): T =
        ModuleMutationCoordinator.withNonCancellableMutation(block)
}

@Singleton
internal class RootPresetRunner @Inject constructor() : PresetRunner {
    private val moduleDir = ServiceLifecycleController.MODULE_DIR
    private val zapretDir = "$moduleDir/zapret2"
    private val presetsDir = "$zapretDir/presets"
    private val commandBuilder = "$zapretDir/scripts/command-builder.sh"

    override suspend fun scanPresets(): List<String>? {
        val result = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(commandBuilder)} --scan-presets-machine " +
                RootFileIo.shellQuote(zapretDir),
        )
        return result.stdout.takeIf { result.success }
    }

    override suspend fun validatePreset(
        candidateFileName: String,
        logicalFileName: String,
    ): PresetValidation {
        if (!isSafeName(candidateFileName) || !PresetNamePolicy.isValid(logicalFileName)) {
            return PresetValidation.Quarantined(PresetIssue.UNSAFE_PRESET_NAME)
        }
        val candidatePath = "$presetsDir/$candidateFileName"
        val result = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(commandBuilder)} --validate-preset-machine " +
                "${RootFileIo.shellQuote(zapretDir)} ${RootFileIo.shellQuote(candidatePath)} " +
                RootFileIo.shellQuote(logicalFileName),
        )
        return PresetMachineProtocol.parseValidation(result.stdout, logicalFileName)
    }

    override suspend fun snapshotActiveConfig(): ActivePresetConfig? {
        val values = RuntimeConfigStore.readCoreResult()
        if (!values.usesRuntimeConfig) return null
        val presetMode = values.values["preset_mode"] ?: return null
        val presetFile = values.values["preset_file"] ?: return null
        return ActivePresetConfig(presetMode, presetFile)
    }

    override suspend fun loadSelection(): PresetSelection? {
        val result = RuntimeConfigStore.readCoreResult()
        if (!result.usesRuntimeConfig) return null
        val values = result.values
        val activeMode = values["preset_mode"] ?: return null
        val activePresetFile = values["preset_file"] ?: return null
        val activeCmdlineFile = values["custom_cmdline_file"]
            ?.takeIf(RuntimeConfigStore::isSafeCommandLineFileName)
            ?: return null
        return PresetSelection(
            activeMode = activeMode,
            activePresetFile = activePresetFile,
            activeCmdlineFile = activeCmdlineFile,
        )
    }

    override suspend fun writeActiveConfig(config: ActivePresetConfig): Boolean {
        return RuntimeConfigStore.setActiveModeValues(
            RuntimeConfigStore.CoreSettingsUpdate(
                presetMode = config.presetMode,
                presetFile = config.presetFile,
            ),
        )
    }

    override suspend fun snapshotFile(fileName: String): PresetFileSnapshot {
        if (!isSafeName(fileName)) return PresetFileSnapshot.Unsafe
        val path = "$presetsDir/$fileName"
        val quoted = RootFileIo.shellQuote(path)
        val result = ServiceLifecycleController.executeRoot(
            """
                if [ ! -e $quoted ] && [ ! -L $quoted ]; then echo MISSING; exit 0; fi
                [ -f $quoted ] && [ ! -L $quoted ] && [ -r $quoted ] &&
                    [ "${'$'}(stat -c %u $quoted 2>/dev/null)" = 0 ] &&
                    [ "${'$'}(stat -c %h $quoted 2>/dev/null)" = 1 ] || { echo UNSAFE; exit 0; }
                z2_mode=${'$'}(stat -c %a $quoted 2>/dev/null) || { echo UNSAFE; exit 0; }
                case "${'$'}z2_mode" in 600|644) ;; *) echo UNSAFE; exit 0 ;; esac
                z2_size=${'$'}(stat -c %s $quoted 2>/dev/null) || { echo UNSAFE; exit 0; }
                case "${'$'}z2_size" in ''|*[!0-9]*) echo UNSAFE; exit 0 ;; esac
                [ "${'$'}z2_size" -gt 0 ] && [ "${'$'}z2_size" -le $MAX_PRESET_BYTES ] ||
                    { echo UNSAFE; exit 0; }
                echo PRESENT
            """.trimIndent(),
        )
        if (!result.success) return PresetFileSnapshot.Unsafe
        return when (result.stdout.singleOrNull()?.trim()) {
            "MISSING" -> PresetFileSnapshot.Missing
            "PRESENT" -> RootFileIo.readSecureRegularText(path, MAX_PRESET_BYTES)
                ?.takeIf(PresetContentPolicy::isPersistable)
                ?.let(PresetFileSnapshot::Present)
                ?: PresetFileSnapshot.Unsafe
            else -> PresetFileSnapshot.Unsafe
        }
    }

    override suspend fun writeCandidate(fileName: String, content: String): Boolean {
        val normalized = PresetContentPolicy.normalizedForWrite(content)
        if (!isSafeName(fileName) || !PresetContentPolicy.isAllowed(normalized)) {
            return false
        }
        return RootFileIo.writeTextAtomically(
            "$presetsDir/$fileName",
            normalized,
            "__ZAPRET_PRESET_CANDIDATE_EOF__",
        )
    }

    override suspend fun replaceCandidate(candidateFileName: String, targetFileName: String): Boolean {
        if (!isSafeName(candidateFileName) || !PresetNamePolicy.isValid(targetFileName)) return false
        val candidate = "$presetsDir/$candidateFileName"
        val target = "$presetsDir/$targetFileName"
        val quotedCandidate = RootFileIo.shellQuote(candidate)
        val quotedTarget = RootFileIo.shellQuote(target)
        val command = """
            [ -f $quotedCandidate ] && [ ! -L $quotedCandidate ] &&
                [ "${'$'}(stat -c %u $quotedCandidate 2>/dev/null)" = 0 ] &&
                [ "${'$'}(stat -c %a $quotedCandidate 2>/dev/null)" = 644 ] &&
                [ "${'$'}(stat -c %h $quotedCandidate 2>/dev/null)" = 1 ] || exit 1
            z2_size=${'$'}(stat -c %s $quotedCandidate 2>/dev/null) || exit 1
            case "${'$'}z2_size" in ''|*[!0-9]*) exit 1 ;; esac
            [ "${'$'}z2_size" -gt 0 ] && [ "${'$'}z2_size" -le $MAX_PRESET_BYTES ] || exit 1
            if [ -e $quotedTarget ] || [ -L $quotedTarget ]; then
                [ -f $quotedTarget ] && [ ! -L $quotedTarget ] &&
                    [ "${'$'}(stat -c %u $quotedTarget 2>/dev/null)" = 0 ] &&
                    [ "${'$'}(stat -c %h $quotedTarget 2>/dev/null)" = 1 ] || exit 1
                z2_target_mode=${'$'}(stat -c %a $quotedTarget 2>/dev/null) || exit 1
                case "${'$'}z2_target_mode" in 600|644) ;; *) exit 1 ;; esac
            fi
            mv $quotedCandidate $quotedTarget || exit 1
            [ -f $quotedTarget ] && [ ! -L $quotedTarget ] &&
                [ "${'$'}(stat -c %u $quotedTarget 2>/dev/null)" = 0 ] &&
                [ "${'$'}(stat -c %a $quotedTarget 2>/dev/null)" = 644 ] &&
                [ "${'$'}(stat -c %h $quotedTarget 2>/dev/null)" = 1 ] || exit 1
            sync
        """.trimIndent()
        return ServiceLifecycleController.executeRoot(command).success
    }

    override suspend fun restoreFile(fileName: String, snapshot: PresetFileSnapshot): Boolean {
        if (!PresetNamePolicy.isValid(fileName)) return false
        return when (snapshot) {
            PresetFileSnapshot.Missing -> removeFile(fileName)
            is PresetFileSnapshot.Present -> {
                val normalized = PresetContentPolicy.normalizedForWrite(snapshot.content)
                PresetContentPolicy.isAllowed(normalized) && RootFileIo.writeTextAtomically(
                    "$presetsDir/$fileName",
                    normalized,
                    "__ZAPRET_PRESET_ROLLBACK_EOF__",
                )
            }
            PresetFileSnapshot.Unsafe -> false
        }
    }

    override suspend fun removeFile(fileName: String): Boolean {
        if (!isSafeName(fileName)) return false
        return RootFileIo.removeFile("$presetsDir/$fileName")
    }

    override suspend fun restart(): Boolean = ServiceLifecycleController.restart().success

    private fun isSafeName(fileName: String): Boolean =
        RootFileIo.isSimpleFileName(fileName, ".txt")

    private companion object {
        const val MAX_PRESET_BYTES = PresetContentPolicy.MAX_BYTES
    }
}

@Singleton
internal class TransactionalPresetRepository @Inject constructor(
    private val runner: PresetRunner,
    private val mutationGate: PresetMutationGate,
) : PresetRepository {

    override suspend fun loadCatalog(): PresetCatalog? = withContext(Dispatchers.IO) {
        val discovery = runner.scanPresets()?.let(PresetMachineProtocol::parseDiscovery)
            ?: return@withContext null
        val selection = runner.loadSelection() ?: return@withContext null
        PresetCatalog(
            discovery = discovery,
            selection = selection,
        )
    }

    override suspend fun readCompatible(fileName: String): String? = withContext(Dispatchers.IO) {
        if (!PresetNamePolicy.isValid(fileName)) return@withContext null
        if (runner.validatePreset(fileName, fileName) != PresetValidation.Compatible) return@withContext null
        (runner.snapshotFile(fileName) as? PresetFileSnapshot.Present)?.content
    }

    override suspend fun apply(fileName: String): PresetMutationOutcome = safelyMutate {
        if (!PresetNamePolicy.isValid(fileName)) {
            return@safelyMutate PresetMutationOutcome.Rejected(PresetIssue.UNSAFE_PRESET_NAME)
        }
        when (val validation = runner.validatePreset(fileName, fileName)) {
            PresetValidation.Compatible -> Unit
            is PresetValidation.Quarantined -> return@safelyMutate PresetMutationOutcome.Rejected(validation.issue)
            PresetValidation.ProtocolFailure -> return@safelyMutate PresetMutationOutcome.IoFailed
        }
        val oldConfig = runner.snapshotActiveConfig() ?: return@safelyMutate PresetMutationOutcome.IoFailed
        when (writeConfigResult(ActivePresetConfig("file", fileName))) {
            true -> Unit
            false -> return@safelyMutate PresetMutationOutcome.IoFailed
            null -> return@safelyMutate if (writeConfigOrFalse(oldConfig)) {
                PresetMutationOutcome.WriteFailedRolledBack
            } else {
                PresetMutationOutcome.RollbackFailed
            }
        }
        if (restartOrFalse()) PresetMutationOutcome.Applied
        else if (writeConfigOrFalse(oldConfig)) PresetMutationOutcome.RestartFailedRolledBack
        else PresetMutationOutcome.RollbackFailed
    }

    override suspend fun save(
        fileName: String,
        expectedContent: String?,
        content: String,
        applyAfterSave: Boolean,
    ): PresetMutationOutcome = safelyMutate {
        if (!PresetNamePolicy.isValid(fileName)) {
            return@safelyMutate PresetMutationOutcome.Rejected(PresetIssue.UNSAFE_PRESET_NAME)
        }
        val normalized = PresetContentPolicy.normalizedForWrite(content)
        if (!PresetContentPolicy.isAllowed(normalized)) {
            return@safelyMutate PresetMutationOutcome.Rejected(PresetIssue.PRESET_TOO_LARGE)
        }
        val oldFile = runner.snapshotFile(fileName)
        if (oldFile == PresetFileSnapshot.Unsafe) {
            return@safelyMutate PresetMutationOutcome.Rejected(PresetIssue.PRESET_SYMLINK)
        }
        val sourceMatches = when (oldFile) {
            PresetFileSnapshot.Missing -> expectedContent == null
            is PresetFileSnapshot.Present -> expectedContent != null &&
                canonicalProtectedText(oldFile.content) == canonicalProtectedText(expectedContent)
            PresetFileSnapshot.Unsafe -> false
        }
        if (!sourceMatches) return@safelyMutate PresetMutationOutcome.SourceChanged
        val oldConfig = if (applyAfterSave) runner.snapshotActiveConfig() else null
        if (applyAfterSave && oldConfig == null) return@safelyMutate PresetMutationOutcome.IoFailed

        val candidate = candidateName(fileName)
        if (!booleanResult { runner.writeCandidate(candidate, normalized) }) {
            return@safelyMutate cleanupCandidate(candidate, PresetMutationOutcome.IoFailed)
        }
        val candidateValidation = try {
            runner.validatePreset(candidate, fileName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@safelyMutate cleanupCandidate(candidate, PresetMutationOutcome.IoFailed)
        }
        when (candidateValidation) {
            PresetValidation.Compatible -> Unit
            is PresetValidation.Quarantined -> {
                return@safelyMutate cleanupCandidate(
                    candidate,
                    PresetMutationOutcome.Rejected(candidateValidation.issue),
                )
            }
            PresetValidation.ProtocolFailure -> {
                return@safelyMutate cleanupCandidate(candidate, PresetMutationOutcome.IoFailed)
            }
        }
        if (!booleanResult { runner.replaceCandidate(candidate, fileName) }) {
            val candidateRemoved = removeOrFalse(candidate)
            val targetUnchanged = snapshotFileOrNull(fileName) == oldFile
            return@safelyMutate when {
                targetUnchanged && candidateRemoved -> PresetMutationOutcome.IoFailed
                restoreFileOrFalse(fileName, oldFile) && candidateRemoved ->
                    PresetMutationOutcome.WriteFailedRolledBack
                else -> PresetMutationOutcome.RollbackFailed
            }
        }
        val persistedFile = snapshotFileOrNull(fileName)
        val persistedMatches = persistedFile is PresetFileSnapshot.Present &&
            canonicalProtectedText(persistedFile.content) == canonicalProtectedText(normalized)
        if (!persistedMatches) {
            return@safelyMutate if (restoreFileOrFalse(fileName, oldFile)) {
                PresetMutationOutcome.WriteFailedRolledBack
            } else {
                PresetMutationOutcome.RollbackFailed
            }
        }
        if (!applyAfterSave) return@safelyMutate PresetMutationOutcome.Saved

        val requestedConfig = ActivePresetConfig("file", fileName)
        when (writeConfigResult(requestedConfig)) {
            true -> Unit
            false -> return@safelyMutate if (restoreFileOrFalse(fileName, oldFile)) {
                PresetMutationOutcome.WriteFailedRolledBack
            } else {
                PresetMutationOutcome.RollbackFailed
            }
            null -> {
                val configRestored = writeConfigOrFalse(requireNotNull(oldConfig))
                val fileRestored = restoreFileOrFalse(fileName, oldFile)
                return@safelyMutate if (configRestored && fileRestored) {
                    PresetMutationOutcome.WriteFailedRolledBack
                } else {
                    PresetMutationOutcome.RollbackFailed
                }
            }
        }
        if (restartOrFalse()) return@safelyMutate PresetMutationOutcome.SavedAndApplied

        val configRestored = writeConfigOrFalse(requireNotNull(oldConfig))
        val fileRestored = restoreFileOrFalse(fileName, oldFile)
        if (configRestored && fileRestored) PresetMutationOutcome.RestartFailedRolledBack
        else PresetMutationOutcome.RollbackFailed
    }

    override suspend fun switchToCategories(): PresetMutationOutcome = safelyMutate {
        val oldConfig = runner.snapshotActiveConfig() ?: return@safelyMutate PresetMutationOutcome.IoFailed
        when (writeConfigResult(oldConfig.copy(presetMode = "categories"))) {
            true -> Unit
            false -> return@safelyMutate PresetMutationOutcome.IoFailed
            null -> return@safelyMutate if (writeConfigOrFalse(oldConfig)) {
                PresetMutationOutcome.WriteFailedRolledBack
            } else {
                PresetMutationOutcome.RollbackFailed
            }
        }
        if (restartOrFalse()) PresetMutationOutcome.CategoriesEnabled
        else if (writeConfigOrFalse(oldConfig)) PresetMutationOutcome.RestartFailedRolledBack
        else PresetMutationOutcome.RollbackFailed
    }

    private suspend fun cleanupCandidate(
        candidate: String,
        cleanOutcome: PresetMutationOutcome,
    ): PresetMutationOutcome = if (removeOrFalse(candidate)) {
        cleanOutcome
    } else {
        PresetMutationOutcome.RollbackFailed
    }

    private suspend fun snapshotFileOrNull(fileName: String): PresetFileSnapshot? = try {
        runner.snapshotFile(fileName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun restoreFileOrFalse(
        fileName: String,
        snapshot: PresetFileSnapshot,
    ): Boolean = booleanResult { runner.restoreFile(fileName, snapshot) }

    private suspend fun removeOrFalse(fileName: String): Boolean =
        booleanResult { runner.removeFile(fileName) }

    private suspend fun writeConfigOrFalse(config: ActivePresetConfig): Boolean =
        writeConfigResult(config) == true

    private suspend fun writeConfigResult(config: ActivePresetConfig): Boolean? = try {
        runner.writeActiveConfig(config)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun booleanResult(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun restartOrFalse(): Boolean = try {
        runner.restart()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun safelyMutate(
        block: suspend () -> PresetMutationOutcome,
    ): PresetMutationOutcome = try {
        withContext(Dispatchers.IO) { mutationGate.mutate(block) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
        PresetMutationOutcome.Blocked
    } catch (_: RuntimeConfigRollbackException) {
        PresetMutationOutcome.RollbackFailed
    } catch (_: Exception) {
        PresetMutationOutcome.IoFailed
    }

    private fun candidateName(fileName: String): String =
        "_${fileName.removeSuffix(".txt").take(180)}.candidate.${System.nanoTime()}.txt"
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PresetRepositoryModule {
    @Binds
    abstract fun bindPresetRepository(implementation: TransactionalPresetRepository): PresetRepository

    @Binds
    abstract fun bindPresetRunner(implementation: RootPresetRunner): PresetRunner

    @Binds
    abstract fun bindPresetMutationGate(implementation: ModulePresetMutationGate): PresetMutationGate
}
