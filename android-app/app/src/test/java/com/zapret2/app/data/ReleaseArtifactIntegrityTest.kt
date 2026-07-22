package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseArtifactIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun downloadProgress_isBoundedForUntrustedContentLength() {
        assertEquals(0, requireNotNull(boundedDownloadPercent(totalBytesRead = 0, contentLength = 100)))
        assertEquals(33, requireNotNull(boundedDownloadPercent(totalBytesRead = 1, contentLength = 3)))
        assertEquals(100, requireNotNull(boundedDownloadPercent(totalBytesRead = 100, contentLength = 100)))
        assertEquals(100, requireNotNull(boundedDownloadPercent(totalBytesRead = 500, contentLength = 100)))
        assertNull(boundedDownloadPercent(totalBytesRead = -1, contentLength = 100))
        assertNull(boundedDownloadPercent(totalBytesRead = 1, contentLength = 0))
        assertNull(boundedDownloadPercent(totalBytesRead = 1, contentLength = -1))
        assertNull(
            boundedDownloadPercent(
                totalBytesRead = 512L * 1024L * 1024L + 1,
                contentLength = 512L * 1024L * 1024L,
            ),
        )
        assertNull(
            boundedDownloadPercent(
                totalBytesRead = 1,
                contentLength = 512L * 1024L * 1024L + 1,
            ),
        )
    }

    @Test
    fun downloadRedirects_acceptOnlyReviewedGetRedirectStatuses() {
        listOf(301, 302, 303, 307, 308).forEach {
            assertTrue(it.toString(), isSupportedDownloadRedirectStatus(it))
        }
        listOf(200, 300, 304, 305, 306, 309, 400).forEach {
            assertFalse(it.toString(), isSupportedDownloadRedirectStatus(it))
        }
    }

    @Test
    fun releaseAssetUrl_acceptsOnlyExactGithubReleaseAndCdnHosts() {
        listOf(
            "https://github.com/youtubediscord/magisk-zapret2/releases/download/v1/app.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/file",
            "https://release-assets.githubusercontent.com/github-production-release-asset/file?sp=r",
            "https://github-releases.githubusercontent.com/archive/module.zip",
            "https://github.com:443/youtubediscord/magisk-zapret2/releases/download/v1/module.zip",
        ).forEach { assertTrue(it, isTrustedReleaseAssetUrl(it)) }
        assertTrue(
            isTrustedReleaseAssetUrl(
                "https://github.com/youtubediscord/magisk-zapret2/releases/download/v1/app.apk",
                allowCdnRedirects = false,
            ),
        )
        assertFalse(
            isTrustedReleaseAssetUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/file",
                allowCdnRedirects = false,
            ),
        )

        listOf(
            "http://github.com/youtubediscord/magisk-zapret2/releases/download/v1/app.apk",
            "https://github.com/other/repository/releases/download/v1/app.apk",
            "https://github.com/youtubediscord/magisk-zapret2/releases/app.apk",
            "https://api.github.com/repos/owner/repository/releases/assets/1",
            "https://example.test/app.apk",
            "https://github.com.evil.test/app.apk",
            "https://user@github.com/app.apk",
            "https://github.com:444/app.apk",
            "https://github.com/app.apk#fragment",
            "https://127.0.0.1/app.apk",
            "https://[::1]/app.apk",
            "https://github.com/app.apk\nX-Test: injected",
            "https://github.com/${"a".repeat(2_100)}",
        ).forEach { assertFalse(it, isTrustedReleaseAssetUrl(it)) }
    }

    @Test
    fun digestMetadata_requiresExactSha256AndRejectsMissingOrMalformedValues() {
        assertTrue(ReleaseArtifactIntegrity.parseSha256Digest(null).isFailure)
        val uppercase = "AB".repeat(32)
        assertEquals("ab".repeat(32), ReleaseArtifactIntegrity.parseSha256Digest("sha256:$uppercase").getOrThrow())
        assertTrue(ReleaseArtifactIntegrity.parseSha256Digest("").isFailure)
        assertTrue(ReleaseArtifactIntegrity.parseSha256Digest("md5:${"ab".repeat(16)}").isFailure)
        assertTrue(ReleaseArtifactIntegrity.parseSha256Digest("sha256:1234").isFailure)
    }

    @Test
    fun partialDownloadNames_acceptOnlyOwnedLegacyOrUuidFiles() {
        val expected = "zapret2.apk"
        assertTrue(isUpdatePartialFileName(expected, "$expected.part.123456"))
        assertTrue(isUpdatePartialFileName(expected, "$expected.part.-123456"))
        assertTrue(
            isUpdatePartialFileName(
                expected,
                "$expected.part.123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertFalse(isUpdatePartialFileName(expected, expected))
        assertFalse(isUpdatePartialFileName(expected, "$expected.part."))
        assertFalse(isUpdatePartialFileName(expected, "$expected.part.123.tmp"))
        assertFalse(isUpdatePartialFileName(expected, "other.apk.part.123456"))
        assertFalse(isUpdatePartialFileName("../zapret2.apk", "$expected.part.123456"))
    }

    @Test
    fun downloadedFileMustMatchAdvertisedDigest() {
        val file = temporaryFolder.newFile("asset.zip").apply { writeText("trusted release bytes") }
        val expected = ReleaseArtifactIntegrity.sha256(file)

        assertTrue(ReleaseArtifactIntegrity.matches(file, expected))
        assertFalse(ReleaseArtifactIntegrity.matches(file, "00".repeat(32)))
        assertFalse(ReleaseArtifactIntegrity.matches(file, "not-a-digest"))
    }

    @Test
    fun apkSigningIdentity_acceptsExactOrRotatedSingleSignerAndRejectsAllOtherShapes() {
        val installed = ApkSigningIdentity(
            currentCertificates = setOf("current"),
            hasMultipleSigners = false,
            certificateHistory = setOf("old", "current"),
        )
        assertTrue(
            apkSigningIdentitiesAreCompatible(
                installed,
                ApkSigningIdentity(
                    currentCertificates = setOf("current"),
                    hasMultipleSigners = false,
                    certificateHistory = setOf("old", "current"),
                ),
            ),
        )
        assertTrue(
            apkSigningIdentitiesAreCompatible(
                installed,
                ApkSigningIdentity(
                    currentCertificates = setOf("next"),
                    hasMultipleSigners = false,
                    certificateHistory = setOf("old", "current", "next"),
                ),
            ),
        )
        assertFalse(
            apkSigningIdentitiesAreCompatible(
                installed,
                ApkSigningIdentity(setOf("foreign"), hasMultipleSigners = false),
            ),
        )
        assertFalse(
            "An old signer from installed history is not a forward update",
            apkSigningIdentitiesAreCompatible(
                installed,
                ApkSigningIdentity(setOf("old"), hasMultipleSigners = false),
            ),
        )
        assertFalse(
            "Candidate history must authenticate its own current signer",
            apkSigningIdentitiesAreCompatible(
                installed,
                ApkSigningIdentity(
                    currentCertificates = setOf("next"),
                    hasMultipleSigners = false,
                    certificateHistory = setOf("current"),
                ),
            ),
        )

        val multi = ApkSigningIdentity(setOf("one", "two"), hasMultipleSigners = true)
        assertTrue(apkSigningIdentitiesAreCompatible(multi, multi.copy()))
        assertFalse(
            apkSigningIdentitiesAreCompatible(
                multi,
                ApkSigningIdentity(setOf("one"), hasMultipleSigners = true),
            ),
        )
        assertFalse(
            apkSigningIdentitiesAreCompatible(
                multi,
                ApkSigningIdentity(setOf("one", "two"), hasMultipleSigners = false),
            ),
        )
        assertFalse(
            apkSigningIdentitiesAreCompatible(
                ApkSigningIdentity(emptySet(), hasMultipleSigners = false),
                installed,
            ),
        )
    }

    @Test
    fun releaseVersionComparison_isBoundedStrictAndPrereleaseAware() {
        assertTrue(isNewerReleaseVersion("2.0.0", "1.99.99"))
        assertTrue(isNewerReleaseVersion("v2.0", "1.9.9"))
        assertTrue(isNewerReleaseVersion("2.0.0", "2.0.0-beta.1"))
        assertFalse(isNewerReleaseVersion("2.0.0-beta.1", "2.0.0"))
        assertTrue(isNewerReleaseVersion("2.0.0-beta.11", "2.0.0-beta.2"))
        assertFalse(isNewerReleaseVersion("2.0.0-beta.2", "2.0.0-beta.11"))
        assertFalse(isNewerReleaseVersion("2.0.0", "2.0.0"))
        assertFalse(isNewerReleaseVersion("not-a-version", "1.0.0"))
        assertFalse(isNewerReleaseVersion("vv2.0.0", "1.0.0"))
        assertFalse(isNewerReleaseVersion("999999999999999999999999.0", "1.0.0"))
        assertTrue(isNewerReleaseVersion("2.0.0", "unknown"))
        assertEquals(100_201L, requireNotNull(projectReleaseVersionCode("v2.0.100201")))
        assertEquals(100_201L, requireNotNull(projectReleaseVersionCode("2.0.100201")))
        assertNull(projectReleaseVersionCode("2.0.0"))
        assertNull(projectReleaseVersionCode("2.100201"))
        assertNull(projectReleaseVersionCode("2.0.0.100201"))
        assertNull(projectReleaseVersionCode("02.0.100201"))
        assertNull(projectReleaseVersionCode("2.0.0100201"))
        assertNull(projectReleaseVersionCode("999999999999999999999999.0.100201"))
        assertNull(projectReleaseVersionCode("2.0.100201-beta.1"))
        assertNull(projectReleaseVersionCode("2.0.2100000001"))
        assertTrue(isProjectReleaseTag("v2.0.100201"))
        assertFalse(isProjectReleaseTag("2.0.100201"))
        assertFalse(isProjectReleaseTag("v2.0.0"))
        assertFalse(moduleVersionAllowsInstall(null, "2.0.100201", allowSameVersionRepair = false))
        assertTrue(moduleVersionAllowsInstall(null, "2.0.100201", allowSameVersionRepair = true))
        assertTrue(moduleVersionAllowsInstall("v1.9.100100", "1.9.100201", allowSameVersionRepair = false))
        assertFalse(moduleVersionAllowsInstall("v1.9.100201", "1.9.100201", allowSameVersionRepair = false))
        assertTrue(moduleVersionAllowsInstall("v1.9.100201", "1.9.100201", allowSameVersionRepair = true))
        assertFalse(moduleVersionAllowsInstall("v2.0.100300", "1.9.100201", allowSameVersionRepair = true))
    }

    @Test
    fun standardModuleInstall_requiresExactPublishedVersionAndArchiveGeneration() {
        val digest = "ab".repeat(32)
        val generation = InstallGenerationMetadata.Record("generation-1", digest)

        assertTrue(
            standardInstallPublicationMatches(
                installedVersion = "v2.0.100201",
                installGeneration = generation,
                expectedReleaseVersion = "2.0.100201",
                expectedArchiveSha256 = digest,
            ),
        )
        assertFalse(
            standardInstallPublicationMatches(
                "v2.0.100200",
                generation,
                "2.0.100201",
                digest,
            ),
        )
        assertFalse(
            standardInstallPublicationMatches(
                "v2.0.100201",
                generation.copy(archiveSha256 = "cd".repeat(32)),
                "2.0.100201",
                digest,
            ),
        )
        assertFalse(standardInstallPublicationMatches(null, generation, "2.0.100201", digest))
        assertFalse(standardInstallPublicationMatches("v2.0.100201", null, "2.0.100201", digest))
        assertFalse(
            standardInstallPublicationMatches(
                "v2.0.100201",
                generation,
                "2.0.100201",
                digest.uppercase(),
            ),
        )
    }
}
