package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModulePackageContractTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun selectBinaryDirectory_requiresSupportedPrimaryAbiAndUsesPackagedNames() {
        assertEquals(
            "arm64-v8a",
            ModulePackageContract.selectBinaryDirectory(listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            "armeabi-v7a",
            ModulePackageContract.selectBinaryDirectory(listOf("armeabi-v7a", "armeabi"))
        )
        assertNull(ModulePackageContract.selectBinaryDirectory(listOf("arm64-custom")))
        assertNull(ModulePackageContract.selectBinaryDirectory(listOf("armeabi-v7a-custom")))
        assertNull(ModulePackageContract.selectBinaryDirectory(listOf("x86_64", "arm64-v8a")))
        assertNull(ModulePackageContract.selectBinaryDirectory(listOf("x86_64", "x86")))
        assertNull(ModulePackageContract.selectBinaryDirectory(emptyList()))
    }

    @Test
    fun stagingAndArchive_acceptExactCurrentCiLayoutForBothArmAbis() {
        val stage = temporaryFolder.newFolder("valid-package")
        writeValidPackage(stage)
        val archive = zip(stage, "valid.zip")

        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            assertNull(ModulePackageContract.validateStaging(stage, abi))
            assertNull(ModulePackageContract.validateArchive(archive, abi))
        }
    }

    @Test
    fun runtimeManifestMetadata_isExactAdjacentOrderedAndUniqueForStagingAndArchive() {
        val schema = "schema|1|zapret2-runtime"
        val ownerProtocol = "owner_protocol|7|zapret2-firewall"
        val validManifest = sourceManifest()
        assertTrue(validManifest.startsWith("$schema\n$ownerProtocol\n"))
        val allowlist = validManifest.removePrefix("$schema\n$ownerProtocol\n")
        val invalidManifests = listOf(
            "missing-schema" to "$ownerProtocol\n$allowlist",
            "missing-owner-protocol" to "$schema\n$allowlist",
            "owner-protocol-v6" to "$schema\nowner_protocol|6|zapret2-firewall\n$allowlist",
            "owner-protocol-non-7" to "$schema\nowner_protocol|8|zapret2-firewall\n$allowlist",
            "owner-protocol-wrong-namespace" to "$schema\nowner_protocol|7|zapret2-runtime\n$allowlist",
            "metadata-reordered" to "$ownerProtocol\n$schema\n$allowlist",
            "duplicate-schema" to "$schema\n$ownerProtocol\n$schema\n$allowlist",
            "duplicate-owner-protocol" to "$schema\n$ownerProtocol\n$ownerProtocol\n$allowlist",
            "comment-between-metadata" to "$schema\n# separator\n$ownerProtocol\n$allowlist",
            "blank-between-metadata" to "$schema\n\n$ownerProtocol\n$allowlist",
            "owner-protocol-later" to "$schema\n$allowlist$ownerProtocol\n",
        )

        invalidManifests.forEachIndexed { index, (name, manifest) ->
            val stage = temporaryFolder.newFolder("runtime-metadata-$index-$name")
            writeValidPackage(stage)
            File(stage, ModulePackageContract.RUNTIME_MANIFEST_PATH).writeText(manifest)

            assertNotNull(
                "Staging must reject $name",
                ModulePackageContract.validateStaging(stage, "arm64-v8a"),
            )
            assertNotNull(
                "Archive must reject $name",
                ModulePackageContract.validateArchive(zip(stage, "runtime-metadata-$index.zip"), "arm64-v8a"),
            )
        }
    }

    @Test
    fun packageAndInstalledRootExecutablesAreSeparated() {
        assertEquals(setOf("customize.sh"), ModulePackageContract.installerOnlyExecutables.toSet())
        assertEquals(
            setOf("service.sh", "uninstall.sh", "action.sh"),
            ModulePackageContract.moduleRootExecutables.toSet(),
        )
        assertEquals(listOf(ModulePackageContract.RUNTIME_MANIFEST_PATH), ModulePackageContract.requiredRegularFiles)
    }

    @Test
    fun mandatoryRuntimeManifestMinimum_isExactUniqueAndDeclaredBySource() {
        val expected = setOf(
            "immutable-file|0644|module.prop",
            "immutable-file|0644|zapret2/runtime-manifest.tsv",
            "immutable-file|0644|zapret2/lifecycle-contract.version",
            "immutable-file|0644|zapret2/upstream-zapret2.commit",
            "immutable-file|0644|zapret2/strategy-catalogs/tcp.txt",
            "immutable-file|0644|zapret2/strategy-catalogs/udp.txt",
            "immutable-file|0644|zapret2/strategy-catalogs/voice.txt",
            "immutable-file|0644|zapret2/strategy-catalogs/http80.txt",
            "mutable-seed|0644|zapret2/runtime.ini",
            "mutable-seed|0644|zapret2/hosts.ini",
            "mutable-seed|0644|zapret2/lua/zapret-custom.lua",
            "mutable-seed|0644|zapret2/lua/init_vars.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/custom_funcs.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-antidpi.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-auto.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-lib.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-multishake.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-obfs.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-pcap.lua",
            "runtime-dependency-immutable|0644|zapret2/lua/zapret-tests.lua",
            "immutable-exec|0755|customize.sh",
            "immutable-exec|0755|service.sh",
            "immutable-exec|0755|uninstall.sh",
            "immutable-exec|0755|action.sh",
            "immutable-exec|0755|zapret2/scripts/common.sh",
            "immutable-exec|0755|zapret2/scripts/command-builder.sh",
            "immutable-exec|0755|zapret2/scripts/package-contract.sh",
            "immutable-exec|0755|zapret2/scripts/runtime-init.sh",
            "immutable-exec|0755|zapret2/scripts/zapret-start.sh",
            "immutable-exec|0755|zapret2/scripts/zapret-stop.sh",
            "immutable-exec|0755|zapret2/scripts/zapret-restart.sh",
            "immutable-exec|0755|zapret2/scripts/zapret-status.sh",
            "immutable-exec|0755|zapret2/scripts/zapret-full-rollback.sh",
            "immutable-exec|0755|zapret2/scripts/lifecycle/purge-contract.sh",
            "immutable-exec|0755|zapret2/scripts/lifecycle/zapret-purge.sh",
            "immutable-exec|0755|system/bin/zapret2-start",
            "immutable-exec|0755|system/bin/zapret2-stop",
            "immutable-exec|0755|system/bin/zapret2-status",
            "immutable-exec|0755|system/bin/zapret2-restart",
            "immutable-exec|0755|system/bin/zapret2-full-rollback",
            "abi-exec|0755|zapret2/bin/{abi}/nfqws2",
            "installed-exec|0755|zapret2/nfqws2",
        )
        val actual = ModulePackageContract.mandatoryRuntimeManifestLines
        val declared = sourceManifest().lineSequence().toSet()

        assertEquals(expected.size, actual.size)
        assertEquals(expected, actual.toSet())
        assertTrue(declared.containsAll(expected))
    }

    @Test
    fun fullRollbackScriptAndExactStaticWrapperAreRequired() {
        assertTrue(sourceManifest().contains("immutable-exec|0755|zapret2/scripts/zapret-full-rollback.sh"))
        val wrapper = ModulePackageContract.wrappers.single { it.verb == "full-rollback" }
        assertEquals("system/bin/zapret2-full-rollback", wrapper.relativePath)
        assertEquals(
            "#!/system/bin/sh\n" +
                "exec /data/adb/modules/zapret2/zapret2/scripts/zapret-full-rollback.sh \"\$@\"\n",
            wrapper.bytes.toString(Charsets.UTF_8),
        )

        val missingScript = temporaryFolder.newFolder("missing-full-rollback-script")
        writeValidPackage(missingScript)
        File(missingScript, "zapret2/scripts/zapret-full-rollback.sh").delete()
        assertNotNull(ModulePackageContract.validateStaging(missingScript, "arm64-v8a"))

        val invalidWrapper = temporaryFolder.newFolder("invalid-full-rollback-wrapper")
        writeValidPackage(invalidWrapper)
        File(invalidWrapper, wrapper.relativePath).appendText("# invalid\n")
        assertNotNull(ModulePackageContract.validateStaging(invalidWrapper, "arm64-v8a"))
    }

    @Test
    fun lifecycleContractMarker_isMandatoryAndExact() {
        val valid = temporaryFolder.newFolder("valid-lifecycle-contract")
        writeValidPackage(valid)
        assertEquals(
            "${ModulePackageContract.LIFECYCLE_CONTRACT_VERSION}\n",
            File(valid, ModulePackageContract.LIFECYCLE_CONTRACT_PATH).readText(),
        )
        assertNull(ModulePackageContract.validateStaging(valid, "arm64-v8a"))

        listOf("", "1\n", "2", "2\nextra\n").forEachIndexed { index, content ->
            val invalid = temporaryFolder.newFolder("invalid-lifecycle-contract-$index")
            writeValidPackage(invalid)
            File(invalid, ModulePackageContract.LIFECYCLE_CONTRACT_PATH).writeText(content)
            assertNotNull(ModulePackageContract.validateStaging(invalid, "arm64-v8a"))
            assertNotNull(
                ModulePackageContract.validateArchive(
                    zip(invalid, "invalid-lifecycle-contract-$index.zip"),
                    "arm64-v8a",
                )
            )
        }
    }

    @Test
    fun purgeLifecycleScriptsAreNonNegotiableRuntimeExecutables() {
        assertTrue(ModulePackageContract.PURGE_CONTRACT_PATH in ModulePackageContract.mandatoryRuntimeExecutables)
        assertTrue(ModulePackageContract.PURGE_SCRIPT_PATH in ModulePackageContract.mandatoryRuntimeExecutables)

        listOf(
            ModulePackageContract.PURGE_CONTRACT_PATH,
            ModulePackageContract.PURGE_SCRIPT_PATH,
        ).forEachIndexed { index, path ->
            val stage = temporaryFolder.newFolder("missing-purge-script-$index")
            writeValidPackage(stage)
            assertTrue(File(stage, path).delete())
            assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))
        }
    }

    @Test
    fun runtimeBuildersAndCoreLuaLibrariesAreRequired() {
        val requiredRuntimeFiles = setOf(
            "zapret2/scripts/command-builder.sh",
            "zapret2/scripts/package-contract.sh",
            "zapret2/scripts/runtime-init.sh",
            "zapret2/lua/zapret-lib.lua",
            "zapret2/lua/zapret-antidpi.lua",
            "zapret2/lua/zapret-auto.lua",
        )
        val manifest = sourceManifest()
        requiredRuntimeFiles.forEach { relative ->
            assertTrue("Manifest must declare $relative", manifest.lineSequence().any { it.endsWith("|$relative") })
        }

        requiredRuntimeFiles.forEachIndexed { index, relative ->
            val stage = temporaryFolder.newFolder("missing-runtime-$index")
            writeValidPackage(stage)
            File(stage, relative).delete()
            assertNotNull("Expected missing $relative to be rejected", ModulePackageContract.validateStaging(stage, "arm64-v8a"))
        }
    }

    @Test
    fun moduleProp_requiresExactlyOneZapret2Identity() {
        val stage = temporaryFolder.newFolder("identity")
        writeValidPackage(stage)
        val moduleProp = File(stage, "module.prop")

        moduleProp.writeText("id=foreign\nversion=1\n")
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        moduleProp.writeText("id=zapret2\nid=zapret2\n")
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        moduleProp.writeText("version=1\n")
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))
    }

    @Test
    fun moduleProp_bindsRequiredMetadataToTheSelectedRelease() {
        val stage = temporaryFolder.newFolder("release-metadata")
        writeValidPackage(stage)

        assertNull(ModulePackageContract.validateStaging(stage, "arm64-v8a", "1.0.100"))
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a", "2.0.0"))
        val archive = zip(stage, "release-metadata.zip")
        assertNull(ModulePackageContract.validateArchive(archive, "arm64-v8a", "1.0.100"))
        assertNotNull(ModulePackageContract.validateArchive(archive, "arm64-v8a", "2.0.0"))

        val moduleProp = File(stage, "module.prop")
        moduleProp.writeText(validModuleProp().replace("versionCode=1000100", "versionCode=01000100"))
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a", "1.0.100"))

        moduleProp.writeText(validModuleProp().replace("version=v1.0.100", "version=1.0.100"))
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        moduleProp.writeText(validModuleProp().replace("version=v1.0.100", "version=v1.0.101"))
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        moduleProp.writeText(validModuleProp().replace("version=v1.0.100", "version=v1.0.0100"))
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        moduleProp.writeText(
            validModuleProp().replace(
                "https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json",
                "https://example.test/update.json",
            ),
        )
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a", "1.0.100"))
    }

    @Test
    fun contractRejectsMissingNonRegularAndEmptyRequiredArtifacts() {
        val missing = temporaryFolder.newFolder("missing")
        writeValidPackage(missing)
        File(missing, "zapret2/scripts/common.sh").delete()
        assertNotNull(ModulePackageContract.validateStaging(missing, "arm64-v8a"))

        val nonRegular = temporaryFolder.newFolder("non-regular")
        writeValidPackage(nonRegular)
        File(nonRegular, "service.sh").delete()
        File(nonRegular, "service.sh").mkdir()
        assertNotNull(ModulePackageContract.validateStaging(nonRegular, "arm64-v8a"))

        val emptyBinary = temporaryFolder.newFolder("empty-binary")
        writeValidPackage(emptyBinary)
        File(emptyBinary, ModulePackageContract.binaryRelativePath("armeabi-v7a")).writeBytes(byteArrayOf())
        assertNotNull(ModulePackageContract.validateStaging(emptyBinary, "armeabi-v7a"))
    }

    @Test
    fun contractRejectsSymlinkAndNonExactOrOversizedWrapper() {
        val stage = temporaryFolder.newFolder("invalid-wrapper")
        writeValidPackage(stage)
        val wrapper = File(stage, "system/bin/zapret2-start")
        wrapper.appendText("# unexpected\n")
        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))

        val symlinkStage = temporaryFolder.newFolder("symlink")
        writeValidPackage(symlinkStage)
        val service = File(symlinkStage, "service.sh")
        val target = File(symlinkStage, "real-service.sh").apply { writeText("#!/system/bin/sh\n") }
        service.delete()
        try {
            Files.createSymbolicLink(service.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        assertNotNull(ModulePackageContract.validateStaging(symlinkStage, "arm64-v8a"))
    }

    @Test
    fun packageRejectsInstallerOwnedDisableAndGenerationArtifacts() {
        ModulePackageContract.installerOwnedArtifacts.forEachIndexed { index, relative ->
            val stage = temporaryFolder.newFolder("installer-owned-$index")
            writeValidPackage(stage)
            File(stage, relative).apply {
                parentFile?.mkdirs()
                writeText("unexpected\n")
            }
            assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))
            assertNotNull(ModulePackageContract.validateArchive(zip(stage, "installer-owned-$index.zip"), "arm64-v8a"))
        }
    }

    @Test
    fun sourceManifestDeclaresExactCompatibleAndQuarantinedCatalog() {
        val lines = sourceManifest().lineSequence().toList()
        assertEquals(98, lines.count { it.startsWith("preset-compatible|0644|") })
        assertEquals(0, lines.count { it.startsWith("preset-quarantined|0644|") })
        assertTrue(lines.contains("immutable-file|0644|${ModulePackageContract.RUNTIME_MANIFEST_PATH}"))
        assertTrue(lines.contains("mutable-seed|0644|zapret2/runtime.ini"))
        assertTrue(lines.contains("runtime-dependency-immutable|0644|zapret2/lua/zapret-auto.lua"))
        assertTrue(lines.contains("abi-exec|0755|zapret2/bin/{abi}/nfqws2"))
    }

    @Test
    fun archiveAndStagingRejectEveryUndeclaredEntry() {
        val extraFile = temporaryFolder.newFolder("undeclared-file")
        writeValidPackage(extraFile)
        File(extraFile, "zapret2/scripts/not-declared.sh").writeText("#!/system/bin/sh\n")
        assertNotNull(ModulePackageContract.validateStaging(extraFile, "arm64-v8a"))
        assertNotNull(ModulePackageContract.validateArchive(zip(extraFile, "undeclared-file.zip"), "arm64-v8a"))

        val extraDirectory = temporaryFolder.newFolder("undeclared-directory")
        writeValidPackage(extraDirectory)
        File(extraDirectory, "zapret2/empty-undeclared").mkdirs()
        assertNotNull(ModulePackageContract.validateStaging(extraDirectory, "arm64-v8a"))

        val unsafeMeta = temporaryFolder.newFolder("unsafe-meta-entry")
        writeValidPackage(unsafeMeta)
        assertNotNull(
            ModulePackageContract.validateArchive(
                zip(
                    unsafeMeta,
                    "unsafe-meta-entry.zip",
                    mapOf("META-INF/../escape" to byteArrayOf(1)),
                ),
                "arm64-v8a",
            ),
        )

        val recoveryMeta = temporaryFolder.newFolder("recovery-meta-entry")
        writeValidPackage(recoveryMeta)
        assertEquals(
            "Recovery flashing metadata is unsupported",
            ModulePackageContract.validateArchive(
                zip(
                    recoveryMeta,
                    "recovery-meta-entry.zip",
                    mapOf("META-INF/com/google/android/update-binary" to byteArrayOf(1)),
                ),
                "arm64-v8a",
            ),
        )
    }

    @Test
    fun manifestCannotOmitRequiredEntryEvenWhenPackageAlsoOmitsIt() {
        val stage = temporaryFolder.newFolder("manifest-required-omission")
        writeValidPackage(stage)
        val required = "installed-exec|0755|zapret2/nfqws2"
        val manifest = File(stage, ModulePackageContract.RUNTIME_MANIFEST_PATH)
        manifest.writeText(
            manifest.readLines().filterNot { it == required }.joinToString("\n", postfix = "\n"),
        )

        assertNotNull(ModulePackageContract.validateStaging(stage, "arm64-v8a"))
        assertNotNull(ModulePackageContract.validateArchive(zip(stage, "manifest-required-omission.zip"), "arm64-v8a"))
    }

    @Test
    fun sourceManifestLookupIgnoresAncestorWorkingDirectoryDecoy() {
        val decoy = temporaryFolder.newFolder("cwd-decoy")
        File(decoy, "android-app/settings.gradle.kts").apply {
            parentFile?.mkdirs()
            writeText("// decoy\n")
        }
        File(decoy, ModulePackageContract.RUNTIME_MANIFEST_PATH).apply {
            parentFile?.mkdirs()
            writeText("schema|1|zapret2-runtime\n# decoy\n")
        }
        val previous = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", File(decoy, "android-app").absolutePath)
            val canonical = sourceManifest()
            assertFalse(canonical.contains("# decoy"))
            assertTrue(canonical.contains("immutable-exec|0755|zapret2/scripts/zapret-stop.sh"))
        } finally {
            if (previous == null) System.clearProperty("user.dir") else System.setProperty("user.dir", previous)
        }
    }

    @Test
    fun malformedManifestAndMissingDeclaredFileAreRejected() {
        val traversal = temporaryFolder.newFolder("manifest-traversal")
        writeValidPackage(traversal)
        File(traversal, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("immutable-file|0644|zapret2/../escape\n")
        assertNotNull(ModulePackageContract.validateStaging(traversal, "arm64-v8a"))

        val controlCharacter = temporaryFolder.newFolder("manifest-control-character")
        writeValidPackage(controlCharacter)
        File(controlCharacter, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("immutable-file|0644|zapret2/tab\tname\n")
        assertNotNull(ModulePackageContract.validateStaging(controlCharacter, "arm64-v8a"))

        val duplicate = temporaryFolder.newFolder("manifest-duplicate")
        writeValidPackage(duplicate)
        File(duplicate, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("immutable-file|0644|module.prop\n")
        assertNotNull(ModulePackageContract.validateStaging(duplicate, "arm64-v8a"))

        val arbitraryInstalledExecutable = temporaryFolder.newFolder("manifest-arbitrary-installed-exec")
        writeValidPackage(arbitraryInstalledExecutable)
        File(arbitraryInstalledExecutable, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("installed-exec|0755|zapret2/generated-helper\n")
        assertNotNull(ModulePackageContract.validateStaging(arbitraryInstalledExecutable, "arm64-v8a"))
        assertNotNull(
            ModulePackageContract.validateArchive(
                zip(arbitraryInstalledExecutable, "manifest-arbitrary-installed-exec.zip"),
                "arm64-v8a",
            ),
        )

        val missingAuto = temporaryFolder.newFolder("manifest-missing-auto")
        writeValidPackage(missingAuto)
        File(missingAuto, "zapret2/lua/zapret-auto.lua").delete()
        assertNotNull(ModulePackageContract.validateStaging(missingAuto, "arm64-v8a"))
        assertNotNull(ModulePackageContract.validateArchive(zip(missingAuto, "manifest-missing-auto.zip"), "arm64-v8a"))

        val crlfShell = temporaryFolder.newFolder("manifest-crlf-shell")
        writeValidPackage(crlfShell)
        File(crlfShell, "zapret2/scripts/command-builder.sh")
            .writeText("#!/system/bin/sh\r\necho invalid\r\n")
        assertNotNull(ModulePackageContract.validateStaging(crlfShell, "arm64-v8a"))
        assertNotNull(ModulePackageContract.validateArchive(zip(crlfShell, "manifest-crlf-shell.zip"), "arm64-v8a"))
    }

    @Test
    fun manifestRejectsRuntimeInvalidPresetNamesAndOverlongPathComponents() {
        val hiddenPreset = temporaryFolder.newFolder("manifest-hidden-preset")
        writeValidPackage(hiddenPreset)
        val hiddenManifest = File(hiddenPreset, ModulePackageContract.RUNTIME_MANIFEST_PATH)
        val compatibleLine = hiddenManifest.readLines().first { it.startsWith("preset-compatible|0644|") }
        val originalPath = compatibleLine.substringAfterLast('|')
        val hiddenPath = originalPath.substringBeforeLast('/') + "/_hidden.txt"
        assertTrue(File(hiddenPreset, originalPath).renameTo(File(hiddenPreset, hiddenPath)))
        hiddenManifest.writeText(
            hiddenManifest.readText().replace(
                compatibleLine,
                "preset-compatible|0644|$hiddenPath",
            ),
        )
        assertNotNull(ModulePackageContract.validateStaging(hiddenPreset, "arm64-v8a"))
        assertNotNull(
            ModulePackageContract.validateArchive(
                zip(hiddenPreset, "manifest-hidden-preset.zip"),
                "arm64-v8a",
            ),
        )

        val overlongComponent = "x".repeat(256)
        val overlongPath = "zapret2/$overlongComponent"
        val overlongManifest = temporaryFolder.newFolder("manifest-overlong-component")
        writeValidPackage(overlongManifest)
        File(overlongManifest, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("immutable-file|0644|$overlongPath\n")
        val archive = zip(
            overlongManifest,
            "manifest-overlong-component.zip",
            mapOf(overlongPath to byteArrayOf(1)),
        )
        assertNotNull(ModulePackageContract.validateArchive(archive, "arm64-v8a"))
    }

    @Test
    fun archiveTopologyRejectsFileParentsDuplicateKindsAndExpandedAbiCollisions() {
        assertTrue(
            ModulePackageContract.archivePathTopologyIsValid(
                listOf("META-INF" to true, "META-INF/file" to false),
            ),
        )
        assertFalse(
            ModulePackageContract.archivePathTopologyIsValid(
                listOf("module.prop" to false, "module.prop/child" to false),
            ),
        )
        assertFalse(
            ModulePackageContract.archivePathTopologyIsValid(
                listOf("META-INF" to false, "META-INF/file" to false),
            ),
        )
        assertFalse(
            ModulePackageContract.archivePathTopologyIsValid(
                listOf("same" to true, "same" to false),
            ),
        )

        val conflictingArchive = temporaryFolder.newFolder("archive-file-parent")
        writeValidPackage(conflictingArchive)
        assertNotNull(
            ModulePackageContract.validateArchive(
                zip(
                    conflictingArchive,
                    "archive-file-parent.zip",
                    linkedMapOf(
                        "META-INF" to byteArrayOf(1),
                        "META-INF/child" to byteArrayOf(1),
                    ),
                ),
                "arm64-v8a",
            ),
        )

        val expandedAbiCollision = temporaryFolder.newFolder("manifest-expanded-abi-collision")
        writeValidPackage(expandedAbiCollision)
        File(expandedAbiCollision, ModulePackageContract.RUNTIME_MANIFEST_PATH)
            .appendText("immutable-exec|0755|zapret2/bin/arm64-v8a/nfqws2\n")
        assertNotNull(ModulePackageContract.validateStaging(expandedAbiCollision, "arm64-v8a"))
        assertNotNull(
            ModulePackageContract.validateArchive(
                zip(expandedAbiCollision, "manifest-expanded-abi-collision.zip"),
                "arm64-v8a",
            ),
        )
    }

    private fun writeValidPackage(stage: File) {
        val manifest = sourceManifest()
        File(stage, ModulePackageContract.RUNTIME_MANIFEST_PATH).apply {
            parentFile?.mkdirs()
            writeText(manifest)
        }
        manifest.lineSequence().forEach manifestLine@{ line ->
            if (
                line.isEmpty() ||
                line.startsWith('#') ||
                line.startsWith("schema|") ||
                line.startsWith("owner_protocol|")
            ) return@manifestLine
            val (artifactClass, _, declaredPath) = line.split('|')
            if (artifactClass == "installed-exec") return@manifestLine
            val paths = if (artifactClass == "abi-exec") {
                listOf("arm64-v8a", "armeabi-v7a").map { declaredPath.replace("{abi}", it) }
            } else {
                listOf(declaredPath)
            }
            paths.forEach packagePath@{ relative ->
                if (relative == ModulePackageContract.RUNTIME_MANIFEST_PATH) return@packagePath
                File(stage, relative).apply {
                    parentFile?.mkdirs()
                    when (relative) {
                        "module.prop" -> writeText(validModuleProp())
                        ModulePackageContract.LIFECYCLE_CONTRACT_PATH ->
                            writeText("${ModulePackageContract.LIFECYCLE_CONTRACT_VERSION}\n")
                        else -> writeText(
                            if (artifactClass == "immutable-exec") "#!/system/bin/sh\nfixture\n" else "fixture\n",
                        )
                    }
                }
            }
        }
        ModulePackageContract.wrappers.forEach { wrapper ->
            File(stage, wrapper.relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(wrapper.bytes)
            }
        }
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            File(stage, ModulePackageContract.binaryRelativePath(abi))
                .writeBytes(byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        }
    }

    private fun validModuleProp(): String = """
        id=zapret2
        name=Zapret2 DPI Bypass
        version=v1.0.100
        versionCode=1000100
        author=bol-van
        description=DPI bypass using nfqws2 with Lua strategies and the Zapret2 Android app.
        updateJson=https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json
    """.trimIndent() + "\n"

    private fun sourceManifest(): String {
        return repositoryFile(ModulePackageContract.RUNTIME_MANIFEST_PATH).readText()
    }

    private fun repositoryFile(relativePath: String): File {
        val codeSource = runCatching {
            val location = requireNotNull(javaClass.protectionDomain?.codeSource?.location)
            File(location.toURI())
        }.getOrElse { throw IllegalStateException("The test code source is unavailable", it) }
        val roots = generateSequence(codeSource.absoluteFile) { it.parentFile }.take(16).toList()
        val repositoryRoot = roots.firstOrNull { root ->
            File(root, "android-app/settings.gradle.kts").isFile &&
                File(root, "zapret2/runtime-manifest.tsv").isFile
        } ?: error("Repository root containing the Android project and runtime manifest was not found")
        return File(repositoryRoot, relativePath)
    }

    private fun zip(
        source: File,
        name: String,
        additionalEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        val output = temporaryFolder.newFile(name)
        ZipOutputStream(output.outputStream()).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(source).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            additionalEntries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output
    }
}
