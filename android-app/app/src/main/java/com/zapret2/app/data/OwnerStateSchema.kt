package com.zapret2.app.data

import java.security.MessageDigest

/** Schema shared by every Android-side verifier of owner.meta. */
internal object OwnerStateSchema {
    const val VERSION = 7
    const val CURRENT_FIELD_COUNT = 33
    // Read-only compatibility for legacy schemas that hex-encoded up to
    // 256 KiB of argv. Current records contain only the digest and are tighter.
    const val MAX_FILE_BYTES = 1024 * 1024
    const val MAX_CURRENT_FILE_BYTES = 64 * 1024
    const val LEGACY_V6_VERSION = 6
    const val LEGACY_V5_VERSION = 5
    const val LEGACY_V4_VERSION = 4
    const val LEGACY_V3_VERSION = 3
    const val STATE_DIR = "/data/adb/zapret2-state"
    const val PID_FILE = "$STATE_DIR/nfqws2.pid"
    const val OWNER_FILE = "$STATE_DIR/owner.meta"
    const val LOG_FILE = "$STATE_DIR/nfqws2.log"
    const val ERROR_LOG_FILE = "$STATE_DIR/nfqws2.error"
    const val RUNTIME_CMDLINE_FILE = "$STATE_DIR/nfqws2.cmdline"
    private const val NFQWS_FILE = "/data/adb/modules/zapret2/zapret2/nfqws2"

    val legacyV3Fields = listOf(
        "version", "pid", "starttime", "argv_hex", "qnum", "exe", "generation", "phase",
    )
    val legacyV4Fields = listOf(
        "version", "pid", "starttime", "argv_hex", "qnum", "exe", "generation", "phase",
        "install_generation", "install_archive_sha256", "ports_tcp", "ports_udp", "stun_ports",
        "pkt_out", "pkt_in", "desync_mark", "ipv4_active", "ipv6_active", "ipv4_connbytes",
        "ipv4_multiport", "ipv4_mark", "ipv6_connbytes", "ipv6_multiport", "ipv6_mark",
        "ipv4_rules", "ipv6_rules", "ipv4_spec", "ipv6_spec", "firewall_fingerprint",
    )
    val legacyV5Fields = buildList {
        addAll(legacyV4Fields.take(7))
        add("boot_id")
        addAll(legacyV4Fields.drop(7))
    }
    val legacyV6Fields = buildList {
        addAll(legacyV5Fields.take(11))
        addAll(listOf("firewall_tag", "out_chain", "in_chain"))
        addAll(legacyV5Fields.drop(11))
    }
    val currentFields = legacyV6Fields.map { if (it == "argv_hex") "argv_sha256" else it }
    val fields = currentFields.toSet()

    init {
        require(currentFields.size == CURRENT_FIELD_COUNT)
        require(fields.size == CURRENT_FIELD_COUNT)
    }

    enum class Disposition {
        CURRENT,
        COMPATIBLE_READ_ONLY,
        LEGACY_RECOVERY_REQUIRED,
        INVALID_RECOVERY_REQUIRED,
    }

    data class Reconciliation(
        val disposition: Disposition,
        val values: Map<String, String>,
    ) {
        val isHealthyCandidate: Boolean
            get() = disposition == Disposition.CURRENT || disposition == Disposition.COMPATIBLE_READ_ONLY

        val recoveryRequired: Boolean
            get() = !isHealthyCandidate
    }

    fun accepts(rawVersion: String): Boolean = rawVersion == VERSION.toString()

