package com.zapret2.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Helper object for opening Telegram links.
 * Checks for installed Telegram clients and opens links
 * in the app if available, otherwise falls back to browser.
 */
object TelegramHelper {

    /**
     * List of known Telegram client package names.
     * Ordered by preference (official clients first).
     */
    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",           // Official Telegram
        "org.telegram.messenger.web",       // Telegram X
        "com.radolyn.ayugram",              // Ayugram
        "org.thunderdog.challegram",        // Nekogram (correct package name)
        "nekox.messenger",                   // Nekogram X
        "org.telegram.plus",                 // Plus Messenger
        "org.telegram.BifToGram",           // BifToGram
        "tw.nekomimi.nekogram"              // Nekogram (alternative)
    )

    /**
     * Opens a Telegram link in an installed Telegram client.
     * If no Telegram client is installed, opens in browser.
     *
     * @param context Android context for starting activity
     * @param url Telegram URL to open (t.me/... or tg://...)
     */
    fun openTelegramLink(context: Context, url: String) {
        val installedClient = getInstalledTelegramClient(context)

        val intent = if (installedClient != null) {
            // Open in installed Telegram client
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(installedClient)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            // No Telegram client installed - open in browser
            val browserUrl = convertToHttpsUrl(url)
            Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser if app intent fails
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(convertToHttpsUrl(url))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }
    }

    /**
     * Gets the first installed Telegram client from the known list.
     *
     * @param context Android context for package manager access
     * @return Package name of installed client, or null if none found
     */
    fun getInstalledTelegramClient(context: Context): String? {
        return TELEGRAM_PACKAGES.firstOrNull { packageName ->
            isPackageInstalled(context, packageName)
        }
    }

    /**
     * Checks if any Telegram client is installed.
     *
     * @param context Android context for package manager access
     * @return true if at least one Telegram client is installed
     */
    fun isTelegramInstalled(context: Context): Boolean {
        return getInstalledTelegramClient(context) != null
    }

    /**
     * Gets all installed Telegram clients.
     *
     * @param context Android context for package manager access
     * @return List of package names of installed Telegram clients
     */
    fun getAllInstalledTelegramClients(context: Context): List<String> {
        return TELEGRAM_PACKAGES.filter { packageName ->
            isPackageInstalled(context, packageName)
        }
    }

    /**
     * Checks if a specific package is installed on the device.
     *
     * @param context Android context for package manager access
     * @param packageName Package name to check
     * @return true if package is installed, false otherwise
     */
    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Converts a tg:// URL to https://t.me/ URL for browser fallback.
     *
     * @param url Original URL (may be tg:// or https://)
     * @return HTTPS URL suitable for browser
     */
    private fun convertToHttpsUrl(url: String): String {
        return when {
            url.startsWith("tg://") -> {
                // Convert tg://resolve?domain=xxx to https://t.me/xxx
                val domain = url.substringAfter("domain=").substringBefore("&")
                if (domain.isNotEmpty()) {
                    "https://t.me/$domain"
                } else {
                    // Handle other tg:// links
                    url.replace("tg://", "https://t.me/")
                }
            }
            url.startsWith("http://t.me/") -> {
                url.replace("http://", "https://")
            }
            else -> url
        }
    }

    /**
     * Creates a proper Telegram link for a channel or username.
     *
     * @param username Telegram username (without @)
     * @return Proper t.me URL
     */
    fun createTelegramLink(username: String): String {
        val cleanUsername = username.removePrefix("@").trim()
        return "https://t.me/$cleanUsername"
    }
}
