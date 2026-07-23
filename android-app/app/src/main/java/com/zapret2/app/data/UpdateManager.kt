package com.zapret2.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.Os
import androidx.core.content.FileProvider
import com.zapret2.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

private const val RELEASE_DOWNLOAD_PATH_PREFIX =
    "/youtubediscord/magisk-zapret2/releases/download/"
private const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L

private val TRUSTED_RELEASE_CDN_HOSTS = setOf(
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

/** Exact network boundary for GitHub release assets and their GitHub-owned CDN redirects. */
internal fun isTrustedReleaseAssetUrl(
    value: String,
    allowCdnRedirects: Boolean = true,
): Boolean {
    if (value.isEmpty() || value.length > 2_048 || value.any(Char::isISOControl)) return false
    return try {
        val parsed = URL(value)
        val host = parsed.host.lowercase(Locale.ROOT)
        val trustedLocation = when {
            host == "github.com" -> parsed.path.startsWith(RELEASE_DOWNLOAD_PATH_PREFIX)
            allowCdnRedirects -> host in TRUSTED_RELEASE_CDN_HOSTS
            else -> false
        }
        parsed.protocol == "https" &&
            trustedLocation &&
            parsed.userInfo == null &&
            parsed.ref == null &&
            parsed.port in setOf(-1, 443)
    } catch (_: Exception) {
        false
    }
}

/** Returns a UI-safe percentage only for byte counts inside the enforced download budget. */
internal fun boundedDownloadPercent(totalBytesRead: Long, contentLength: Long): Int? {
    if (totalBytesRead !in 0L..MAX_DOWNLOAD_BYTES || contentLength !in 1L..MAX_DOWNLOAD_BYTES) {
        return null
    }
    if (totalBytesRead >= contentLength) return 100
    return ((totalBytesRead * 100L) / contentLength).toInt()
}

internal fun isSupportedDownloadRedirectStatus(responseCode: Int): Boolean = when (responseCode) {
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    307,
    308 -> true
    else -> false
}

internal data class ApkSigningIdentity(
    val currentCertificates: Set<String>,
    val hasMultipleSigners: Boolean,
    val certificateHistory: Set<String> = currentCertificates,
)

internal fun apkSigningIdentitiesAreCompatible(
    installed: ApkSigningIdentity,
    candidate: ApkSigningIdentity,
): Boolean {
    fun ApkSigningIdentity.hasValidShape(): Boolean {
        if (currentCertificates.isEmpty() || !certificateHistory.containsAll(currentCertificates)) {
            return false
        }
        return if (hasMultipleSigners) {
            currentCertificates.size > 1 && certificateHistory == currentCertificates
        } else {
            currentCertificates.size == 1
        }
    }

    if (!installed.hasValidShape() || !candidate.hasValidShape()) return false
    return if (installed.hasMultipleSigners || candidate.hasMultipleSigners) {
        installed.hasMultipleSigners == candidate.hasMultipleSigners &&
            installed.currentCertificates == candidate.currentCertificates
    } else {
        installed.currentCertificates.single() in candidate.certificateHistory
    }
}

private val RELEASE_VERSION_PATTERN = Regex(
    "([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?",
)
private val PROJECT_RELEASE_VERSION_PATTERN = Regex(
    "^v?([1-9][0-9]{0,3})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]{0,3})$",
)

private data class ParsedReleaseVersion(
    val numericParts: List<Long>,
    val preRelease: String?,
)

internal fun projectReleaseVersionCode(version: String): Long? {
    val match = PROJECT_RELEASE_VERSION_PATTERN.matchEntire(version) ?: return null
    val major = match.groupValues[1].toLongOrNull()?.takeIf { it in 1L..2_100L } ?: return null
    val minor = match.groupValues[2].toLongOrNull()?.takeIf { it in 0L..99L } ?: return null
    val patch = match.groupValues[3].toLongOrNull()?.takeIf { it in 0L..9_999L } ?: return null
    return (major * 1_000_000L + minor * 10_000L + patch)
        .takeIf { it in 1L..2_100_000_000L }
}

internal fun isProjectReleaseTag(tag: String): Boolean =
    tag.startsWith("v") && projectReleaseVersionCode(tag) != null

internal fun moduleVersionAllowsInstall(
    installedVersion: String?,
    expectedReleaseVersion: String,
    allowSameVersionRepair: Boolean,
): Boolean {
    if (installedVersion == null) return allowSameVersionRepair
    val sameVersion = installedVersion == "v$expectedReleaseVersion"
    return isNewerReleaseVersion(expectedReleaseVersion, installedVersion) ||
        (allowSameVersionRepair && sameVersion)
}

internal fun standardInstallPublicationMatches(
    installedVersion: String?,
    installGeneration: InstallGenerationMetadata.Record?,
    expectedReleaseVersion: String,
    expectedArchiveSha256: String,
): Boolean =
    projectReleaseVersionCode(expectedReleaseVersion) != null &&
        expectedArchiveSha256.length == 64 &&
        expectedArchiveSha256.all { it in '0'..'9' || it in 'a'..'f' } &&
        installedVersion == "v$expectedReleaseVersion" &&
        installGeneration?.archiveSha256 == expectedArchiveSha256

data class ModuleInstallResult(
    val success: Boolean,
    val needsReboot: Boolean,
)

private data class ValidatedModuleArtifact(
    val file: File,
    val binaryDirectory: String,
    val archiveSha256: String,
)

internal fun isUpdatePartialFileName(expectedFileName: String, candidateName: String): Boolean {
    if (!RootFileIo.isSimpleFileName(expectedFileName)) return false
    val suffix = candidateName.removePrefix("$expectedFileName.part.")
        .takeIf { candidateName.startsWith("$expectedFileName.part.") }
        ?: return false
    return suffix.matches(Regex("-?[0-9]+")) ||
        suffix.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
}

private fun parseReleaseVersion(version: String): ParsedReleaseVersion? {
    val match = RELEASE_VERSION_PATTERN.matchEntire(version.removePrefix("v")) ?: return null
    val numericParts = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    val preRelease = match.groupValues[2].ifEmpty { null }
    if (preRelease?.split('.')?.any { it.all(Char::isDigit) && it.toLongOrNull() == null } == true) {
        return null
    }
    return ParsedReleaseVersion(
        numericParts = numericParts,
        preRelease = preRelease,
    )
}

internal fun isNewerReleaseVersion(latest: String, current: String): Boolean {
    val latestVersion = parseReleaseVersion(latest) ?: return false
    val currentVersion = parseReleaseVersion(current) ?: return true
    for (index in 0 until maxOf(latestVersion.numericParts.size, currentVersion.numericParts.size)) {
        val latestPart = latestVersion.numericParts.getOrElse(index) { 0L }
        val currentPart = currentVersion.numericParts.getOrElse(index) { 0L }
        when {
            latestPart > currentPart -> return true
            latestPart < currentPart -> return false
        }
    }
    val latestPreRelease = latestVersion.preRelease
    val currentPreRelease = currentVersion.preRelease
    return when {
        latestPreRelease == null -> currentPreRelease != null
        currentPreRelease == null -> false
        else -> comparePreRelease(latestPreRelease, currentPreRelease) > 0
    }
}

private fun comparePreRelease(left: String, right: String): Int {
    val leftParts = left.split('.')
    val rightParts = right.split('.')
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts.getOrNull(index) ?: return -1
        val rightPart = rightParts.getOrNull(index) ?: return 1
        val leftNumber = leftPart.toLongOrNull()
        val rightNumber = rightPart.toLongOrNull()
        val comparison = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> leftPart.compareTo(rightPart)
        }
        if (comparison != 0) return comparison
    }
    return 0
}

