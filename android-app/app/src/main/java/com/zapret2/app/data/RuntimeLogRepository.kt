package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal enum class RuntimeLogSelection { MAIN, WARNINGS }

internal sealed interface ProtectedTextRead {
    data class Content(val value: String) : ProtectedTextRead
    data object Absent : ProtectedTextRead
    data object Failed : ProtectedTextRead
}

/** Bounded, identity-checked access to private runtime diagnostics. */
class RuntimeLogRepository @Inject constructor() {

    internal fun readCommandLine(): ProtectedTextRead = readSingleFile(
        path = OwnerStateSchema.RUNTIME_CMDLINE_FILE,
        maxBytes = MAX_CMDLINE_BYTES,
        maxLines = null,
    )

    internal fun readFailureTail(): ProtectedTextRead {
        val error = readSingleFile(OwnerStateSchema.ERROR_LOG_FILE, MAX_LOG_FILE_BYTES, FAILURE_LOG_LINES)
        if (error is ProtectedTextRead.Content && error.value.isNotBlank()) return error
        if (error == ProtectedTextRead.Failed) return error
        return readSingleFile(OwnerStateSchema.LOG_FILE, MAX_LOG_FILE_BYTES, FALLBACK_LOG_LINES)
    }

    internal fun readLogs(selection: RuntimeLogSelection): ProtectedTextRead {
        val paths = when (selection) {
            RuntimeLogSelection.MAIN -> listOf(OwnerStateSchema.LOG_FILE)
            RuntimeLogSelection.WARNINGS -> listOf(OwnerStateSchema.LOG_FILE, OwnerStateSchema.ERROR_LOG_FILE)
        }
        val command = buildString {
            append("z2_any=0\n")
            paths.forEach { path ->
                val quoted = RootFileIo.shellQuote(path)
                append("if [ -e ").append(quoted).append(" ] || [ -L ").append(quoted).append(" ]; then\n")
                append(secureFilePredicate(quoted, MAX_LOG_FILE_BYTES)).append(" || exit 1\n")
                append("z2_any=1\ntail -n ").append(MAX_LOG_LINES).append(' ').append(quoted).append(" || exit 1\nfi\n")
            }
            append("[ \"${'$'}z2_any\" = 1 ] || echo ").append(ABSENT_MARKER).append('\n')
        }
        return try {
            val result = Shell.cmd(command).exec()
            when {
                !result.isSuccess -> ProtectedTextRead.Failed
                result.out == listOf(ABSENT_MARKER) -> ProtectedTextRead.Absent
                result.out.any { it == ABSENT_MARKER } -> ProtectedTextRead.Failed
                else -> ProtectedTextRead.Content(
                    result.out.joinToString("\n").takeLast(MAX_RENDER_CHARS),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProtectedTextRead.Failed
        }
    }

    internal fun clear(selection: RuntimeLogSelection): Boolean {
        ModuleMutationCoordinator.requirePrivilegedMutationContext()
        val paths = when (selection) {
            RuntimeLogSelection.MAIN -> listOf(OwnerStateSchema.LOG_FILE)
            RuntimeLogSelection.WARNINGS -> listOf(OwnerStateSchema.LOG_FILE, OwnerStateSchema.ERROR_LOG_FILE)
        }
        val command = buildString {
            paths.forEach { path ->
                val quoted = RootFileIo.shellQuote(path)
                append("if [ -e ").append(quoted).append(" ] || [ -L ").append(quoted).append(" ]; then\n")
                append(secureFilePredicate(quoted, MAX_LOG_FILE_BYTES)).append(" || exit 1\n")
                append(": > ").append(quoted).append(" || exit 1\n")
                append("chmod 0600 ").append(quoted).append(" || exit 1\n")
                append(secureFilePredicate(quoted, MAX_LOG_FILE_BYTES)).append(" || exit 1\nfi\n")
            }
        }
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun readSingleFile(path: String, maxBytes: Int, maxLines: Int?): ProtectedTextRead {
        val quoted = RootFileIo.shellQuote(path)
        val reader = maxLines?.let { "tail -n $it $quoted" } ?: "cat $quoted"
        val command = """
            if [ ! -e $quoted ] && [ ! -L $quoted ]; then
                echo $ABSENT_MARKER
                exit 0
            fi
            ${secureFilePredicate(quoted, maxBytes)} || exit 1
            $reader
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            when {
                !result.isSuccess -> ProtectedTextRead.Failed
                result.out == listOf(ABSENT_MARKER) -> ProtectedTextRead.Absent
                result.out.any { it == ABSENT_MARKER } -> ProtectedTextRead.Failed
                else -> ProtectedTextRead.Content(result.out.joinToString("\n").takeLast(MAX_RENDER_CHARS))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProtectedTextRead.Failed
        }
    }

    private fun secureFilePredicate(quotedPath: String, maxBytes: Int): String =
        "[ -f $quotedPath ] && [ ! -L $quotedPath ] && [ -r $quotedPath ] && " +
            "[ \"${'$'}(stat -c %u $quotedPath 2>/dev/null)\" = 0 ] && " +
            "[ \"${'$'}(stat -c %a $quotedPath 2>/dev/null)\" = 600 ] && " +
            "[ \"${'$'}(stat -c %h $quotedPath 2>/dev/null)\" = 1 ] && " +
            "z2_size=${'$'}(stat -c %s $quotedPath 2>/dev/null) && " +
            "case \"${'$'}z2_size\" in ''|*[!0-9]*) false ;; *) " +
            "[ \"${'$'}z2_size\" -le $maxBytes ] ;; esac"

    private companion object {
        const val ABSENT_MARKER = "Z2_PROTECTED_TEXT_ABSENT=1"
        const val MAX_CMDLINE_BYTES = 256 * 1024
        const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_LOG_LINES = 500
        const val FAILURE_LOG_LINES = 200
        const val FALLBACK_LOG_LINES = 20
        const val MAX_RENDER_CHARS = 256 * 1024
    }
}
