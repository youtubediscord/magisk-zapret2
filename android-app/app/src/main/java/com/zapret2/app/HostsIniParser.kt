package com.zapret2.app

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HostsIniData(
    val dnsPresets: List<String>,
    val dnsServices: List<DnsService>,
    val directServices: List<DirectService>
)

data class DnsService(val name: String, val domains: List<DnsDomain>)

data class DnsDomain(val hostname: String, val ipsByPreset: List<String>)

data class DirectService(val name: String, val entries: List<String>)

object HostsIniParser {

    private const val HOSTS_INI_PATH = "/data/adb/modules/zapret2/zapret2/hosts.ini"
    private const val MARKER_BEGIN = "# BEGIN zapret2-dns"
    private const val MARKER_END = "# END zapret2-dns"

    private val SERVICE_HEADER_REGEX = Regex("""^\[(.+)]$""")

    private enum class State {
        INITIAL, DNS_PRESETS, SERVICES_DNS, SERVICES_DIRECT
    }

    data class ParseResult(
        val data: HostsIniData?,
        val error: String?
    )

    /**
     * Parse hosts.ini from the device via root shell.
     * Returns ParseResult with either data or an error message.
     */
    suspend fun parse(): ParseResult = withContext(Dispatchers.IO) {
        // Check if file exists first
        val existsResult = Shell.cmd("[ -f \"$HOSTS_INI_PATH\" ] && echo YES").exec()
        if (existsResult.out.firstOrNull() != "YES") {
            return@withContext ParseResult(null, "File not found: $HOSTS_INI_PATH")
        }

        val result = Shell.cmd("cat \"$HOSTS_INI_PATH\" 2>/dev/null").exec()
        if (!result.isSuccess) {
            return@withContext ParseResult(null, "Shell command failed (exit ${result.code})")
        }
        if (result.out.isEmpty()) {
            return@withContext ParseResult(null, "File is empty")
        }

        val data = parseLines(result.out)
        if (data.dnsServices.isEmpty() && data.directServices.isEmpty()) {
            return@withContext ParseResult(null, "Parsed OK but no services found (${result.out.size} lines, ${data.dnsPresets.size} presets)")
        }

        ParseResult(data, null)
    }

    /**
     * Parse hosts.ini from raw lines (useful for testing or when content
     * is already available).
     */
    fun parseLines(lines: List<String>): HostsIniData {
        val dnsPresets = mutableListOf<String>()
        val dnsServices = mutableListOf<DnsService>()
        val directServices = mutableListOf<DirectService>()

        var state = State.INITIAL

        // SERVICES_DNS working state
        var currentDnsServiceName: String? = null
        var currentDnsDomains = mutableListOf<DnsDomain>()
        var domainBuffer = mutableListOf<String>()

        // SERVICES_DIRECT working state
        var currentDirectServiceName: String? = null
        var currentDirectEntries = mutableListOf<String>()

        fun flushDomainBuffer() {
            if (domainBuffer.isNotEmpty()) {
                val hostname = domainBuffer[0]
                val ips = if (domainBuffer.size > 1) {
                    domainBuffer.subList(1, domainBuffer.size).toList()
                } else {
                    emptyList()
                }
                currentDnsDomains.add(DnsDomain(hostname, ips))
                domainBuffer = mutableListOf()
            }
        }

        fun flushDnsService() {
            flushDomainBuffer()
            currentDnsServiceName?.let { name ->
                if (currentDnsDomains.isNotEmpty()) {
                    dnsServices.add(DnsService(name, currentDnsDomains.toList()))
                }
            }
            currentDnsDomains = mutableListOf()
            currentDnsServiceName = null
        }

        fun flushDirectService() {
            currentDirectServiceName?.let { name ->
                if (currentDirectEntries.isNotEmpty()) {
                    directServices.add(DirectService(name, currentDirectEntries.toList()))
                }
            }
            currentDirectEntries = mutableListOf()
            currentDirectServiceName = null
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            // Detect top-level section transitions
            when (line.trim()) {
                "[DNS]" -> {
                    state = State.DNS_PRESETS
                    continue
                }
                "[SERVICES_DNS]" -> {
                    state = State.SERVICES_DNS
                    continue
                }
                "[SERVICES_DIRECT]" -> {
                    // Flush any pending DNS service before switching
                    if (state == State.SERVICES_DNS) {
                        flushDnsService()
                    }
                    state = State.SERVICES_DIRECT
                    continue
                }
            }

            when (state) {
                State.INITIAL -> {
                    // Skip lines before any section header
                }

                State.DNS_PRESETS -> {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        dnsPresets.add(trimmed)
                    }
                }

                State.SERVICES_DNS -> {
                    val headerMatch = SERVICE_HEADER_REGEX.matchEntire(line.trim())
                    if (headerMatch != null) {
                        // New service header inside SERVICES_DNS
                        flushDnsService()
                        currentDnsServiceName = headerMatch.groupValues[1]
                    } else if (line.trim().isEmpty()) {
                        // Empty line signals end of a domain block
                        flushDomainBuffer()
                    } else {
                        // Non-empty, non-header line: part of domain buffer
                        domainBuffer.add(line.trim())
                    }
                }

                State.SERVICES_DIRECT -> {
                    val headerMatch = SERVICE_HEADER_REGEX.matchEntire(line.trim())
                    if (headerMatch != null) {
                        // New service header inside SERVICES_DIRECT
                        flushDirectService()
                        currentDirectServiceName = headerMatch.groupValues[1]
                    } else if (line.trim().isNotEmpty()) {
                        // Non-empty line with content (should be "IP domain" format)
                        currentDirectEntries.add(line.trim())
                    }
                    // Empty lines in SERVICES_DIRECT are just separators, ignored
                }
            }
        }