    /** Strict, side-effect-free classification used by Android owner reconciliation. */
    fun reconcile(lines: List<String>, currentBootId: String): Reconciliation {
        if (canonicalWireBytes(lines) !in 1..MAX_FILE_BYTES) return invalid()
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return invalid()
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val keys = pairs.map { it.first }
        if (keys.distinct().size != keys.size) return invalid()
        val values = pairs.toMap()
        return when (values["version"]) {
            LEGACY_V3_VERSION.toString() ->
                if (keys == legacyV3Fields) legacy(values) else invalid(values)
            LEGACY_V4_VERSION.toString() ->
                if (keys == legacyV4Fields) legacy(values) else invalid(values)
            LEGACY_V5_VERSION.toString() -> {
                val bootId = values["boot_id"].orEmpty()
                if (keys == legacyV5Fields && isValidBootId(bootId)) legacy(values) else invalid(values)
            }
            LEGACY_V6_VERSION.toString() -> {
                val bootId = values["boot_id"].orEmpty()
                when {
                    keys != legacyV6Fields || !isValidBootId(bootId) || !isValidBootId(currentBootId) ->
                        invalid(values)
                    bootId != currentBootId -> legacy(values)
                    hasValidFirewallIdentity(values) && hasValidPayload(values, "argv_hex") ->
                        Reconciliation(Disposition.COMPATIBLE_READ_ONLY, values)
                    else -> invalid(values)
                }
            }
            VERSION.toString() -> {
                val bootId = values["boot_id"].orEmpty()
                if (keys == currentFields && isValidBootId(bootId) &&
                    isValidBootId(currentBootId) && bootId == currentBootId &&
                    canonicalWireBytes(lines) <= MAX_CURRENT_FILE_BYTES &&
                    hasValidFirewallIdentity(values) && hasValidPayload(values, "argv_sha256")
                ) {
                    Reconciliation(Disposition.CURRENT, values)
                } else {
                    invalid(values)
                }
            }
            else -> invalid(values)
        }
    }

    fun isValidBootId(value: String): Boolean =
        value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    fun hasValidFirewallIdentity(values: Map<String, String>): Boolean {
        val tag = values["firewall_tag"].orEmpty()
        return tag.matches(Regex("[A-Za-z0-9]{10}")) &&
            values["out_chain"] == "Z2O_$tag" && values["in_chain"] == "Z2I_$tag"
    }

