package com.zapret2.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import com.zapret2.app.BuildConfig
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
        private const val MODULE_DIR = "/data/adb/modules/zapret2"
        private const val RUNTIME_CONFIG_PATH = "$MODULE_DIR/zapret2/runtime.ini"
        private const val RUNTIME_CONFIG_BACKUP_PATH = "/data/local/tmp/zapret2-runtime.ini.bak"
        private const val CATEGORIES_CONFIG_PATH = "$MODULE_DIR/zapret2/categories.ini"
        private const val CATEGORIES_CONFIG_BACKUP_PATH = "/data/local/tmp/zapret2-categories.ini.bak"
        private const val CONFIG_SH_PATH = "$MODULE_DIR/zapret2/config.sh"
        private const val CONFIG_SH_BACKUP_PATH = "/data/local/tmp/zapret2-config.sh.bak"
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

            val currentApkVersion = BuildConfig.VERSION_NAME
            val apkOutdated = isNewerVersion(release.version, currentApkVersion)

            // Also check installed module version
            val moduleOutdated = try {
                val modResult = Shell.cmd("cat $MODULE_DIR/module.prop 2>/dev/null").exec()
                if (modResult.isSuccess) {
                    val moduleVersion = modResult.out
                        .firstOrNull { it.startsWith("version=") }
                        ?.substringAfter("version=")
                        ?.trim()
                        ?.removePrefix("v")
                        ?: ""
                    if (moduleVersion.isNotEmpty()) {
                        isNewerVersion(release.version, moduleVersion)
                    } else true // No version found, consider outdated
                } else true // Module not installed, consider outdated
            } catch (e: Exception) {
                true
            }

            if (apkOutdated || moduleOutdated) {
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
        progress: suspend (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Follow GitHub redirects (github.com -> objects.githubusercontent.com)
            var currentUrl = URL(url)
            var redirectCount = 0
            while (redirectCount < 5) {
                connection = currentUrl.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = false // handle manually for HTTPS->HTTPS
                    setRequestProperty("User-Agent", "Zapret2-Android/${BuildConfig.VERSION_NAME}")
                }

                val responseCode = connection.responseCode
                if (responseCode in 301..302 || responseCode == 307 || responseCode == 308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) {
                        return@withContext DownloadResult.Error("Redirect without Location header")
                    }
                    currentUrl = URL(location)
                    redirectCount++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext DownloadResult.Error("Server returned $responseCode")
                }
                break
            }

            if (connection == null) {
                return@withContext DownloadResult.Error("Too many redirects")
            }

            val fileLength = connection!!.contentLength.toLong()
            val outputFile = File(context.cacheDir, fileName)

            if (outputFile.exists()) {
                outputFile.delete()
            }

            connection!!.inputStream.use { input ->
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
                                progress(currentProgress)
                            }
                        }
                    }
                }
            }

            progress(100)
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

            val modDir = MODULE_DIR
            val quotedModuleFile = shellQuote(moduleFile.absolutePath)

            // Check if module is already installed
            val moduleExists = Shell.cmd("[ -d $modDir ] && echo 1 || echo 0").exec()
                .out.firstOrNull()?.trim() == "1"

            if (moduleExists) {
                // Hot update - no reboot needed!
                // 1. Stop the service
                Shell.cmd("$modDir/zapret2/scripts/zapret-stop.sh 2>/dev/null || true").exec()

                // 2. Preserve user config files across raw unzip updates
                val backupResult = Shell.cmd(
                    "rm -f ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)} ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)} ${shellQuote(CONFIG_SH_BACKUP_PATH)} && " +
                        "if [ -f ${shellQuote(RUNTIME_CONFIG_PATH)} ]; then " +
                        "cp ${shellQuote(RUNTIME_CONFIG_PATH)} ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)}; fi && " +
                        "if [ -f ${shellQuote(CATEGORIES_CONFIG_PATH)} ]; then " +
                        "cp ${shellQuote(CATEGORIES_CONFIG_PATH)} ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)}; fi && " +
                        "if [ -f ${shellQuote(CONFIG_SH_PATH)} ]; then " +
                        "cp ${shellQuote(CONFIG_SH_PATH)} ${shellQuote(CONFIG_SH_BACKUP_PATH)}; fi"
                ).exec()

                if (!backupResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // 3. Extract new files over existing module
                val extractResult = Shell.cmd(
                    "unzip -o $quotedModuleFile -d $modDir -x 'META-INF/*'"
                ).exec()

                if (!extractResult.isSuccess) {
                    Shell.cmd("rm -f ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)} ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)} ${shellQuote(CONFIG_SH_BACKUP_PATH)}").exec()
                    return@withContext Pair(false, false)
                }

                // 4. Restore user config files if they existed before update
                val restoreResult = Shell.cmd(
                    "if [ -f ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)} ]; then " +
                        "cp ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)} ${shellQuote(RUNTIME_CONFIG_PATH)} && " +
                        "rm -f ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)}; fi && " +
                        "if [ -f ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)} ]; then " +
                        "cp ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)} ${shellQuote(CATEGORIES_CONFIG_PATH)} && " +
                        "rm -f ${shellQuote(CATEGORIES_CONFIG_BACKUP_PATH)}; fi && " +
                        "if [ -f ${shellQuote(CONFIG_SH_BACKUP_PATH)} ]; then " +
                        "cp ${shellQuote(CONFIG_SH_BACKUP_PATH)} ${shellQuote(CONFIG_SH_PATH)} && " +
                        "rm -f ${shellQuote(CONFIG_SH_BACKUP_PATH)}; fi"
                ).exec()

                if (!restoreResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // 5. Make sure runtime.ini exists before restart so startup stays runtime-first
                if (!RuntimeConfigStore.ensureRuntimeConfig()) {
                    Shell.cmd("rm -f ${shellQuote(RUNTIME_CONFIG_BACKUP_PATH)}").exec()
                    return@withContext Pair(false, false)
                }

                // 6. Fix permissions
                Shell.cmd("chmod 755 $modDir/zapret2/nfqws2").exec()
                Shell.cmd("chmod 755 $modDir/zapret2/scripts/*.sh").exec()
                Shell.cmd("chmod 755 $modDir/service.sh").exec()

                // 7. Restart the service (always use fast restart path)
                val restartResult = Shell.cmd("sh $modDir/zapret2/scripts/zapret-restart.sh").exec()
                if (!restartResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // Hot update successful - no reboot needed
                Pair(true, false)
            } else {
                // First install - use Magisk installer (requires reboot)
                val magiskCheck = Shell.cmd("magisk -v").exec()
                if (!magiskCheck.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val result = Shell.cmd("magisk --install-module $quotedModuleFile").exec()
                // First install requires reboot
                Pair(result.isSuccess, true)
            }
        } catch (e: Exception) {
            Pair(false, false)
        }
    }

    /**
     * Downloads and installs both module and APK in sequence with progress reporting.
     * Reports progress via onProgress callback: (progress 0..1, status text).
     *
     * @param apkUrl URL for APK download, or null to skip
     * @param moduleUrl URL for module ZIP download, or null to skip
     * @param onProgress Callback with (progress: Float, statusText: String)
     * @return Result with Boolean indicating if reboot is needed
     */
    suspend fun updateAll(
        apkUrl: String?,
        moduleUrl: String?,
        onProgress: suspend (Float, String) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var needsReboot = false

        // Calculate weight distribution based on what we're updating
        val hasModule = moduleUrl != null
        val hasApk = apkUrl != null
        val moduleWeight = if (hasModule && hasApk) 0.55f else if (hasModule) 1f else 0f
        val apkStartAt = moduleWeight

        // Step 1: Download and install module (if available)
        if (moduleUrl != null) {
            onProgress(0f, "Скачивание модуля...")
            val moduleResult = downloadFile(moduleUrl, "zapret2-module.zip") { percent ->
                val progress = (percent / 100f) * moduleWeight * 0.8f
                onProgress(progress, "Скачивание модуля... $percent%")
            }

            when (moduleResult) {
                is DownloadResult.Success -> {
                    onProgress(moduleWeight * 0.8f, "Установка модуля...")
                    val (success, reboot) = installModule(moduleResult.file)
                    if (!success) {
                        return@withContext Result.failure(Exception("Ошибка установки модуля"))
                    }
                    needsReboot = reboot
                    onProgress(moduleWeight, "Модуль установлен")
                }
                is DownloadResult.Error -> {
                    return@withContext Result.failure(Exception("Ошибка скачивания модуля: ${moduleResult.message}"))
                }
            }
        }

        // Step 2: Download and install APK (if available)
        if (apkUrl != null) {
            onProgress(apkStartAt, "Скачивание APK...")
            val apkWeight = 1f - apkStartAt
            val apkResult = downloadFile(apkUrl, "zapret2-update.apk") { percent ->
                val progress = apkStartAt + (percent / 100f) * apkWeight * 0.9f
                onProgress(progress, "Скачивание APK... $percent%")
            }

            when (apkResult) {
                is DownloadResult.Success -> {
                    onProgress(1f, "Установка APK...")
                    installApk(apkResult.file)
                }
                is DownloadResult.Error -> {
                    return@withContext Result.failure(Exception("Ошибка скачивания APK: ${apkResult.message}"))
                }
            }
        }

        onProgress(1f, "Готово")
        Result.success(needsReboot)
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

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
