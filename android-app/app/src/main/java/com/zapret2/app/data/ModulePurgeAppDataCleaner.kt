package com.zapret2.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** APK-owned half of the clean-reset contract. It never touches the installed APK. */
class ModulePurgeAppDataCleaner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var storageCleared = true
            listOfNotNull(
                context.cacheDir,
                context.codeCacheDir,
                context.filesDir,
                context.noBackupFilesDir,
                *context.externalCacheDirs,
                *context.getExternalFilesDirs(null),
            ).distinctBy { it.absolutePath }.forEach { directory ->
                val cleared = clearOwnedDirectoryContents(directory)
                storageCleared = cleared && storageCleared
            }
            var databasesCleared = true
            context.databaseList().forEach { database ->
                val cleared = runCatching { context.deleteDatabase(database) }.getOrDefault(false)
                databasesCleared = cleared && databasesCleared
            }
            val preferencesCleared = context
                .getSharedPreferences("zapret2_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            storageCleared && databasesCleared && preferencesCleared
        }.getOrDefault(false)
    }

    private fun clearOwnedDirectoryContents(directory: File): Boolean {
        if (!directory.exists()) return true
        val root = directory.canonicalFile
        if (!root.isDirectory) return false
        return root.listFiles().orEmpty().all { child ->
            runCatching {
                val absolute = child.absoluteFile
                val canonical = child.canonicalFile
                absolute.parentFile == directory.absoluteFile &&
                    canonical.parentFile == root &&
                    canonical == File(root, child.name).absoluteFile &&
                    child.deleteRecursively()
            }.getOrDefault(false)
        }
    }
}
