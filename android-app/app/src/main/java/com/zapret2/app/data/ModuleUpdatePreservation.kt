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
        artifacts(ModulePackageContract.PreservationPolicy.CUSTOM_PRESETS).forEach { artifact ->
            appendCustomPresets(artifact, sourceModuleDir, targetModuleDir)
        }
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

    private fun StringBuilder.appendCustomPresets(
        artifact: ModulePackageContract.PreservationArtifact,
        sourceModuleDir: String,
        targetModuleDir: String,
    ) {
        val sourceDirectory = RootFileIo.shellQuote("$sourceModuleDir/${artifact.relativePath}")
        val targetDirectory = RootFileIo.shellQuote("$targetModuleDir/${artifact.relativePath}")
        val packageContract = RootFileIo.shellQuote(
            "$targetModuleDir/${ModulePackageContract.PACKAGE_CONTRACT_SCRIPT_PATH}",
        )
        val commandBuilder = RootFileIo.shellQuote(
            "$targetModuleDir/${ModulePackageContract.COMMAND_BUILDER_SCRIPT_PATH}",
        )
        val zapretDirectory = RootFileIo.shellQuote("$targetModuleDir/zapret2")
        append("if [ \"${'$'}status\" -eq 0 ] && { [ -e $sourceDirectory ] || [ -L $sourceDirectory ]; }; then\n")
        append("  if [ ! -d $sourceDirectory ] || [ -L $sourceDirectory ]; then status=1\n")
        append("  elif find $sourceDirectory -mindepth 1 -maxdepth 1 ! -type f -print -quit | grep -q .; then status=1\n")
        append("  elif find $sourceDirectory -mindepth 1 -maxdepth 1 -type f ! -name '*.txt' -print -quit | grep -q .; then status=1\n")
        append("  else\n")
        append("    [ -f $packageContract ] && [ ! -L $packageContract ] && . $packageContract || status=1\n")
        append("    for source_preset in $sourceDirectory/*.txt; do\n")
        append("      [ \"${'$'}status\" -eq 0 ] || break\n")
        append("      [ -e \"${'$'}source_preset\" ] || continue\n")
        append("      preset_name=${'$'}{source_preset##*/}\n")
        append("      package_contract_safe_preset_name \"${'$'}preset_name\" || { status=1; break; }\n")
        append("      target_preset=$targetDirectory/\"${'$'}preset_name\"\n")
        append("      if [ -e \"${'$'}target_preset\" ] || [ -L \"${'$'}target_preset\" ]; then\n")
        append("        [ -f \"${'$'}target_preset\" ] && [ ! -L \"${'$'}target_preset\" ] || status=1\n")
        append("        continue\n")
        append("      fi\n")
        append("      [ ! -L \"${'$'}source_preset\" ] && [ \"${'$'}(stat -c %u \"${'$'}source_preset\" 2>/dev/null)\" = 0 ] && ")
        append("[ \"${'$'}(stat -c %h \"${'$'}source_preset\" 2>/dev/null)\" = 1 ] || { status=1; break; }\n")
        append("      size=${'$'}(wc -c < \"${'$'}source_preset\" 2>/dev/null) || { status=1; break; }\n")
        append("      case \"${'$'}size\" in ''|*[!0-9]*) status=1; break ;; esac\n")
        append("      [ \"${'$'}size\" -gt 0 ] && [ \"${'$'}size\" -le 1048576 ] || { status=1; break; }\n")
        append("      cp \"${'$'}source_preset\" \"${'$'}target_preset\" && chmod 0644 \"${'$'}target_preset\" && ")
        append("cmp -s \"${'$'}source_preset\" \"${'$'}target_preset\" || { status=1; break; }\n")
        append("      /system/bin/sh $commandBuilder --validate-preset-machine $zapretDirectory ")
        append("\"${'$'}target_preset\" \"${'$'}preset_name\" >/dev/null 2>&1 || { status=1; break; }\n")
        append("    done\n")
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
