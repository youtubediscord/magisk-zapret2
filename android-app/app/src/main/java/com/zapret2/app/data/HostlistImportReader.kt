package com.zapret2.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

internal const val MAX_HOSTLIST_IMPORT_BYTES = 1024 * 1024

internal enum class HostlistImportFailure {
    INVALID_NAME,
    TOO_LARGE,
    INVALID_ENCODING,
    INVALID_CONTENT,
    READ_FAILED,
}

internal sealed interface HostlistImportValidation {
    data class Valid(val fileName: String, val content: String) : HostlistImportValidation
    data class Failure(val reason: HostlistImportFailure) : HostlistImportValidation
}

/** Bounded Android document-provider boundary for hostlist imports. */
class HostlistImportReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    internal fun readAndValidate(uri: Uri): HostlistImportValidation {
        val fileName = try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_NAME)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBoundedBytes(MAX_HOSTLIST_IMPORT_BYTES)
                    ?: ByteArray(MAX_HOSTLIST_IMPORT_BYTES + 1)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return HostlistImportValidation.Failure(HostlistImportFailure.READ_FAILED)
        return validateHostlistImport(fileName, bytes)
    }
}

private val IPSET_HOSTLIST_FILE_NAME = Regex(
    pattern = "(?:ipset-[A-Za-z0-9][A-Za-z0-9._-]*|[A-Za-z0-9][A-Za-z0-9._-]*-ipset)\\.txt",
    option = RegexOption.IGNORE_CASE,
)

internal fun isIpSetHostlistFileName(fileName: String): Boolean =
    IPSET_HOSTLIST_FILE_NAME.matches(fileName)

internal fun normalizedHostlistDataLineOrNull(rawLine: String): String? =
    rawLine.trim().takeIf { line -> line.isNotEmpty() && !line.startsWith("#") }

internal fun countHostlistDataLines(content: String): Int =
    content.lineSequence().count { line -> normalizedHostlistDataLineOrNull(line) != null }

internal fun isValidHostlistContent(fileName: String, content: String): Boolean {
    if (!HostlistRepository.isValidFileName(fileName) || '\u0000' in content) return false
    val ipset = isIpSetHostlistFileName(fileName)
    var dataLines = 0
    content.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLine ->
        val line = normalizedHostlistDataLineOrNull(rawLine) ?: return@forEach
        if (!isValidHostlistDataLine(line, ipset)) return false
        dataLines++
    }
    return dataLines > 0
}

internal fun validateHostlistImport(fileName: String, bytes: ByteArray): HostlistImportValidation {
    if (bytes.size > MAX_HOSTLIST_IMPORT_BYTES) {
        return HostlistImportValidation.Failure(HostlistImportFailure.TOO_LARGE)
    }
    if (!HostlistRepository.isValidFileName(fileName)) {
        return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_NAME)
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (_: Exception) {
        return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_ENCODING)
    }
    if ('\u0000' in text) {
        return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_ENCODING)
    }
    val ipset = isIpSetHostlistFileName(fileName)
    val normalized = linkedSetOf<String>()
    var dataLines = 0
    text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("#")) {
            normalized += line
            return@forEach
        }
        if (!isValidHostlistDataLine(line, ipset)) {
            return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_CONTENT)
        }
        normalized += line.lowercase()
        dataLines++
    }
    if (dataLines == 0) {
        return HostlistImportValidation.Failure(HostlistImportFailure.INVALID_CONTENT)
    }
    return HostlistImportValidation.Valid(fileName, normalized.joinToString("\n") + "\n")
}

private fun isValidHostlistDataLine(value: String, ipset: Boolean): Boolean =
    if (ipset) isValidIpOrCidr(value) else isValidHostEntry(value)

private fun isValidHostEntry(value: String): Boolean {
    val host = value.removePrefix(".")
    if (host.isBlank() || host.length > 253 ||
        host.any { !it.isLetterOrDigit() && it !in ".-_*" }
    ) {
        return false
    }
    return host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            (label.first().isLetterOrDigit() || label.first() in "_*") &&
            (label.last().isLetterOrDigit() || label.last() in "_*")
    }
}

private fun isValidIpOrCidr(value: String): Boolean {
    val parts = value.split('/', limit = 2)
    if (parts.size > 2 || parts[0].isBlank()) return false
    val address = parts[0]
    val isIpv4 = address.split('.').let { octets ->
        octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
        }
    }
    val isIpv6 = if (':' in address && address.matches(Regex("[0-9A-Fa-f:.]+"))) {
        runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
    } else {
        false
    }
    if (!isIpv4 && !isIpv6) return false
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return parts.size == 1
    return prefix in if (isIpv4) 0..32 else 0..128
}
