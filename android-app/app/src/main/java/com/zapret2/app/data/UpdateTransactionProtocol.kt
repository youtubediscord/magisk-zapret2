package com.zapret2.app.data

import java.security.MessageDigest

/** Pure builders for owner-bound, digest-CAS update journal mutations. */
internal object UpdateTransactionProtocol {
    const val TRANSACTION_VERSION = "3"
    const val LEGACY_TRANSACTION_VERSION = "2"
    const val CLEANUP_VERSION = "2"
    const val LEGACY_CLEANUP_VERSION = "1"
    const val CLEANUP_PENDING = "${ModuleMutationCoordinator.STATE_DIR}/update.cleanup"

    data class Owner(
        val pid: String,
        val starttime: String,
        val createdEpoch: String,
        val bootId: String,
        val token: String,
    )

    data class Publication(val digest: String, val command: String)

    data class CleanupPending(
        val owner: Owner,
        val transactionDigest: String,
        val paths: List<String>,
        val version: String = CLEANUP_VERSION,
    )

    data class TerminalPlan(
        val command: String,
        val pending: CleanupPending,
        val pendingDigest: String,
    )

    enum class TerminalResolution {
        COMMITTED,
        NOT_COMMITTED,
        AMBIGUOUS_COMMITTED,
    }

    /** A retained journal must never retain its live app-process lock after the operation ends. */
    fun shouldReleaseOwnerLock(
        markerAcquired: Boolean,
        @Suppress("UNUSED_PARAMETER") recoveryArtifactsRetained: Boolean,
    ): Boolean = markerAcquired

    fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun buildPublication(
        owner: Owner,
        content: String,
        expectedPriorDigest: String?,
        tempPath: String,
    ): Publication? {
        if (!validOwner(owner) || expectedPriorDigest != null && !validDigest(expectedPriorDigest)) return null
        val newDigest = sha256(content)
        val delimiter = "__Z2_TRANSACTION_${owner.pid}_${newDigest.take(16)}__"
        val command = buildString {
            appendPrelude(owner, tempPath)
            appendPriorCas(expectedPriorDigest, cleanupTemp = false)
            append("umask 077\nrm -f \"${'$'}tmp\"\n")
            append("cat > \"${'$'}tmp\" <<'").append(delimiter).append("'\n")
            append(content).append(delimiter).append('\n')
            append("chmod 0600 \"${'$'}tmp\" || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}(stat -c %u \"${'$'}tmp\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %h \"${'$'}tmp\" 2>/dev/null)\" = 1 ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}(sha256sum \"${'$'}tmp\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(newDigest)).append(" ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            appendOwnerCas(owner, cleanupTemp = true)
            appendPriorCas(expectedPriorDigest, cleanupTemp = true)
            append("mv -f \"${'$'}tmp\" \"${'$'}transaction\" || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("sync || exit 1\n")
            append("[ \"${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(newDigest)).append(" ]\n")
        }
        return Publication(newDigest, command)
    }

    fun buildTerminalDelete(
        owner: Owner,
        expectedDigest: String,
        prerequisite: String,
        cleanupPaths: List<String> = emptyList(),
    ): String? = buildTerminalDeletePlan(owner, expectedDigest, prerequisite, cleanupPaths)?.command

    fun buildTerminalDeletePlan(
        owner: Owner,
        expectedDigest: String,
        prerequisite: String,
        cleanupPaths: List<String> = emptyList(),
    ): TerminalPlan? {
        if (!validOwner(owner) || !validDigest(expectedDigest) || prerequisite.isBlank() ||
            cleanupPaths.size !in 1..4 || cleanupPaths.any { !validCleanupPath(it) } ||
            cleanupPaths.distinct().size != cleanupPaths.size
        ) return null
        val pending = CleanupPending(owner, expectedDigest, cleanupPaths)
        val pendingContent = cleanupContent(pending)
        val pendingDigest = sha256(pendingContent)
        val delimiter = "__Z2_CLEANUP_${owner.pid}_${pendingDigest.take(16)}__"
        val command = buildString {
            appendPrelude(owner, tempPath = null)
            append("cleanup=").append(RootFileIo.shellQuote(CLEANUP_PENDING)).append('\n')
            append("cleanup_tmp=").append(RootFileIo.shellQuote("$CLEANUP_PENDING.${owner.pid}.${owner.token}.tmp")).append('\n')
            appendPriorCas(expectedDigest, cleanupTemp = false)
            append("{ ").append(prerequisite).append("; } || exit 1\n")
            cleanupPaths.forEach { path ->
                append(cleanupPathPredicate(path)).append(" || exit 1\n")
            }
            append("[ ! -e \"${'$'}cleanup\" ] && [ ! -L \"${'$'}cleanup\" ] || exit 1\n")
            append("umask 077\nrm -f \"${'$'}cleanup_tmp\"\n")
            append("cat > \"${'$'}cleanup_tmp\" <<'").append(delimiter).append("'\n")
            append(pendingContent).append(delimiter).append('\n')
            append("chmod 0600 \"${'$'}cleanup_tmp\" || { rm -f \"${'$'}cleanup_tmp\"; exit 1; }\n")
            append("[ \"${'$'}(stat -c %u \"${'$'}cleanup_tmp\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %a \"${'$'}cleanup_tmp\" 2>/dev/null)\" = 600 ] && ")
                .append("[ \"${'$'}(stat -c %h \"${'$'}cleanup_tmp\" 2>/dev/null)\" = 1 ] || { rm -f \"${'$'}cleanup_tmp\"; exit 1; }\n")
            append("[ \"${'$'}(sha256sum \"${'$'}cleanup_tmp\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(pendingDigest)).append(" ] || { rm -f \"${'$'}cleanup_tmp\"; exit 1; }\n")
            appendOwnerCas(owner, cleanupTemp = false)
            appendPriorCas(expectedDigest, cleanupTemp = false)
            append("mv -f \"${'$'}cleanup_tmp\" \"${'$'}cleanup\" || { rm -f \"${'$'}cleanup_tmp\"; exit 1; }\n")
            append("sync || { rm -f \"${'$'}cleanup\"; exit 1; }\n")
            append("[ -f \"${'$'}cleanup\" ] && [ ! -L \"${'$'}cleanup\" ] && ")
                .append("[ \"${'$'}(stat -c %u \"${'$'}cleanup\" 2>/dev/null)\" = 0 ] && ")
                .append("[ \"${'$'}(stat -c %a \"${'$'}cleanup\" 2>/dev/null)\" = 600 ] && ")
                .append("[ \"${'$'}(stat -c %h \"${'$'}cleanup\" 2>/dev/null)\" = 1 ] && ")
                .append("[ \"${'$'}(sha256sum \"${'$'}cleanup\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(pendingDigest)).append(" ] || exit 1\n")
            append("{ ").append(prerequisite).append("; } || exit 1\n")
            appendOwnerCas(owner, cleanupTemp = false)
            appendPriorCas(expectedDigest, cleanupTemp = false)
            cleanupPaths.forEach { path ->
                append(cleanupPathPredicate(path)).append(" || exit 1\n")
            }
            append("rm -f \"${'$'}transaction\" || exit 1\n")
            append("cleanup_ok=1\nrm -rf ")
            cleanupPaths.forEach { append(RootFileIo.shellQuote(it)).append(' ') }
            append("|| cleanup_ok=0\n")
            cleanupPaths.forEach { path ->
                val quoted = RootFileIo.shellQuote(path)
                append("[ ! -e $quoted ] && [ ! -L $quoted ] || cleanup_ok=0\n")
            }
            append("sync || cleanup_ok=0\n")
            append("if [ \"${'$'}cleanup_ok\" = 1 ]; then rm -f \"${'$'}cleanup\" || true; sync || true; fi\n")
            append("exit 0\n")
        }
        return TerminalPlan(command, pending, pendingDigest)
    }

    fun buildTerminalCommitProbe(owner: Owner, plan: TerminalPlan): String? {
        if (!validOwner(owner) || plan.pending.owner != owner ||
            sha256(cleanupContent(plan.pending)) != plan.pendingDigest
        ) return null
        return buildString {
            appendPrelude(owner, tempPath = null)
            append("cleanup=").append(RootFileIo.shellQuote(CLEANUP_PENDING)).append('\n')
            append("if [ -e \"${'$'}transaction\" ] || [ -L \"${'$'}transaction\" ]; then\n")
            appendPriorCas(plan.pending.transactionDigest, cleanupTemp = false)
            append("echo Z2_TERMINAL_COMMIT=not_committed\nexit 0\nfi\n")
            append("if [ -e \"${'$'}cleanup\" ] || [ -L \"${'$'}cleanup\" ]; then\n")
            appendCleanupCas(plan.pendingDigest)
            append("fi\necho Z2_TERMINAL_COMMIT=committed\nexit 0\n")
        }
    }

    fun resolveTerminalAttempt(
        primarySucceeded: Boolean,
        probeSucceeded: Boolean,
        probeLines: List<String>,
    ): TerminalResolution = when {
        primarySucceeded -> TerminalResolution.COMMITTED
        !probeSucceeded -> TerminalResolution.AMBIGUOUS_COMMITTED
        probeLines == listOf("Z2_TERMINAL_COMMIT=committed") -> TerminalResolution.COMMITTED
        probeLines == listOf("Z2_TERMINAL_COMMIT=not_committed") -> TerminalResolution.NOT_COMMITTED
        else -> TerminalResolution.AMBIGUOUS_COMMITTED
    }

    fun buildConsumeUncommittedCleanup(
        owner: Owner,
        expectedTransactionDigest: String,
        cleanupPaths: List<String>,
    ): String? {
        if (!validOwner(owner) || !validDigest(expectedTransactionDigest) || cleanupPaths.size !in 1..4 ||
            cleanupPaths.any { !validCleanupPath(it) } || cleanupPaths.distinct().size != cleanupPaths.size
        ) return null
        val pending = CleanupPending(owner, expectedTransactionDigest, cleanupPaths)
        val pendingDigest = sha256(cleanupContent(pending))
        return buildString {
            appendPrelude(owner, tempPath = null)
            append("cleanup=").append(RootFileIo.shellQuote(CLEANUP_PENDING)).append('\n')
            appendPriorCas(expectedTransactionDigest, cleanupTemp = false)
            append("if [ ! -e \"${'$'}cleanup\" ] && [ ! -L \"${'$'}cleanup\" ]; then exit 0; fi\n")
            appendCleanupCas(pendingDigest)
            appendOwnerCas(owner, cleanupTemp = false)
            appendPriorCas(expectedTransactionDigest, cleanupTemp = false)
            appendCleanupCas(pendingDigest)
            append("rm -f \"${'$'}cleanup\" || exit 1\n")
            append("sync || true\nexit 0\n")
        }
    }

    fun cleanupContent(pending: CleanupPending): String = buildString {
        append("version=").append(pending.version).append('\n')
        append("owner_pid=").append(pending.owner.pid).append('\n')
        append("owner_starttime=").append(pending.owner.starttime).append('\n')
        append("owner_created_epoch=").append(pending.owner.createdEpoch).append('\n')
        if (pending.version == CLEANUP_VERSION) {
            append("owner_boot_id=").append(pending.owner.bootId).append('\n')
        }
        append("owner_token=").append(pending.owner.token).append('\n')
        append("transaction_digest=").append(pending.transactionDigest).append('\n')
        append("cleanup_count=").append(pending.paths.size).append('\n')
        pending.paths.forEachIndexed { index, path ->
            append("cleanup_").append(index + 1).append('=').append(path).append('\n')
        }
    }

    fun parseCleanupPending(lines: List<String>): CleanupPending? {
        if (lines.isEmpty() || lines.any { it.isBlank() || it != it.trim() || '=' !in it }) return null
        val pairs = lines.map { it.substringBefore('=') to it.substringAfter('=') }
        if (pairs.map { it.first }.distinct().size != pairs.size) return null
        val values = pairs.toMap()
        val count = values["cleanup_count"]?.toIntOrNull() ?: return null
        if (count !in 1..4) return null
        val version = values["version"] ?: return null
        val ownerKeys = when (version) {
            CLEANUP_VERSION -> setOf("owner_pid", "owner_starttime", "owner_created_epoch", "owner_boot_id", "owner_token")
            LEGACY_CLEANUP_VERSION -> setOf("owner_pid", "owner_starttime", "owner_created_epoch", "owner_token")
            else -> return null
        }
        val expectedKeys = setOf("version", "transaction_digest", "cleanup_count") +
            ownerKeys + (1..count).map { "cleanup_$it" }
        if (values.keys != expectedKeys) return null
        val owner = Owner(
            values.getValue("owner_pid"),
            values.getValue("owner_starttime"),
            values.getValue("owner_created_epoch"),
            values["owner_boot_id"].orEmpty(),
            values.getValue("owner_token"),
        )
        val digest = values.getValue("transaction_digest")
        val paths = (1..count).map { values.getValue("cleanup_$it") }
        return CleanupPending(owner, digest, paths, version).takeIf {
            (validOwner(owner) || version == LEGACY_CLEANUP_VERSION && validLegacyOwner(owner)) &&
                validDigest(digest) && paths.distinct().size == paths.size &&
                paths.all(::validCleanupPath)
        }
    }

    fun buildPendingCleanupRecovery(
        recoveryOwner: Owner,
        pending: CleanupPending,
        expectedPendingDigest: String,
    ): String? {
        if (!validOwner(recoveryOwner) || !validDigest(expectedPendingDigest) ||
            sha256(cleanupContent(pending)) != expectedPendingDigest
        ) return null
        return buildString {
            appendPrelude(recoveryOwner, tempPath = null)
            append("cleanup=").append(RootFileIo.shellQuote(CLEANUP_PENDING)).append('\n')
            appendCleanupCas(expectedPendingDigest)
            pending.paths.forEach { path ->
                append(cleanupPathPredicate(path)).append(" || exit 1\n")
            }
            appendOwnerCas(recoveryOwner, cleanupTemp = false)
            appendCleanupCas(expectedPendingDigest)
            pending.paths.forEach { path ->
                append(cleanupPathPredicate(path)).append(" || exit 1\n")
            }
            append("rm -rf ")
            pending.paths.forEach { append(RootFileIo.shellQuote(it)).append(' ') }
            append("|| exit 1\n")
            pending.paths.forEach { path ->
                val quoted = RootFileIo.shellQuote(path)
                append("[ ! -e $quoted ] && [ ! -L $quoted ] || exit 1\n")
            }
            append("sync || exit 1\n")
            appendOwnerCas(recoveryOwner, cleanupTemp = false)
            appendCleanupCas(expectedPendingDigest)
            append("rm -f \"${'$'}cleanup\" || exit 1\n")
            append("sync || true\nexit 0\n")
        }
    }

    fun buildDiscardUncommittedCleanup(
        recoveryOwner: Owner,
        expectedTransactionDigest: String,
        expectedPendingDigest: String,
    ): String? {
        if (!validOwner(recoveryOwner) || !validDigest(expectedTransactionDigest) ||
            !validDigest(expectedPendingDigest)
        ) return null
        return buildString {
            appendPrelude(recoveryOwner, tempPath = null)
            append("cleanup=").append(RootFileIo.shellQuote(CLEANUP_PENDING)).append('\n')
            appendPriorCas(expectedTransactionDigest, cleanupTemp = false)
            appendCleanupCas(expectedPendingDigest)
            appendOwnerCas(recoveryOwner, cleanupTemp = false)
            appendPriorCas(expectedTransactionDigest, cleanupTemp = false)
            appendCleanupCas(expectedPendingDigest)
            append("rm -f \"${'$'}cleanup\" || exit 1\n")
            append("sync || true\nexit 0\n")
        }
    }

    fun buildActiveMove(
        owner: Owner,
        intentDigest: String,
        resultContent: String,
        moduleDir: String,
        backupDir: String,
        sourcePrerequisite: String,
        movedPrerequisite: String,
        tempPath: String,
    ): Publication? {
        if (!validOwner(owner) || !validDigest(intentDigest) ||
            moduleDir != ServiceLifecycleController.MODULE_DIR || !validBackupPath(backupDir) ||
            sourcePrerequisite.isBlank() || movedPrerequisite.isBlank()
        ) return null
        val resultDigest = sha256(resultContent)
        val delimiter = "__Z2_ACTIVE_MOVED_${owner.pid}_${resultDigest.take(16)}__"
        val module = RootFileIo.shellQuote(moduleDir)
        val backup = RootFileIo.shellQuote(backupDir)
        val command = buildString {
            appendPrelude(owner, tempPath)
            appendPriorCas(intentDigest, cleanupTemp = false)
            append("{ ").append(sourcePrerequisite).append("; } || exit 1\n")
            append("[ ! -e $backup ] && [ ! -L $backup ] || exit 1\n")
            append("umask 077\nrm -f \"${'$'}tmp\"\n")
            append("cat > \"${'$'}tmp\" <<'").append(delimiter).append("'\n")
            append(resultContent).append(delimiter).append('\n')
            append("chmod 0600 \"${'$'}tmp\" || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ \"${'$'}(sha256sum \"${'$'}tmp\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(resultDigest)).append(" ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            appendOwnerCas(owner, cleanupTemp = true)
            appendPriorCas(intentDigest, cleanupTemp = true)
            append("{ ").append(sourcePrerequisite).append("; } || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ ! -e $backup ] && [ ! -L $backup ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("mv $module $backup || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            appendOwnerCas(owner, cleanupTemp = true)
            appendPriorCas(intentDigest, cleanupTemp = true)
            append("{ ").append(movedPrerequisite).append("; } || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("[ ! -e $module ] && [ ! -L $module ] || { rm -f \"${'$'}tmp\"; exit 1; }\n")
            append("mv -f \"${'$'}tmp\" \"${'$'}transaction\" || exit 1\n")
            append("sync || exit 1\n")
            append("[ \"${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
                .append(RootFileIo.shellQuote(resultDigest)).append(" ]\n")
        }
        return Publication(resultDigest, command)
    }

    /** Promotes only the exact prepared directory while the active-move journal is still owned. */
    fun buildCandidatePromotion(
        owner: Owner,
        expectedDigest: String,
        updateDir: String,
        moduleDir: String,
        candidatePrerequisite: String,
        promotedPrerequisite: String,
    ): String? {
        if (!validOwner(owner) || !validDigest(expectedDigest) || !validUpdatePath(updateDir) ||
            moduleDir != ServiceLifecycleController.MODULE_DIR || candidatePrerequisite.isBlank() ||
            promotedPrerequisite.isBlank()
        ) return null
        val update = RootFileIo.shellQuote(updateDir)
        val module = RootFileIo.shellQuote(moduleDir)
        val updatePredicate = secureRootDirectoryPredicate(update)
        val modulePredicate = secureRootDirectoryPredicate(module)
        return buildString {
            appendPrelude(owner, tempPath = null)
            appendPriorCas(expectedDigest, cleanupTemp = false)
            append(updatePredicate).append(" || exit 1\n")
            append("{ ").append(candidatePrerequisite).append("; } || exit 1\n")
            append("[ ! -e $module ] && [ ! -L $module ] || exit 1\n")
            appendOwnerCas(owner, cleanupTemp = false)
            appendPriorCas(expectedDigest, cleanupTemp = false)
            append(updatePredicate).append(" || exit 1\n")
            append("{ ").append(candidatePrerequisite).append("; } || exit 1\n")
            append("[ ! -e $module ] && [ ! -L $module ] || exit 1\n")
            append("mv $update $module || exit 1\n")
            append("sync || exit 1\n")
            append("[ ! -e $update ] && [ ! -L $update ] && ")
                .append(modulePredicate).append(" && { ")
                .append(promotedPrerequisite).append("; }\n")
        }
    }

    /** Reusable exact owner CAS prefix, optionally bound to one transaction digest. */
    fun buildOwnerGuard(owner: Owner, expectedTransactionDigest: String?): String? {
        if (!validOwner(owner) ||
            expectedTransactionDigest != null && !validDigest(expectedTransactionDigest)
        ) return null
        return buildString {
            appendPrelude(owner, tempPath = null)
            appendPriorCas(expectedTransactionDigest, cleanupTemp = false)
            appendOwnerCas(owner, cleanupTemp = false)
        }
    }

    /** Exact owner/journal CAS prefix for compound rollback and recovery mutations. */
    fun buildOwnerTransactionGuard(owner: Owner, expectedDigest: String): String? =
        buildOwnerGuard(owner, expectedDigest)

    private fun StringBuilder.appendPrelude(owner: Owner, tempPath: String?) {
        append("state_dir=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)).append('\n')
        append("lock=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)).append('\n')
        append("transaction=").append(RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)).append('\n')
        tempPath?.let { append("tmp=").append(RootFileIo.shellQuote(it)).append('\n') }
        append("[ -d \"${'$'}state_dir\" ] && [ ! -L \"${'$'}state_dir\" ] && ")
            .append("[ \"${'$'}(stat -c %u \"${'$'}state_dir\" 2>/dev/null)\" = 0 ] && ")
            .append("[ \"${'$'}(stat -c %a \"${'$'}state_dir\" 2>/dev/null)\" = 700 ] || exit 1\n")
        append(UpdateLockProtocol.shellParser()).append('\n')
        appendOwnerCas(owner, cleanupTemp = false)
    }

    private fun StringBuilder.appendOwnerCas(owner: Owner, cleanupTemp: Boolean) {
        val failure = if (cleanupTemp) "{ rm -f \"${'$'}tmp\"; exit 1; }" else "exit 1"
        append("z2_read_update_lock \"${'$'}lock\" && z2_update_lock_owner_alive || $failure\n")
        append("[ \"${'$'}z2_lock_pid:${'$'}z2_lock_start:${'$'}z2_lock_created:${'$'}z2_lock_boot:${'$'}z2_lock_token\" = ")
            .append(RootFileIo.shellQuote("${owner.pid}:${owner.starttime}:${owner.createdEpoch}:${owner.bootId}:${owner.token}"))
            .append(" ] || $failure\n")
    }

    private fun StringBuilder.appendPriorCas(expectedDigest: String?, cleanupTemp: Boolean) {
        val failure = if (cleanupTemp) "{ rm -f \"${'$'}tmp\"; exit 1; }" else "exit 1"
        if (expectedDigest == null) {
            append("[ ! -e \"${'$'}transaction\" ] && [ ! -L \"${'$'}transaction\" ] || $failure\n")
            return
        }
        append("[ -f \"${'$'}transaction\" ] && [ ! -L \"${'$'}transaction\" ] && ")
            .append("[ \"${'$'}(stat -c %u \"${'$'}transaction\" 2>/dev/null)\" = 0 ] && ")
            .append("[ \"${'$'}(stat -c %a \"${'$'}transaction\" 2>/dev/null)\" = 600 ] && ")
            .append("[ \"${'$'}(stat -c %h \"${'$'}transaction\" 2>/dev/null)\" = 1 ] || $failure\n")
        append("[ \"${'$'}(sha256sum \"${'$'}transaction\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
            .append(RootFileIo.shellQuote(expectedDigest)).append(" ] || $failure\n")
    }

    private fun StringBuilder.appendCleanupCas(expectedDigest: String) {
        append("[ -f \"${'$'}cleanup\" ] && [ ! -L \"${'$'}cleanup\" ] && ")
            .append("[ \"${'$'}(stat -c %u \"${'$'}cleanup\" 2>/dev/null)\" = 0 ] && ")
            .append("[ \"${'$'}(stat -c %a \"${'$'}cleanup\" 2>/dev/null)\" = 600 ] && ")
            .append("[ \"${'$'}(stat -c %h \"${'$'}cleanup\" 2>/dev/null)\" = 1 ] || exit 1\n")
        append("[ \"${'$'}(sha256sum \"${'$'}cleanup\" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = ")
            .append(RootFileIo.shellQuote(expectedDigest)).append(" ] || exit 1\n")
    }

    private fun validOwner(owner: Owner): Boolean =
        owner.pid.matches(Regex("[1-9][0-9]*")) && owner.pid.toIntOrNull() != null &&
            ProtocolDecimal.isCanonicalNonNegativeLong(owner.starttime) &&
            ProtocolDecimal.isCanonicalNonNegativeLong(owner.createdEpoch) &&
            owner.bootId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
            owner.token.matches(Regex("[A-Za-z0-9._-]+"))

    private fun validLegacyOwner(owner: Owner): Boolean =
        owner.bootId.isEmpty() && owner.pid.matches(Regex("[1-9][0-9]*")) &&
            owner.pid.toIntOrNull() != null &&
            ProtocolDecimal.isCanonicalNonNegativeLong(owner.starttime) &&
            ProtocolDecimal.isCanonicalNonNegativeLong(owner.createdEpoch) &&
            owner.token.matches(Regex("[A-Za-z0-9._-]+"))

    private fun validDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

    private fun validCleanupPath(value: String): Boolean = value.matches(
        Regex("/data/adb/modules/\\.zapret2-(?:update|backup|failed|recovery)-[A-Za-z0-9._-]+"),
    )

    private fun validUpdatePath(value: String): Boolean = value.matches(
        Regex("/data/adb/modules/\\.zapret2-update-[A-Za-z0-9._-]+"),
    )

    private fun validBackupPath(value: String): Boolean = value.matches(
        Regex("/data/adb/modules/\\.zapret2-backup-[A-Za-z0-9._-]+"),
    )

    private fun secureRootDirectoryPredicate(quotedPath: String): String =
        "{ [ -d $quotedPath ] && [ ! -L $quotedPath ] && " +
            "[ \"${'$'}(stat -c %u $quotedPath 2>/dev/null)\" = 0 ] && " +
            "z2_promote_mode=${'$'}(stat -c %a $quotedPath 2>/dev/null) && " +
            "case \"${'$'}z2_promote_mode\" in 700|711|750|751|755) true ;; *) false ;; esac; }"

    private fun cleanupPathPredicate(path: String): String {
        val quoted = RootFileIo.shellQuote(path)
        return "{ [ ! -e $quoted ] && [ ! -L $quoted ]; } || " +
            secureRootDirectoryPredicate(quoted)
    }
}
