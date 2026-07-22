package com.zapret2.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsFileSyntaxTest {

    @Test
    fun validFile_acceptsCommentsWhitespaceIpv4Ipv6AndAliases() {
        val content = """
            # base mappings
            127.0.0.1 localhost localhost.localdomain
            ::1 localhost6 # inline comment

            0.0.0.0 ads.example.test
        """.trimIndent()

        assertTrue(HostsFileSyntax.isValidFile(content))
    }

    @Test
    fun invalidFile_rejectsMissingMappingsMalformedAddressesAndMalformedHostnames() {
        listOf(
            "",
            "# comments only",
            "localhost",
            "999.0.0.1 example.test",
            "127.0.0.1",
            "127.0.0.1 -invalid.test",
            "127.0.0.1 invalid_.test",
            "127.0.0.1 пример.рф",
            "127.0.0.1 valid.test\u0000",
        ).forEach { content ->
            assertFalse(content, HostsFileSyntax.isValidFile(content))
        }
    }

    @Test
    fun packagedDnsCatalogUsesTheSameAddressAndHostnameBoundary() {
        assertTrue(HostsFileSyntax.isValidIpAddress("1.2.3.4"))
        assertTrue(HostsFileSyntax.isValidIpAddress("2001:db8::1"))
        assertTrue(HostsFileSyntax.isValidHostname("cdn-1.example.test"))
        assertFalse(HostsFileSyntax.isValidIpAddress("01.2.3.4"))
        assertFalse(HostsFileSyntax.isValidHostname("cdn_1.example.test"))
    }
}
