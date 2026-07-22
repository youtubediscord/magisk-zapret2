package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HostsIniParserTest {

    @Test
    fun parseLines_flushesDnsDomainsAndServicesAcrossSectionBoundaries() {
        val data = HostsIniParser.parseLines(sampleIni().lines())

        assertEquals(listOf("Cloudflare", "Google"), data.dnsPresets)
        assertEquals(
            listOf(
                DnsService(
                    name = "YouTube",
                    domains = listOf(
                        DnsDomain("youtube.com", listOf("1.1.1.1", "8.8.8.8")),
                        DnsDomain("googlevideo.com", listOf("1.0.0.1", "8.8.4.4")),
                    ),
                ),
                DnsService(
                    name = "Discord",
                    domains = listOf(DnsDomain("discord.com", listOf("1.1.1.1", "8.8.8.8"))),
                ),
            ),
            data.dnsServices,
        )
        assertEquals(
            listOf(DirectService("Local", listOf("127.0.0.1 local.test", "::1 local.test"))),
            data.directServices,
        )
    }

    @Test
    fun generateHostsBlock_selectsRequestedServicesAndPresetIndex() {
        val data = HostsIniParser.parseLines(sampleIni().lines())
        val block = HostsIniParser.generateHostsBlock(
            data = data,
            presetIndex = 1,
            selectedDnsServices = setOf("YouTube"),
            selectedDirectServices = setOf("Local"),
        )

        assertEquals(
            """
            # BEGIN zapret2-dns
            8.8.8.8 youtube.com
            8.8.4.4 googlevideo.com
            127.0.0.1 local.test
            ::1 local.test
            # END zapret2-dns
            """.trimIndent() + "\n",
            block,
        )
        assertFalse(block.contains("discord.com"))
    }

    @Test
    fun generateHostsBlock_rejectsOutOfRangePresetInsteadOfSilentlyGeneratingAnEmptyMapping() {
        val data = HostsIniData(
            dnsPresets = listOf("Only"),
            dnsServices = listOf(DnsService("One", listOf(DnsDomain("example.com", listOf("1.2.3.4"))))),
            directServices = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.generateHostsBlock(data, 5, setOf("One"), emptySet())
        }
    }

    @Test
    fun parser_rejectsDuplicateSectionsServicesDomainsEntriesAndInvalidAddresses() {
        val valid = sampleIni()
        val invalidCatalogs = listOf(
            "$valid\n[DNS]\nDuplicate",
            valid.replace("[Discord]", "[YouTube]"),
            valid.replace("discord.com", "youtube.com"),
            valid.replace("8.8.8.8", "999.8.8.8"),
            valid.replace("::1 local.test", "not-an-ip local.test"),
            valid.replace("::1 local.test", "127.0.0.1 local.test"),
            valid.replace("8.8.4.4\n[Discord]", "8.8.4.4\n8.8.8.9\n[Discord]"),
            valid.replace("[YouTube]", "orphan.example\n1.2.3.4\n\n[YouTube]"),
            valid.replace("[Discord]", "[Empty]\n[Discord]"),
            valid.replace("[Local]", "orphan.local\n[Local]"),
        )

        invalidCatalogs.forEach { catalog ->
            assertThrows(IllegalArgumentException::class.java) {
                HostsIniParser.parseLines(catalog.lines())
            }
        }
    }

    @Test
    fun generator_rejectsSelectionsThatAreNotInTheExactCatalog() {
        val data = HostsIniParser.parseLines(sampleIni().lines())

        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.generateHostsBlock(data, 0, setOf("Unknown"), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.generateHostsBlock(data, 0, emptySet(), setOf("Unknown"))
        }
    }

    @Test
    fun packagedHostsCatalog_isStrictlyValidAndHasOneAddressCardinality() {
        val data = HostsIniParser.parseLines(repositoryFile("zapret2/hosts.ini").readLines())
        val addressCounts = data.dnsServices.flatMap { service ->
            service.domains.map { it.ipsByPreset.size }
        }

        assertEquals(7, data.dnsPresets.size)
        assertEquals(57, data.dnsServices.size)
        assertEquals(11, data.directServices.size)
        assertEquals(803, addressCounts.size)
        assertEquals(setOf(6), addressCounts.toSet())
    }

    @Test
    fun smartMerge_replacesExistingManagedBlockWithoutDuplicatingMarkers() {
        val current = "127.0.0.1 localhost\n# BEGIN zapret2-dns\n9.9.9.9 old.test\n# END zapret2-dns\n::1 localhost\n"
        val replacement = "# BEGIN zapret2-dns\n1.1.1.1 new.test\n# END zapret2-dns\n"

        val merged = HostsIniParser.smartMerge(current, replacement)

        assertTrue(merged.contains("127.0.0.1 localhost"))
        assertTrue(merged.contains("1.1.1.1 new.test"))
        assertTrue(merged.contains("::1 localhost"))
        assertFalse(merged.contains("old.test"))
        assertEquals(1, "# BEGIN zapret2-dns".toRegex().findAll(merged).count())
        assertEquals(1, "# END zapret2-dns".toRegex().findAll(merged).count())
        assertTrue(merged.endsWith("\n"))
    }

    @Test
    fun smartMerge_appendsManagedBlockWhenMissing() {
        val block = "# BEGIN zapret2-dns\n1.1.1.1 new.test\n# END zapret2-dns\n"

        val merged = HostsIniParser.smartMerge("127.0.0.1 localhost\n", block)

        assertTrue(merged.startsWith("127.0.0.1 localhost\n# BEGIN zapret2-dns"))
        assertTrue(merged.endsWith("\n"))
    }

    @Test
    fun removeZapretBlock_removesOnlyManagedContent() {
        val current = "127.0.0.1 localhost\n# BEGIN zapret2-dns\n1.1.1.1 managed.test\n# END zapret2-dns\n::1 localhost\n"

        val result = HostsIniParser.removeZapretBlock(current)

        assertEquals("127.0.0.1 localhost\n::1 localhost\n", result)
        assertFalse(result.contains("managed.test"))
        assertFalse(result.contains("zapret2-dns"))
    }

    @Test
    fun mergeAndRemove_rejectMissingDuplicateOrOutOfOrderMarkers() {
        val validBlock = "# BEGIN zapret2-dns\n1.1.1.1 new.test\n# END zapret2-dns\n"
        val duplicate = validBlock + validBlock
        val missingEnd = "127.0.0.1 localhost\n# BEGIN zapret2-dns\n"
        val outOfOrder = "# END zapret2-dns\n# BEGIN zapret2-dns\n"

        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.smartMerge(duplicate, validBlock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.removeZapretBlock(missingEnd)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.removeZapretBlock(outOfOrder)
        }
    }

    @Test
    fun mergeAndRemove_requireExactMarkerLinesButAcceptCrLf() {
        val validBlock = "# BEGIN zapret2-dns\n1.1.1.1 new.test\n# END zapret2-dns\n"
        val embeddedBegin = "127.0.0.1 localhost # BEGIN zapret2-dns\n# END zapret2-dns\n"
        val trailingEnd = "# BEGIN zapret2-dns\n# END zapret2-dns trailing\n"

        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.smartMerge(embeddedBegin, validBlock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostsIniParser.removeZapretBlock(trailingEnd)
        }
        assertEquals(
            "127.0.0.1 localhost\n",
            HostsIniParser.removeZapretBlock(
                "127.0.0.1 localhost\r\n# BEGIN zapret2-dns\r\n1.1.1.1 old.test\r\n# END zapret2-dns\r\n",
            ),
        )
    }

    private fun sampleIni(): String = """
        # ignored comment before sections
        [DNS]
        Cloudflare
        Google
        [SERVICES_DNS]
        [YouTube]
        youtube.com
        1.1.1.1
        8.8.8.8

        googlevideo.com
        1.0.0.1
        8.8.4.4
        [Discord]
        discord.com
        1.1.1.1
        8.8.8.8
        [SERVICES_DIRECT]
        [Local]
        127.0.0.1 local.test
        ::1 local.test
    """.trimIndent()

    private fun repositoryFile(relativePath: String): File {
        val codeSource = requireNotNull(javaClass.protectionDomain?.codeSource?.location)
        val start = File(codeSource.toURI()).absoluteFile
        val repositoryRoot = generateSequence(start) { it.parentFile }
            .take(16)
            .firstOrNull { root ->
                File(root, "android-app/settings.gradle.kts").isFile &&
                    File(root, "zapret2/runtime-manifest.tsv").isFile
            }
            ?: error("Repository root was not found")
        return File(repositoryRoot, relativePath)
    }
}
