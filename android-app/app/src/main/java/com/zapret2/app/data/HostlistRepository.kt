package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

private val normalizeHostlistDataLineAwk = """
    z2_line = ${'$'}0
    sub(/^[[:space:]]+/, "", z2_line)
    sub(/[[:space:]]+${'$'}/, "", z2_line)
    if (z2_line == "" || substr(z2_line, 1, 1) == "#") next
""".trimIndent()

internal data class HostlistFileRecord(
    val fileName: String,
    val path: String,
    val entryCount: Int,
    val sizeBytes: Long,
)

internal data class HostlistSearchPage(
    val items: List<String>,
    val totalMatches: Int,
)

internal enum class HostlistTargetState { MISSING, PRESENT, UNSAFE }

internal sealed interface HostlistSnapshot {
    data class Present(val content: String) : HostlistSnapshot
    data object Missing : HostlistSnapshot
    data object Unsafe : HostlistSnapshot
}

internal sealed interface HostlistWriteOutcome {
    data class Written(val replacedExisting: Boolean) : HostlistWriteOutcome
    data object Failed : HostlistWriteOutcome
    data object RollbackFailed : HostlistWriteOutcome
}

internal sealed interface HostlistConditionalWriteOutcome {
    data class Written(val persistedContent: String) : HostlistConditionalWriteOutcome
    data object SourceChanged : HostlistConditionalWriteOutcome
    data object Failed : HostlistConditionalWriteOutcome
    data object RollbackFailed : HostlistConditionalWriteOutcome
}

/** Root-backed hostlist boundary. ViewModels never construct privileged shell commands. */
class HostlistRepository @Inject constructor() {

    internal fun isAllowedPath(path: String): Boolean {
        val prefix = "$LISTS_DIR/"
        if (!path.startsWith(prefix)) return false
        return pathForFileName(path.removePrefix(prefix)) == path
    }

    internal fun pathForFileName(fileName: String): String? =
        fileName.takeIf(::isValidFileName)?.let { "$LISTS_DIR/$it" }

    internal fun inspectTarget(path: String): HostlistTargetState {
        if (!isAllowedPath(path)) return HostlistTargetState.UNSAFE
        val quoted = RootFileIo.shellQuote(path)
        val result = Shell.cmd(
            """
                if [ ! -e $quoted ] && [ ! -L $quoted ]; then echo MISSING; exit 0; fi
                [ -f $quoted ] && [ ! -L $quoted ] && [ "${'$'}(stat -c %u $quoted 2>/dev/null)" = 0 ] &&
                    [ "${'$'}(stat -c %h $quoted 2>/dev/null)" = 1 ] || { echo UNSAFE; exit 0; }
                z2_mode=${'$'}(stat -c %a $quoted 2>/dev/null) || { echo UNSAFE; exit 0; }
                case "${'$'}z2_mode" in 600|644) ;; *) echo UNSAFE; exit 0 ;; esac
                z2_size=${'$'}(stat -c %s $quoted 2>/dev/null) || { echo UNSAFE; exit 0; }
                case "${'$'}z2_size" in ''|*[!0-9]*) echo UNSAFE; exit 0 ;; esac
                [ "${'$'}z2_size" -le $MAX_HOSTLIST_BYTES ] || { echo UNSAFE; exit 0; }
                echo PRESENT
            """.trimIndent(),
        ).exec()
        return result.out.singleOrNull()?.takeIf { result.isSuccess }?.let {
            runCatching { HostlistTargetState.valueOf(it) }.getOrNull()
        } ?: HostlistTargetState.UNSAFE
    }

