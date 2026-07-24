package com.zapret2.app.data

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** One strict contract shared by downloaded archives and root-manager pending installation. */
internal object ModulePackageContract {
    private const val MODULE_ID = RootModuleContract.MODULE_ID
    internal const val MODULE_UPDATE_JSON =
        "https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json"
    internal const val MAX_MODULE_PROP_BYTES = 4 * 1024
    internal const val MAX_RUNTIME_MANIFEST_BYTES = 256 * 1024
    private const val RUNTIME_MANIFEST_SCHEMA = "schema|1|zapret2-runtime"
    private const val RUNTIME_MANIFEST_OWNER_PROTOCOL = "owner_protocol|7|zapret2-firewall"
    const val RUNTIME_MANIFEST_PATH = "zapret2/runtime-manifest.tsv"
    internal const val PACKAGE_CONTRACT_SCRIPT_PATH = "zapret2/scripts/package-contract.sh"
    internal const val COMMAND_BUILDER_SCRIPT_PATH = "zapret2/scripts/command-builder.sh"
    internal const val LIFECYCLE_CONTRACT_PATH = "zapret2/lifecycle-contract.version"
    internal const val LIFECYCLE_CONTRACT_VERSION = "7"
    internal const val PURGE_CONTRACT_PATH = "zapret2/scripts/lifecycle/purge-contract.sh"
    internal const val PURGE_SCRIPT_PATH = "zapret2/scripts/lifecycle/zapret-purge.sh"
    internal const val MAX_SHELL_EXEC_BYTES = 256 * 1024
    private const val MAX_PATH_COMPONENT_BYTES = 255
    private val shellShebang = "#!/system/bin/sh\n".toByteArray(Charsets.UTF_8)
    private val modulePropertyKey = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    data class Wrapper(val verb: String) {
        val relativePath: String = "system/bin/zapret2-$verb"
        val bytes: ByteArray = (
            "#!/system/bin/sh\n" +
                "exec ${RootModuleContract.SCRIPTS_DIR}/zapret-$verb.sh \"\$@\"\n"
            ).toByteArray(Charsets.UTF_8)
    }

    val wrappers = listOf("start", "stop", "status", "restart", "full-rollback").map(::Wrapper)

    val installerOnlyExecutables = listOf("customize.sh")

    val moduleRootExecutables = listOf(
        "service.sh",
        "uninstall.sh",
        "action.sh",
    )

    /**
     * Non-negotiable runtime surface. The signed manifest may extend this catalog, but it must
     * never redefine the module down to a self-consistent package that cannot install, start,
     * recover, or be controlled by the app.
     */
    val mandatoryRuntimeExecutables = listOf(
        "zapret2/scripts/common.sh",
        "zapret2/scripts/firewall-reconciler.sh",
        COMMAND_BUILDER_SCRIPT_PATH,
        PACKAGE_CONTRACT_SCRIPT_PATH,
        "zapret2/scripts/runtime-config.sh",
        "zapret2/scripts/runtime-init.sh",
        "zapret2/scripts/zapret-start.sh",
        "zapret2/scripts/zapret-stop.sh",
        "zapret2/scripts/zapret-restart.sh",
        "zapret2/scripts/zapret-status.sh",
        "zapret2/scripts/zapret-full-rollback.sh",
        PURGE_CONTRACT_PATH,
        PURGE_SCRIPT_PATH,
    )

    private val mandatoryImmutableFiles = listOf(
        "module.prop",
        RUNTIME_MANIFEST_PATH,
        LIFECYCLE_CONTRACT_PATH,
        "zapret2/upstream-zapret2.commit",
        "zapret2/upstream-zapret2.release",
        "zapret2/upstream-zapret2.archive.sha256",
        "zapret2/strategy-catalogs/tcp.txt",
        "zapret2/strategy-catalogs/udp.txt",
        "zapret2/strategy-catalogs/voice.txt",
        "zapret2/strategy-catalogs/http80.txt",
    )

