package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.data.HostlistImportFailure
import com.zapret2.app.data.HostlistImportValidation
import com.zapret2.app.data.MAX_HOSTLIST_IMPORT_BYTES
import com.zapret2.app.data.isIpSetHostlistFileName
import com.zapret2.app.data.validateHostlistImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostlistsStateTest {

    @Test
    fun importValidation_normalizesDomainsAndDeduplicatesEntries() {
        val result = validateHostlistImport(
            "custom.txt",
            "# Keep\nExample.COM\nexample.com\n*.video.example\nscontent-*.cdninstagram.com\n".toByteArray(),
        )

        assertEquals(
            "# Keep\nexample.com\n*.video.example\nscontent-*.cdninstagram.com\n",
            (result as HostlistImportValidation.Valid).content,
        )
    }

    @Test
    fun importValidation_acceptsIpSetLiteralsAndRejectsTraversalOrInvalidContent() {
        val ipset = validateHostlistImport(
            "ipset-custom.txt",
            "192.0.2.1\n2001:db8::/32\n".toByteArray(),
        )
        assertTrue(ipset is HostlistImportValidation.Valid)
        assertEquals(
            HostlistImportFailure.INVALID_NAME,
            (validateHostlistImport("../escape.txt", "example.com".toByteArray())
                as HostlistImportValidation.Failure).reason,
        )
        assertEquals(
            HostlistImportFailure.INVALID_CONTENT,
            (validateHostlistImport("custom.txt", "not a host".toByteArray())
                as HostlistImportValidation.Failure).reason,
        )
    }

    @Test
    fun importValidation_recognizesPrefixAndSuffixIpSetNamesForCidrContent() {
        val cidrContent = "104.16.0.0/13\n2001:db8::/32\n".toByteArray()

        listOf(
            "ipset-custom.txt",
            "cloudflare-ipset.txt",
            "CUSTOM-IPSET.TXT",
        ).forEach { fileName ->
            assertTrue(
                "Expected the ipset naming contract to accept CIDR content: $fileName",
                validateHostlistImport(fileName, cidrContent) is HostlistImportValidation.Valid,
            )
        }
    }

    @Test
    fun importValidation_doesNotTreatEmbeddedIpSetTextAsIpSetContract() {
        val cidrContent = "104.16.0.0/13\n".toByteArray()

        listOf(
            "cloudflare-ipset-backup.txt",
            "notipset-custom.txt",
            "customipset.txt",
            "ipset-.txt",
        ).forEach { fileName ->
            assertFalse(
                "Expected a non-contract name to stay a domain hostlist: $fileName",
                isIpSetHostlistFileName(fileName),
            )
            assertEquals(
                HostlistImportFailure.INVALID_CONTENT,
                (validateHostlistImport(fileName, cidrContent) as HostlistImportValidation.Failure).reason,
            )
        }
    }

    @Test
    fun importValidation_enforcesSizeEncodingAndDurableResult() {
        assertEquals(
            HostlistImportFailure.TOO_LARGE,
            (validateHostlistImport("large.txt", ByteArray(MAX_HOSTLIST_IMPORT_BYTES + 1))
                as HostlistImportValidation.Failure).reason,
        )
        assertEquals(
            HostlistImportFailure.INVALID_ENCODING,
            (validateHostlistImport("bad.txt", byteArrayOf(0xC3.toByte(), 0x28))
                as HostlistImportValidation.Failure).reason,
        )
        assertEquals(
            HostlistImportResult.UPDATED,
            restoreImportResult(
                SavedStateHandle(mapOf("hostlists_import_result" to HostlistImportResult.UPDATED.name)),
            ),
        )
        val corruptState = SavedStateHandle(mapOf("hostlists_import_result" to "invalid"))
        assertEquals(null, restoreImportResult(corruptState))
        assertFalse(corruptState.contains("hostlists_import_result"))
    }

    @Test
    fun catalogMutations_requireCurrentAuthoritativeCatalog() {
        val ready = HostlistsUiState(hasAuthoritativeCatalog = true)
        assertTrue(ready.canReloadCatalog)
        assertTrue(ready.canStartCatalogOperation)
        assertTrue(ready.canOpenHostlist)
        assertTrue(HostlistsUiState().canReloadCatalog)
        assertFalse(HostlistsUiState().canStartCatalogOperation)
        assertFalse(
            HostlistsUiState(
                hasAuthoritativeCatalog = true,
                loadError = HostlistsLoadError.ROOT_COMMAND_FAILED,
            ).canStartCatalogOperation,
        )
        listOf(
            HostlistsUiState(hasAuthoritativeCatalog = true, isLoading = true),
            HostlistsUiState(hasAuthoritativeCatalog = true, isRefreshing = true),
            HostlistsUiState(hasAuthoritativeCatalog = true, isImporting = true),
        ).forEach { state ->
            assertFalse(state.canStartCatalogOperation)
            assertFalse(state.canOpenHostlist)
        }
    }
}