        // Flush whatever is pending at end of file
        when (state) {
            State.SERVICES_DNS -> flushDnsService()
            State.SERVICES_DIRECT -> flushDirectService()
            else -> {}
        }

        return HostsIniData(
            dnsPresets = dnsPresets.toList(),
            dnsServices = dnsServices.toList(),
            directServices = directServices.toList()
        )
    }

    /**
     * Generate a hosts-format block for the selected services and DNS preset.
     *
     * @param data Parsed hosts.ini data
     * @param presetIndex Index into [HostsIniData.dnsPresets] (0-based)
     * @param selectedDnsServices Set of [DnsService.name] values to include
     * @param selectedDirectServices Set of [DirectService.name] values to include
     * @return A string block wrapped with BEGIN/END markers, ready to merge into /system/etc/hosts
     */
    fun generateHostsBlock(
        data: HostsIniData,
        presetIndex: Int,
        selectedDnsServices: Set<String>,
        selectedDirectServices: Set<String>
    ): String {
        val lines = mutableListOf<String>()
        lines.add(MARKER_BEGIN)

        for (service in data.dnsServices) {
            if (service.name in selectedDnsServices) {
                for (domain in service.domains) {
                    if (presetIndex in domain.ipsByPreset.indices) {
                        val ip = domain.ipsByPreset[presetIndex]
                        if (ip.isNotBlank()) {
                            lines.add("$ip ${domain.hostname}")
                        }
                    }
                }
            }
        }

        for (service in data.directServices) {
            if (service.name in selectedDirectServices) {
                lines.addAll(service.entries)
            }
        }

        lines.add(MARKER_END)
        return lines.joinToString("\n") + "\n"
    }

    /**
     * Smart merge: locate the existing zapret2-dns block (by markers) in
     * [currentHosts] and replace it with [block]. If no markers are found,
     * append the block at the end.
     */
    fun smartMerge(currentHosts: String, block: String): String {
        val beginIdx = currentHosts.indexOf(MARKER_BEGIN)
        val endIdx = currentHosts.indexOf(MARKER_END)

        if (beginIdx >= 0 && endIdx >= 0 && endIdx > beginIdx) {
            val before = currentHosts.substring(0, beginIdx)
            val after = currentHosts.substring(endIdx + MARKER_END.length)
            val result = before + block + after
            return if (result.endsWith("\n")) result else "$result\n"
        }

        // No existing block -- append
        val trimmed = currentHosts.trimEnd()
        return if (trimmed.isEmpty()) {
            "$block\n"
        } else {
            "$trimmed\n$block\n"
        }
    }

    /**
     * Remove the zapret2-dns block (between markers, inclusive) from the hosts
     * content, cleaning up extra blank lines left behind.
     */
    fun removeZapretBlock(currentHosts: String): String {
        val beginIdx = currentHosts.indexOf(MARKER_BEGIN)
        val endIdx = currentHosts.indexOf(MARKER_END)

        if (beginIdx >= 0 && endIdx >= 0 && endIdx > beginIdx) {
            val before = currentHosts.substring(0, beginIdx)
            val after = currentHosts.substring(endIdx + MARKER_END.length)
            val merged = (before + after).trim()
            return if (merged.isEmpty()) "\n" else "$merged\n"
        }
        return currentHosts
    }
}