    private val mandatoryMutableSeeds = listOf(
        "zapret2/runtime.ini",
        "zapret2/hosts.ini",
        "zapret2/lua/zapret-custom.lua",
        "zapret2/lua/init_vars.lua",
    )

    private val mandatoryRuntimeDependencies = listOf(
        "zapret2/lua/custom_funcs.lua",
        "zapret2/lua/zapret-antidpi.lua",
        "zapret2/lua/zapret-auto.lua",
        "zapret2/lua/zapret-lib.lua",
        "zapret2/lua/zapret-obfs.lua",
        "zapret2/lua/zapret-pcap.lua",
        "zapret2/lua/zapret-tests.lua",
        "zapret2/lua/zapret-multishake.lua",
    )

    val mandatoryRuntimeRegularFiles =
        mandatoryImmutableFiles + mandatoryMutableSeeds + mandatoryRuntimeDependencies

    /**
     * Constant-size bootstrap surface used only to decide whether the published generation can
     * answer lifecycle requests. The complete manifest is validated before publication; polling
     * must never turn that release audit into a recurring device workload.
     */
    val runtimeReadinessRegularFiles = listOf(
        "module.prop",
        LIFECYCLE_CONTRACT_PATH,
    )

    val runtimeReadinessExecutables = listOf(
        "zapret2/nfqws2",
        "zapret2/scripts/zapret-status.sh",
    )

    internal val mandatoryRuntimeManifestLines = buildList {
        mandatoryImmutableFiles.forEach { add("immutable-file|0644|$it") }
        mandatoryMutableSeeds.forEach { add("mutable-seed|0644|$it") }
        mandatoryRuntimeDependencies.forEach { add("runtime-dependency-immutable|0644|$it") }
        installerOnlyExecutables.forEach { add("immutable-exec|0755|$it") }
        moduleRootExecutables.forEach { add("immutable-exec|0755|$it") }
        mandatoryRuntimeExecutables.forEach { add("immutable-exec|0755|$it") }
        wrappers.forEach { add("immutable-exec|0755|${it.relativePath}") }
        add("abi-exec|0755|zapret2/bin/{abi}/nfqws2")
        add("installed-exec|0755|zapret2/nfqws2")
    }

    /** Bootstrap checked after recursive copy; all package files come from the manifest. */
    val requiredRegularFiles = listOf(RUNTIME_MANIFEST_PATH)

    const val DISABLE_MARKER = "disable"
    val installerOwnedArtifacts = setOf(DISABLE_MARKER, InstallGenerationMetadata.RELATIVE_PATH)

