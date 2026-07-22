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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    "^v?((?:0|[1-9][0-9]*))\\.((?:0|[1-9][0-9]*))\\.([1-9][0-9]{0,9})$",
)

private data class ParsedReleaseVersion(
    val numericParts: List<Long>,
    val preRelease: String?,
)

internal fun projectReleaseVersionCode(version: String): Long? {
    val match = PROJECT_RELEASE_VERSION_PATTERN.matchEntire(version) ?: return null
    if (match.groupValues[1].toLongOrNull() == null || match.groupValues[2].toLongOrNull() == null) {
        return null
    }
    return match.groupValues[3].toLongOrNull()
        ?.takeIf { it in 1L..2_100_000_000L }
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
        private const val PRIVATE_EXECUTABLE_MODE = 0b111_000_000 // 0700
        private const val MODULE_DIR = "/data/adb/modules/zapret2"
        private const val MODULE_UPDATE_DIR = "/data/adb/modules_update/zapret2"
        private const val STATE_DIR = ModuleMutationCoordinator.STATE_DIR
        private const val UPDATE_LOCK = ModuleMutationCoordinator.UPDATE_LOCK
        private const val UPDATE_TRANSACTION = ModuleMutationCoordinator.UPDATE_TRANSACTION
    }

    private data class UpdateMarker(
        val pid: Int,
        val starttime: String,
        val createdEpoch: String,
        val bootId: String,
        val token: String
    )

    private class FatalUpdateRecoveryException(message: String) : IllegalStateException(message)
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
     * Installs a Magisk module.
     * Uses hot update (no reboot) if module is already installed,
     * otherwise uses standard Magisk installer (requires reboot).
     *
     * @param moduleFile The module ZIP file to install
     * @return Pair<Boolean, Boolean> - (success, needsReboot)
     */
    suspend fun installModule(
        moduleFile: File,
        expectedReleaseVersion: String,
        allowSameVersionRepair: Boolean,
    ): Pair<Boolean, Boolean> {
        var completedResult: Pair<Boolean, Boolean>? = null
        return try {
            withContext(Dispatchers.IO) {
                ModuleMutationCoordinator.withModuleUpdate {
                    ServiceLifecycleController.runExclusiveLifecycleTask {
                        when (ModuleUpdateRecovery.recoverLocked()) {
                            ModuleUpdateRecovery.Result.NotNeeded,
                            ModuleUpdateRecovery.Result.Recovered ->
                                installModuleLocked(
                                    moduleFile,
                                    expectedReleaseVersion,
                                    allowSameVersionRepair,
                                    recordCommitted = { completedResult = it },
                                )
                            is ModuleUpdateRecovery.Result.Blocked,
                            is ModuleUpdateRecovery.Result.Failed -> Pair(false, false)
                        }
                    }
                }.also { completedResult = it }
            }
        } catch (cancelled: CancellationException) {
            // A successful terminal commit must not be hidden by prompt cancellation while
            // returning from the IO context; the caller still has to finish the APK handoff.
            completedResult?.takeIf { it.first } ?: throw cancelled
        }
    }

    private suspend fun installModuleLocked(
        moduleFile: File,
        expectedReleaseVersion: String,
        allowSameVersionRepair: Boolean,
        recordCommitted: (Pair<Boolean, Boolean>) -> Unit,
    ): Pair<Boolean, Boolean> {
        val binaryDirectory = ModulePackageContract.selectBinaryDirectory(Build.SUPPORTED_ABIS.toList())
            ?: return Pair(false, false)
        if (!moduleFile.isFile ||
            validateModuleArchive(moduleFile, binaryDirectory, expectedReleaseVersion) != null
        ) {
            return Pair(false, false)
        }

        val archiveSha256 = calculateSha256(moduleFile) ?: return Pair(false, false)

        val moduleCheck = ServiceLifecycleController.executeRoot(
            """
                z2_live=${RootFileIo.shellQuote(MODULE_DIR)}
                z2_pending=${RootFileIo.shellQuote(MODULE_UPDATE_DIR)}
                [ ! -e "${'$'}z2_pending" ] && [ ! -L "${'$'}z2_pending" ] || exit 1
                if [ ! -e "${'$'}z2_live" ] && [ ! -L "${'$'}z2_live" ]; then
                    echo Z2_MODULE_LAYOUT=MISSING
                else
                    [ -d "${'$'}z2_live" ] && [ ! -L "${'$'}z2_live" ] &&
                        [ "${'$'}(stat -c %u "${'$'}z2_live" 2>/dev/null)" = 0 ] || exit 1
                    z2_live_mode=${'$'}(stat -c %a "${'$'}z2_live" 2>/dev/null) || exit 1
                    case "${'$'}z2_live_mode" in 700|711|750|751|755) ;; *) exit 1 ;; esac
                    echo Z2_MODULE_LAYOUT=PRESENT
                fi
            """.trimIndent()
        )
        val moduleExists = when (moduleCheck.stdout.singleOrNull().takeIf { moduleCheck.success }) {
            "Z2_MODULE_LAYOUT=MISSING" -> false
            "Z2_MODULE_LAYOUT=PRESENT" -> true
            else -> return Pair(false, false)
        }

        if (!moduleExists) {
            val magiskCheck = ServiceLifecycleController.executeRoot("magisk -v")
            if (!magiskCheck.success) return Pair(false, false)
            val result = Pair(true, true)
            return when (CancellationSafeTerminalCommit.run(
                command = {
                    ServiceLifecycleController.executeRoot(
                        "magisk --install-module ${RootFileIo.shellQuote(moduleFile.absolutePath)}"
                    )
                    verifyStandardModuleInstall(expectedReleaseVersion, archiveSha256, binaryDirectory)
                },
                commandSucceeded = { it },
                onCommitted = { recordCommitted(result) },
            )) {
                is CancellationSafeTerminalCommit.Outcome.Committed -> result
                is CancellationSafeTerminalCommit.Outcome.Failed -> Pair(false, false)
            }
        }

        val installedVersion = RootFileIo.readSecureRegularText(
            "$MODULE_DIR/module.prop",
            ModulePackageContract.MAX_MODULE_PROP_BYTES,
        )?.let(ModulePackageContract::validatedInstalledVersion)
        if (
            !moduleVersionAllowsInstall(
                installedVersion,
                expectedReleaseVersion,
                allowSameVersionRepair,
            )
        ) {
            throw ModuleInstallRejectedException()
        }

        val installGeneration = InstallGenerationMetadata.create(archiveSha256)
            ?: return Pair(false, false)

        val stagingResult = extractModuleArchive(moduleFile, binaryDirectory, expectedReleaseVersion)
        val stagingDir = stagingResult.getOrNull() ?: return Pair(false, false)
        val stagingViolation = try {
            ModulePackageContract.validateStaging(
                stagingDir,
                binaryDirectory,
                expectedReleaseVersion,
            )
        } catch (error: Exception) {
            deleteTreeBestEffort(stagingDir)
            throw error
        }
        if (stagingViolation != null) {
            deleteTreeBestEffort(stagingDir)
            return Pair(false, false)
        }
        val transactionId = "${android.os.Process.myPid()}-${System.nanoTime()}"
        val updateDir = "/data/adb/modules/.zapret2-update-$transactionId"
        val backupDir = "/data/adb/modules/.zapret2-backup-$transactionId"
        val failedDir = "/data/adb/modules/.zapret2-failed-$transactionId"
        val recoveryDir = "/data/adb/modules/.zapret2-recovery-$transactionId"
        var transactionCreated = false
        var activeCandidate = false
        var serviceStopped = false
        var retainRecoveryArtifacts = false
        var markerAcquired = false
        var transactionDigest: String? = null
        lateinit var preUpdateState: ModuleUpdateStatePolicy.VerifiedState
        lateinit var disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation
        lateinit var marker: UpdateMarker

        try {
            // The try/finally boundary is established before publication. If cancellation wins
            // the handoff race after the guard commits, finally still owns and releases the lock.
            marker = CancellationSafeLockHandoff.acquire(
                publish = { acquireUpdateMarker().getOrThrow() },
                releaseIfCancelled = { published ->
                    releaseUpdateMarker(published).also { release ->
                        check(release.success) {
                            release.diagnosticText().ifBlank {
                                "Unable to release a cancelled update-lock acquisition"
                            }
                        }
                    }
                },
            )
            markerAcquired = true
            currentCoroutineContext().ensureActive()
            disableMarkerExpectation = snapshotDisableMarkerExpectation()
                ?: throw IllegalStateException("The installed disable marker is unsafe or could not be inspected")
            preUpdateState = snapshotAuthorizedServiceState(marker, disableMarkerExpectation)
                ?: throw IllegalStateException(
                    "Module update requires a verified Running or Stopped service state; current state is degraded"
                )
            if (disableMarkerExpectation == ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT &&
                preUpdateState == ModuleUpdateStatePolicy.VerifiedState.RUNNING
            ) {
                throw IllegalStateException(
                    "A disabled module cannot have a verified running pre-update state"
                )
            }
            val transactionPaths = listOf(updateDir, backupDir, failedDir, recoveryDir)
            val freshWorkspacePredicate = transactionPaths.joinToString(" && ") { path ->
                val quoted = RootFileIo.shellQuote(path)
                "{ [ ! -e $quoted ] && [ ! -L $quoted ]; }"
            }
            val siblingWorkspacePredicate = listOf(backupDir, failedDir, recoveryDir)
                .joinToString(" && ") { path ->
                    val quoted = RootFileIo.shellQuote(path)
                    "{ [ ! -e $quoted ] && [ ! -L $quoted ]; }"
                }
            val initialOwnerGuard = requireNotNull(
                UpdateTransactionProtocol.buildOwnerGuard(
                    owner = marker.transactionOwner(),
                    expectedTransactionDigest = null,
                )
            )
            val workspacePreflight = ServiceLifecycleController.executeRoot(
                "$initialOwnerGuard\n$freshWorkspacePredicate || exit 1\n" +
                    "$initialOwnerGuard\n$freshWorkspacePredicate || exit 1"
            )
            if (!workspacePreflight.success) {
                throw IllegalStateException("The update workspace is not fresh or safely owned")
            }
            // Capture the verified pre-update state durably before stopping the service or
            // preparing a candidate directory. Recovery can therefore always restore intent.
            transactionDigest = writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "prepared",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = null,
                )
            if (transactionDigest == null) {
                return Pair(false, false)
            }
            transactionCreated = true

            if (preUpdateState == ModuleUpdateStatePolicy.VerifiedState.RUNNING) {
                val stopScript = "$MODULE_DIR/zapret2/scripts/zapret-stop.sh"
                val stopResult = ServiceLifecycleController.executeRoot(
                    verifiedUpdateLifecycle(
                        marker = marker,
                        script = stopScript,
                        disableMarkerExpectation = disableMarkerExpectation,
                        requireInstallGeneration = false,
                    )
                )
                if (!stopResult.success ||
                    !verifyServiceState(
                        state = ModuleUpdateStatePolicy.VerifiedState.STOPPED,
                        marker = marker,
                        disableMarkerExpectation = disableMarkerExpectation,
                        requireInstallGeneration = false,
                    )
                ) {
                    throw IllegalStateException("Unable to stop the service for module update")
                }
                serviceStopped = true
            }

            transactionDigest = writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "stopped",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = requireNotNull(transactionDigest),
                ) ?: throw IllegalStateException("Unable to persist the stopped update phase")

            val prepareGuard = requireNotNull(
                UpdateTransactionProtocol.buildOwnerTransactionGuard(
                    owner = marker.transactionOwner(),
                    expectedDigest = requireNotNull(transactionDigest),
                )
            )
            val prepareCommand = buildString {
                append(prepareGuard).append('\n')
                append(freshWorkspacePredicate).append(" || exit 1\n")
                // The package is the sole source of immutable files. Only the explicit mutable
                // allowlist below may cross the release boundary from the installed module.
                append("umask 077\nmkdir ").append(RootFileIo.shellQuote(updateDir)).append(" || exit 1\n")
                append("chmod 0700 ").append(RootFileIo.shellQuote(updateDir)).append(" || exit 1\n")
                append("{ ").append(ModuleUpdateRecovery.safeRootDirectoryPredicate(updateDir))
                    .append("; } || exit 1\n")
                append(prepareGuard).append('\n')
                append(siblingWorkspacePredicate).append(" || exit 1\n")
                append("cp -R ").append(RootFileIo.shellQuote("${stagingDir.absolutePath}/.")).append(' ')
                    .append(RootFileIo.shellQuote(updateDir)).append("\n")
                append("status=${'$'}?\n")
                val installerOnlyCandidate = "$updateDir/customize.sh"
                append("if [ \"${'$'}status\" -eq 0 ]; then\n")
                append("  [ -f ").append(RootFileIo.shellQuote(installerOnlyCandidate))
                    .append(" ] && [ ! -L ").append(RootFileIo.shellQuote(installerOnlyCandidate))
                    .append(" ] || status=1\n")
                append("  if [ \"${'$'}status\" -eq 0 ]; then rm -f ")
                    .append(RootFileIo.shellQuote(installerOnlyCandidate))
                    .append(" || status=${'$'}?; fi\n")
                append("fi\n")
                append(ModuleUpdatePreservation.buildShell(MODULE_DIR, updateDir))
                append(InstallGenerationMetadata.buildPublicationShell(updateDir, installGeneration))
                append("if [ \"${'$'}status\" -eq 0 ]; then\n")
                (ModulePackageContract.requiredRegularFiles +
                    ModulePackageContract.wrappers.map { it.relativePath }).forEach { relative ->
                    val target = "$updateDir/$relative"
                    append("  [ -f ").append(RootFileIo.shellQuote(target)).append(" ] && [ ! -L ")
                        .append(RootFileIo.shellQuote(target)).append(" ] || status=1\n")
                }
                val candidateModuleProp = "$updateDir/module.prop"
                append("  [ \"${'$'}(grep -c '^id=' ")
                    .append(RootFileIo.shellQuote(candidateModuleProp)).append(" 2>/dev/null)\" -eq 1 ] && grep -qx 'id=zapret2' ")
                    .append(RootFileIo.shellQuote(candidateModuleProp)).append(" || status=1\n")
                append("fi\n")
                append("if [ \"${'$'}status\" -eq 0 ]; then\n")
                val binarySource = "${stagingDir.absolutePath}/${ModulePackageContract.binaryRelativePath(binaryDirectory)}"
                val binaryTemp = "$updateDir/zapret2/.nfqws2.$transactionId"
                val binaryTarget = "$updateDir/zapret2/nfqws2"
                append("  [ -f ").append(RootFileIo.shellQuote(binarySource)).append(" ] && [ ! -L ")
                    .append(RootFileIo.shellQuote(binarySource)).append(" ] || status=1\n")
                append("  if [ \"${'$'}status\" -eq 0 ]; then cp ")
                    .append(RootFileIo.shellQuote(binarySource)).append(' ')
                    .append(RootFileIo.shellQuote(binaryTemp)).append(" || status=${'$'}?; fi\n")
                append("  if [ \"${'$'}status\" -eq 0 ]; then chmod 755 ")
                    .append(RootFileIo.shellQuote(binaryTemp)).append(" || status=${'$'}?; fi\n")
                append("  if [ \"${'$'}status\" -eq 0 ]; then mv -f ")
                    .append(RootFileIo.shellQuote(binaryTemp)).append(' ')
                    .append(RootFileIo.shellQuote(binaryTarget)).append(" || status=${'$'}?; fi\n")
                ModulePackageContract.hotUpdateRootExecutables.forEach { relative ->
                    val source = "${stagingDir.absolutePath}/$relative"
                    val target = "$updateDir/$relative"
                    append("  if [ \"${'$'}status\" -eq 0 ]; then chmod 755 ")
                        .append(RootFileIo.shellQuote(target)).append(" || status=${'$'}?; fi\n")
                    append("  if [ \"${'$'}status\" -eq 0 ]; then [ -f ")
                        .append(RootFileIo.shellQuote(target)).append(" ] && [ ! -L ")
                        .append(RootFileIo.shellQuote(target)).append(" ] && [ \"${'$'}(stat -c %a ")
                        .append(RootFileIo.shellQuote(target)).append(" 2>/dev/null)\" = 755 ] && cmp -s ")
                        .append(RootFileIo.shellQuote(source)).append(' ')
                        .append(RootFileIo.shellQuote(target)).append(" || status=1; fi\n")
                }
                append("  for script in ").append(RootFileIo.shellQuote("$updateDir/zapret2/scripts")).append("/*.sh; do\n")
                append("    [ -f \"${'$'}script\" ] || continue\n")
                append("    chmod 755 \"${'$'}script\" || status=${'$'}?\n")
                append("  done\n")
                ModulePackageContract.wrappers.forEach { wrapper ->
                    val source = "${stagingDir.absolutePath}/${wrapper.relativePath}"
                    val target = "$updateDir/${wrapper.relativePath}"
                    append("  if [ \"${'$'}status\" -eq 0 ]; then chmod 755 ")
                        .append(RootFileIo.shellQuote(target)).append(" || status=${'$'}?; fi\n")
                    append("  if [ \"${'$'}status\" -eq 0 ]; then [ -f ")
                        .append(RootFileIo.shellQuote(target)).append(" ] && [ ! -L ")
                        .append(RootFileIo.shellQuote(target)).append(" ] && [ \"${'$'}(stat -c %a ")
                        .append(RootFileIo.shellQuote(target)).append(" 2>/dev/null)\" = 755 ] && cmp -s ")
                        .append(RootFileIo.shellQuote(source)).append(' ')
                        .append(RootFileIo.shellQuote(target)).append(" || status=1; fi\n")
                }
                append("  if [ \"${'$'}status\" -eq 0 ]; then [ -f ")
                    .append(RootFileIo.shellQuote(binaryTarget)).append(" ] && [ ! -L ")
                    .append(RootFileIo.shellQuote(binaryTarget)).append(" ] && [ \"${'$'}(stat -c %a ")
                    .append(RootFileIo.shellQuote(binaryTarget)).append(" 2>/dev/null)\" = 755 ] && cmp -s ")
                    .append(RootFileIo.shellQuote(binarySource)).append(' ')
                    .append(RootFileIo.shellQuote(binaryTarget)).append(" || status=1; fi\n")
                val packageContract = "$updateDir/zapret2/scripts/package-contract.sh"
                val commandBuilder = "$updateDir/${ModulePackageContract.COMMAND_BUILDER_SCRIPT_PATH}"
                append("  if [ \"${'$'}status\" -eq 0 ]; then\n")
                append("    [ -f ").append(RootFileIo.shellQuote(packageContract)).append(" ] && [ ! -L ")
                    .append(RootFileIo.shellQuote(packageContract)).append(" ] || status=1\n")
                append("    if [ \"${'$'}status\" -eq 0 ]; then . ")
                    .append(RootFileIo.shellQuote(packageContract)).append(" && package_contract_apply_modes ")
                    .append(RootFileIo.shellQuote(updateDir)).append(" installed && package_contract_validate_modes ")
                    .append(RootFileIo.shellQuote(updateDir)).append(" installed && package_contract_validate_all ")
                    .append(RootFileIo.shellQuote(updateDir)).append(" installed || status=${'$'}?; fi\n")
                append("    if [ \"${'$'}status\" -eq 0 ]; then active_preset=${'$'}(package_contract_runtime_core_value ")
                    .append(RootFileIo.shellQuote(updateDir)).append(" active_preset) || status=${'$'}?; fi\n")
                append("    if [ \"${'$'}status\" -eq 0 ]; then /system/bin/sh ")
                    .append(RootFileIo.shellQuote(commandBuilder)).append(" --preflight-preset-machine ")
                    .append(RootFileIo.shellQuote("$updateDir/zapret2")).append(" ")
                    .append(RootFileIo.shellQuote("$updateDir/zapret2/presets")).append("/\"${'$'}active_preset\" ")
                    .append("\"${'$'}active_preset\" >/dev/null 2>&1 || status=${'$'}?; fi\n")
                append("  fi\n")
                append("fi\n")
                append("if [ \"${'$'}status\" -eq 0 ]; then\n")
                append("  { ").append(ModuleUpdateRecovery.moduleIntegrityPredicate(updateDir, requireInstallGeneration = true))
                    .append("; } && { ")
                    .append(ModuleUpdatePreservation.expectedDisableMarkerPredicate(updateDir, disableMarkerExpectation))
                    .append("; } && ").append(siblingWorkspacePredicate).append(" || status=1\n")
                append("fi\n")
                append("if [ \"${'$'}status\" -ne 0 ]; then\n")
                append(prepareGuard).append('\n')
                append("  { ").append(ModuleUpdateRecovery.safeRootDirectoryOrAbsentPredicate(updateDir))
                    .append("; } || exit 1\n")
                append("  ").append(siblingWorkspacePredicate).append(" || exit 1\n")
                append("  rm -rf ").append(RootFileIo.shellQuote(updateDir)).append(" || exit 1\n")
                append("  sync || exit 1\n")
                append("fi\n")
                append("exit \"${'$'}status\"")
            }
            if (!ServiceLifecycleController.executeRoot(prepareCommand).success) {
                throw IllegalStateException("Unable to prepare the module update candidate")
            }

            transactionDigest = writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "candidate_ready",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = requireNotNull(transactionDigest),
                )
            if (transactionDigest == null) {
                throw IllegalStateException("Unable to persist the prepared candidate phase")
            }

            transactionDigest = writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "active_move_intent",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = requireNotNull(transactionDigest),
                )
            val activeMoveIntentDigest = transactionDigest
            val activeMovedContent = buildUpdateTransactionContent(
                marker, transactionId, "active_moved", updateDir, backupDir, failedDir,
                preUpdateState, disableMarkerExpectation,
            )
            val activeMove = activeMoveIntentDigest?.let { digest ->
                UpdateTransactionProtocol.buildActiveMove(
                    owner = marker.transactionOwner(),
                    intentDigest = digest,
                    resultContent = activeMovedContent,
                    moduleDir = MODULE_DIR,
                    backupDir = backupDir,
                    sourcePrerequisite =
                        "{ ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } && " +
                            "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; }",
                    movedPrerequisite =
                        "{ ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } && " +
                            "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(backupDir, disableMarkerExpectation)}; }",
                    tempPath = "$STATE_DIR/.update.transaction.move.${marker.pid}.${marker.token}",
                )
            }
            val moveActiveResult = activeMove?.let {
                ServiceLifecycleController.executeRoot(it.command).also { result ->
                    if (result.success) transactionDigest = it.digest
                }
            }
            if (moveActiveResult?.success != true) {
                val rollback = rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                serviceStopped = false
                return Pair(false, false)
            }

            val promotionCommand = UpdateTransactionProtocol.buildCandidatePromotion(
                owner = marker.transactionOwner(),
                expectedDigest = requireNotNull(transactionDigest),
                updateDir = updateDir,
                moduleDir = MODULE_DIR,
                candidatePrerequisite =
                    "{ ${ModuleUpdateRecovery.moduleIntegrityPredicate(updateDir, requireInstallGeneration = true)}; } && " +
                        "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(updateDir, disableMarkerExpectation)}; }",
                promotedPrerequisite =
                    "{ ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = true)}; } && " +
                        "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; }",
            ) ?: throw IllegalStateException("Unable to build the owner-bound candidate promotion")
            val promoteResult = ServiceLifecycleController.executeRoot(promotionCommand)
            val candidateDigest = if (promoteResult.success) writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "candidate_active",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = requireNotNull(transactionDigest),
                ) else null
            if (candidateDigest == null) {
                val rollback = rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                serviceStopped = false
                return Pair(false, false)
            }
            transactionDigest = candidateDigest
            activeCandidate = true

            val runtimeReady = RuntimeConfigStore.ensureRuntimeConfig()
            val activeVerified = runtimeReady &&
                restoreServiceState(
                    preUpdateState,
                    marker,
                    disableMarkerExpectation,
                    requireInstallGeneration = true,
                ) &&
                verifyExpectedDisableMarker(disableMarkerExpectation)
            if (!activeVerified) {
                val rollback = rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                activeCandidate = false
                serviceStopped = false
                return Pair(false, false)
            }

            val verifiedDigest = writeUpdateTransaction(
                    marker = marker,
                    transactionId = transactionId,
                    phase = "verified",
                    updateDir = updateDir,
                    backupDir = backupDir,
                    failedDir = failedDir,
                    preUpdateState = preUpdateState,
                    disableMarkerExpectation = disableMarkerExpectation,
                    expectedPriorDigest = requireNotNull(transactionDigest),
                )
            if (verifiedDigest == null) {
                val rollback = rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                activeCandidate = false
                serviceStopped = false
                return Pair(false, false)
            }
            transactionDigest = verifiedDigest

            // The verified phase is durable, but the transaction still protects rollback. Bind
            // the final commit to the exact marker state captured before the directory swap.
            if (!verifyExpectedDisableMarker(disableMarkerExpectation)) {
                val rollback = rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                activeCandidate = false
                serviceStopped = false
                return Pair(false, false)
            }

            val terminalOutcome = CancellationSafeTerminalCommit.run(
                command = {
                    val plan = requireNotNull(UpdateTransactionProtocol.buildTerminalDeletePlan(
                        owner = marker.transactionOwner(),
                        expectedDigest = requireNotNull(transactionDigest),
                        prerequisite = terminalPrerequisite(
                            marker,
                            preUpdateState,
                            disableMarkerExpectation,
                            requireInstallGeneration = true,
                        ),
                        cleanupPaths = listOf(updateDir, backupDir, failedDir),
                    ))
                    executeTerminalPlan(marker, plan)
                },
                commandSucceeded = { it == UpdateTransactionProtocol.TerminalResolution.COMMITTED },
                onCommitted = {
                    transactionCreated = false
                    activeCandidate = false
                    serviceStopped = false
                    recordCommitted(Pair(true, false))
                },
            )
            when (terminalOutcome) {
                is CancellationSafeTerminalCommit.Outcome.Committed -> return Pair(true, false)
                is CancellationSafeTerminalCommit.Outcome.Failed -> {
                    if (terminalOutcome.commandResult ==
                        UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED
                    ) {
                        retainRecoveryArtifacts = true
                        throw FatalUpdateRecoveryException(
                            "FATAL: terminal commit response was ambiguous; exact recovery artifacts were retained"
                        )
                    }
                    throw IllegalStateException(
                        "The terminal update transaction was not committed; rollback is required"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            if (transactionCreated) {
                val rollback = withContext(NonCancellable) {
                    rollbackHotUpdate(transactionId, updateDir, backupDir, failedDir, marker, preUpdateState, disableMarkerExpectation, requireNotNull(transactionDigest))
                }
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) retainRecoveryArtifacts = true
                activeCandidate = false
                serviceStopped = false
            } else if (serviceStopped) {
                withContext(NonCancellable) {
                    if (!restoreServiceState(
                            preUpdateState,
                            marker,
                            disableMarkerExpectation,
                            requireInstallGeneration = false,
                        )
                    ) retainRecoveryArtifacts = true
                }
                serviceStopped = false
            }
            throw cancelled
        } catch (fatal: FatalUpdateRecoveryException) {
            throw fatal
        } catch (error: Exception) {
            if (!markerAcquired) throw error
            if (transactionCreated) {
                val rollback = withContext(NonCancellable) {
                    rollbackHotUpdate(
                        transactionId,
                        updateDir,
                        backupDir,
                        failedDir,
                        marker,
                        preUpdateState,
                        disableMarkerExpectation,
                        requireNotNull(transactionDigest),
                    )
                }
                if (rollback is ModuleUpdateStatePolicy.RollbackResult.Incomplete) {
                    retainRecoveryArtifacts = true
                    currentCoroutineContext().ensureActive()
                    throw FatalUpdateRecoveryException(rollback.fatalRecoveryMessage())
                }
                activeCandidate = false
                serviceStopped = false
            } else if (serviceStopped) {
                val restored = withContext(NonCancellable) {
                    restoreServiceState(
                        preUpdateState,
                        marker,
                        disableMarkerExpectation,
                        requireInstallGeneration = false,
                    )
                }
                if (!restored) {
                    retainRecoveryArtifacts = true
                    currentCoroutineContext().ensureActive()
                    throw FatalUpdateRecoveryException(
                        "FATAL: update failed before swap and the original service state could not be restored; " +
                            "the live update owner lock will be released for a fresh recovery attempt"
                    )
                }
                serviceStopped = false
            }
            currentCoroutineContext().ensureActive()
            return Pair(false, false)
        } finally {
            try {
                withContext(NonCancellable) {
                    if (UpdateTransactionProtocol.shouldReleaseOwnerLock(markerAcquired, retainRecoveryArtifacts)) {
                        val release = releaseUpdateMarker(marker)
                        if (!release.success) {
                            throw IllegalStateException(
                                release.diagnosticText().ifBlank { "Unable to release the Zapret2 update marker safely" }
                            )
                        }
                    }
                }
            } finally {
                deleteTreeBestEffort(stagingDir)
            }
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
            addAll(ModulePackageContract.hotUpdateRootExecutables)
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

    private suspend fun acquireUpdateMarker(): Result<UpdateMarker> {
        val pid = android.os.Process.myPid()
        val token = UUID.randomUUID().toString()
        return UpdateLockProtocol.acquireForUpdate(pid, token).map { record ->
            UpdateMarker(pid, record.starttime, record.createdEpoch, record.bootId, record.token)
        }
    }

    private suspend fun releaseUpdateMarker(marker: UpdateMarker): ServiceLifecycleController.CommandResult {
        return UpdateLockProtocol.release(
            UpdateLockProtocol.Record(
                pid = marker.pid.toString(),
                starttime = marker.starttime,
                createdEpoch = marker.createdEpoch,
                bootId = marker.bootId,
                token = marker.token,
            )
        )
    }

    private fun authorizedUpdateLifecycle(
        marker: UpdateMarker,
        script: String,
        arguments: List<String> = emptyList(),
    ): String {
        val suffix = arguments.joinToString(separator = "") { argument ->
            " ${RootFileIo.shellQuote(argument)}"
        }
        return "ZAPRET2_UPDATE_TOKEN=${RootFileIo.shellQuote(marker.token)} " +
            "ZAPRET2_UPDATE_OWNER_PID=${marker.pid} " +
            "ZAPRET2_UPDATE_OWNER_START=${RootFileIo.shellQuote(marker.starttime)} " +
            "ZAPRET2_UPDATE_OWNER_CREATED=${RootFileIo.shellQuote(marker.createdEpoch)} " +
            "ZAPRET2_UPDATE_OWNER_BOOT=${RootFileIo.shellQuote(marker.bootId)} " +
            "sh ${RootFileIo.shellQuote(script)}$suffix"
    }

    private fun verifiedUpdateLifecycle(
        marker: UpdateMarker,
        script: String,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        arguments: List<String> = emptyList(),
        requireInstallGeneration: Boolean,
    ): String = "{ ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration)}; } && " +
        "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } && " +
        authorizedUpdateLifecycle(marker, script, arguments)

    private suspend fun snapshotAuthorizedServiceState(
        marker: UpdateMarker,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
    ): ModuleUpdateStatePolicy.VerifiedState? {
        val statusScript = "$MODULE_DIR/zapret2/scripts/zapret-status.sh"
        val result = ServiceLifecycleController.executeRoot(
            verifiedUpdateLifecycle(
                marker = marker,
                script = statusScript,
                disableMarkerExpectation = disableMarkerExpectation,
                arguments = listOf("--machine"),
                requireInstallGeneration = false,
            )
        )
        val status = ServiceLifecycleController.parseStatusCommandResult(result)
        return ModuleUpdateStatePolicy.snapshot(status.healthy, status.fullyStopped)
    }

    private suspend fun snapshotDisableMarkerExpectation(): ModuleUpdatePreservation.DisableMarkerExpectation? {
        val marker = RootFileIo.shellQuote("$MODULE_DIR/${ModulePackageContract.DISABLE_MARKER}")
        val result = ServiceLifecycleController.executeRoot(
            "if [ -e $marker ] || [ -L $marker ]; then " +
                "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT)}; } || exit 1; " +
                "echo present; else echo absent; fi"
        )
        if (!result.success || result.stdout.size != 1) return null
        return when (result.stdout.single()) {
            "present" -> ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT
            "absent" -> ModuleUpdatePreservation.DisableMarkerExpectation.ABSENT
            else -> null
        }
    }

    private suspend fun verifyExpectedDisableMarker(
        expectation: ModuleUpdatePreservation.DisableMarkerExpectation,
    ): Boolean = ServiceLifecycleController.executeRoot(
        ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, expectation)
    ).success

    private fun terminalPrerequisite(
        marker: UpdateMarker,
        expectedState: ModuleUpdateStatePolicy.VerifiedState,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        requireInstallGeneration: Boolean,
    ): String {
        val statusScript = "$MODULE_DIR/zapret2/scripts/zapret-status.sh"
        val expectedExit = if (expectedState == ModuleUpdateStatePolicy.VerifiedState.RUNNING) 0 else 1
        return "{ ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration)}; } && " +
            "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } && " +
            "{ z2_terminal_status=0; ${authorizedUpdateLifecycle(marker, statusScript, listOf("--machine"))} >/dev/null 2>&1 || " +
            "z2_terminal_status=${'$'}?; [ \"${'$'}z2_terminal_status\" -eq $expectedExit ]; }"
    }

    private suspend fun executeTerminalPlan(
        marker: UpdateMarker,
        plan: UpdateTransactionProtocol.TerminalPlan,
    ): UpdateTransactionProtocol.TerminalResolution {
        var primarySucceeded = false
        try {
            primarySucceeded = ServiceLifecycleController.executeRoot(plan.command).success
        } catch (_: Exception) {
            // The root shell can disappear after the delete commit point. The exact probe below
            // distinguishes that committed state from every pre-delete failure.
        }
        if (primarySucceeded) return UpdateTransactionProtocol.TerminalResolution.COMMITTED
        val probe = UpdateTransactionProtocol.buildTerminalCommitProbe(marker.transactionOwner(), plan)
            ?: return UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED
        return try {
            val result = ServiceLifecycleController.executeRoot(probe)
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = result.success,
                probeLines = result.stdout,
            )
        } catch (_: Exception) {
            UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED
        }
    }

    private suspend fun writeUpdateTransaction(
        marker: UpdateMarker,
        transactionId: String,
        phase: String,
        updateDir: String,
        backupDir: String,
        failedDir: String,
        preUpdateState: ModuleUpdateStatePolicy.VerifiedState,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        expectedPriorDigest: String?,
    ): String? {
        if (!transactionId.matches(Regex("[A-Za-z0-9._-]+")) ||
            phase !in setOf(
                "prepared",
                "stopped",
                "candidate_ready",
                "active_move_intent",
                "active_moved",
                "candidate_active",
                "verified",
                "restored",
                "restore_copying",
                "restore_candidate_ready",
                "restore_active_moved",
                "restore_candidate_active",
            )
        ) return null
        if (disableMarkerExpectation == ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT &&
            preUpdateState == ModuleUpdateStatePolicy.VerifiedState.RUNNING
        ) return null
        val tempPath = "$STATE_DIR/.update.transaction.${marker.pid}.${marker.token}"
        val content = buildUpdateTransactionContent(
            marker, transactionId, phase, updateDir, backupDir, failedDir,
            preUpdateState, disableMarkerExpectation,
        )
        val publication = UpdateTransactionProtocol.buildPublication(
            owner = marker.transactionOwner(),
            content = content,
            expectedPriorDigest = expectedPriorDigest,
            tempPath = tempPath,
        ) ?: return null
        return publication.digest.takeIf {
            ServiceLifecycleController.executeRoot(publication.command).success
        }
    }

    private fun buildUpdateTransactionContent(
        marker: UpdateMarker,
        transactionId: String,
        phase: String,
        updateDir: String,
        backupDir: String,
        failedDir: String,
        preUpdateState: ModuleUpdateStatePolicy.VerifiedState,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
    ): String = buildString {
            append("version=").append(UpdateTransactionProtocol.TRANSACTION_VERSION).append('\n')
        append("transaction_id=").append(transactionId).append('\n')
        append("phase=").append(phase).append('\n')
        append("created_epoch=").append(marker.createdEpoch).append('\n')
        append("pre_update_state=").append(preUpdateState.wireValue).append('\n')
        append("disable_marker_expectation=").append(disableMarkerExpectation.wireValue).append('\n')
        append("owner_pid=").append(marker.pid).append('\n')
        append("owner_starttime=").append(marker.starttime).append('\n')
        append("owner_created_epoch=").append(marker.createdEpoch).append('\n')
        append("owner_boot_id=").append(marker.bootId).append('\n')
        append("module_dir=").append(MODULE_DIR).append('\n')
        append("update_dir=").append(updateDir).append('\n')
        append("backup_dir=").append(backupDir).append('\n')
        append("failed_dir=").append(failedDir).append('\n')
    }

    private fun UpdateMarker.transactionOwner() = UpdateTransactionProtocol.Owner(
        pid = pid.toString(),
        starttime = starttime,
        createdEpoch = createdEpoch,
        bootId = bootId,
        token = token,
    )

    private suspend fun restoreServiceState(
        state: ModuleUpdateStatePolicy.VerifiedState,
        marker: UpdateMarker,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        requireInstallGeneration: Boolean,
    ): Boolean {
        val script = when (state) {
            ModuleUpdateStatePolicy.VerifiedState.RUNNING -> "$MODULE_DIR/zapret2/scripts/zapret-start.sh"
            ModuleUpdateStatePolicy.VerifiedState.STOPPED -> "$MODULE_DIR/zapret2/scripts/zapret-stop.sh"
        }
        val command = ServiceLifecycleController.executeRoot(
            verifiedUpdateLifecycle(
                marker = marker,
                script = script,
                disableMarkerExpectation = disableMarkerExpectation,
                requireInstallGeneration = requireInstallGeneration,
            )
        )
        return command.success && verifyServiceState(
            state,
            marker,
            disableMarkerExpectation,
            requireInstallGeneration,
        )
    }

    private suspend fun verifyServiceState(
        state: ModuleUpdateStatePolicy.VerifiedState,
        marker: UpdateMarker,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        requireInstallGeneration: Boolean,
    ): Boolean {
        val statusScript = "$MODULE_DIR/zapret2/scripts/zapret-status.sh"
        repeat(5) {
            val result = ServiceLifecycleController.executeRoot(
                verifiedUpdateLifecycle(
                    marker = marker,
                    script = statusScript,
                    disableMarkerExpectation = disableMarkerExpectation,
                    arguments = listOf("--machine"),
                    requireInstallGeneration = requireInstallGeneration,
                )
            )
            val status = ServiceLifecycleController.parseStatusCommandResult(result)
            if (ModuleUpdateStatePolicy.matches(state, status.healthy, status.fullyStopped)) return true
            kotlinx.coroutines.delay(250)
        }
        return false
    }

    private suspend fun rollbackHotUpdate(
        transactionId: String,
        updateDir: String,
        backupDir: String,
        failedDir: String,
        marker: UpdateMarker,
        preUpdateState: ModuleUpdateStatePolicy.VerifiedState,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        expectedTransactionDigest: String,
    ): ModuleUpdateStatePolicy.RollbackResult {
        var retainedTransactionDigest = expectedTransactionDigest
        suspend fun incomplete(message: String) = incompleteRollback(
            message = message,
            backupDir = backupDir,
            disableMarkerExpectation = disableMarkerExpectation,
            expectedTransactionDigest = retainedTransactionDigest,
        )
        val stopScript = "$MODULE_DIR/zapret2/scripts/zapret-stop.sh"
        val rollbackGuard = UpdateTransactionProtocol.buildOwnerTransactionGuard(
            marker.transactionOwner(),
            expectedTransactionDigest,
        ) ?: return incomplete("The rollback owner/journal identity is invalid")
        val moduleProbe = ServiceLifecycleController.executeRoot(
            """
                $rollbackGuard
                if [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ]; then
                    echo absent
                elif { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } &&
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; }; then
                    echo present
                else
                    exit 1
                fi
            """.trimIndent()
        )
        val modulePresent = when {
            moduleProbe.success && moduleProbe.stdout == listOf("present") -> true
            moduleProbe.success && moduleProbe.stdout == listOf("absent") -> false
            else -> return incomplete("The active module tree became unsafe before rollback")
        }
        if (modulePresent) {
            val stopped = ServiceLifecycleController.executeRoot(
                rollbackGuard + "\n" + verifiedUpdateLifecycle(
                        marker = marker,
                        script = stopScript,
                        disableMarkerExpectation = disableMarkerExpectation,
                        requireInstallGeneration = false,
                    )
            )
            if (!stopped.success ||
                !verifyServiceState(
                    state = ModuleUpdateStatePolicy.VerifiedState.STOPPED,
                    marker = marker,
                    disableMarkerExpectation = disableMarkerExpectation,
                    requireInstallGeneration = false,
                )
            ) {
                return incomplete("Unable to stop the update candidate")
            }
        }

        val rollback = ServiceLifecycleController.executeRoot(
            """
                $rollbackGuard
                if [ -e ${RootFileIo.shellQuote(backupDir)} ] || [ -L ${RootFileIo.shellQuote(backupDir)} ]; then
                    { ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(backupDir, disableMarkerExpectation)}; } || exit 1
                    if [ -e ${RootFileIo.shellQuote(MODULE_DIR)} ] || [ -L ${RootFileIo.shellQuote(MODULE_DIR)} ]; then
                        { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                        { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } || exit 1
                        [ ! -e ${RootFileIo.shellQuote(failedDir)} ] && [ ! -L ${RootFileIo.shellQuote(failedDir)} ] || exit 1
                        $rollbackGuard
                        mv ${RootFileIo.shellQuote(MODULE_DIR)} ${RootFileIo.shellQuote(failedDir)} || exit 1
                    fi
                    $rollbackGuard
                    { ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } || exit 1
                    [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ] || exit 1
                    if ! cp -a ${RootFileIo.shellQuote(backupDir)} ${RootFileIo.shellQuote(MODULE_DIR)}; then
                        if [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ] &&
                            { ${ModuleUpdateRecovery.moduleIntegrityPredicate(failedDir, requireInstallGeneration = false)}; } &&
                            { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(failedDir, disableMarkerExpectation)}; }; then
                            $rollbackGuard
                            [ ! -e ${RootFileIo.shellQuote(MODULE_DIR)} ] && [ ! -L ${RootFileIo.shellQuote(MODULE_DIR)} ] || exit 1
                            { ${ModuleUpdateRecovery.moduleIntegrityPredicate(failedDir, requireInstallGeneration = false)}; } || exit 1
                            { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(failedDir, disableMarkerExpectation)}; } || exit 1
                            mv ${RootFileIo.shellQuote(failedDir)} ${RootFileIo.shellQuote(MODULE_DIR)} || exit 1
                            { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                            { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } || exit 1
                        fi
                        sync || exit 1
                        exit 1
                    fi
                    { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } || exit 1
                else
                    [ ! -L ${RootFileIo.shellQuote(backupDir)} ] || exit 1
                    { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } || exit 1
                fi
                sync || exit 1
            """.trimIndent()
        )
        if (!rollback.success) return incomplete("Unable to restore the previous module directory")

        val retainedBackup = ServiceLifecycleController.executeRoot(
            """
                $rollbackGuard
                { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(MODULE_DIR, disableMarkerExpectation)}; } || exit 1
                if [ ! -e ${RootFileIo.shellQuote(backupDir)} ] && [ ! -L ${RootFileIo.shellQuote(backupDir)} ]; then
                    $rollbackGuard
                    { ${ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)}; } || exit 1
                    cp -a ${RootFileIo.shellQuote(MODULE_DIR)} ${RootFileIo.shellQuote(backupDir)} || exit 1
                else
                    { ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } || exit 1
                    { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(backupDir, disableMarkerExpectation)}; } || exit 1
                fi
                { ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } || exit 1
                { ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(backupDir, disableMarkerExpectation)}; } || exit 1
                sync || exit 1
            """.trimIndent()
        )
        if (!retainedBackup.success) {
            return incomplete("Previous module was restored, but its recovery backup could not be retained")
        }

        if (!restoreServiceState(
                preUpdateState,
                marker,
                disableMarkerExpectation,
                requireInstallGeneration = false,
            )
        ) {
            return incomplete(
                "Previous module files were restored, but service state ${preUpdateState.wireValue} could not be verified"
            )
        }

        if (!verifyExpectedDisableMarker(disableMarkerExpectation)) {
            return incomplete(
                "Previous module files were restored, but its disable marker no longer matches the pre-update state"
            )
        }

        val cleanupConsumed = try {
            val command = requireNotNull(UpdateTransactionProtocol.buildConsumeUncommittedCleanup(
                owner = marker.transactionOwner(),
                expectedTransactionDigest = expectedTransactionDigest,
                cleanupPaths = listOf(updateDir, backupDir, failedDir),
            ))
            ServiceLifecycleController.executeRoot(command).success
        } catch (_: Exception) {
            false
        }
        if (!cleanupConsumed) {
            return incomplete(
                "Rollback was verified, but pre-commit cleanup evidence could not be consumed safely"
            )
        }

        val restoredDigest = writeUpdateTransaction(
                marker = marker,
                transactionId = transactionId,
                phase = "restored",
                updateDir = updateDir,
                backupDir = backupDir,
                failedDir = failedDir,
                preUpdateState = preUpdateState,
                disableMarkerExpectation = disableMarkerExpectation,
                expectedPriorDigest = expectedTransactionDigest,
            )
        if (restoredDigest == null) {
            return incomplete("Rollback was verified, but its restored phase was not durable")
        }
        retainedTransactionDigest = restoredDigest

        val terminalOutcome = CancellationSafeTerminalCommit.run(
            command = {
                val plan = requireNotNull(UpdateTransactionProtocol.buildTerminalDeletePlan(
                    owner = marker.transactionOwner(),
                    expectedDigest = restoredDigest,
                    prerequisite = terminalPrerequisite(
                        marker,
                        preUpdateState,
                        disableMarkerExpectation,
                        requireInstallGeneration = false,
                    ),
                    cleanupPaths = listOf(updateDir, backupDir, failedDir),
                ))
                executeTerminalPlan(marker, plan)
            },
            commandSucceeded = { it == UpdateTransactionProtocol.TerminalResolution.COMMITTED },
            onCommitted = {},
        )
        return when (terminalOutcome) {
            is CancellationSafeTerminalCommit.Outcome.Committed ->
                ModuleUpdateStatePolicy.RollbackResult.Restored(preUpdateState)
            is CancellationSafeTerminalCommit.Outcome.Failed -> incomplete(
                "Rollback was verified, but its terminal transaction could not be removed"
            )
        }
    }

    private suspend fun incompleteRollback(
        message: String,
        backupDir: String,
        disableMarkerExpectation: ModuleUpdatePreservation.DisableMarkerExpectation,
        expectedTransactionDigest: String,
    ): ModuleUpdateStatePolicy.RollbackResult.Incomplete {
        val backupRetained = ServiceLifecycleController.executeRoot(
            "{ ${ModuleUpdateRecovery.moduleIntegrityPredicate(backupDir, requireInstallGeneration = false)}; } && " +
                "{ ${ModuleUpdatePreservation.expectedDisableMarkerPredicate(backupDir, disableMarkerExpectation)}; }"
        ).success
        val stateDir = RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)
        val transaction = RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)
        val transactionRetained = ServiceLifecycleController.executeRoot(
            "[ -d $stateDir ] && [ ! -L $stateDir ] && " +
                "[ \"${'$'}(stat -c %u $stateDir 2>/dev/null)\" = 0 ] && " +
                "[ \"${'$'}(stat -c %a $stateDir 2>/dev/null)\" = 700 ] && " +
                "[ -f $transaction ] && [ ! -L $transaction ] && " +
                "[ \"${'$'}(stat -c %u $transaction 2>/dev/null)\" = 0 ] && " +
                "[ \"${'$'}(stat -c %a $transaction 2>/dev/null)\" = 600 ] && " +
                "[ \"${'$'}(stat -c %h $transaction 2>/dev/null)\" = 1 ] && " +
                "[ \"${'$'}(sha256sum $transaction 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }')\" = " +
                RootFileIo.shellQuote(expectedTransactionDigest) + " ]"
        ).success
        return ModuleUpdateStatePolicy.RollbackResult.Incomplete(
            message = "$message; backup_retained=${if (backupRetained) 1 else 0}; " +
                "transaction_retained=${if (transactionRetained) 1 else 0}",
            backupRetained = backupRetained,
            transactionRetained = transactionRetained,
        )
    }

    private fun ModuleUpdateStatePolicy.RollbackResult.Incomplete.fatalRecoveryMessage(): String =
        "FATAL: $message; the live update owner lock will be released for a fresh recovery attempt"

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

    private fun extractModuleArchive(
        moduleFile: File,
        binaryDirectory: String,
        expectedReleaseVersion: String,
    ): Result<File> {
        validateModuleArchive(moduleFile, binaryDirectory, expectedReleaseVersion)?.let {
            return Result.failure(IllegalArgumentException(it.name))
        }
        val stagingDir = File(context.cacheDir, "zapret2-module-stage-${System.nanoTime()}")
        return try {
            if (!stagingDir.mkdir()) return Result.failure(IllegalStateException("Unable to create update staging directory"))
            if (!setExactMode(stagingDir, PRIVATE_DIRECTORY_MODE)) {
                throw IllegalStateException("Unable to secure update staging directory")
            }
            val canonicalRoot = stagingDir.canonicalFile
            var totalWritten = 0L
            ZipFile(moduleFile).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    val name = entry.name
                    if (name == "META-INF" || name.startsWith("META-INF/")) continue
                    val target = File(stagingDir, name).canonicalFile
                    if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw IllegalArgumentException("Archive entry escapes staging directory")
                    }
                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw IllegalStateException("Unable to create staging directory")
                        }
                        if (!setExactMode(target, PRIVATE_DIRECTORY_MODE)) {
                            throw IllegalStateException("Unable to secure staging directory")
                        }
                        continue
                    }
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IllegalStateException("Unable to create staging directory")
                    }
                    if (parent != null && !setExactMode(parent, PRIVATE_DIRECTORY_MODE)) {
                        throw IllegalStateException("Unable to secure staging directory")
                    }
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var entryWritten = 0L
                            while (true) {
                                val count = input.readWithProgress(buffer)
                                if (count < 0) break
                                entryWritten += count
                                totalWritten += count
                                if (entryWritten > MAX_ARCHIVE_ENTRY_BYTES || totalWritten > MAX_ARCHIVE_TOTAL_BYTES) {
                                    throw IllegalStateException("Archive expands beyond the size limit")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val targetMode = if (name == ModulePackageContract.UPDATE_GUARD_PATH) {
                        PRIVATE_EXECUTABLE_MODE
                    } else {
                        PRIVATE_FILE_MODE
                    }
                    if (!setExactMode(target, targetMode)) {
                        throw IllegalStateException("Unable to restrict staging file permissions")
                    }
                }
            }
            stagingDir.walkTopDown().filter { it.isDirectory }.forEach {
                if (!setExactMode(it, PRIVATE_DIRECTORY_MODE)) {
                    throw IllegalStateException("Unable to restrict staging directory permissions")
                }
            }
            ModulePackageContract.validateStaging(
                stagingDir,
                binaryDirectory,
                expectedReleaseVersion,
            )?.let {
                throw IllegalArgumentException(it)
            }
            Result.success(stagingDir)
        } catch (cancelled: CancellationException) {
            deleteTreeBestEffort(stagingDir)
            throw cancelled
        } catch (error: Exception) {
            deleteTreeBestEffort(stagingDir)
            Result.failure(error)
        }
    }

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
        var preparedModule: File? = null
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
                    is DownloadResult.Success -> preparedModule = result.file
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
            preparedModule?.let { moduleFile ->
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

            preparedModule?.let { moduleFile ->
                onProgress(UpdateProgress(0.86f, UpdateStage.INSTALLING_MODULE))
                var installFailure: UpdateFailure? = null
                val install = try {
                    installModule(
                        moduleFile,
                        expectedReleaseVersion,
                        release.allowSameVersionModuleRepair,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: FatalUpdateRecoveryException) {
                    installFailure = UpdateFailure.ModuleRecoveryRequired(fatal.message.orEmpty())
                    Pair(false, false)
                } catch (_: ModuleInstallRejectedException) {
                    installFailure = UpdateFailure.ModuleRejected
                    Pair(false, false)
                } catch (_: Exception) {
                    installFailure = UpdateFailure.ModuleInstallationFailed
                    Pair(false, false)
                }
                moduleOutcome = if (install.first) {
                    ModuleArtifactOutcome.Installed(requiresReboot = install.second)
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
            deleteFileBestEffort(preparedModule)
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

    /** Extracted module trees are app-private and can be retried by normal cache eviction. */
    private fun deleteTreeBestEffort(directory: File) {
        try {
            directory.deleteRecursively()
        } catch (_: Exception) {
            // The privileged owner lock must not depend on non-privileged cache cleanup.
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
