package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import javax.inject.Inject

internal sealed interface CommandLineRead {
    data class Content(val value: String) : CommandLineRead
    data object Empty : CommandLineRead
}

internal sealed interface CommandLineSnapshot {
    data class Present(val content: String) : CommandLineSnapshot
    data object Missing : CommandLineSnapshot
    data object Unsafe : CommandLineSnapshot
}

internal data class CommandLineBinding(
    val read: CommandLineRead,
    val manualSnapshot: CommandLineSnapshot,
)

internal enum class CommandLineValidation { VALID, INVALID, FAILED }

/** Protected manual nfqws2 command-line source. */
class CommandLineRepository @Inject constructor() {
    internal fun readBinding(fileName: String): CommandLineBinding? {
        val manual = snapshotManual(fileName)
        val read = when (manual) {
            is CommandLineSnapshot.Present -> if (manual.content.isNotBlank()) {
                CommandLineRead.Content(manual.content)
            } else {
                CommandLineRead.Empty
            }
            CommandLineSnapshot.Missing -> CommandLineRead.Empty
            CommandLineSnapshot.Unsafe -> return null
        }
        return CommandLineBinding(read = read, manualSnapshot = manual)
    }

    internal fun snapshotManual(fileName: String): CommandLineSnapshot {
        val path = manualCommandPath(fileName) ?: return CommandLineSnapshot.Unsafe
        val quoted = RootFileIo.shellQuote(path)
        val probe = Shell.cmd(
            "if [ ! -e $quoted ] && [ ! -L $quoted ]; then echo MISSING; else echo PRESENT; fi",
        ).exec()
        return when (probe.out.singleOrNull().takeIf { probe.isSuccess }) {
            "MISSING" -> CommandLineSnapshot.Missing
            "PRESENT" -> RootFileIo.readSecureRegularText(
                path = path,
                maxBytes = MAX_COMMAND_BYTES,
                allowEmpty = true,
            )
                ?.takeIf(::isContentSizeAllowed)
                ?.let(CommandLineSnapshot::Present) ?: CommandLineSnapshot.Unsafe
            else -> CommandLineSnapshot.Unsafe
        }
    }

    internal fun writeManual(fileName: String, content: String): Boolean {
        val path = manualCommandPath(fileName) ?: return false
        if (!isContentSizeAllowed(content)) return false
        return RootFileIo.writeTextAtomically(
            path,
            content,
            "__ZAPRET_CMDLINE_EOF__",
            fileMode = "0644",
        )
    }

    internal fun restore(fileName: String, snapshot: CommandLineSnapshot): Boolean = when (snapshot) {
        is CommandLineSnapshot.Present -> writeManual(fileName, snapshot.content)
        CommandLineSnapshot.Missing -> manualCommandPath(fileName)?.let(RootFileIo::removeFile) ?: false
        CommandLineSnapshot.Unsafe -> false
    }

    internal fun isSafeFileName(fileName: String): Boolean =
        RuntimeConfigStore.isSafeCommandLineFileName(fileName)

    internal fun isContentSizeAllowed(content: String): Boolean =
        (canonicalProtectedText(content) + "\n").toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES

    internal fun validateManual(fileName: String): CommandLineValidation {
        if (!isSafeFileName(fileName)) return CommandLineValidation.INVALID
        val result = ServiceLifecycleController.executeRoot(
            "/system/bin/sh ${RootFileIo.shellQuote(COMMAND_BUILDER)} --validate-cmdline-machine " +
                "${RootFileIo.shellQuote(MANUAL_COMMAND_DIRECTORY)} ${RootFileIo.shellQuote(fileName)}",
        )
        return parseCommandLineValidation(result.stdout, result.success, fileName)
    }

    private fun manualCommandPath(fileName: String): String? =
        fileName.takeIf(::isSafeFileName)?.let { "$MANUAL_COMMAND_DIRECTORY/$it" }

    internal companion object {
        const val MANUAL_COMMAND_DIRECTORY = "/data/adb/modules/zapret2/zapret2"
        const val COMMAND_BUILDER = "$MANUAL_COMMAND_DIRECTORY/scripts/command-builder.sh"
        const val MAX_COMMAND_BYTES = ModulePackageContract.MAX_PRESERVED_COMMAND_LINE_BYTES
    }
}

internal fun parseCommandLineValidation(
    output: List<String>,
    commandSucceeded: Boolean,
    fileName: String,
): CommandLineValidation {
    val line = output.singleOrNull() ?: return CommandLineValidation.FAILED
    return when {
        commandSucceeded && line == "Z2_CMDLINE\t1\tOK\t$fileName" -> CommandLineValidation.VALID
        !commandSucceeded && line == "Z2_CMDLINE\t1\tINVALID\t$fileName" -> CommandLineValidation.INVALID
        else -> CommandLineValidation.FAILED
    }
}