    internal fun listFiles(): Result<List<HostlistFileRecord>> = try {
        val directory = RootFileIo.shellQuote(LISTS_DIR)
        val result = Shell.cmd(
            """
                [ -d $directory ] && [ ! -L $directory ] || exit 1
                for z2_file in $directory/*.txt; do
                    [ -e "${'$'}z2_file" ] || continue
                    [ -f "${'$'}z2_file" ] && [ ! -L "${'$'}z2_file" ] && [ -r "${'$'}z2_file" ] || exit 1
                    z2_name=${'$'}{z2_file##*/}
                    case "${'$'}z2_name" in ''|*[!A-Za-z0-9._-]*|.*|*.|*..) exit 1 ;; esac
                    z2_meta=${'$'}(stat -c '%u:%a:%h:%s' "${'$'}z2_file" 2>/dev/null) || exit 1
                    IFS=: read -r z2_uid z2_mode z2_links z2_size <<EOF
                    ${'$'}z2_meta
                    EOF
                    [ "${'$'}z2_uid" = 0 ] && [ "${'$'}z2_links" = 1 ] || exit 1
                    case "${'$'}z2_mode" in 600|644) ;; *) exit 1 ;; esac
                    case "${'$'}z2_size" in ''|*[!0-9]*) exit 1 ;; esac
                    [ "${'$'}z2_size" -le $MAX_HOSTLIST_BYTES ] || exit 1
                    z2_lines=${'$'}(awk '
                        {
                            $normalizeHostlistDataLineAwk
                            z2_count++
                        }
                        END { print z2_count + 0 }
                    ' "${'$'}z2_file" 2>/dev/null) || exit 1
                    case "${'$'}z2_lines" in ''|*[!0-9]*) exit 1 ;; esac
                    printf '%s|%s|%s|%s\n' "${'$'}z2_name" "${'$'}z2_file" "${'$'}z2_lines" "${'$'}z2_size"
                done
            """.trimIndent(),
        ).exec()
        check(result.isSuccess) { "Unable to enumerate protected hostlists" }
        Result.success(
            result.out.map { line -> requireNotNull(parseHostlistRecord(line)) }
                .sortedByDescending(HostlistFileRecord::entryCount),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    internal fun countEntries(path: String): Int? {
        if (!isAllowedPath(path)) return null
        val quoted = RootFileIo.shellQuote(path)
        val result = Shell.cmd(
            secureFilePrelude(quoted) + "\n" + """
                awk '
                    {
                        $normalizeHostlistDataLineAwk
                        z2_count++
                    }
                    END { print z2_count + 0 }
                ' $quoted 2>/dev/null
            """.trimIndent(),
        ).exec()
        return result.out.singleOrNull()?.trim()?.toIntOrNull()?.takeIf { result.isSuccess && it >= 0 }
    }

    internal fun readPage(path: String, offset: Int, limit: Int): List<String>? {
        if (!isAllowedPath(path) || offset < 0 || limit !in 1..MAX_PAGE_SIZE) return null
        val quoted = RootFileIo.shellQuote(path)
        val start = offset + 1
        val end = offset + limit
        val result = Shell.cmd(
            secureFilePrelude(quoted) + "\n" + """
                awk -v z2_start=$start -v z2_end=$end '
                    {
                        $normalizeHostlistDataLineAwk
                        z2_count++
                        if (z2_count >= z2_start && z2_count <= z2_end) print z2_line
                        if (z2_count >= z2_end) exit
                    }
                ' $quoted 2>/dev/null
            """.trimIndent(),
        ).exec()
        return result.out.takeIf { result.isSuccess }?.normalizeDataLines()
    }

    internal fun searchPage(path: String, query: String, offset: Int, limit: Int): HostlistSearchPage? {
        val normalizedQuery = query.replace('\r', ' ').replace('\n', ' ').trim().take(MAX_QUERY_CHARS)
        if (!isAllowedPath(path) || normalizedQuery.isEmpty() || offset < 0 || limit !in 1..MAX_PAGE_SIZE) {
            return null
        }
        val quotedPath = RootFileIo.shellQuote(path)
        val quotedQuery = RootFileIo.shellQuote(normalizedQuery)
        val start = offset + 1
        val end = offset + limit
        val result = Shell.cmd(
            """
                ${secureFilePrelude(quotedPath)}
                Z2_HOSTLIST_NEEDLE=$quotedQuery awk -v z2_start=$start -v z2_end=$end '
                    BEGIN { z2_needle = tolower(ENVIRON["Z2_HOSTLIST_NEEDLE"]); z2_count = 0 }
                    {
                        $normalizeHostlistDataLineAwk
                        if (index(tolower(z2_line), z2_needle)) {
                            z2_count++
                            if (z2_count >= z2_start && z2_count <= z2_end) {
                                z2_page[z2_count - z2_start + 1] = z2_line
                            }
                        }
                    }
                    END {
                        printf "$COUNT_PREFIX%d\n", z2_count
                        for (z2_i = 1; z2_i <= z2_end - z2_start + 1; z2_i++) {
                            if (z2_i in z2_page) print z2_page[z2_i]
                        }
                    }
                ' $quotedPath 2>/dev/null
            """.trimIndent(),
        ).exec()
        if (!result.isSuccess || result.out.isEmpty()) return null
        val countLine = result.out.first()
        val count = countLine.removePrefix(COUNT_PREFIX).takeIf { countLine.startsWith(COUNT_PREFIX) }
            ?.toIntOrNull() ?: return null
        return HostlistSearchPage(result.out.drop(1).normalizeDataLines(), count)
    }

    internal fun readForEditing(path: String): String? {
        if (!isAllowedPath(path)) return null
        return RootFileIo.readSecureRegularText(
            path = path,
            maxBytes = MAX_EDIT_BYTES,
            allowEmpty = true,
        )
            ?.takeIf(::isEditableContentSizeAllowed)
    }

    internal fun ensureDirectory(): Boolean = RootFileIo.ensureDirectory(LISTS_DIR)

    internal fun snapshot(path: String): HostlistSnapshot = when (inspectTarget(path)) {
        HostlistTargetState.MISSING -> HostlistSnapshot.Missing
        HostlistTargetState.PRESENT -> RootFileIo.readSecureRegularText(
            path = path,
            maxBytes = MAX_EDIT_BYTES,
            allowEmpty = true,
        )
            ?.takeIf(::isEditableContentSizeAllowed)
            ?.let(HostlistSnapshot::Present) ?: HostlistSnapshot.Unsafe
        HostlistTargetState.UNSAFE -> HostlistSnapshot.Unsafe
    }

    internal fun writeWithRollback(
        path: String,
        content: String,
        delimiterPrefix: String,
    ): HostlistWriteOutcome {
        if (!isValidWritableContent(path, content)) return HostlistWriteOutcome.Failed
        val snapshot = snapshot(path)
        if (snapshot == HostlistSnapshot.Unsafe) return HostlistWriteOutcome.Failed
        val written = writeOrFalse(path, content, delimiterPrefix)
        if (written) {
            return HostlistWriteOutcome.Written(snapshot is HostlistSnapshot.Present)
        }
        return if (restoreOrFalse(path, snapshot)) {
            HostlistWriteOutcome.Failed
        } else {
            HostlistWriteOutcome.RollbackFailed
        }
    }

    internal fun writeIfUnchangedWithRollback(
        path: String,
        expectedContent: String,
        content: String,
        delimiterPrefix: String,
    ): HostlistConditionalWriteOutcome {
        if (!isValidWritableContent(path, content)) return HostlistConditionalWriteOutcome.Failed
        val snapshot = snapshot(path)
        if (snapshot !is HostlistSnapshot.Present) {
            return if (snapshot == HostlistSnapshot.Unsafe) {
                HostlistConditionalWriteOutcome.Failed
            } else {
                HostlistConditionalWriteOutcome.SourceChanged
            }
        }
        if (snapshot.content != expectedContent) return HostlistConditionalWriteOutcome.SourceChanged
        val written = writeOrFalse(path, content, delimiterPrefix)
        val persisted = if (written) {
            readForEditingOrNull(path)
        } else {
            null
        }
        if (persisted != null && canonicalProtectedText(persisted) == canonicalProtectedText(content)) {
            return HostlistConditionalWriteOutcome.Written(persisted)
        }
        return if (restoreOrFalse(path, snapshot)) {
            HostlistConditionalWriteOutcome.Failed
        } else {
            HostlistConditionalWriteOutcome.RollbackFailed
        }
    }

    private fun write(path: String, content: String, delimiterPrefix: String): Boolean {
        if (!isAllowedPath(path) || !isEditableContentSizeAllowed(content)) return false
        if (inspectTarget(path) == HostlistTargetState.UNSAFE) return false
        return RootFileIo.writeTextAtomically(path, content, delimiterPrefix, fileMode = "0644")
    }

    private fun restore(path: String, snapshot: HostlistSnapshot): Boolean = when (snapshot) {
        is HostlistSnapshot.Present -> write(path, snapshot.content, "Z2_HOSTLIST_ROLLBACK")
        HostlistSnapshot.Missing -> RootFileIo.removeFile(path)
        HostlistSnapshot.Unsafe -> false
    }

    private fun writeOrFalse(path: String, content: String, delimiterPrefix: String): Boolean = try {
        write(path, content, delimiterPrefix)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun readForEditingOrNull(path: String): String? = try {
        readForEditing(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun restoreOrFalse(path: String, snapshot: HostlistSnapshot): Boolean = try {
        restore(path, snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    internal fun isEditableContentSizeAllowed(content: String): Boolean =
        (canonicalProtectedText(content) + "\n").toByteArray(Charsets.UTF_8).size <= MAX_EDIT_BYTES

    private fun isValidWritableContent(path: String, content: String): Boolean {
        if (!isAllowedPath(path) || !isEditableContentSizeAllowed(content)) return false
        return isValidHostlistContent(path.substringAfterLast('/'), content)
    }

    private fun secureFilePrelude(quotedPath: String): String = """
        [ -f $quotedPath ] && [ ! -L $quotedPath ] && [ -r $quotedPath ] || exit 1
        z2_meta=${'$'}(stat -c '%u:%a:%h:%s' $quotedPath 2>/dev/null) || exit 1
        IFS=: read -r z2_uid z2_mode z2_links z2_size <<EOF
        ${'$'}z2_meta
        EOF
        [ "${'$'}z2_uid" = 0 ] && [ "${'$'}z2_links" = 1 ] || exit 1
        case "${'$'}z2_mode" in 600|644) ;; *) exit 1 ;; esac
        case "${'$'}z2_size" in ''|*[!0-9]*) exit 1 ;; esac
        [ "${'$'}z2_size" -le $MAX_HOSTLIST_BYTES ] || exit 1
    """.trimIndent()

    private fun List<String>.normalizeDataLines(): List<String> =
        mapNotNull(::normalizedHostlistDataLineOrNull)

    internal companion object {
        internal const val LISTS_DIR = "/data/adb/modules/zapret2/zapret2/lists"
        internal const val MAX_HOSTLIST_BYTES = 16 * 1024 * 1024
        const val MAX_EDIT_BYTES = 1024 * 1024
        const val MAX_PAGE_SIZE = 500
        const val MAX_QUERY_CHARS = 256
        const val COUNT_PREFIX = "Z2_MATCH_COUNT="

        internal fun isValidFileName(fileName: String): Boolean =
            RootFileIo.isSimpleFileName(fileName) &&
                fileName.endsWith(".txt", ignoreCase = true) &&
                fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))
    }
}

internal fun parseHostlistRecord(line: String): HostlistFileRecord? {
    val parts = line.split('|', limit = 4)
    if (parts.size != 4) return null
    val fileName = parts[0]
    val path = parts[1]
    val lines = parts[2].toIntOrNull()
    val bytes = parts[3].toLongOrNull()
    return HostlistFileRecord(
        fileName = fileName,
        path = path,
        entryCount = lines ?: return null,
        sizeBytes = bytes ?: return null,
    ).takeIf {
        HostlistRepository.isValidFileName(fileName) &&
            RootFileIo.isDirectChildPath(path, HostlistRepository.LISTS_DIR, ".txt") &&
            path == "${HostlistRepository.LISTS_DIR}/$fileName" &&
            it.entryCount >= 0 && it.sizeBytes in 0..HostlistRepository.MAX_HOSTLIST_BYTES.toLong()
    }
}
