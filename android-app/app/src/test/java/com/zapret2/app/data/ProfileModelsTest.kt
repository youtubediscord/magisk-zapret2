package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileModelsTest {
    private val source = """
        --lua-init=@lua/zapret-lib.lua
        --blob=tls:@bin/tls_clienthello_iana_org.bin

        --name=HTTP
        --filter-tcp=80
        --hostlist=lists/other.txt
        --lua-desync=multisplit:pos=2

        --new

        --name=Discord voice
        --filter-udp=443
        --filter-l7=discord,stun
        --hostlist=lists/discord.txt
        --lua-desync=fake:blob=tls
        --lua-desync=multisplit:pos=1

        --new

        --name=QUIC
        --skip
        --filter-udp=443
        --hostlist=lists/youtube.txt
        --lua-desync=fake:blob=tls
    """.trimIndent()

    @Test
    fun parse_preservesOrderedProfilesAndClassifiesTheirCatalogs() {
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", source))

        assertEquals(listOf("HTTP", "Discord voice", "QUIC"), document.profiles.map { it.name })
        assertEquals(
            listOf(StrategyCatalogScope.HTTP80, StrategyCatalogScope.VOICE, StrategyCatalogScope.UDP),
            document.profiles.map { it.catalogScope },
        )
        assertTrue(document.profiles[0].enabled)
        assertFalse(document.profiles[2].enabled)
        assertEquals(64, document.sourceSha256.length)
        assertEquals(setOf("tls"), document.declaredBlobs)
        assertTrue(document.sourceText.endsWith('\n'))
    }

    @Test
    fun enableToggle_changesOnlySkipInsideTheSelectedBlock() {
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", source))
        val disabled = requireNotNull(PresetProfileParser.setEnabled(document, 0, false))
        val disabledDocument = requireNotNull(PresetProfileParser.parse("Default.txt", disabled))

        assertFalse(disabledDocument.profiles[0].enabled)
        assertFalse(disabledDocument.profiles[2].enabled)
        assertTrue(disabled.contains("--name=HTTP\n--skip\n--filter-tcp=80"))

        val enabled = requireNotNull(PresetProfileParser.setEnabled(disabledDocument, 2, true))
        val enabledDocument = requireNotNull(PresetProfileParser.parse("Default.txt", enabled))
        assertTrue(enabledDocument.profiles[2].enabled)
        assertEquals(1, enabled.lineSequence().count { it == "--skip" })
    }

    @Test
    fun strategyReplacement_keepsFiltersSelectorsAndOrderedStrategyArguments() {
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", source))
        val replacement = listOf("--lua-desync=fake:blob=one", "--lua-desync=multisplit:pos=3")
        val updated = requireNotNull(PresetProfileParser.replaceStrategy(document, 0, replacement))
        val parsed = requireNotNull(PresetProfileParser.parse("Default.txt", updated))

        assertEquals(replacement, parsed.profiles[0].strategies)
        assertEquals(listOf("--filter-tcp=80"), parsed.profiles[0].filters)
        assertEquals(listOf("--hostlist=lists/other.txt"), parsed.profiles[0].selectors)
        assertEquals(document.profiles[1].strategies, parsed.profiles[1].strategies)
        assertNull(PresetProfileParser.replaceStrategy(document, 0, listOf("--filter-tcp=443")))
    }

    @Test
    fun renameAndSelectorReplacement_touchOnlyTheirOwnLines() {
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", source))
        val renamed = requireNotNull(PresetProfileParser.rename(document, 0, "  Plain HTTP  "))
        val renamedDocument = requireNotNull(PresetProfileParser.parse("Default.txt", renamed))
        val selected = requireNotNull(
            PresetProfileParser.replaceSelector(renamedDocument, 0, 0, "lists/google.txt"),
        )
        val parsed = requireNotNull(PresetProfileParser.parse("Default.txt", selected))

        assertEquals("Plain HTTP", parsed.profiles[0].name)
        assertEquals(listOf("--hostlist=lists/google.txt"), parsed.profiles[0].selectors)
        assertEquals(document.profiles[0].strategies, parsed.profiles[0].strategies)
        assertEquals(document.profiles[1], parsed.profiles[1])
        assertNull(PresetProfileParser.rename(document, 0, "\n"))
        assertNull(PresetProfileParser.replaceSelector(document, 0, 0, "../google.txt"))
    }

    @Test
    fun mixedProtocolProfileDoesNotGuessStrategyCatalog() {
        val mixed = source.replaceFirst("--filter-tcp=80", "--filter-tcp=80\n--filter-udp=443")
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", mixed))

        assertNull(document.profiles.first().catalogScope)
    }

    @Test
    fun interleavedStrategyAndPayloadCannotBeRewrittenAsOneCatalogStrategy() {
        val interleaved = source.replaceFirst(
            "--lua-desync=multisplit:pos=2",
            "--lua-desync=multisplit:pos=2\n--payload=http_req\n--lua-desync=fake:blob=tls",
        )
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", interleaved))

        assertNull(document.profiles.first().catalogScope)
        assertNull(PresetProfileParser.replaceStrategy(document, 0, listOf("--lua-desync=pass")))
    }

    @Test
    fun strategyCatalogCombinesMetadataAndArgumentBlobDependencies() {
        val catalog = """
            [safe]
            name = Safe
            blobs = explicitly_declared
            --lua-desync=fake:blob=referenced:seqovl_pattern=referenced_too
            --lua-desync=fake:blob=fake_default_tls:pattern=0x00
        """.trimIndent()

        val entry = requireNotNull(parseStrategyCatalog(catalog)).single()

        assertEquals(setOf("explicitly_declared", "referenced", "referenced_too"), entry.requiredBlobs)
        assertNull(parseStrategyCatalog(catalog.replace("explicitly_declared", "bad,,value")))
    }

    @Test
    fun selectorTypeMatchingNeverOffersIpSetsAsHostlistsOrViceVersa() {
        assertTrue(profileListEntryMatchesSelector("other.txt", "--hostlist=lists/other.txt"))
        assertFalse(profileListEntryMatchesSelector("ipset-discord.txt", "--hostlist=lists/other.txt"))
        assertTrue(profileListEntryMatchesSelector("ipset-discord.txt", "--ipset=lists/ipset-discord.txt"))
        assertFalse(profileListEntryMatchesSelector("other.txt", "--ipset=lists/ipset-discord.txt"))
    }

    @Test
    fun move_reordersWholeBlocksWithoutChangingGlobalOptions() {
        val document = requireNotNull(PresetProfileParser.parse("Default.txt", source))
        val moved = requireNotNull(PresetProfileParser.move(document, 2, 0))
        val parsed = requireNotNull(PresetProfileParser.parse("Default.txt", moved))

        assertEquals(listOf("QUIC", "HTTP", "Discord voice"), parsed.profiles.map { it.name })
        assertTrue(moved.startsWith("--lua-init=@lua/zapret-lib.lua\n--blob=tls:@bin/tls_clienthello_iana_org.bin"))
        assertEquals(2, moved.lineSequence().count { it == "--new" })
    }

    @Test
    fun parse_rejectsMissingSeparatorsAndIncompleteProfiles() {
        assertNull(PresetProfileParser.parse("broken.txt", source.replaceFirst("\n--new\n", "\n")))
        assertNull(PresetProfileParser.parse("broken.txt", source.replace("--lua-desync=multisplit:pos=2", "")))
        assertNotNull(PresetProfileParser.parse("Default.txt", source))
    }
}