    fun selectBinaryDirectory(supportedAbis: List<String>): String? =
        supportedAbis.firstOrNull()?.lowercase(Locale.ROOT)?.let { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                "armeabi-v7a", "armeabi" -> "armeabi-v7a"
                else -> null
            }
        }

    fun validateArchive(
        archive: File,
        binaryDirectory: String,
        expectedReleaseVersion: String? = null,
    ): String? {
        if (!archive.isFile) return "Module archive is missing"
        if (!isSupportedBinaryDirectory(binaryDirectory)) return "Unsupported device ABI"
        return try {
            ZipFile(archive).use { zip ->
                val archiveEntries = zip.entries().asSequence().toList()
                val archivePaths = archiveEntries.map { entry ->
                    entry.name.trimEnd('/') to entry.isDirectory
                }
                if (!archivePathTopologyIsValid(archivePaths)) {
                    return "Package contains conflicting archive paths"
                }
                val entriesByName = archiveEntries.groupBy { it.name.trimEnd('/') }
                installerOwnedArtifacts.firstOrNull(entriesByName::containsKey)?.let { artifact ->
                    return "Package must not ship installer-owned state: $artifact"
                }
                val manifestMatches = entriesByName[RUNTIME_MANIFEST_PATH].orEmpty()
                if (manifestMatches.size != 1 || manifestMatches.single().isDirectory) {
                    return "Runtime manifest is missing, duplicated, or invalid"
                }
                val manifestBytes = zip.getInputStream(manifestMatches.single()).use {
                    it.readBoundedBytes(MAX_RUNTIME_MANIFEST_BYTES)
                        ?: return "Runtime manifest exceeds the size limit"
                }
                val manifest = parseRuntimeManifest(manifestBytes)
                    ?: return "Runtime manifest is invalid"
                val lifecycleContractEntry = entriesByName[LIFECYCLE_CONTRACT_PATH]
                    .orEmpty()
                    .singleOrNull()
                    ?.takeUnless { it.isDirectory }
                    ?: return "Lifecycle contract marker is missing or invalid"
                val lifecycleContract = zip.getInputStream(lifecycleContractEntry).use {
                    it.readBoundedBytes(16) ?: return "Lifecycle contract marker is too large"
                }
                if (lifecycleContract.toString(Charsets.UTF_8) != "$LIFECYCLE_CONTRACT_VERSION\n") {
                    return "Lifecycle contract marker is invalid"
                }
                val allowedFiles = manifest.packagePaths().toSet()
                val allowedDirectories = allowedFiles.ancestorDirectories()
                archiveEntries.forEach { entry ->
                    val rawPath = entry.name
                    val path = if (rawPath.endsWith('/')) rawPath.dropLast(1) else rawPath
                    if (!isSafeManifestPath(path)) return "Package contains unsafe entry $rawPath"
                    if (path == "META-INF" || path.startsWith("META-INF/")) {
                        return "Recovery flashing metadata is unsupported"
                    }
                    val allowed = if (entry.isDirectory) path in allowedDirectories else path in allowedFiles
                    if (!allowed) return "Package contains undeclared entry $path"
                }
                manifest.packagePaths().forEach { path ->
                    val matches = entriesByName[path].orEmpty()
                    if (matches.size != 1 || matches.single().isDirectory) {
                        return "Package file $path is missing, duplicated, or invalid"
                    }
                    if (matches.single().size == 0L || zip.getInputStream(matches.single()).use { it.read() } < 0) {
                        return "Package file $path is empty"
                    }
                }
                manifest.shellExecutablePaths().forEach { path ->
                    val entry = entriesByName.getValue(path).single()
                    val bytes = zip.getInputStream(entry).use {
                        it.readBoundedBytes(MAX_SHELL_EXEC_BYTES)
                            ?: return "Package shell executable $path exceeds the size limit"
                    }
                    validateShellExecutable(bytes)?.let { reason ->
                        return "Package shell executable $path is invalid: $reason"
                    }
                }

                val moduleProp = entriesByName.getValue("module.prop").single()
                val modulePropBytes = zip.getInputStream(moduleProp).use {
                    it.readBoundedBytes(MAX_MODULE_PROP_BYTES)
                        ?: return "module.prop exceeds the size limit"
                }
                validateModuleProp(modulePropBytes, expectedReleaseVersion)?.let { return it }

                wrappers.forEach { wrapper ->
                    val entry = entriesByName.getValue(wrapper.relativePath).single()
                    val actual = zip.getInputStream(entry).use {
                        it.readBoundedBytes(wrapper.bytes.size)
                            ?: return "Package wrapper ${wrapper.relativePath} is too large"
                    }
                    if (!actual.contentEquals(wrapper.bytes)) {
                        return "Package wrapper ${wrapper.relativePath} is invalid"
                    }
                }

                val binaryPath = binaryRelativePath(binaryDirectory)
                val binaries = entriesByName[binaryPath].orEmpty()
                if (binaries.size != 1 || binaries.single().isDirectory) {
                    return "Package does not contain the required $binaryDirectory nfqws2 binary"
                }
                val binary = binaries.single()
                if (binary.size == 0L || zip.getInputStream(binary).use { it.read() } < 0) {
                    return "Package contains an empty $binaryDirectory nfqws2 binary"
                }
            }
            null
        } catch (_: Exception) {
            "Invalid module archive"
        }
    }

    fun validateStaging(
        stagingDir: File,
        binaryDirectory: String,
        expectedReleaseVersion: String? = null,
    ): String? {
        if (!isSupportedBinaryDirectory(binaryDirectory)) return "Unsupported device ABI"
        installerOwnedArtifacts.forEach { relativePath ->
            if (File(stagingDir, relativePath).existsOrLinks()) {
                return "Package must not ship installer-owned state: $relativePath"
            }
        }
        val manifestFile = File(stagingDir, RUNTIME_MANIFEST_PATH)
        if (!manifestFile.isRegularNonLink() || manifestFile.length() <= 0L) {
            return "Runtime manifest is missing or invalid"
        }
        if (manifestFile.length() > MAX_RUNTIME_MANIFEST_BYTES) return "Runtime manifest exceeds the size limit"
        val manifestBytes = manifestFile.inputStream().use {
            it.readBoundedBytes(MAX_RUNTIME_MANIFEST_BYTES)
                ?: return "Runtime manifest exceeds the size limit"
        }
        val manifest = parseRuntimeManifest(manifestBytes) ?: return "Runtime manifest is invalid"
        val lifecycleContract = File(stagingDir, LIFECYCLE_CONTRACT_PATH)
        if (!lifecycleContract.isRegularNonLink() ||
            lifecycleContract.length() > 16L ||
            lifecycleContract.readText() != "$LIFECYCLE_CONTRACT_VERSION\n"
        ) {
            return "Lifecycle contract marker is invalid"
        }
        val allowedFiles = manifest.packagePaths().toSet()
        val allowedDirectories = allowedFiles.ancestorDirectories()
        stagingDir.walkTopDown().drop(1).forEach { entry ->
            val relative = entry.relativeTo(stagingDir).invariantSeparatorsPath
            if (runCatching { entry.canonicalFile != entry.absoluteFile }.getOrDefault(true)) {
                return "Package contains a symbolic or non-canonical entry: $relative"
            }
            when {
                entry.isFile && relative !in allowedFiles -> return "Package contains undeclared file $relative"
                entry.isDirectory && relative !in allowedDirectories -> return "Package contains undeclared directory $relative"
                !entry.isFile && !entry.isDirectory -> return "Package contains unsupported entry $relative"
            }
        }
        manifest.packagePaths().forEach { path ->
            val file = File(stagingDir, path)
            if (!file.isRegularNonLink() || file.length() <= 0L) {
                return "Package file $path is missing, empty, or invalid"
            }
        }
        manifest.shellExecutablePaths().forEach { path ->
            val file = File(stagingDir, path)
            if (file.length() > MAX_SHELL_EXEC_BYTES) {
                return "Package shell executable $path exceeds the size limit"
            }
            val bytes = file.inputStream().use {
                it.readBoundedBytes(MAX_SHELL_EXEC_BYTES)
                    ?: return "Package shell executable $path exceeds the size limit"
            }
            validateShellExecutable(bytes)?.let { reason ->
                return "Package shell executable $path is invalid: $reason"
            }
        }

        val moduleProp = File(stagingDir, "module.prop")
        if (moduleProp.length() > MAX_MODULE_PROP_BYTES) return "module.prop exceeds the size limit"
        validateModuleProp(moduleProp.readBytes(), expectedReleaseVersion)?.let { return it }

        wrappers.forEach { wrapper ->
            val file = File(stagingDir, wrapper.relativePath)
            if (!file.isRegularNonLink() || file.length() != wrapper.bytes.size.toLong()) {
                return "Package wrapper ${wrapper.relativePath} is missing or invalid"
            }
            val actual = file.inputStream().use { it.readBoundedBytes(wrapper.bytes.size) }
                ?: return "Package wrapper ${wrapper.relativePath} changed during validation"
            if (!actual.contentEquals(wrapper.bytes)) {
                return "Package wrapper ${wrapper.relativePath} is missing or invalid"
            }
        }
        val binary = File(stagingDir, binaryRelativePath(binaryDirectory))
        if (!binary.isRegularNonLink() || binary.length() <= 0L) {
            return "Package does not contain the required $binaryDirectory nfqws2 binary"
        }
        return null
    }

    fun binaryRelativePath(binaryDirectory: String): String =
        "zapret2/bin/$binaryDirectory/nfqws2"

    private fun isSupportedBinaryDirectory(value: String): Boolean =
        value == "arm64-v8a" || value == "armeabi-v7a"

    private data class RuntimeManifestEntry(
        val artifactClass: String,
        val mode: String,
        val path: String,
    )

    private data class RuntimeManifest(val entries: List<RuntimeManifestEntry>) {
        fun packagePaths(): List<String> = entries.flatMap { entry ->
            when (entry.artifactClass) {
                "installed-exec" -> emptyList()
                "abi-exec" -> listOf(
                    entry.path.replace("{abi}", "arm64-v8a"),
                    entry.path.replace("{abi}", "armeabi-v7a"),
                )
                else -> listOf(entry.path)
            }
        }

        fun shellExecutablePaths(): List<String> = entries
            .filter { it.artifactClass == "immutable-exec" }
            .map { it.path }
    }

    private fun Set<String>.ancestorDirectories(): Set<String> = buildSet {
        this@ancestorDirectories.forEach { path ->
            var parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            while (parent.isNotEmpty()) {
                add(parent)
                parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
            }
        }
    }

    private fun parseRuntimeManifest(bytes: ByteArray): RuntimeManifest? {
        if (bytes.isEmpty() || bytes.indexOf(0) >= 0) return null
        val allowedFileClasses = setOf(
            "immutable-file",
            "mutable-seed",
            "runtime-dependency-immutable",
            "runtime-dependency-mutable-seed",
            "preset-compatible",
            "preset-quarantined",
        )
        val allowedExecClasses = setOf("immutable-exec", "abi-exec", "installed-exec")
        val entries = mutableListOf<RuntimeManifestEntry>()
        val paths = mutableSetOf<String>()
        val lines = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.removeSuffix("\r") }
            .iterator()

        if (!lines.hasNext() || lines.next() != RUNTIME_MANIFEST_SCHEMA) return null
        if (!lines.hasNext() || lines.next() != RUNTIME_MANIFEST_OWNER_PROTOCOL) return null

        lines.forEach { line ->
            if (line.isEmpty() || line.startsWith('#')) return@forEach
            val fields = line.split('|')
            if (fields.size != 3) return null
            val (artifactClass, mode, path) = fields
            when (artifactClass) {
                in allowedFileClasses -> if (mode != "0644") return null
                in allowedExecClasses -> if (mode != "0755") return null
                else -> return null
            }
            if (!isSafeManifestPath(path) || !paths.add(path)) return null
            if (artifactClass == "abi-exec" && path != "zapret2/bin/{abi}/nfqws2") return null
            if (artifactClass == "installed-exec" && path != "zapret2/nfqws2") return null
            if (artifactClass.startsWith("preset-")) {
                val presetName = path.removePrefix("zapret2/presets/")
                if (presetName == path || !PresetNamePolicy.isValid(presetName)) return null
            }
            entries += RuntimeManifestEntry(artifactClass, mode, path)
        }

        if (entries.isEmpty()) return null
        val requiredEntries = mandatoryRuntimeManifestLines.mapTo(mutableSetOf()) { line ->
            val (artifactClass, mode, path) = line.split('|', limit = 3)
            RuntimeManifestEntry(artifactClass, mode, path)
        }
        if (!entries.containsAll(requiredEntries)) return null
        val manifest = RuntimeManifest(entries)
        val expandedPaths = manifest.packagePaths()
        if (expandedPaths.size != expandedPaths.toSet().size ||
            !archivePathTopologyIsValid(expandedPaths.map { path -> path to false })
        ) return null
        return manifest
    }

    internal fun archivePathTopologyIsValid(entries: Iterable<Pair<String, Boolean>>): Boolean {
        val snapshot = entries.toList()
        val allPaths = hashSetOf<String>()
        val filePaths = hashSetOf<String>()
        snapshot.forEach { (path, isDirectory) ->
            if (path.isEmpty() || !allPaths.add(path)) return false
            if (!isDirectory) filePaths += path
        }
        return snapshot.all { (path, _) ->
            var ancestor = path.substringBeforeLast('/', missingDelimiterValue = "")
            while (ancestor.isNotEmpty()) {
                if (ancestor in filePaths) return@all false
                ancestor = ancestor.substringBeforeLast('/', missingDelimiterValue = "")
            }
            true
        }
    }

    private fun isSafeManifestPath(path: String): Boolean {
        if (path.isBlank() || path.trim() != path || path.startsWith('/') || path.contains('\\')) return false
        if (path.any { it.isISOControl() || it == '|' }) return false
        val parts = path.split('/')
        return parts.none { component ->
            component.isEmpty() || component == "." || component == ".." ||
                component.toByteArray(Charsets.UTF_8).size > MAX_PATH_COMPONENT_BYTES
        }
    }

    private fun validateModuleProp(bytes: ByteArray, expectedReleaseVersion: String?): String? {
        if (bytes.isEmpty() || bytes.indexOf(0) >= 0 || bytes.indexOf('\r'.code.toByte()) >= 0) {
            return "module.prop contains invalid data"
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            return "module.prop is not valid UTF-8"
        }
        val properties = linkedMapOf<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return "module.prop contains a malformed property"
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!modulePropertyKey.matches(key) || value.any(Char::isISOControl)) {
                return "module.prop contains an invalid property"
            }
            if (properties.put(key, value) != null) {
                return "module.prop contains a duplicate property"
            }
        }
        val required = setOf(
            "id", "name", "version", "versionCode", "author", "description", "updateJson",
        )
        if (!properties.keys.containsAll(required)) return "module.prop is missing required properties"
        if ("webRoot" in properties) return "module.prop declares the retired WebUI root"
        if (properties.getValue("id") != MODULE_ID) return "module.prop has an unexpected module ID"
        if (listOf("name", "author", "description").any { properties.getValue(it).isBlank() }) {
            return "module.prop contains an empty required property"
        }
        val version = properties.getValue("version")
        if (!version.startsWith('v')) return "module.prop has a noncanonical project version"
        val embeddedVersionCode = projectReleaseVersionCode(version)
            ?: return "module.prop has an invalid project version"
        if (expectedReleaseVersion != null && version != "v$expectedReleaseVersion") {
            return "Module version does not match the selected release"
        }
        val versionCode = properties.getValue("versionCode")
        val parsedVersionCode = versionCode.toIntOrNull()
        if (parsedVersionCode == null || parsedVersionCode <= 0 || parsedVersionCode.toString() != versionCode) {
            return "module.prop has an invalid versionCode"
        }
        if (parsedVersionCode.toLong() != embeddedVersionCode) {
            return "module.prop versionCode does not match its version"
        }
        if (properties.getValue("updateJson") != MODULE_UPDATE_JSON) {
            return "module.prop has an unexpected update channel"
        }
        return null
    }

    /** Returns the version only when installed metadata satisfies the full package identity. */
    internal fun validatedInstalledVersion(content: String): String? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MODULE_PROP_BYTES) return null
        if (validateModuleProp(bytes, expectedReleaseVersion = null) != null) return null
        return content.lineSequence()
            .map(String::trim)
            .first { it.substringBefore('=').trim() == "version" }
            .substringAfter('=')
            .trim()
    }

    internal fun validateShellExecutable(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "empty"
        bytes.size > MAX_SHELL_EXEC_BYTES -> "too large"
        bytes.indexOf(0) >= 0 -> "contains NUL bytes"
        bytes.indexOf('\r'.code.toByte()) >= 0 -> "contains CR bytes"
        bytes.size < shellShebang.size ||
            !bytes.copyOfRange(0, shellShebang.size).contentEquals(shellShebang) -> "invalid shebang"
        else -> null
    }

    private fun File.isRegularNonLink(): Boolean =
        isFile && runCatching { canonicalFile == absoluteFile }.getOrDefault(false)

    private fun File.existsOrLinks(): Boolean =
        exists() || runCatching { canonicalFile != absoluteFile }.getOrDefault(true)
}
