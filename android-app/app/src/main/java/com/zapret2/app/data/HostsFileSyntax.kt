package com.zapret2.app.data

import java.net.Inet6Address
import java.net.InetAddress

/** Pure syntax boundary shared by packaged DNS mappings and the editable hosts overlay. */
internal object HostsFileSyntax {

    fun isValidFile(content: String): Boolean {
        val canonical = canonicalProtectedText(content)
        if (canonical.isBlank()) return false
        var mappingCount = 0

        for (rawLine in canonical.lineSequence()) {
            if (rawLine.any { it.isISOControl() && it != '\t' }) return false
            val record = rawLine.substringBefore('#').trim()
            if (record.isEmpty()) continue
            val fields = record.split(WHITESPACE_REGEX)
            if (fields.size < 2 || !isValidIpAddress(fields.first())) return false
            if (fields.drop(1).any { !isValidHostname(it) }) return false
            mappingCount++
        }

        return mappingCount > 0
    }

    fun isValidIpAddress(value: String): Boolean {
        val ipv4 = value.split('.')
        if (ipv4.size == 4 && ipv4.all { octet ->
                octet.isNotEmpty() && octet.all(Char::isDigit) &&
                    (octet == "0" || !octet.startsWith('0')) &&
                    octet.toIntOrNull()?.let { it in 0..255 } == true
            }
        ) return true
        if (':' !in value || !value.matches(IPV6_TOKEN_REGEX)) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }

    fun isValidHostname(value: String): Boolean {
        if (value.length !in 1..MAX_HOSTNAME_CHARS || value.startsWith('.') || value.endsWith('.')) {
            return false
        }
        return value.split('.').all { label ->
            label.length in 1..MAX_HOSTNAME_LABEL_CHARS &&
                isAsciiLetterOrDigit(label.first()) && isAsciiLetterOrDigit(label.last()) &&
                label.all { isAsciiLetterOrDigit(it) || it == '-' }
        }
    }

    private fun isAsciiLetterOrDigit(value: Char): Boolean =
        value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9'

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val IPV6_TOKEN_REGEX = Regex("[0-9A-Fa-f:.]+")
    private const val MAX_HOSTNAME_CHARS = 253
    private const val MAX_HOSTNAME_LABEL_CHARS = 63
}
