package com.zapret2.app.data

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
    private const val MAX_HOSTS_INI_BYTES = 1024 * 1024
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
        val content = RootFileIo.readSecureRegularText(HOSTS_INI_PATH, MAX_HOSTS_INI_BYTES)
            ?: return@withContext ParseResult(null, "hosts.ini is missing, unsafe, empty, or unreadable")
        val lines = content.lineSequence().toList()
        val data = parseLinesOrNull(lines)
        if (data == null) {
            return@withContext ParseResult(
                null,
                "hosts.ini has an invalid or ambiguous catalog schema",
            )
        }

        ParseResult(data, null)
    }

    /**
     * Parse hosts.ini from raw lines (useful for testing or when content
     * is already available).
     */
    fun parseLines(lines: List<String>): HostsIniData = requireNotNull(parseLinesOrNull(lines)) {
        "Invalid or ambiguous hosts.ini catalog"
    }

    internal fun parseLinesOrNull(lines: List<String>): HostsIniData? {
        if (!hasExactTopLevelSections(lines)) return null
        if (!hasWellFormedSectionContent(lines)) return null
        val data = parseLinesUnchecked(lines)
        return data.takeIf(::isValidCatalog)
    }

    private fun parseLinesUnchecked(lines: List<String>): HostsIniData {
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

    private fun hasExactTopLevelSections(lines: List<String>): Boolean {
        val trimmed = lines.map { it.trim() }
        val dnsIndexes = trimmed.indices.filter { trimmed[it] == "[DNS]" }
        val servicesDnsIndexes = trimmed.indices.filter { trimmed[it] == "[SERVICES_DNS]" }
        val servicesDirectIndexes = trimmed.indices.filter { trimmed[it] == "[SERVICES_DIRECT]" }
        if (dnsIndexes.size != 1 || servicesDnsIndexes.size != 1 ||
            servicesDirectIndexes.size != 1
        ) return false
        val dnsIndex = dnsIndexes.single()
        val servicesDnsIndex = servicesDnsIndexes.single()
        val servicesDirectIndex = servicesDirectIndexes.single()
        if (dnsIndex >= servicesDnsIndex || servicesDnsIndex >= servicesDirectIndex) return false
        return trimmed.take(dnsIndex).all { it.isEmpty() || it.startsWith("#") || it.startsWith(";") }
    }

    private fun hasWellFormedSectionContent(lines: List<String>): Boolean {
        val trimmed = lines.map { it.trim() }
        val dnsIndex = trimmed.indexOf("[DNS]")
        val servicesDnsIndex = trimmed.indexOf("[SERVICES_DNS]")
        val servicesDirectIndex = trimmed.indexOf("[SERVICES_DIRECT]")

        val presetLines = trimmed.subList(dnsIndex + 1, servicesDnsIndex)
            .filter(String::isNotEmpty)
        if (presetLines.isEmpty() || presetLines.any { !isValidLabel(it) }) return false

        if (!hasWellFormedDnsServices(trimmed.subList(servicesDnsIndex + 1, servicesDirectIndex))) {
            return false
        }
        return hasWellFormedDirectServices(trimmed.subList(servicesDirectIndex + 1, trimmed.size))
    }

    private fun hasWellFormedDnsServices(lines: List<String>): Boolean {
        var hasService = false
        var currentServiceHasDomain = false
        var domainValueCount = 0

        fun flushDomain(): Boolean {
            if (domainValueCount == 0) return true
            if (domainValueCount < 2) return false
            currentServiceHasDomain = true
            domainValueCount = 0
            return true
        }

        for (line in lines) {
            val header = SERVICE_HEADER_REGEX.matchEntire(line)
            when {
                header != null -> {
                    if (!flushDomain() || (hasService && !currentServiceHasDomain)) return false
                    if (!isValidLabel(header.groupValues[1])) return false
                    hasService = true
                    currentServiceHasDomain = false
                }
                line.isEmpty() -> if (!flushDomain()) return false
                !hasService -> return false
                else -> domainValueCount++
            }
        }

        return flushDomain() && hasService && currentServiceHasDomain
    }

    private fun hasWellFormedDirectServices(lines: List<String>): Boolean {
        var hasService = false
        var currentServiceHasEntry = false

        for (line in lines) {
            val header = SERVICE_HEADER_REGEX.matchEntire(line)
            when {
                header != null -> {
                    if (hasService && !currentServiceHasEntry) return false
                    if (!isValidLabel(header.groupValues[1])) return false
                    hasService = true
                    currentServiceHasEntry = false
                }
                line.isEmpty() -> Unit
                !hasService -> return false
                else -> currentServiceHasEntry = true
            }
        }

        return !hasService || currentServiceHasEntry
    }

    private fun isValidCatalog(data: HostsIniData): Boolean {
        if (data.dnsPresets.isEmpty() || data.dnsServices.isEmpty()) {
            return false
        }
        if (!hasUniqueValidLabels(data.dnsPresets)) return false
        if (!hasUniqueValidLabels(data.dnsServices.map(DnsService::name))) return false
        if (!hasUniqueValidLabels(data.directServices.map(DirectService::name))) return false

        val addressCounts = data.dnsServices.flatMap { service ->
            if (service.domains.isEmpty() ||
                service.domains.map { it.hostname.lowercase() }.distinct().size != service.domains.size
            ) return false
            service.domains.map { domain ->
                if (!HostsFileSyntax.isValidHostname(domain.hostname) || domain.ipsByPreset.isEmpty() ||
                    domain.ipsByPreset.any { !HostsFileSyntax.isValidIpAddress(it) }
                ) return false
                domain.ipsByPreset.size
            }
        }
        if (addressCounts.isEmpty() || addressCounts.distinct().size != 1 ||
            addressCounts.first() > data.dnsPresets.size
        ) return false

        val allDnsHostnames = data.dnsServices.flatMap { service ->
            service.domains.map { it.hostname.lowercase() }
        }
        if (allDnsHostnames.distinct().size != allDnsHostnames.size) return false

        val allDirectEntries = data.directServices.flatMap { service ->
            service.entries.map { it.lowercase() }
        }
        if (allDirectEntries.distinct().size != allDirectEntries.size) return false

        return data.directServices.all { service ->
            service.entries.isNotEmpty() &&
                service.entries.map { it.lowercase() }.distinct().size == service.entries.size &&
                service.entries.all(::isValidHostsEntry)
        }
    }

    private fun hasUniqueValidLabels(labels: List<String>): Boolean =
        labels.all(::isValidLabel) && labels.map { it.lowercase() }.distinct().size == labels.size

    private fun isValidLabel(value: String): Boolean =
        value.isNotBlank() && value == value.trim() && value.length <= MAX_LABEL_CHARS &&
            value.none { it.isISOControl() || it == '[' || it == ']' }

    private fun isValidHostsEntry(value: String): Boolean {
        val parts = value.trim().split(Regex("\\s+"))
        return parts.size == 2 &&
            HostsFileSyntax.isValidIpAddress(parts[0]) &&
            HostsFileSyntax.isValidHostname(parts[1])
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
        require(isValidCatalog(data)) { "DNS catalog is invalid" }
        require(presetIndex in data.dnsPresets.indices) { "DNS preset index is invalid" }
        require(selectedDnsServices.all { selected ->
            data.dnsServices.any { it.name == selected }
        }) { "Unknown DNS service selection" }
        require(selectedDirectServices.all { selected ->
            data.directServices.any { it.name == selected }
        }) { "Unknown direct service selection" }
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
        require(markerRange(block) != null) { "Generated hosts block has invalid markers" }
        val range = markerRange(currentHosts)
        if (range != null) {
            val before = currentHosts.substring(0, range.first).trimEnd('\r', '\n')
            val after = currentHosts.substring(range.last + 1).trimStart('\r', '\n')
            return joinHostsSegments(before, block.trimEnd('\r', '\n'), after)
        }

        return joinHostsSegments(currentHosts.trimEnd('\r', '\n'), block.trimEnd('\r', '\n'))
    }

    /**
     * Remove the zapret2-dns block (between markers, inclusive) from the hosts
     * content, cleaning up extra blank lines left behind.
     */
    fun removeZapretBlock(currentHosts: String): String {
        val range = markerRange(currentHosts) ?: return currentHosts
        val before = currentHosts.substring(0, range.first).trimEnd('\r', '\n')
        val after = currentHosts.substring(range.last + 1).trimStart('\r', '\n')
        return joinHostsSegments(before, after)
    }

    private fun markerRange(content: String): IntRange? {
        val rawBegins = Regex(Regex.escape(MARKER_BEGIN)).findAll(content).toList()
        val rawEnds = Regex(Regex.escape(MARKER_END)).findAll(content).toList()
        val begins = exactMarkerLines(content, MARKER_BEGIN)
        val ends = exactMarkerLines(content, MARKER_END)
        require(rawBegins.size == begins.size && rawEnds.size == ends.size) {
            "Hosts content contains malformed Zapret2 marker text"
        }
        require(begins.size <= 1 && ends.size <= 1 && begins.size == ends.size) {
            "Hosts content has missing or duplicate Zapret2 markers"
        }
        if (begins.isEmpty()) return null
        val begin = begins.single().range.first
        val end = ends.single().range.last
        require(end > begins.single().range.last) { "Zapret2 hosts markers are out of order" }
        return begin..end
    }

    private fun exactMarkerLines(content: String, marker: String): List<MatchResult> =
        Regex("(?m)^${Regex.escape(marker)}\\r?$").findAll(content).toList()

    private fun joinHostsSegments(vararg segments: String): String {
        val content = segments.filter(String::isNotEmpty).joinToString("\n")
        return if (content.isEmpty()) "\n" else "$content\n"
    }

    private const val MAX_LABEL_CHARS = 128
}
