package com.zapret2.app.viewmodel

internal const val MAX_LOG_SHARE_CHARS = 64 * 1024

/**
 * Shared privacy boundary for exported logs and user-visible diagnostics.
 *
 * Redaction runs before tail bounding so a truncation boundary cannot expose part of a
 * credential. Protocol names, ports, queue numbers, public addresses, module paths and failure
 * text remain intact so the result is still actionable.
 */
internal fun redactedBoundedLogShareText(text: String): String {
    if (text.isBlank()) return ""

    val redacted = text
        .replace(SECRET_ASSIGNMENT) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_SECRET"
        }
        .replace(AUTHORIZATION_VALUE) { match -> "${match.groupValues[1]} $REDACTED_SECRET" }
        .replace(PRIVATE_IDENTIFIER_ASSIGNMENT) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_PRIVATE"
        }
        .replace(PRIVATE_IPV4, REDACTED_PRIVATE)
        .replace(PRIVATE_IPV6, REDACTED_PRIVATE)
        .replace(ANDROID_PRIVATE_PATH, REDACTED_PRIVATE)
        .replace(WINDOWS_PRIVATE_PATH, REDACTED_PRIVATE)
        .trim()

    return redacted.takeLast(MAX_LOG_SHARE_CHARS)
}

private const val REDACTED_SECRET = "[REDACTED_SECRET]"
private const val REDACTED_PRIVATE = "[REDACTED_PRIVATE]"

private val SECRET_ASSIGNMENT = Regex(
    """(?i)\b(password|passwd|pwd|access[_-]?token|refresh[_-]?token|token|authorization|auth|cookie|session|secret|api[_-]?key)(\s*[:=]\s*)(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|(?:Bearer|Basic)\s+[^\s,;]+|[^\s,;]+)""",
)
private val AUTHORIZATION_VALUE = Regex("""(?i)\b(Bearer|Basic)\s+[^\s,;]+""")
private val PRIVATE_IDENTIFIER_ASSIGNMENT = Regex(
    """(?i)\b(host|hostname|ssid|private[_-]?domain)(\s*[:=]\s*)(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|[^\s,;]+)""",
)
private val PRIVATE_IPV4 = Regex(
    """(?<![\d.])(?:10(?:\.\d{1,3}){3}|127(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|169\.254(?:\.\d{1,3}){2})(?![\d.])""",
)
private val PRIVATE_IPV6 = Regex(
    """(?i)(?<![0-9a-f:])(?:::1|f[cd][0-9a-f]{2}(?::[0-9a-f]{0,4})+|fe[89ab][0-9a-f](?::[0-9a-f]{0,4})+)(?![0-9a-f:])""",
)
private val ANDROID_PRIVATE_PATH = Regex(
    """(?i)(?:/data/user/\d+|/data/data|/storage/emulated/\d+|/sdcard|/home/[^/\s]+)(?:/[^\s,;]*)?""",
)
private val WINDOWS_PRIVATE_PATH = Regex(
    """(?i)\b[A-Z]:\\Users\\[^\\\s]+(?:\\[^\s,;]*)?""",
)
