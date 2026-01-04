package com.zapret2.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    }

    /**
     * Data class representing a release from GitHub.
     */
    data class Release(
        val version: String,
        val apkUrl: String?,
        val moduleUrl: String?,
        val changelog: String
    )

    /**
     * Sealed class for update check results.
     */
    sealed class UpdateResult {
        data class Available(val release: Release) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * Sealed class for download results.
     */
    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * Checks for available updates from GitHub Releases.
     * Compares the current app version with the latest release version.
     *
     * @return UpdateResult indicating if an update is available, up to date, or error
     */
    suspend fun checkForUpdates(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val result = fetchLatestRelease()

            if (result.isFailure) {
                return@withContext UpdateResult.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }

            val release = result.getOrNull()
                ?: return@withContext UpdateResult.Error("Empty response from server")

            val currentVersion = BuildConfig.VERSION_NAME

            if (isNewerVersion(release.version, currentVersion)) {
                UpdateResult.Available(release)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    /**
     * Fetches the latest release information from GitHub API.
     *
     * @return Result with Release object or error details
     */
    private suspend fun fetchLatestRelease(): Result<Release> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Zapret2-Android/${BuildConfig.VERSION_NAME}")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }
                return@withContext Result.failure(Exception("HTTP $responseCode: $errorBody"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }

            if (response.isBlank()) {
                return@withContext Result.failure(Exception("Empty response"))
            }

            val release = parseReleaseJson(response)
            Result.success(release)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("No internet connection"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Connection timeout"))
        } catch (e: javax.net.ssl.SSLException) {
            Result.failure(Exception("SSL error: ${e.message}"))
        } catch (e: org.json.JSONException) {
            Result.failure(Exception("Invalid JSON: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        } finally {
            connection?.disconnect()
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

        val tagName = jsonObject.optString("tag_name", "").removePrefix("v")
        val body = jsonObject.optString("body", "No changelog available")

        var apkUrl: String? = null
        var moduleUrl: String? = null

        val assets = jsonObject.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")

                when {
                    name.endsWith(".apk") -> apkUrl = downloadUrl
                    name.endsWith(".zip") && name.contains("magisk", ignoreCase = true) -> moduleUrl = downloadUrl
                }
            }
        }

        return Release(
            version = tagName,
            apkUrl = apkUrl,
            moduleUrl = moduleUrl,
            changelog = body
        )
    }

    /**
     * Downloads a file from the given URL to the cache directory.
     * Reports download progress via callback.
     *
     * @param url URL of the file to download
     * @param fileName Name for the downloaded file
     * @param progress Callback function receiving progress percentage (0-100)
     * @return DownloadResult with the downloaded file or error
     */
    suspend fun downloadFile(
        url: String,
        fileName: String,
        progress: (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val downloadUrl = URL(url)
            connection = downloadUrl.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Zapret2-Android/${BuildConfig.VERSION_NAME}")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadResult.Error("Server returned ${connection.responseCode}")
            }

            val fileLength = connection.contentLength
            val outputFile = File(context.cacheDir, fileName)

            // Delete existing file if present
            if (outputFile.exists()) {
                outputFile.delete()
            }

            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0
                    var lastReportedProgress = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (fileLength > 0) {
                            val currentProgress = ((totalBytesRead * 100) / fileLength).toInt()
                            if (currentProgress != lastReportedProgress) {
                                lastReportedProgress = currentProgress
                                withContext(Dispatchers.Main) {
                                    progress(currentProgress)
                                }
                            }
                        }
                    }
                }
            }

            // Final progress update
            withContext(Dispatchers.Main) {
                progress(100)
            }

            DownloadResult.Success(outputFile)
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Download failed")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Initiates APK installation via system installer.
     * Uses FileProvider for Android 7.0+ compatibility.
     *
     * @param apkFile The APK file to install
     */
    fun installApk(apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }

    /**
     * Installs a Magisk module.
     * Uses hot update (no reboot) if module is already installed,
     * otherwise uses standard Magisk installer (requires reboot).
     *
     * @param moduleFile The module ZIP file to install
     * @return Pair<Boolean, Boolean> - (success, needsReboot)
     */
    suspend fun installModule(moduleFile: File): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        try {
            if (!moduleFile.exists()) {
                return@withContext Pair(false, false)
            }

            val modDir = "/data/adb/modules/zapret2"

            // Check if module is already installed
            val moduleExists = Shell.cmd("[ -d $modDir ] && echo 1 || echo 0").exec()
                .out.firstOrNull()?.trim() == "1"

            if (moduleExists) {
                // Hot update - no reboot needed!
                // 1. Stop the service
                Shell.cmd("$modDir/zapret2/scripts/zapret-stop.sh 2>/dev/null || true").exec()

                // 2. Extract new files over existing module
                val extractResult = Shell.cmd(
                    "unzip -o ${moduleFile.absolutePath} -d $modDir -x 'META-INF/*'"
                ).exec()

                if (!extractResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // 3. Fix permissions
                Shell.cmd("chmod 755 $modDir/zapret2/nfqws2").exec()
                Shell.cmd("chmod 755 $modDir/zapret2/scripts/*.sh").exec()
                Shell.cmd("chmod 755 $modDir/service.sh").exec()

                // 4. Restart the service
                Shell.cmd("$modDir/zapret2/scripts/zapret-start.sh &").exec()

                // Hot update successful - no reboot needed
                Pair(true, false)
            } else {
                // First install - use Magisk installer (requires reboot)
                val magiskCheck = Shell.cmd("magisk -v").exec()
                if (!magiskCheck.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val result = Shell.cmd("magisk --install-module ${moduleFile.absolutePath}").exec()
                // First install requires reboot
                Pair(result.isSuccess, true)
            }
        } catch (e: Exception) {
            Pair(false, false)
        }
    }

    /**
     * Compares two version strings to determine if latest is newer than current.
     * Supports semantic versioning (e.g., "1.0.0", "1.2.3").
     *
     * @param latest The latest version string
     * @param current The current version string
     * @return true if latest is newer than current, false otherwise
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = parseVersion(latest)
        val currentParts = parseVersion(current)

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            when {
                latestPart > currentPart -> return true
                latestPart < currentPart -> return false
            }
        }

        return false
    }

    /**
     * Parses a version string into a list of integer components.
     * Handles versions with "v" prefix and non-numeric suffixes.
     *
     * @param version Version string to parse (e.g., "v1.2.3-beta")
     * @return List of integer version components
     */
    private fun parseVersion(version: String): List<Int> {
        return version
            .removePrefix("v")
            .split(".")
            .mapNotNull { part ->
                // Extract numeric part only (handles "1-beta" -> "1")
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
    }

    /**
     * Gets the current APK version name.
     *
     * @return Current version string from BuildConfig
     */
    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Clears downloaded update files from cache.
     */
    fun clearUpdateCache() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk") || file.name.endsWith(".zip")) {
                file.delete()
            }
        }
    }
}