    private fun canonicalWireBytes(lines: List<String>): Int =
        lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }

    private fun hasValidPayload(values: Map<String, String>, commandIdentityField: String): Boolean {
        fun canonicalPositive(name: String, maxDigits: Int = 19): Long? = values[name]
            ?.takeIf { it.matches(Regex("[1-9][0-9]{0,${maxDigits - 1}}")) }
            ?.toLongOrNull()
        fun bit(name: String): Int? = values[name]?.toIntOrNull()?.takeIf { it in 0..1 }

        val pid = canonicalPositive("pid", maxDigits = 10) ?: return false
        val starttime = values["starttime"]?.takeIf(ProtocolDecimal::isCanonicalNonNegativeLong) ?: return false
        val qnum = values["qnum"]?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull() ?: return false
        val generation = values["generation"].orEmpty()
        val installGeneration = values["install_generation"].orEmpty()
        val archiveDigest = values["install_archive_sha256"].orEmpty()
        val tcpPorts = parseCanonicalPorts(values["ports_tcp"].orEmpty()) ?: return false
        val udpPorts = parseCanonicalPorts(values["ports_udp"].orEmpty()) ?: return false
        val stunPorts = parseCanonicalPorts(values["stun_ports"].orEmpty()) ?: return false
        val pktOut = canonicalPositive("pkt_out", maxDigits = 9) ?: return false
        val pktIn = canonicalPositive("pkt_in", maxDigits = 9) ?: return false
        val ipv4Active = bit("ipv4_active") ?: return false
        val ipv6Active = bit("ipv6_active") ?: return false
        val ipv4Connbytes = bit("ipv4_connbytes") ?: return false
        val ipv4Multiport = bit("ipv4_multiport") ?: return false
        val ipv4Mark = bit("ipv4_mark") ?: return false
        val ipv6Connbytes = bit("ipv6_connbytes") ?: return false
        val ipv6Multiport = bit("ipv6_multiport") ?: return false
        val ipv6Mark = bit("ipv6_mark") ?: return false
        val ipv4Rules = values["ipv4_rules"]?.takeIf(ProtocolDecimal::isCanonicalNonNegativeLong)?.toLong() ?: return false
        val ipv6Rules = values["ipv6_rules"]?.takeIf(ProtocolDecimal::isCanonicalNonNegativeLong)?.toLong() ?: return false
        if (pid <= 0 || starttime.isEmpty() || qnum !in 1..65535 || pktOut <= 0 || pktIn <= 0) return false
        val commandIdentity = values[commandIdentityField].orEmpty()
        if (commandIdentityField == "argv_sha256") {
            if (!commandIdentity.matches(Regex("[0-9a-f]{64}"))) return false
        } else if (!commandIdentity.matches(Regex("[0-9A-Fa-f]+"))) {
            return false
        }
        if (values["exe"] != NFQWS_FILE || !generation.matches(Regex("[A-Za-z0-9._-]+"))) return false
        if (values["phase"] !in setOf("launched", "active", "stopping", "error")) return false
        if (!installGeneration.matches(Regex("[A-Za-z0-9._-]{1,128}"))) return false
        if (!archiveDigest.matches(Regex("[0-9a-f]{64}"))) return false
        if (values["stun_ports"] != "3478,5349,19302" || ipv4Active != 1) return false
        val mark = values["desync_mark"].orEmpty()
        if (ProtocolMark.canonicalOrNull(mark) != mark) return false

        fun expectedRules(active: Int, multiport: Int): Long =
            (if (multiport == 1) 6L else 2L * (tcpPorts.size + udpPorts.size + stunPorts.size)) * active
        if (ipv4Rules != expectedRules(ipv4Active, ipv4Multiport) ||
            ipv6Rules != expectedRules(ipv6Active, ipv6Multiport)
        ) {
            return false
        }

        val ipv4Spec = buildFirewallSpec(
            family = "ipv4", active = ipv4Active, values = values,
            connbytes = ipv4Connbytes, multiport = ipv4Multiport, markCapability = ipv4Mark,
            rules = ipv4Rules,
        )
        val ipv6Spec = buildFirewallSpec(
            family = "ipv6", active = ipv6Active, values = values,
            connbytes = ipv6Connbytes, multiport = ipv6Multiport, markCapability = ipv6Mark,
            rules = ipv6Rules,
        )
        if (values["ipv4_spec"] != ipv4Spec || values["ipv6_spec"] != ipv6Spec) return false
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$ipv4Spec\n$ipv6Spec\n".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return values["firewall_fingerprint"] == fingerprint
    }

    private fun buildFirewallSpec(
        family: String,
        active: Int,
        values: Map<String, String>,
        connbytes: Int,
        multiport: Int,
        markCapability: Int,
        rules: Long,
    ): String = buildString {
        append("family:").append(family)
        append(";active:").append(active)
        append(";tag:").append(values.getValue("firewall_tag"))
        append(";outchain:").append(values.getValue("out_chain"))
        append(";inchain:").append(values.getValue("in_chain"))
        append(";qnum:").append(values.getValue("qnum"))
        append(";tcp:").append(values.getValue("ports_tcp"))
        append(";udp:").append(values.getValue("ports_udp"))
        append(";stun:").append(values.getValue("stun_ports"))
        append(";out:").append(values.getValue("pkt_out"))
        append(";in:").append(values.getValue("pkt_in"))
        append(";mark:").append(values.getValue("desync_mark"))
        append(";connbytes:").append(connbytes)
        append(";multiport:").append(multiport)
        append(";markcap:").append(markCapability)
        append(";rules:").append(rules)
    }

    private fun parseCanonicalPorts(value: String): List<String>? {
        if (value.isEmpty() || value.startsWith(',') || value.endsWith(',') || ",," in value) return null
        val items = value.split(',')
        return items.takeIf {
            it.all { item ->
                val bounds = item.split(':')
                if (bounds.size !in 1..2) return@all false
                if (bounds.any { bound -> !bound.matches(Regex("0|[1-9][0-9]*")) }) return@all false
                val first = bounds[0].toIntOrNull() ?: return@all false
                val last = bounds.getOrElse(1) { bounds[0] }.toIntOrNull() ?: return@all false
                first in 0..65535 && last in first..65535
            }
        }
    }

    private fun legacy(values: Map<String, String>) =
        Reconciliation(Disposition.LEGACY_RECOVERY_REQUIRED, values)

    private fun invalid(values: Map<String, String> = emptyMap()) =
        Reconciliation(Disposition.INVALID_RECOVERY_REQUIRED, values)
}

/** Canonical decimal fields shared across owner status, rollback, and update protocols. */
internal object ProtocolDecimal {
    private val nonNegative = Regex("0|[1-9][0-9]*")

    fun isCanonicalNonNegativeLong(value: String): Boolean =
        value.matches(nonNegative) && value.toLongOrNull() != null
}

/** Canonical unsigned 32-bit netfilter mark shared by runtime and owner validation. */
internal object ProtocolMark {
    fun canonicalOrNull(value: String): String? {
        val parsed = when {
            value.startsWith("0x") || value.startsWith("0X") ->
                value.substring(2).takeIf { it.matches(Regex("[0-9A-Fa-f]+")) }?.toLongOrNull(16)
            value.matches(Regex("[0-9]+")) -> value.toLongOrNull()
            else -> null
        } ?: return null
        if (parsed !in 0..0xffff_ffffL) return null
        return "0x${parsed.toString(16)}"
    }
}
