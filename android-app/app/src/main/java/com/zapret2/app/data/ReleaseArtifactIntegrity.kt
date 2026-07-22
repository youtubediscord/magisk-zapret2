package com.zapret2.app.data

import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object ReleaseArtifactIntegrity {
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** Release assets are trusted only with GitHub's exact sha256 digest metadata. */
    fun parseSha256Digest(advertised: String?): Result<String> {
        if (advertised == null) {
            return Result.failure(IllegalArgumentException("Release artifact SHA-256 digest is missing"))
        }
        val parts = advertised.trim().split(':', limit = 2)
        if (parts.size != 2 || parts[0] != "sha256" || !SHA256.matches(parts[1])) {
            return Result.failure(IllegalArgumentException("Malformed release artifact digest"))
        }
        return Result.success(parts[1].lowercase(Locale.ROOT))
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.readWithProgress(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun matches(file: File, expectedSha256: String): Boolean =
        SHA256.matches(expectedSha256) && sha256(file) == expectedSha256.lowercase(Locale.ROOT)
}
