package com.zapret2.app.data

/**
 * One root-framework boundary shared by module discovery, lifecycle commands and updates.
 *
 * Magisk, KernelSU and APatch intentionally use the same active and pending module roots.
 * Their installation commands differ, so backend selection is kept here instead of being
 * reimplemented by repositories and controllers.
 */
object RootModuleContract {
    const val MODULE_ID = "zapret2"
    const val MODULE_STORAGE_DIR = "/data/adb"
    const val ACTIVE_MODULES_DIR = "$MODULE_STORAGE_DIR/modules"
    const val PENDING_MODULES_DIR = "$MODULE_STORAGE_DIR/modules_update"
    const val ACTIVE_MODULE_DIR = "$ACTIVE_MODULES_DIR/$MODULE_ID"
    const val PENDING_MODULE_DIR = "$PENDING_MODULES_DIR/$MODULE_ID"
    const val RUNTIME_DIR = "$ACTIVE_MODULE_DIR/zapret2"
    const val SCRIPTS_DIR = "$RUNTIME_DIR/scripts"

    internal enum class InstallerBackend(val wireValue: String) {
        MAGISK("magisk"),
        KERNEL_SU("kernelsu"),
        APATCH("apatch"),
    }

    internal fun installerProbeCommand(): String = """
        z2_root_executable_is_safe() {
            [ -f "${'$'}1" ] && [ ! -L "${'$'}1" ] && [ -x "${'$'}1" ] &&
                [ "${'$'}(stat -c %u "${'$'}1" 2>/dev/null)" = 0 ]
        }
        if z2_root_executable_is_safe /data/adb/apd &&
            /data/adb/apd --version >/dev/null 2>&1; then
            printf 'Z2_ROOT_INSTALLER=apatch\n'
        elif z2_root_executable_is_safe /data/adb/ksud &&
            /data/adb/ksud --version >/dev/null 2>&1; then
            printf 'Z2_ROOT_INSTALLER=kernelsu\n'
        elif command -v magisk >/dev/null 2>&1 && magisk -v >/dev/null 2>&1; then
            printf 'Z2_ROOT_INSTALLER=magisk\n'
        else
            printf 'Z2_ROOT_INSTALLER=unsupported\n'
        fi
    """.trimIndent()

    internal fun parseInstallerBackend(lines: List<String>): InstallerBackend? {
        val value = lines.singleOrNull()
            ?.takeIf { it.startsWith("Z2_ROOT_INSTALLER=") }
            ?.substringAfter('=')
            ?: return null
        return InstallerBackend.entries.firstOrNull { it.wireValue == value }
    }

    internal fun installCommand(backend: InstallerBackend, archivePath: String): String {
        val archive = RootFileIo.shellQuote(archivePath)
        return when (backend) {
            InstallerBackend.MAGISK -> "magisk --install-module $archive"
            InstallerBackend.KERNEL_SU -> "/data/adb/ksud module install $archive"
            InstallerBackend.APATCH -> "/data/adb/apd module install $archive"
        }
    }
}
