package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallGenerationMetadataTest {
    private val digest = "0123456789abcdef".repeat(4)

    @Test
    fun parser_acceptsOnlyExactPackageSchema() {
        val valid = listOf(
            "version=1",
            "module_dir=/data/adb/modules/zapret2",
            "generation=generation-1",
            "archive_sha256=$digest",
        )

        assertEquals("generation-1", InstallGenerationMetadata.parse(valid)?.generation)
        assertEquals(
            setOf("version", "module_dir", "generation", "archive_sha256"),
            InstallGenerationMetadata.fields,
        )
        assertNotNull(InstallGenerationMetadata.parse(valid.reversed()))
        assertNull(InstallGenerationMetadata.parse(valid + "future=value"))
        assertNull(InstallGenerationMetadata.parse(valid + "generation=duplicate"))
        assertNull(InstallGenerationMetadata.parse(valid.map {
            if (it.startsWith("archive_sha256=")) "archive_sha256=${digest.uppercase()}" else it
        }))
    }

    @Test
    fun publicationShell_writesAndRevalidatesPrivateInstallerOwnedMetadata() {
        val record = InstallGenerationMetadata.create(digest)
        assertNotNull(record)
        val shell = InstallGenerationMetadata.buildPublicationShell("/candidate", record!!)

        assertTrue(shell.contains("/candidate/zapret2/install-generation.meta"))
        assertTrue(shell.contains("chmod 0600"))
        assertTrue(shell.contains("archive_sha256=%s"))
        assertTrue(shell.contains(digest))
        assertTrue(shell.contains("stat -c %u"))
        assertTrue(shell.contains("stat -c %a"))
        assertTrue(shell.contains("stat -c %h"))
        assertTrue(shell.contains("install_meta_size"))
        assertTrue(shell.contains("-le 1024"))
        assertTrue(shell.contains("#install_meta_seen}"))
    }
}