/**
 * Manager for checking and installing updates from GitHub Releases.
 * Handles both APK updates and Magisk module updates.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/youtubediscord/magisk-zapret2/releases/latest"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val BUFFER_SIZE = 8192
        private const val MAX_RELEASE_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_CHANGELOG_CHARS = 64 * 1024
        private const val MAX_RELEASE_ASSETS = 1_000
        private const val MAX_ARCHIVE_ENTRIES = 20_000
        private const val MAX_ARCHIVE_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_ARCHIVE_TOTAL_BYTES = 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_PATH_COMPONENT_BYTES = 255
        private const val UPDATE_CACHE_DIRECTORY = "updates"
        private const val UPDATE_APK_FILE = "zapret2-update.apk"
        private const val UPDATE_MODULE_FILE = "zapret2-module.zip"
        private const val PRIVATE_FILE_MODE = 0b110_000_000 // 0600
        private const val PRIVATE_DIRECTORY_MODE = 0b111_000_000 // 0700
        private const val MODULE_DIR = "/data/adb/modules/zapret2"
        private const val MODULE_UPDATE_DIR = "/data/adb/modules_update/zapret2"
    }

    private class ModuleInstallRejectedException : IllegalStateException()
    private class DownloadTooLargeException : IllegalStateException()

    /**
     * Data class representing a release from GitHub.
     */
    data class ReleaseArtifact(
        val url: String,
        val sha256: String,
    )

    data class Release(
        val version: String,
        val apkArtifact: ReleaseArtifact?,
        val moduleArtifact: ReleaseArtifact?,
        val changelog: String,
        val allowSameVersionModuleRepair: Boolean = false,
    ) {
        val apkUrl: String? get() = apkArtifact?.url
        val moduleUrl: String? get() = moduleArtifact?.url
    }

    /**
     * Sealed class for update check results.
     */
    sealed class UpdateResult {
        data class Available(val release: Release) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val reason: UpdateCheckFailure) : UpdateResult()
    }

    sealed interface UpdateCheckFailure {
        data object NoInternet : UpdateCheckFailure
        data object ConnectionTimeout : UpdateCheckFailure
        data object SecureConnectionFailed : UpdateCheckFailure
        data class ServerResponse(val statusCode: Int) : UpdateCheckFailure {
            init {
                require(statusCode in 100..599)
            }
        }
        data object MetadataTooLarge : UpdateCheckFailure
        data object EmptyResponse : UpdateCheckFailure
        data object InvalidMetadata : UpdateCheckFailure
        data object RequestFailed : UpdateCheckFailure
    }

    private sealed interface ReleaseFetchResult {
        data class Success(val release: Release) : ReleaseFetchResult
        data class Failure(val reason: UpdateCheckFailure) : ReleaseFetchResult
    }

    /**
     * Sealed class for download results.
     */
    private sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val reason: DownloadFailureReason) : DownloadResult()
    }

    /**
     * Checks for available updates from GitHub Releases.
     * Compares the current app version with the latest release version.
     *
     * @return UpdateResult indicating if an update is available, up to date, or error
     */
    suspend fun checkForUpdates(
        currentModuleVersion: String?,
        allowModuleUpdate: Boolean,
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val release = when (val result = fetchLatestRelease()) {
                is ReleaseFetchResult.Success -> result.release
                is ReleaseFetchResult.Failure -> return@withContext UpdateResult.Error(result.reason)
            }

            val currentApkVersion = BuildConfig.VERSION_NAME
            val apkOutdated = release.apkUrl != null && isNewerReleaseVersion(release.version, currentApkVersion)

            val moduleOutdated = allowModuleUpdate && release.moduleUrl != null &&
                (currentModuleVersion.isNullOrBlank() ||
                    isNewerReleaseVersion(release.version, currentModuleVersion))

            if (apkOutdated || moduleOutdated) {
                UpdateResult.Available(
                    release.onlyOutdatedArtifacts(
                        apkOutdated = apkOutdated,
                        moduleOutdated = moduleOutdated,
                        allowSameVersionModuleRepair =
                            moduleOutdated && currentModuleVersion.isNullOrBlank(),
                    )
                )
            } else {
                UpdateResult.UpToDate
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UpdateResult.Error(UpdateCheckFailure.RequestFailed)
        }
    }

    /**
     * Fetches the latest release information from GitHub API.
     *
     * @return A typed release or presentation-safe failure reason
     */
    private suspend fun fetchLatestRelease(): ReleaseFetchResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Zapret2-Android/${BuildConfig.VERSION_NAME}")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext ReleaseFetchResult.Failure(
                    UpdateCheckFailure.ServerResponse(responseCode),
                )
            }

            val response = connection.inputStream.use { stream ->
                stream.readBoundedBytes(MAX_RELEASE_RESPONSE_BYTES)?.toString(Charsets.UTF_8)
            } ?: return@withContext ReleaseFetchResult.Failure(UpdateCheckFailure.MetadataTooLarge)

            if (response.isBlank()) {
                return@withContext ReleaseFetchResult.Failure(UpdateCheckFailure.EmptyResponse)
            }

            val release = parseReleaseJson(response)
            ReleaseFetchResult.Success(release)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.net.UnknownHostException) {
            ReleaseFetchResult.Failure(UpdateCheckFailure.NoInternet)
        } catch (_: java.net.SocketTimeoutException) {
            ReleaseFetchResult.Failure(UpdateCheckFailure.ConnectionTimeout)
        } catch (_: javax.net.ssl.SSLException) {
            ReleaseFetchResult.Failure(UpdateCheckFailure.SecureConnectionFailed)
        } catch (_: org.json.JSONException) {
            ReleaseFetchResult.Failure(UpdateCheckFailure.InvalidMetadata)
        } catch (_: Exception) {
            ReleaseFetchResult.Failure(UpdateCheckFailure.RequestFailed)
        } finally {
            disconnectBestEffort(connection)
        }
    }

    /**
     * Parses the GitHub API JSON response into a Release object.
     *
     * @param json Raw JSON string from GitHub API
     * @return Release object with parsed data
     */
    private fun parseReleaseJson(json: String): Release {
        val jsonObject = JSONObject(json)

        val rawTagName = jsonObject.optString("tag_name", "")
        if (rawTagName.length > 65 || !isProjectReleaseTag(rawTagName)) {
            throw org.json.JSONException("Release tag is missing or invalid")
        }
        val tagName = rawTagName.removePrefix("v")
        val body = jsonObject.optString("body", "")
        if (body.length > MAX_CHANGELOG_CHARS || body.any { it == '\u0000' }) {
            throw org.json.JSONException("Release changelog is too large or invalid")
        }

        var apkArtifact: ReleaseArtifact? = null
        var moduleArtifact: ReleaseArtifact? = null

        val assets = jsonObject.optJSONArray("assets")
        if (assets != null) {
            if (assets.length() > MAX_RELEASE_ASSETS) {
                throw org.json.JSONException("Release contains too many assets")
            }
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.length > 256 || name.any(Char::isISOControl)) {
                    throw org.json.JSONException("Release asset name is invalid")
                }
                val installableKind = when {
                    name.endsWith(".apk", ignoreCase = true) -> "APK"
                    name.endsWith(".zip", ignoreCase = true) && name.contains("magisk", ignoreCase = true) -> "module"
                    else -> null
                } ?: continue
                val downloadUrl = asset.optString("browser_download_url", "")
                    .takeIf { isTrustedReleaseAssetUrl(it, allowCdnRedirects = false) }
                    ?: throw org.json.JSONException("$installableKind asset has an untrusted release URL")
                val advertisedDigest = asset.optString("digest", "").takeIf { it.isNotBlank() }
                val trustedDigest = ReleaseArtifactIntegrity.parseSha256Digest(advertisedDigest)
                    .getOrElse { throw org.json.JSONException("$installableKind asset: ${it.message}") }
                val releaseArtifact = ReleaseArtifact(downloadUrl, trustedDigest)

                when (installableKind) {
                    "APK" -> {
                        if (apkArtifact != null) throw org.json.JSONException("Release has multiple APK assets")
                        apkArtifact = releaseArtifact
                    }
                    "module" -> {
                        if (moduleArtifact != null) throw org.json.JSONException("Release has multiple module assets")
                        moduleArtifact = releaseArtifact
                    }
                }
            }
        }
        if (apkArtifact == null && moduleArtifact == null) {
            throw org.json.JSONException("Release contains no trusted installable assets")
        }

        return Release(
            version = tagName,
            apkArtifact = apkArtifact,
            moduleArtifact = moduleArtifact,
            changelog = body,
        )
    }

    /**
     * Downloads a file from the given URL to the cache directory.
     * Reports download progress via callback.
     *
     * @param url URL of the file to download
     * @param fileName Name for the downloaded file
     * @param expectedSha256 Digest bound to this exact release asset
     * @param progress Callback function receiving progress percentage (0-100)
     * @return DownloadResult with the downloaded file or error
     */
    private suspend fun downloadFile(
        url: String,
        fileName: String,
        expectedSha256: String,
        progress: suspend (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var partialFile: File? = null
        var finalizedFile: File? = null
        try {
            if (!RootFileIo.isSimpleFileName(fileName)) {
                return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
            }
            // Follow GitHub redirects (github.com -> objects.githubusercontent.com)
            var currentUrl = URL(url)
            if (!isTrustedReleaseAssetUrl(currentUrl.toExternalForm(), allowCdnRedirects = false)) {
                return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
            }
            var redirectCount = 0
            var responseReady = false
            while (redirectCount <= 5) {
                connection = currentUrl.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = false // handle manually for HTTPS->HTTPS
                    setRequestProperty("User-Agent", "Zapret2-Android/${BuildConfig.VERSION_NAME}")
                }

                val responseCode = connection.responseCode
                if (isSupportedDownloadRedirectStatus(responseCode)) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) {
                        return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
                    }
                    currentUrl = URL(currentUrl, location)
                    if (!isTrustedReleaseAssetUrl(currentUrl.toExternalForm())) {
                        return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
                    }
                    redirectCount++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext DownloadResult.Error(DownloadFailureReason.SERVER_REJECTED)
                }
                responseReady = true
                break
            }

            if (!responseReady) {
                return@withContext DownloadResult.Error(DownloadFailureReason.TOO_MANY_REDIRECTS)
            }
            val activeConnection = connection
                ?: return@withContext DownloadResult.Error(DownloadFailureReason.FAILED)

            val fileLength = activeConnection.contentLengthLong
            if (fileLength > MAX_DOWNLOAD_BYTES) {
                return@withContext DownloadResult.Error(DownloadFailureReason.TOO_LARGE)
            }
            val updateCache = updateCacheDirectory()
                ?: return@withContext DownloadResult.Error(DownloadFailureReason.STORAGE_UNAVAILABLE)
            if (!pruneStalePartialDownloads(updateCache, fileName)) {
                return@withContext DownloadResult.Error(DownloadFailureReason.STORAGE_UNAVAILABLE)
            }
            val outputFile = File(updateCache, fileName)
            if (outputFile.canonicalFile.parentFile != updateCache) {
                return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
            }
            val downloadPart = File(updateCache, "$fileName.part.${UUID.randomUUID()}")
            if (downloadPart.exists() || downloadPart.canonicalFile.parentFile != updateCache) {
                return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
            }
            partialFile = downloadPart
            if (ReleaseArtifactIntegrity.parseSha256Digest("sha256:$expectedSha256").getOrNull() != expectedSha256) {
                return@withContext DownloadResult.Error(DownloadFailureReason.SECURITY_POLICY_REJECTED)
            }
            val downloadDigest = MessageDigest.getInstance("SHA-256")

            if (outputFile.exists() && !outputFile.delete()) {
                return@withContext DownloadResult.Error(DownloadFailureReason.STORAGE_UNAVAILABLE)
            }

            activeConnection.inputStream.use { input ->
                downloadPart.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0
                    var lastReportedProgress = -1

                    while (input.readWithProgress(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadDigest.update(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (totalBytesRead > MAX_DOWNLOAD_BYTES) {
                            throw DownloadTooLargeException()
                        }

                        boundedDownloadPercent(totalBytesRead, fileLength)?.let { currentProgress ->
                            if (currentProgress != lastReportedProgress) {
                                lastReportedProgress = currentProgress
                                progress(currentProgress)
                            }
                        }
                    }
                }
            }
            if (!setExactMode(downloadPart, PRIVATE_FILE_MODE)) {
                return@withContext DownloadResult.Error(DownloadFailureReason.STORAGE_UNAVAILABLE)
            }

            val actualSha256 = downloadDigest.digest()
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (actualSha256 != expectedSha256) {
                return@withContext DownloadResult.Error(DownloadFailureReason.CHECKSUM_MISMATCH)
            }

            if (!downloadPart.renameTo(outputFile)) {
                return@withContext DownloadResult.Error(DownloadFailureReason.STORAGE_UNAVAILABLE)
            }
            partialFile = null
            finalizedFile = outputFile

            progress(100)
            DownloadResult.Success(outputFile).also { finalizedFile = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DownloadTooLargeException) {
            DownloadResult.Error(DownloadFailureReason.TOO_LARGE)
        } catch (_: java.net.UnknownHostException) {
            DownloadResult.Error(DownloadFailureReason.NO_INTERNET)
        } catch (_: java.net.SocketTimeoutException) {
            DownloadResult.Error(DownloadFailureReason.CONNECTION_TIMEOUT)
        } catch (_: javax.net.ssl.SSLException) {
            DownloadResult.Error(DownloadFailureReason.SECURE_CONNECTION_FAILED)
        } catch (_: Exception) {
            DownloadResult.Error(DownloadFailureReason.FAILED)
        } finally {
            deleteFileBestEffort(partialFile)
            deleteFileBestEffort(finalizedFile)
            disconnectBestEffort(connection)
        }
    }

    /**
     * Initiates APK installation via system installer.
     * Uses FileProvider for Android 7.0+ compatibility.
     *
     * @param apkFile The APK file to install
     */
    private fun installApk(apkFile: File) {
        val updateCache = updateCacheDirectory()
            ?: throw IllegalStateException("Update cache is unavailable")
        val canonicalApk = apkFile.canonicalFile
        require(
            canonicalApk.parentFile == updateCache &&
                canonicalApk.name == UPDATE_APK_FILE &&
                canonicalApk.isFile &&
                canonicalApk.length() in 1L..MAX_DOWNLOAD_BYTES &&
                setExactMode(canonicalApk, PRIVATE_FILE_MODE),
        ) { "APK is outside the private update cache" }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            canonicalApk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    internal fun validateApkArtifact(
        apkFile: File,
        expectedReleaseVersion: String,
        expectedVersionCode: Long,
    ): ArtifactValidationReason? {
        if (!apkFile.isFile || apkFile.length() <= 0L || apkFile.length() > MAX_DOWNLOAD_BYTES) {
            return ArtifactValidationReason.APK_FILE_INVALID
        }
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val packageManager = context.packageManager
            val candidate = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return ArtifactValidationReason.APK_UNREADABLE
            val installed = packageManager.getPackageInfo(context.packageName, flags)
            if (candidate.packageName != context.packageName) {
                return ArtifactValidationReason.APK_PACKAGE_ID_MISMATCH
            }
            if (candidate.versionCodeCompat() <= installed.versionCodeCompat()) {
                return ArtifactValidationReason.APK_NOT_NEWER
            }
            if (candidate.versionCodeCompat() != expectedVersionCode) {
                return ArtifactValidationReason.APK_VERSION_CODE_MISMATCH
            }
            if (candidate.versionName != expectedReleaseVersion) {
                return ArtifactValidationReason.APK_VERSION_MISMATCH
            }
            val installedIdentity = installed.signingIdentity()
                ?: return ArtifactValidationReason.INSTALLED_APK_SIGNER_UNAVAILABLE
            val candidateIdentity = candidate.signingIdentity()
                ?: return ArtifactValidationReason.APK_SIGNER_UNAVAILABLE
            if (!apkSigningIdentitiesAreCompatible(installedIdentity, candidateIdentity)) {
                return ArtifactValidationReason.APK_SIGNER_MISMATCH
            }
            null
        } catch (_: Exception) {
            ArtifactValidationReason.APK_VALIDATION_FAILED
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingIdentity(): ApkSigningIdentity? {
        val (currentSignatures, historySignatures, multiple) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return null
            val hasMultipleSigners = info.hasMultipleSigners()
            Triple(
                info.apkContentsSigners,
                if (hasMultipleSigners) info.apkContentsSigners else info.signingCertificateHistory,
                hasMultipleSigners,
            )
        } else {
            val current = this.signatures
            Triple(current, current, current.orEmpty().size > 1)
        }
        fun Array<android.content.pm.Signature>?.digests(): Set<String> =
            orEmpty().mapTo(linkedSetOf()) { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
        return ApkSigningIdentity(
            currentCertificates = currentSignatures.digests(),
            hasMultipleSigners = multiple,
            certificateHistory = historySignatures.digests(),
        ).takeIf { it.currentCertificates.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    /**
     * Stages a validated Magisk module through Magisk's installer.
     *
     * Magisk explicitly treats mounted module files as unsafe to modify. Every module
     * installation therefore publishes to modules_update and becomes active after reboot.
     * Live reload is reserved for user-owned runtime data and never replaces package code.
     */
    private suspend fun installValidatedModule(
        artifact: ValidatedModuleArtifact,
        expectedReleaseVersion: String,
        allowSameVersionRepair: Boolean,
    ): ModuleInstallResult {
        var completedResult: ModuleInstallResult? = null
        return try {
            withContext(Dispatchers.IO) {
                ModuleMutationCoordinator.withModuleStaging {
                    installModuleLocked(
                        artifact,
                        expectedReleaseVersion,
                        allowSameVersionRepair,
                        recordCommitted = { completedResult = it },
                    )
                }.also { completedResult = it }
            }
        } catch (cancelled: CancellationException) {
            // Magisk may have committed the pending generation before coroutine cancellation.
            // Preserve that terminal result so the UI always reports the required reboot.
            completedResult?.takeIf { it.success } ?: throw cancelled
        }
    }

    private suspend fun installModuleLocked(
        artifact: ValidatedModuleArtifact,
        expectedReleaseVersion: String,
        allowSameVersionRepair: Boolean,
        recordCommitted: (ModuleInstallResult) -> Unit,
    ): ModuleInstallResult {
        val moduleFile = artifact.file
        val binaryDirectory = artifact.binaryDirectory
        if (!moduleFile.isFile || calculateSha256(moduleFile) != artifact.archiveSha256) {
            return ModuleInstallResult(false, false)
        }
        val archiveSha256 = artifact.archiveSha256

        val layout = ServiceLifecycleController.executeRoot(
            """
                z2_live=${RootFileIo.shellQuote(MODULE_DIR)}
                z2_pending=${RootFileIo.shellQuote(MODULE_UPDATE_DIR)}
                [ ! -e "${'$'}z2_pending" ] && [ ! -L "${'$'}z2_pending" ] || exit 1
                if [ ! -e "${'$'}z2_live" ] && [ ! -L "${'$'}z2_live" ]; then
                    printf 'Z2_MODULE_LAYOUT=MISSING\n'
                else
                    [ -d "${'$'}z2_live" ] && [ ! -L "${'$'}z2_live" ] &&
                        [ "${'$'}(stat -c %u "${'$'}z2_live" 2>/dev/null)" = 0 ] || exit 1
                    z2_live_mode=${'$'}(stat -c %a "${'$'}z2_live" 2>/dev/null) || exit 1
                    case "${'$'}z2_live_mode" in 700|711|750|751|755) ;; *) exit 1 ;; esac
                    printf 'Z2_MODULE_LAYOUT=PRESENT\n'
                fi
            """.trimIndent(),
        )
        val moduleExists = when (layout.stdout.singleOrNull().takeIf { layout.success }) {
            "Z2_MODULE_LAYOUT=MISSING" -> false
            "Z2_MODULE_LAYOUT=PRESENT" -> true
            else -> return ModuleInstallResult(false, false)
        }
        if (moduleExists) {
            val installedVersion = RootFileIo.readSecureRegularText(
                "$MODULE_DIR/module.prop",
                ModulePackageContract.MAX_MODULE_PROP_BYTES,
            )?.let(ModulePackageContract::validatedInstalledVersion)
            if (!moduleVersionAllowsInstall(
                    installedVersion,
                    expectedReleaseVersion,
                    allowSameVersionRepair,
                )
            ) {
                throw ModuleInstallRejectedException()
            }
        }

        if (!ServiceLifecycleController.executeRoot("magisk -v").success) {
            return ModuleInstallResult(false, false)
        }
        val result = ModuleInstallResult(success = true, needsReboot = true)
        return when (CancellationSafeTerminalCommit.run(
            command = {
                val install = ServiceLifecycleController.executeRoot(
                    "magisk --install-module ${RootFileIo.shellQuote(moduleFile.absolutePath)}",
                )
                install.success && verifyStandardModuleInstall(
                    expectedReleaseVersion,
                    archiveSha256,
                    binaryDirectory,
                )
            },
            commandSucceeded = { it },
            onCommitted = { recordCommitted(result) },
        )) {
            is CancellationSafeTerminalCommit.Outcome.Committed -> result
            is CancellationSafeTerminalCommit.Outcome.Failed ->
                ModuleInstallResult(false, false)
        }
    }

    private suspend fun verifyStandardModuleInstall(
        expectedReleaseVersion: String,
        expectedArchiveSha256: String,
        binaryDirectory: String,
    ): Boolean {
        val regularFiles = ModulePackageContract.mandatoryRuntimeRegularFiles
            .joinToString(" ", transform = RootFileIo::shellQuote)
        val executableFiles = buildList {
            addAll(ModulePackageContract.moduleRootExecutables)
            addAll(ModulePackageContract.mandatoryRuntimeExecutables)
            add("zapret2/nfqws2")
            add(ModulePackageContract.binaryRelativePath(binaryDirectory))
            addAll(ModulePackageContract.wrappers.map { it.relativePath })
        }.distinct().joinToString(" ", transform = RootFileIo::shellQuote)
        val result = ServiceLifecycleController.executeRoot(
            """
                z2_root=${RootFileIo.shellQuote(MODULE_UPDATE_DIR)}
                [ -d "${'$'}z2_root" ] && [ ! -L "${'$'}z2_root" ] &&
                    [ "${'$'}(stat -c %u "${'$'}z2_root" 2>/dev/null)" = 0 ] &&
                    z2_root_mode=${'$'}(stat -c %a "${'$'}z2_root" 2>/dev/null) || exit 1
                case "${'$'}z2_root_mode" in 700|711|750|751|755) ;; *) exit 1 ;; esac
                for z2_relative in $regularFiles; do
                    z2_file="${'$'}z2_root/${'$'}z2_relative"
                    [ -f "${'$'}z2_file" ] && [ ! -L "${'$'}z2_file" ] && [ -s "${'$'}z2_file" ] &&
                        [ "${'$'}(stat -c %u "${'$'}z2_file" 2>/dev/null)" = 0 ] &&
                        [ "${'$'}(stat -c %a "${'$'}z2_file" 2>/dev/null)" = 644 ] &&
                        [ "${'$'}(stat -c %h "${'$'}z2_file" 2>/dev/null)" = 1 ] || exit 1
                done
                for z2_relative in $executableFiles; do
                    z2_file="${'$'}z2_root/${'$'}z2_relative"
                    [ -f "${'$'}z2_file" ] && [ ! -L "${'$'}z2_file" ] && [ -s "${'$'}z2_file" ] &&
                        [ "${'$'}(stat -c %u "${'$'}z2_file" 2>/dev/null)" = 0 ] &&
                        [ "${'$'}(stat -c %a "${'$'}z2_file" 2>/dev/null)" = 755 ] &&
                        [ "${'$'}(stat -c %h "${'$'}z2_file" 2>/dev/null)" = 1 ] || exit 1
                done
                ${InstallGenerationMetadata.shellValidator(
                    "\"${'$'}z2_root/${InstallGenerationMetadata.RELATIVE_PATH}\"",
                    required = true,
                )} || exit 1
                printf 'Z2_STANDARD_INSTALL_ROOT=%s\n' "${'$'}z2_root"
            """.trimIndent()
        )
        val installedRoot = result.stdout.singleOrNull()
            ?.takeIf { result.success && it.startsWith("Z2_STANDARD_INSTALL_ROOT=") }
            ?.removePrefix("Z2_STANDARD_INSTALL_ROOT=")
            ?.takeIf { it == MODULE_UPDATE_DIR }
            ?: return false
        val installedVersion = RootFileIo.readSecureRegularText(
            "$installedRoot/module.prop",
            ModulePackageContract.MAX_MODULE_PROP_BYTES,
        )?.let(ModulePackageContract::validatedInstalledVersion)
        val installGeneration = RootFileIo.readSecureRegularText(
            "$installedRoot/${InstallGenerationMetadata.RELATIVE_PATH}",
            InstallGenerationMetadata.MAX_BYTES,
        )?.lineSequence()?.toList()?.let(InstallGenerationMetadata::parse)
        return standardInstallPublicationMatches(
            installedVersion,
            installGeneration,
            expectedReleaseVersion,
            expectedArchiveSha256,
        )
    }

    private fun validateModuleArchive(
        moduleFile: File,
        binaryDirectory: String,
        expectedReleaseVersion: String,
    ): ArtifactValidationReason? {
        return try {
            var entries = 0
            var totalSize = 0L
            var hasModuleProp = false
            val seenNames = hashSetOf<String>()
            val archivePaths = arrayListOf<Pair<String, Boolean>>()
            ZipFile(moduleFile).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    entries++
                    if (entries > MAX_ARCHIVE_ENTRIES) {
                        return ArtifactValidationReason.MODULE_TOO_MANY_ENTRIES
                    }
                    val name = entry.name
                    if (!isSafeArchiveEntryName(name) || !seenNames.add(name.trimEnd('/'))) {
                        return ArtifactValidationReason.MODULE_UNSAFE_OR_DUPLICATE_PATH
                    }
                    archivePaths += name.trimEnd('/') to entry.isDirectory
                    if (name == "module.prop") hasModuleProp = true
                    val size = entry.size
                    if (size > MAX_ARCHIVE_ENTRY_BYTES) {
                        return ArtifactValidationReason.MODULE_ENTRY_TOO_LARGE
                    }
                    if (size > 0) {
                        totalSize += size
                        if (totalSize > MAX_ARCHIVE_TOTAL_BYTES) {
                            return ArtifactValidationReason.MODULE_EXPANDED_SIZE_TOO_LARGE
                        }
                    }
                }
                if (!ModulePackageContract.archivePathTopologyIsValid(archivePaths)) {
                    return ArtifactValidationReason.MODULE_UNSAFE_OR_DUPLICATE_PATH
                }
            }
            when {
                entries == 0 -> ArtifactValidationReason.MODULE_EMPTY
                !hasModuleProp -> ArtifactValidationReason.MODULE_IDENTITY_MISSING
                else -> ModulePackageContract.validateArchive(
                    moduleFile,
                    binaryDirectory,
                    expectedReleaseVersion,
                )?.let { ArtifactValidationReason.MODULE_PACKAGE_INVALID }
            }
        } catch (_: Exception) {
            ArtifactValidationReason.MODULE_VALIDATION_FAILED
        }
    }

    private fun calculateSha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.readWithProgress(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun isSafeArchiveEntryName(name: String): Boolean {
        val path = if (name.endsWith('/')) name.dropLast(1) else name
        if (path.isBlank() || path.trim() != path || path.any(Char::isISOControl) || path.contains('\\')) return false
        if (path.startsWith('/') || Regex("^[a-zA-Z]:").containsMatchIn(path)) return false
        val parts = path.split('/')
        return parts.isNotEmpty() && parts.none { component ->
            component.isEmpty() || component == "." || component == ".." ||
                component.toByteArray(Charsets.UTF_8).size > MAX_ARCHIVE_PATH_COMPONENT_BYTES
        }
    }

    /** Preflights every requested artifact before the first module mutation. */
    internal suspend fun updateAll(
        release: Release,
        onProgress: suspend (UpdateProgress) -> Unit,
    ): UpdateExecutionReport = withContext(Dispatchers.IO) {
        val expectedVersionCode = requireNotNull(projectReleaseVersionCode(release.version)) {
            "Release version does not contain a valid project versionCode"
        }
        val expectedReleaseVersion = release.version.removePrefix("v")
        val apkArtifact = release.apkArtifact
        val moduleArtifact = release.moduleArtifact
        require(apkArtifact != null || moduleArtifact != null) { "Release has no installable assets" }

        val hasModule = moduleArtifact != null
        val hasApk = apkArtifact != null
        val artifactCount = listOf(hasModule, hasApk).count { it }
        val downloadBudget = if (artifactCount == 2) 0.70f else 0.82f
        val downloadSlice = downloadBudget / artifactCount
        var downloadOffset = 0f
        var moduleOutcome: ModuleArtifactOutcome = ModuleArtifactOutcome.NotRequested
        var apkOutcome: ApkArtifactOutcome = ApkArtifactOutcome.NotRequested
        var downloadedModule: File? = null
        var validatedModule: ValidatedModuleArtifact? = null
        var preparedApk: File? = null
        var retainApkForInstaller = false

        try {
            if (moduleArtifact != null) {
                onProgress(UpdateProgress(downloadOffset, UpdateStage.DOWNLOADING_MODULE))
                when (val result = downloadFile(
                    moduleArtifact.url,
                    UPDATE_MODULE_FILE,
                    moduleArtifact.sha256,
                ) { percent ->
                    val fraction = downloadOffset + (percent / 100f) * downloadSlice
                    onProgress(UpdateProgress(fraction, UpdateStage.DOWNLOADING_MODULE, percent))
                }) {
                    is DownloadResult.Success -> downloadedModule = result.file
                    is DownloadResult.Error -> {
                        moduleOutcome = ModuleArtifactOutcome.Failed(
                            UpdateFailure.Download(result.reason),
                        )
                        if (apkArtifact != null) {
                            apkOutcome = ApkArtifactOutcome.Deferred(
                                UpdateDeferredReason.MODULE_PREFLIGHT_FAILED,
                            )
                        }
                        return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                    }
                }
                downloadOffset += downloadSlice
            }

            if (apkArtifact != null) {
                onProgress(UpdateProgress(downloadOffset, UpdateStage.DOWNLOADING_APK))
                when (val result = downloadFile(
                    apkArtifact.url,
                    UPDATE_APK_FILE,
                    apkArtifact.sha256,
                ) { percent ->
                    val fraction = downloadOffset + (percent / 100f) * downloadSlice
                    onProgress(UpdateProgress(fraction, UpdateStage.DOWNLOADING_APK, percent))
                }) {
                    is DownloadResult.Success -> preparedApk = result.file
                    is DownloadResult.Error -> {
                        if (moduleArtifact != null) {
                            moduleOutcome = ModuleArtifactOutcome.Deferred(
                                UpdateDeferredReason.APK_PREFLIGHT_FAILED,
                            )
                        }
                        apkOutcome = ApkArtifactOutcome.Failed(
                            UpdateFailure.Download(result.reason),
                        )
                        return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                    }
                }
                downloadOffset += downloadSlice
            }

            onProgress(UpdateProgress(downloadBudget, UpdateStage.VALIDATING_ARTIFACTS))
            downloadedModule?.let { moduleFile ->
                val binaryDirectory = ModulePackageContract.selectBinaryDirectory(
                    Build.SUPPORTED_ABIS.toList()
                )
                val validationFailure = if (binaryDirectory == null) {
                    UpdateFailure.UnsupportedAbi
                } else {
                    validateModuleArchive(moduleFile, binaryDirectory, expectedReleaseVersion)
                        ?.let { UpdateFailure.Validation(it) }
                }
                if (validationFailure != null) {
                    moduleOutcome = ModuleArtifactOutcome.Failed(validationFailure)
                    if (apkArtifact != null) {
                        apkOutcome = ApkArtifactOutcome.Deferred(
                            UpdateDeferredReason.MODULE_PREFLIGHT_FAILED,
                        )
                    }
                    return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                }
                validatedModule = ValidatedModuleArtifact(
                    file = moduleFile,
                    binaryDirectory = requireNotNull(binaryDirectory),
                    archiveSha256 = requireNotNull(moduleArtifact).sha256,
                )
            }
            preparedApk?.let { apkFile ->
                val validationFailure = validateApkArtifact(
                    apkFile,
                    expectedReleaseVersion,
                    expectedVersionCode,
                )
                if (validationFailure != null) {
                    if (moduleArtifact != null) {
                        moduleOutcome = ModuleArtifactOutcome.Deferred(
                            UpdateDeferredReason.APK_PREFLIGHT_FAILED,
                        )
                    }
                    apkOutcome = ApkArtifactOutcome.Failed(
                        UpdateFailure.Validation(validationFailure),
                    )
                    return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                }
            }

            suspend fun publishProgress(
                progress: UpdateProgress,
                committedModule: Boolean,
            ) {
                if (!committedModule) {
                    onProgress(progress)
                    return
                }
                try {
                    onProgress(progress)
                } catch (_: CancellationException) {
                    // The module is already committed; presentation cancellation must not
                    // prevent the preflighted APK from reaching the package installer.
                } catch (_: Exception) {
                    // Progress rendering is non-authoritative after the privileged commit.
                }
            }

            suspend fun handoffPreparedApk(
                apkFile: File,
                committedModule: Boolean,
            ): ApkArtifactOutcome {
                // Revalidate immediately before crossing the FileProvider/package-installer boundary.
                val validationFailure = validateApkArtifact(
                    apkFile,
                    expectedReleaseVersion,
                    expectedVersionCode,
                )
                if (validationFailure != null) {
                    return ApkArtifactOutcome.Failed(
                        UpdateFailure.Validation(validationFailure),
                    )
                }
                return try {
                    publishProgress(
                        UpdateProgress(0.99f, UpdateStage.OPENING_APK_INSTALLER),
                        committedModule,
                    )
                    installApk(apkFile)
                    retainApkForInstaller = true
                    publishProgress(
                        UpdateProgress(1f, UpdateStage.APK_INSTALLER_PENDING),
                        committedModule,
                    )
                    ApkArtifactOutcome.InstallerPending
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ApkArtifactOutcome.Failed(UpdateFailure.ApkInstallerUnavailable)
                }
            }

            validatedModule?.let { module ->
                onProgress(UpdateProgress(0.86f, UpdateStage.INSTALLING_MODULE))
                var installFailure: UpdateFailure? = null
                val install = try {
                    installValidatedModule(
                        module,
                        expectedReleaseVersion,
                        release.allowSameVersionModuleRepair,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ModuleInstallRejectedException) {
                    installFailure = UpdateFailure.ModuleRejected
                    ModuleInstallResult(false, false)
                } catch (_: Exception) {
                    installFailure = UpdateFailure.ModuleInstallationFailed
                    ModuleInstallResult(false, false)
                }
                moduleOutcome = if (install.success) {
                    ModuleArtifactOutcome.Installed(
                        requiresReboot = install.needsReboot,
                    )
                } else {
                    ModuleArtifactOutcome.Failed(
                        installFailure ?: UpdateFailure.ModuleInstallationFailed,
                    )
                }
                if (moduleOutcome is ModuleArtifactOutcome.Installed) {
                    val apkFile = preparedApk
                    withContext(NonCancellable) {
                        publishProgress(
                            UpdateProgress(0.95f, UpdateStage.MODULE_INSTALLED),
                            committedModule = true,
                        )
                        if (apkFile != null) {
                            apkOutcome = handoffPreparedApk(apkFile, committedModule = true)
                        } else {
                            publishProgress(
                                UpdateProgress(1f, UpdateStage.COMPLETE),
                                committedModule = true,
                            )
                        }
                    }
                    return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                }
                if (moduleOutcome is ModuleArtifactOutcome.Failed && apkArtifact != null) {
                    apkOutcome = ApkArtifactOutcome.Deferred(
                        UpdateDeferredReason.MODULE_INSTALLATION_FAILED,
                    )
                    return@withContext UpdateExecutionReport(moduleOutcome, apkOutcome)
                }
            }

            preparedApk?.let { apkFile ->
                apkOutcome = handoffPreparedApk(apkFile, committedModule = false)
            }
            UpdateExecutionReport(moduleOutcome, apkOutcome)
        } finally {
            deleteFileBestEffort(downloadedModule)
            if (!retainApkForInstaller) deleteFileBestEffort(preparedApk)
        }
    }

    /**
     * Gets the current APK version name.
     *
     * @return Current version string from BuildConfig
     */
    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME

    private fun updateCacheDirectory(): File? {
        return runCatching {
            val cacheRoot = context.cacheDir.canonicalFile
            val requested = File(cacheRoot, UPDATE_CACHE_DIRECTORY)
            if (!requested.exists() && !requested.mkdir()) return null
            val canonical = requested.canonicalFile
            if (!requested.isDirectory || canonical != requested.absoluteFile || canonical.parentFile != cacheRoot) {
                return null
            }
            if (!setExactMode(canonical, PRIVATE_DIRECTORY_MODE)) return null
            canonical
        }.getOrNull()
    }

    private fun pruneStalePartialDownloads(updateCache: File, fileName: String): Boolean {
        val candidates = updateCache.listFiles() ?: return false
        return candidates.all { candidate ->
            if (!isUpdatePartialFileName(fileName, candidate.name)) return@all true
            runCatching {
                val absolute = candidate.absoluteFile
                absolute.parentFile == updateCache &&
                    candidate.isFile &&
                    candidate.canonicalFile == absolute &&
                    candidate.delete()
            }.getOrDefault(false)
        }
    }

    /** Cleanup must never mask the operation result or prevent a later critical release. */
    private fun deleteFileBestEffort(file: File?) {
        try {
            file?.delete()
        } catch (_: Exception) {
            // App-private stale artifacts are pruned again before the next download.
        }
    }

    /** Keep connection cleanup independent from file-cleanup failures. */
    private fun disconnectBestEffort(connection: HttpURLConnection?) {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
            // The connection is no longer observable once this download scope exits.
        }
    }

    /** Applies and verifies one exact Unix permission mode instead of depending on process umask. */
    private fun setExactMode(file: File, mode: Int): Boolean = runCatching {
        Os.chmod(file.absolutePath, mode)
        (Os.stat(file.absolutePath).st_mode and 0x1FF) == mode
    }.getOrDefault(false)

}
