package com.zapret2.app.data

import java.util.UUID

/** Installer-owned generation identity shared with customize.sh and common.sh. */
internal object InstallGenerationMetadata {
    const val RELATIVE_PATH = "zapret2/install-generation.meta"
    internal const val VERSION = "1"
    internal const val MAX_BYTES = 1024
    private val safeToken = Regex("[A-Za-z0-9._-]{1,128}")
    private val sha256 = Regex("[0-9a-f]{64}")
    internal val fields = setOf("version", "module_dir", "generation", "archive_sha256")

    data class Record(
        val generation: String,
        val archiveSha256: String,
    )

    fun create(archiveSha256: String): Record? = archiveSha256
        .takeIf(sha256::matches)
        ?.let { Record(UUID.randomUUID().toString(), it) }

    fun parse(lines: List<String>): Record? {
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val counts = pairs.groupingBy { it.first }.eachCount()
        if (pairs.size != fields.size || counts.keys != fields || fields.any { counts[it] != 1 }) return null
        val values = pairs.toMap()
        val generation = values["generation"].orEmpty()
        val archive = values["archive_sha256"].orEmpty()
        if (values["version"] != VERSION ||
            values["module_dir"] != RootModuleContract.ACTIVE_MODULE_DIR ||
            !safeToken.matches(generation) ||
            !sha256.matches(archive)
        ) return null
        return Record(generation, archive)
    }

    fun buildPublicationShell(moduleDir: String, record: Record): String {
        require(safeToken.matches(record.generation))
        require(sha256.matches(record.archiveSha256))
        val metadata = RootFileIo.shellQuote("$moduleDir/$RELATIVE_PATH")
        val temp = RootFileIo.shellQuote("$moduleDir/zapret2/.install-generation.meta.${record.generation}")
        return buildString {
            append("if [ \"${'$'}status\" -eq 0 ]; then\n")
            append("  metadata=$metadata\n  metadata_tmp=$temp\n")
            append("  [ ! -e \"${'$'}metadata\" ] && [ ! -L \"${'$'}metadata\" ] || status=1\n")
            append("  [ ! -e \"${'$'}metadata_tmp\" ] && [ ! -L \"${'$'}metadata_tmp\" ] || status=1\n")
            append("  if [ \"${'$'}status\" -eq 0 ]; then\n")
            append("    umask 077\n")
            append("    printf 'version=1\\nmodule_dir=${RootModuleContract.ACTIVE_MODULE_DIR}\\ngeneration=%s\\narchive_sha256=%s\\n' ")
            append(RootFileIo.shellQuote(record.generation)).append(' ')
            append(RootFileIo.shellQuote(record.archiveSha256))
            append(" > \"${'$'}metadata_tmp\" || status=${'$'}?\n")
            append("  fi\n")
            append("  if [ \"${'$'}status\" -eq 0 ]; then chmod 0600 \"${'$'}metadata_tmp\" || status=${'$'}?; fi\n")
            append("  if [ \"${'$'}status\" -eq 0 ]; then mv \"${'$'}metadata_tmp\" \"${'$'}metadata\" || status=${'$'}?; fi\n")
            append("  if [ \"${'$'}status\" -ne 0 ]; then rm -f \"${'$'}metadata_tmp\"; fi\n")
            append("fi\n")
            append("if [ \"${'$'}status\" -eq 0 ]; then\n")
            append(shellValidator("${'$'}metadata", required = true).prependIndent("  "))
            append(" || status=1\nfi\n")
        }
    }

    /** Exact shell-side schema/file validation. Optional mode supports legacy rollback backups. */
    fun shellValidator(pathExpression: String, required: Boolean): String {
        val absence = if (required) "false" else
            "[ ! -e \"${'$'}install_meta\" ] && [ ! -L \"${'$'}install_meta\" ]"
        return """
            {
              install_meta=$pathExpression
              if [ ! -e "${'$'}install_meta" ] && [ ! -L "${'$'}install_meta" ]; then
                $absence
              else
                [ -f "${'$'}install_meta" ] && [ ! -L "${'$'}install_meta" ] &&
                [ "${'$'}(stat -c %u "${'$'}install_meta" 2>/dev/null)" = 0 ] &&
                [ "${'$'}(stat -c %a "${'$'}install_meta" 2>/dev/null)" = 600 ] &&
                [ "${'$'}(stat -c %h "${'$'}install_meta" 2>/dev/null)" = 1 ] &&
                install_meta_size=${'$'}(wc -c < "${'$'}install_meta" 2>/dev/null) &&
                case "${'$'}install_meta_size" in ''|*[!0-9]*) false ;; *) [ "${'$'}install_meta_size" -gt 0 ] && [ "${'$'}install_meta_size" -le $MAX_BYTES ] ;; esac &&
                install_meta_seen="" install_meta_version="" install_meta_module="" install_meta_generation="" install_meta_archive="" &&
                while :; do
                  install_meta_key="" install_meta_value=""
                  IFS='=' read -r install_meta_key install_meta_value || [ -n "${'$'}install_meta_key${'$'}install_meta_value" ] || break
                  case "${'$'}install_meta_key" in
                    version) case "${'$'}install_meta_seen" in *v*) install_meta_seen=invalid; break ;; esac; install_meta_seen="${'$'}{install_meta_seen}v"; install_meta_version="${'$'}install_meta_value" ;;
                    module_dir) case "${'$'}install_meta_seen" in *m*) install_meta_seen=invalid; break ;; esac; install_meta_seen="${'$'}{install_meta_seen}m"; install_meta_module="${'$'}install_meta_value" ;;
                    generation) case "${'$'}install_meta_seen" in *g*) install_meta_seen=invalid; break ;; esac; install_meta_seen="${'$'}{install_meta_seen}g"; install_meta_generation="${'$'}install_meta_value" ;;
                    archive_sha256) case "${'$'}install_meta_seen" in *a*) install_meta_seen=invalid; break ;; esac; install_meta_seen="${'$'}{install_meta_seen}a"; install_meta_archive="${'$'}install_meta_value" ;;
                    *) install_meta_seen=invalid; break ;;
                  esac
                done < "${'$'}install_meta" &&
                [ "${'$'}{#install_meta_seen}" -eq 4 ] && [ "${'$'}install_meta_version" = 1 ] &&
                [ "${'$'}install_meta_module" = ${RootModuleContract.ACTIVE_MODULE_DIR} ] &&
                [ -n "${'$'}install_meta_generation" ] && [ "${'$'}{#install_meta_generation}" -le 128 ] &&
                case "${'$'}install_meta_generation" in *[!A-Za-z0-9._-]*) false ;; *) true ;; esac &&
                [ "${'$'}{#install_meta_archive}" -eq 64 ] &&
                case "${'$'}install_meta_archive" in *[!0-9a-f]*) false ;; *) true ;; esac
              fi
            }
        """.trimIndent()
    }
}
