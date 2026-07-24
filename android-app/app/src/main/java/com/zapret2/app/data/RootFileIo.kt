package com.zapret2.app.data

internal fun canonicalProtectedText(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trimEnd('\n')

internal sealed interface AtomicTextSnapshot {
    data object Missing : AtomicTextSnapshot
    data object Unsafe : AtomicTextSnapshot
    data object Failed : AtomicTextSnapshot
    data class Present(val content: String) : AtomicTextSnapshot
}

/** Small, centralized boundary for root file operations used by the app. */
internal object RootFileIo {

    private const val MAX_FILE_NAME_BYTES = 255
    private const val SNAPSHOT_MISSING = "Z2_ATOMIC_TEXT_MISSING"
    private const val SNAPSHOT_UNSAFE = "Z2_ATOMIC_TEXT_UNSAFE"
    private const val SNAPSHOT_PRESENT = "Z2_ATOMIC_TEXT_PRESENT"

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun isSimpleFileName(fileName: String, requiredSuffix: String? = null): Boolean {
        if (fileName.isBlank() || fileName.length > MAX_FILE_NAME_BYTES || fileName == "." || fileName == "..") return false
        if (fileName.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) return false
        if (fileName.trim() != fileName) return false
        if (fileName.any { it == '/' || it == '\\' || it == '\'' || it == '"' || it.isISOControl() }) return false
        if (requiredSuffix != null && !fileName.endsWith(requiredSuffix, ignoreCase = true)) return false
        return true
    }

    fun isDirectChildPath(path: String, parent: String, requiredSuffix: String? = null): Boolean {
        val normalizedParent = parent.trimEnd('/')
        if (!path.startsWith("$normalizedParent/")) return false
        val child = path.removePrefix("$normalizedParent/")
        return isSimpleFileName(child, requiredSuffix)
    }

    /**
     * Reads one snapshot of a project-owned mutable file.
     *
     * Project mutations publish these files by atomic rename, so stable device/inode/metadata
     * around one `cat` is the relevant observation contract. Re-hashing the file before and after
     * the read would add subprocesses without strengthening that project-owned transaction model.
     * Callers validate the captured bytes and derive any compare-and-swap digest from that exact
     * snapshot.
     */
    fun readAtomicMutableText(
        path: String,
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ): AtomicTextSnapshot {
        if (path.isBlank() || maxBytes <= 0) return AtomicTextSnapshot.Failed
        val minimumBytes = if (allowEmpty) 0 else 1
        val quoted = shellQuote(path)
        val command = """
            if [ ! -e $quoted ] && [ ! -L $quoted ]; then
                echo $SNAPSHOT_MISSING
                exit 0
            fi
            [ -f $quoted ] && [ ! -L $quoted ] && [ -r $quoted ] || {
                echo $SNAPSHOT_UNSAFE
                exit 0
            }
            z2_meta=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || {
                echo $SNAPSHOT_UNSAFE
                exit 0
            }
            IFS=: read -r z2_device z2_inode z2_uid z2_mode z2_links z2_size <<EOF
            ${'$'}z2_meta
            EOF
            [ "${'$'}z2_uid" = 0 ] && [ "${'$'}z2_links" = 1 ] || {
                echo $SNAPSHOT_UNSAFE
                exit 0
            }
            case "${'$'}z2_mode" in 600|644) ;; *)
                echo $SNAPSHOT_UNSAFE
                exit 0
                ;;
            esac
            case "${'$'}z2_size" in ''|*[!0-9]*)
                echo $SNAPSHOT_UNSAFE
                exit 0
                ;;
            esac
            [ "${'$'}z2_size" -ge $minimumBytes ] && [ "${'$'}z2_size" -le $maxBytes ] || {
                echo $SNAPSHOT_UNSAFE
                exit 0
            }
            echo $SNAPSHOT_PRESENT
            cat $quoted || exit 1
            z2_after=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || exit 1
            [ "${'$'}z2_after" = "${'$'}z2_meta" ]
        """.trimIndent()
        val result = RootCommandExecutor.execute(command)
        if (!result.isSuccess) return AtomicTextSnapshot.Failed
        return when (result.out.firstOrNull()) {
            SNAPSHOT_MISSING ->
                if (result.out.size == 1) AtomicTextSnapshot.Missing else AtomicTextSnapshot.Failed
            SNAPSHOT_UNSAFE ->
                if (result.out.size == 1) AtomicTextSnapshot.Unsafe else AtomicTextSnapshot.Failed
            SNAPSHOT_PRESENT -> {
                val content = result.out.drop(1).joinToString("\n")
                if ('\u0000' in content || (!allowEmpty && content.isEmpty())) {
                    AtomicTextSnapshot.Failed
                } else {
                    AtomicTextSnapshot.Present(content)
                }
            }
            else -> AtomicTextSnapshot.Failed
        }
    }

    /** Reads a stable protected text file; empty authoritative files require an explicit opt-in. */
    fun readSecureRegularText(
        path: String,
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ): String? {
        if (path.isBlank() || maxBytes <= 0) return null
        val minimumBytes = if (allowEmpty) 0 else 1
        val quoted = shellQuote(path)
        val command = """
            [ -f $quoted ] && [ ! -L $quoted ] || exit 1
            z2_meta=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || exit 1
            IFS=: read -r z2_device z2_inode z2_uid z2_mode z2_links z2_size <<EOF
            ${'$'}z2_meta
            EOF
            [ "${'$'}z2_uid" = 0 ] && [ "${'$'}z2_links" = 1 ] || exit 1
            case "${'$'}z2_mode" in 600|644) ;; *) exit 1 ;; esac
            case "${'$'}z2_size" in ''|*[!0-9]*) exit 1 ;; esac
            [ "${'$'}z2_size" -ge $minimumBytes ] && [ "${'$'}z2_size" -le $maxBytes ] || exit 1
            z2_digest_before=${'$'}(sha256sum $quoted 2>/dev/null) || exit 1
            z2_digest_before=${'$'}{z2_digest_before%% *}
            [ "${'$'}{#z2_digest_before}" -eq 64 ] || exit 1
            case "${'$'}z2_digest_before" in *[!0-9A-Fa-f]*) exit 1 ;; esac
            cat $quoted || exit 1
            z2_digest_after=${'$'}(sha256sum $quoted 2>/dev/null) || exit 1
            z2_digest_after=${'$'}{z2_digest_after%% *}
            z2_after=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || exit 1
            [ "${'$'}z2_after" = "${'$'}z2_meta" ] &&
                [ "${'$'}z2_digest_after" = "${'$'}z2_digest_before" ]
        """.trimIndent()
        val result = RootCommandExecutor.execute(command)
        if (!result.isSuccess) return null
        return result.out.joinToString("\n").takeIf { '\u0000' !in it }
    }

    /**
     * Reads a release-qualified immutable asset without re-hashing its bytes.
     *
     * Exhaustive byte and semantic validation belongs to release qualification. The active
     * generation receipt is the authority for packaged assets; this runtime boundary only keeps
     * the read bounded and proves that the root-owned regular file did not change identity while
     * it was being read.
     */
    fun readPublishedRegularText(path: String, maxBytes: Int): String? {
        if (path.isBlank() || maxBytes <= 0) return null
        val quoted = shellQuote(path)
        val command = """
            [ -f $quoted ] && [ ! -L $quoted ] || exit 1
            z2_meta=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || exit 1
            IFS=: read -r z2_device z2_inode z2_uid z2_mode z2_links z2_size <<EOF
            ${'$'}z2_meta
            EOF
            [ "${'$'}z2_uid" = 0 ] && [ "${'$'}z2_links" = 1 ] || exit 1
            [ "${'$'}z2_mode" = 644 ] || exit 1
            case "${'$'}z2_size" in ''|*[!0-9]*) exit 1 ;; esac
            [ "${'$'}z2_size" -gt 0 ] && [ "${'$'}z2_size" -le $maxBytes ] || exit 1
            cat $quoted || exit 1
            z2_after=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $quoted 2>/dev/null) || exit 1
            [ "${'$'}z2_after" = "${'$'}z2_meta" ]
        """.trimIndent()
        val result = RootCommandExecutor.execute(command)
        if (!result.isSuccess) return null
        return result.out.joinToString("\n").takeIf { '\u0000' !in it }
    }

    fun ensureDirectory(path: String): Boolean {
        ModuleMutationCoordinator.requirePrivilegedMutationContext()
        val quoted = shellQuote(path)
        val command = """
            if [ -e $quoted ] || [ -L $quoted ]; then
                [ -d $quoted ] && [ ! -L $quoted ] && [ "${'$'}(stat -c %u $quoted 2>/dev/null)" = 0 ] || exit 1
            else
                mkdir -p $quoted || exit 1
            fi
            [ -d $quoted ] && [ ! -L $quoted ] && [ "${'$'}(stat -c %u $quoted 2>/dev/null)" = 0 ]
        """.trimIndent()
        return RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION).isSuccess
    }

    fun removeFile(path: String): Boolean {
        ModuleMutationCoordinator.requirePrivilegedMutationContext()
        val quoted = shellQuote(path)
        val command = """
            if [ ! -e $quoted ] && [ ! -L $quoted ]; then exit 0; fi
            [ -f $quoted ] && [ ! -L $quoted ] &&
                [ "${'$'}(stat -c %u $quoted 2>/dev/null)" = 0 ] &&
                [ "${'$'}(stat -c %h $quoted 2>/dev/null)" = 1 ] || exit 1
            rm -f $quoted || exit 1
            [ ! -e $quoted ] && [ ! -L $quoted ]
        """.trimIndent()
        return RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION).isSuccess
    }

    /**
     * Writes to a sibling temporary file and renames it over the target. The rename is atomic on
     * the module filesystem, and failed writes clean up their temporary file.
     */
    fun writeTextAtomically(
        path: String,
        content: String,
        delimiterPrefix: String,
        fileMode: String = "0644",
        durable: Boolean = true,
    ): Boolean {
        ModuleMutationCoordinator.requirePrivilegedMutationContext()
        if (path.isBlank() || path.any { it == '\u0000' || it == '\n' || it == '\r' } || content.indexOf('\u0000') >= 0) {
            return false
        }
        if (fileMode !in setOf("0600", "0644")) return false
        if (!delimiterPrefix.matches(Regex("[A-Za-z0-9_]{1,80}"))) return false
        val normalized = normalizeLineEndings(content).trimEnd('\n') + "\n"
        val tempPath = "$path.tmp.${android.os.Process.myPid()}.${System.nanoTime()}"
        var delimiter = delimiterPrefix
        while (normalized.lineSequence().any { it == delimiter }) delimiter += "_X"

        val quotedTemp = shellQuote(tempPath)
        val quotedPath = shellQuote(path)
        val safeTarget = "{ [ ! -e $quotedPath ] && [ ! -L $quotedPath ]; } || " +
            "{ [ -f $quotedPath ] && [ ! -L $quotedPath ] && " +
            "[ \"${'$'}(stat -c %u $quotedPath 2>/dev/null)\" = 0 ] && " +
            "[ \"${'$'}(stat -c %h $quotedPath 2>/dev/null)\" = 1 ]; }"
        val command = buildString {
            append(safeTarget).append(" || exit 1\n")
            append("[ ! -e ").append(quotedTemp).append(" ] && [ ! -L ").append(quotedTemp)
            append(" ] || exit 1\n")
            append("cat > ").append(quotedTemp).append(" <<'").append(delimiter).append("'\n")
            append(normalized)
            append(delimiter).append("\n")
            append("status=$?\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then chmod ").append(fileMode).append(' ').append(quotedTemp)
            append("; status=$?; fi\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then [ \"${'$'}(stat -c %u ").append(quotedTemp)
            append(" 2>/dev/null)\" = 0 ] && [ \"${'$'}(stat -c %h ").append(quotedTemp)
            append(" 2>/dev/null)\" = 1 ]; status=$?; fi\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then ").append(safeTarget).append("; status=$?; fi\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then mv ").append(quotedTemp).append(' ').append(quotedPath)
            append("; status=$?; fi\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then [ \"${'$'}(stat -c %u ").append(quotedPath)
            append(" 2>/dev/null)\" = 0 ] && [ \"${'$'}(stat -c %a ").append(quotedPath)
            append(" 2>/dev/null)\" = ").append(fileMode.removePrefix("0")).append(" ] && ")
            append("[ \"${'$'}(stat -c %h ").append(quotedPath).append(" 2>/dev/null)\" = 1 ]; status=$?; fi\n")
            if (durable) {
                append("if [ \"${'$'}status\" -eq 0 ]; then sync; status=$?; fi\n")
            }
            append("if [ \"${'$'}status\" -ne 0 ]; then rm -f ").append(quotedTemp).append("; fi\n")
            append("exit \"${'$'}status\"")
        }
        if (!RootCommandExecutor.execute(command, RootCommandPolicy.MUTATION).isSuccess) return false

        val maxBytes = normalized.toByteArray(Charsets.UTF_8).size.coerceAtLeast(1)
        val persisted = readSecureRegularText(path, maxBytes) ?: return false
        return canonicalProtectedText(persisted) == canonicalProtectedText(normalized)
    }

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
