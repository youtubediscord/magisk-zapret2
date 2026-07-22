package com.zapret2.app.data

/** Builds the fail-closed, allowlisted preservation part of a hot-update candidate. */
internal object ModuleUpdatePreservation {

    enum class DisableMarkerExpectation(val wireValue: String) {
        PRESENT("present"),
        ABSENT("absent");

        companion object {
            fun fromWireValue(value: String): DisableMarkerExpectation? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    fun buildShell(sourceModuleDir: String, targetModuleDir: String): String = buildString {
        artifacts(ModulePackageContract.PreservationPolicy.DIRECTORY_TREE).forEach { artifact ->
            appendDirectoryTree(artifact, sourceModuleDir, targetModuleDir)
        }
        artifacts(ModulePackageContract.PreservationPolicy.REGULAR_FILE).forEach { artifact ->
            appendRegularFile(artifact, sourceModuleDir, targetModuleDir, requireBound = false)
        }
        artifacts(ModulePackageContract.PreservationPolicy.BOUNDED_REGULAR_FILE).forEach { artifact ->
            appendRegularFile(artifact, sourceModuleDir, targetModuleDir, requireBound = true)
        }
        appendConfiguredCommandLine(sourceModuleDir, targetModuleDir)
        artifacts(ModulePackageContract.PreservationPolicy.MODULE_ROOT_MARKER).forEach { artifact ->
            appendModuleRootMarker(artifact, sourceModuleDir, targetModuleDir)
        }
    }

    /** Used when validating either the promoted candidate or a recovery copy. */
    fun safeDisableMarkerPredicate(moduleDir: String): String {
        val marker = RootFileIo.shellQuote("$moduleDir/${ModulePackageContract.DISABLE_MARKER}")
        return "{ [ ! -e $marker ] && [ ! -L $marker ]; } || " +
            "{ [ -f $marker ] && [ ! -L $marker ] && " +
            "[ \"\$(stat -c %u $marker 2>/dev/null)\" = 0 ] && " +
            "[ \"\$(stat -c %h $marker 2>/dev/null)\" = 1 ] && " +
            "[ \"\$(stat -c %s $marker 2>/dev/null)\" = 0 ] && " +
            "[ \"\$(stat -c %a $marker 2>/dev/null)\" = 600 ]; }"
    }

    /** Binds validation to the marker state observed before the directory swap. */
    fun expectedDisableMarkerPredicate(
        moduleDir: String,
        expectation: DisableMarkerExpectation,
    ): String {
        val marker = RootFileIo.shellQuote("$moduleDir/${ModulePackageContract.DISABLE_MARKER}")
        return when (expectation) {
            DisableMarkerExpectation.PRESENT ->
                "{ [ -e $marker ] && ${safeDisableMarkerPredicate(moduleDir)}; }"
            DisableMarkerExpectation.ABSENT ->
                "{ [ ! -e $marker ] && [ ! -L $marker ]; }"
        }
    }

    private fun artifacts(policy: ModulePackageContract.PreservationPolicy) =
        ModulePackageContract.preservationPlan.filter { it.policy == policy }

    private fun StringBuilder.appendDirectoryTree(
        artifact: ModulePackageContract.PreservationArtifact,
        sourceModuleDir: String,
        targetModuleDir: String,
    ) {
        val source = RootFileIo.shellQuote("$sourceModuleDir/${artifact.relativePath}")
        val target = RootFileIo.shellQuote("$targetModuleDir/${artifact.relativePath}")
        append("if [ \"${'$'}status\" -eq 0 ] && { [ -e $source ] || [ -L $source ]; }; then\n")
        append("  if [ ! -d $source ] || [ -L $source ]; then status=1\n")
        append("  elif find $source -type l -print -quit | grep -q .; then status=1\n")
        append("  elif find $source ! -type f ! -type d -print -quit | grep -q .; then status=1\n")
        append("  else rm -rf $target && mkdir -p $target && cp -R $source/. $target || status=${'$'}?; fi\n")
        append("fi\n")
    }

    private fun StringBuilder.appendRegularFile(
        artifact: ModulePackageContract.PreservationArtifact,
        sourceModuleDir: String,
        targetModuleDir: String,
        requireBound: Boolean,
    ) {
        val source = RootFileIo.shellQuote("$sourceModuleDir/${artifact.relativePath}")
        val target = RootFileIo.shellQuote("$targetModuleDir/${artifact.relativePath}")
        append("if [ \"${'$'}status\" -eq 0 ] && { [ -e $source ] || [ -L $source ]; }; then\n")
        append("  if [ ! -f $source ] || [ -L $source ] || ")
        append("[ \"${'$'}(stat -c %u $source 2>/dev/null)\" != 0 ] || ")
        append("[ \"${'$'}(stat -c %h $source 2>/dev/null)\" != 1 ]; then status=1\n")
        if (requireBound) {
            val maxBytes = requireNotNull(artifact.maxBytes)
            append("  else size=${'$'}(wc -c < $source 2>/dev/null) || status=1\n")
            append("    case \"${'$'}size\" in ''|*[!0-9]*) status=1 ;; esac\n")
            append("    if [ \"${'$'}status\" -eq 0 ] && [ \"${'$'}size\" -le $maxBytes ]; then\n")
            appendVerifiedCopy(source, target, indent = "      ")
            append("    else status=1; fi\n")
        } else {
            append("  else\n")
            appendVerifiedCopy(source, target, indent = "    ")
        }
        append("  fi\n")
        append("fi\n")
    }

    private fun StringBuilder.appendModuleRootMarker(
        artifact: ModulePackageContract.PreservationArtifact,
        sourceModuleDir: String,
        targetModuleDir: String,
    ) {
        val source = RootFileIo.shellQuote("$sourceModuleDir/${artifact.relativePath}")
        val target = RootFileIo.shellQuote("$targetModuleDir/${artifact.relativePath}")
        append("if [ \"${'$'}status\" -eq 0 ]; then\n")
        append("  if [ -e $source ] || [ -L $source ]; then\n")
        append("    if [ ! -f $source ] || [ -L $source ] || ")
        append("[ \"${'$'}(stat -c %u $source 2>/dev/null)\" != 0 ] || ")
        append("[ \"${'$'}(stat -c %h $source 2>/dev/null)\" != 1 ] || ")
        append("[ \"${'$'}(stat -c %s $source 2>/dev/null)\" != 0 ] || ")
        append("[ \"${'$'}(stat -c %a $source 2>/dev/null)\" != 600 ]; then status=1\n")
        append("    else\n")
        appendVerifiedMarkerCopy(source, target, indent = "      ")
        append("    fi\n")
        append("  elif [ -e $target ] || [ -L $target ]; then status=1\n")
        append("  fi\n")
        append("fi\n")
    }

    private fun StringBuilder.appendConfiguredCommandLine(
        sourceModuleDir: String,
        targetModuleDir: String,
    ) {
        val sourceDirectory = RootFileIo.shellQuote("$sourceModuleDir/zapret2")
        val targetDirectory = RootFileIo.shellQuote("$targetModuleDir/zapret2")
        val packageContract = RootFileIo.shellQuote(
            "$targetModuleDir/${ModulePackageContract.PACKAGE_CONTRACT_SCRIPT_PATH}",
        )
        val maxBytes = ModulePackageContract.MAX_PRESERVED_COMMAND_LINE_BYTES
        append("if [ \"${'$'}status\" -eq 0 ]; then\n")
        append("  [ -f $packageContract ] && [ ! -L $packageContract ] && . $packageContract || status=1\n")
        append("  if [ \"${'$'}status\" -eq 0 ]; then cmdline_relative=${'$'}(package_contract_configured_cmdline_relative ")
            .append(RootFileIo.shellQuote(sourceModuleDir)).append(") || status=1; fi\n")
        append("  if [ \"${'$'}status\" -eq 0 ]; then cmdline_name=${'$'}{cmdline_relative#zapret2/}; fi\n")
        append("  if [ \"${'$'}status\" -eq 0 ]; then\n")
        append("    source_cmdline=$sourceDirectory/\"${'$'}cmdline_name\"\n")
        append("    target_cmdline=$targetDirectory/\"${'$'}cmdline_name\"\n")
        append("    if [ -e \"${'$'}source_cmdline\" ] || [ -L \"${'$'}source_cmdline\" ]; then\n")
        append("      if [ -e \"${'$'}target_cmdline\" ] || [ -L \"${'$'}target_cmdline\" ]; then status=1\n")
        append("      elif [ ! -f \"${'$'}source_cmdline\" ] || [ -L \"${'$'}source_cmdline\" ] || ")
        append("[ \"${'$'}(stat -c %u \"${'$'}source_cmdline\" 2>/dev/null)\" != 0 ] || ")
        append("[ \"${'$'}(stat -c %h \"${'$'}source_cmdline\" 2>/dev/null)\" != 1 ]; then status=1\n")
        append("      else source_cmdline_mode=${'$'}(stat -c %a \"${'$'}source_cmdline\" 2>/dev/null) || status=1\n")
        append("        case \"${'$'}source_cmdline_mode\" in 600|644) ;; *) status=1 ;; esac\n")
        append("        if [ \"${'$'}status\" -eq 0 ]; then size=${'$'}(wc -c < \"${'$'}source_cmdline\" 2>/dev/null) || status=1; fi\n")
        append("        case \"${'$'}size\" in ''|*[!0-9]*) status=1 ;; esac\n")
        append("        if [ \"${'$'}status\" -eq 0 ] && [ \"${'$'}size\" -le $maxBytes ]; then\n")
        append("          cp -f \"${'$'}source_cmdline\" \"${'$'}target_cmdline\" || status=${'$'}?\n")
        append("          if [ \"${'$'}status\" -eq 0 ]; then chmod 0644 \"${'$'}target_cmdline\" || status=${'$'}?; fi\n")
        append("          if [ \"${'$'}status\" -eq 0 ]; then [ -f \"${'$'}target_cmdline\" ] && [ ! -L \"${'$'}target_cmdline\" ] && ")
        append("[ \"${'$'}(stat -c %u \"${'$'}target_cmdline\" 2>/dev/null)\" = 0 ] && ")
        append("[ \"${'$'}(stat -c %h \"${'$'}target_cmdline\" 2>/dev/null)\" = 1 ] && ")
        append("[ \"${'$'}(stat -c %a \"${'$'}target_cmdline\" 2>/dev/null)\" = 644 ] && ")
        append("cmp -s \"${'$'}source_cmdline\" \"${'$'}target_cmdline\" || status=1; fi\n")
        append("        else status=1; fi\n")
        append("      fi\n")
        append("    fi\n")
        append("  fi\n")
        append("fi\n")
    }

    private fun StringBuilder.appendVerifiedCopy(source: String, target: String, indent: String) {
        append(indent).append("cp -f $source $target || status=${'$'}?\n")
        append(indent).append("if [ \"${'$'}status\" -eq 0 ]; then ")
        append("[ -f $target ] && [ ! -L $target ] && ")
        append("[ \"${'$'}(stat -c %u $target 2>/dev/null)\" = 0 ] && ")
        append("[ \"${'$'}(stat -c %h $target 2>/dev/null)\" = 1 ] && ")
        append("cmp -s $source $target || status=1; fi\n")
    }

    private fun StringBuilder.appendVerifiedMarkerCopy(source: String, target: String, indent: String) {
        append(indent).append("cp -f $source $target || status=${'$'}?\n")
        append(indent).append("if [ \"${'$'}status\" -eq 0 ]; then chmod 0600 $target || status=${'$'}?; fi\n")
        append(indent).append("if [ \"${'$'}status\" -eq 0 ]; then ")
        append("[ -f $target ] && [ ! -L $target ] && ")
        append("[ \"${'$'}(stat -c %u $target 2>/dev/null)\" = 0 ] && ")
        append("[ \"${'$'}(stat -c %h $target 2>/dev/null)\" = 1 ] && ")
        append("[ \"${'$'}(stat -c %s $target 2>/dev/null)\" = 0 ] && ")
        append("[ \"${'$'}(stat -c %a $target 2>/dev/null)\" = 600 ] && ")
        append("cmp -s $source $target || status=1; fi\n")
    }
}
