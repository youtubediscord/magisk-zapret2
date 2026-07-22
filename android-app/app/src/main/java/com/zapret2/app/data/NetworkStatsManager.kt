package com.zapret2.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zapret2.app.AppDebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

internal data class OwnedFirewallIdentity(
    val tag: String,
    val outChain: String,
    val inChain: String,
)

internal data class OwnedFirewallFamilyContract(
    val identity: OwnedFirewallIdentity,
    val active: Boolean,
    val qnum: Int,
    val portsTcp: String,
    val portsUdp: String,
    val stunPorts: String,
    val pktOut: Int,
    val pktIn: Int,
    val desyncMark: String,
    val connbytes: Boolean,
    val multiport: Boolean,
    val markCapability: Boolean,
    val expectedRuleCount: Int,
)

private data class OwnedFirewallContract(
    val ipv4: OwnedFirewallFamilyContract,
    val ipv6: OwnedFirewallFamilyContract,
)

internal data class OwnedIptablesFamily(
    val hasState: Boolean = false,
    val chainCount: Int = 0,
    val anchorCount: Int = 0,
    val mainJumpCount: Int = 0,
    val outNfqueueCount: Int = 0,
    val inNfqueueCount: Int = 0,
    val topologyVerified: Boolean = false,
) {
    val nfqueueRulesCount: Int
        get() = outNfqueueCount + inNfqueueCount
}

/** Pure, fail-closed verifier for the generation-bound v6 firewall topology. */
internal object OwnedIptablesTopologyVerifier {
    private val transferOptions = setOf("-j", "--jump", "-g", "--goto")

    private data class PayloadSpec(
        val chain: String,
        val protocol: String,
        val portOption: String,
        val ports: String,
        val packetCount: Int,
        val connbytesDirection: String,
    )

    fun verify(
        lines: List<String>,
        contract: OwnedFirewallFamilyContract,
    ): OwnedIptablesFamily {
        val identity = contract.identity
        val identityValid = OwnerStateSchema.hasValidFirewallIdentity(
            mapOf(
                "firewall_tag" to identity.tag,
                "out_chain" to identity.outChain,
                "in_chain" to identity.inChain,
            )
        )
        val payloadSpecs = expectedPayloads(contract) ?: return OwnedIptablesFamily()
        if (!identityValid || payloadSpecs.size != contract.expectedRuleCount) {
            return OwnedIptablesFamily()
        }

        val expectedRuleCount = contract.expectedRuleCount
        val perSide = expectedRuleCount / 2
        val outSubchains = (1..perSide).map { "Z2R_${identity.tag}_O$it" }
        val inSubchains = (1..perSide).map { "Z2R_${identity.tag}_I$it" }
        val expectedSubchains = (outSubchains + inSubchains).toSet()
        val expectedChains = if (expectedRuleCount == 0) {
            emptySet()
        } else {
            expectedSubchains + identity.outChain + identity.inChain
        }
        val declarations = mutableMapOf<String, Int>()
        val references = mutableMapOf<String, Int>()
        val mainRules = mutableMapOf(
            identity.outChain to mutableListOf<List<String>>(),
            identity.inChain to mutableListOf(),
        )
        val payloadRules = expectedSubchains.associateWith { mutableListOf<List<String>>() }
        var malformed = false
        var foreignOwnedTopology = false
        var hasState = false
        var exactOutAnchors = 0
        var exactInAnchors = 0

        fun isOwnedName(value: String): Boolean =
            value == "ZAPRET2_OUT" || value == "ZAPRET2_IN" || value == "ZAPRET2_PROBE" ||
                value.startsWith("Z2O_") || value.startsWith("Z2I_") || value.startsWith("Z2R_")

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val tokens = line.split(Regex("\\s+"))
            when (tokens.firstOrNull()) {
                "-P" -> if (tokens.size != 3) malformed = true
                "-N" -> if (tokens.size != 2) malformed = true
                "-A" -> if (tokens.size < 3) malformed = true
                else -> malformed = true
            }
            val declared = tokens.getOrNull(1)?.takeIf { tokens.firstOrNull() == "-N" }
            val source = tokens.getOrNull(1)?.takeIf { tokens.firstOrNull() == "-A" }

            val targets = mutableListOf<String>()
            tokens.forEachIndexed { index, token ->
                if (token in transferOptions) {
                    tokens.getOrNull(index + 1)?.let(targets::add) ?: run { malformed = true }
                }
            }
            val ownedNames = buildList {
                declared?.takeIf(::isOwnedName)?.let(::add)
                source?.takeIf(::isOwnedName)?.let(::add)
                targets.filterTo(this, ::isOwnedName)
            }
            if (ownedNames.isNotEmpty()) {
                hasState = true
                if (ownedNames.any { it !in expectedChains }) foreignOwnedTopology = true
            }

            if (declared != null && isOwnedName(declared)) {
                if (tokens.size != 2) malformed = true
                declarations[declared] = (declarations[declared] ?: 0) + 1
            }
            targets.filter(::isOwnedName).forEach { target ->
                references[target] = (references[target] ?: 0) + 1
            }
            source?.let { chain ->
                mainRules[chain]?.add(tokens)
                payloadRules[chain]?.add(tokens)
            }
            if (tokens == listOf("-A", "OUTPUT", "-j", identity.outChain)) exactOutAnchors++
            if (tokens == listOf("-A", "INPUT", "-j", identity.inChain)) exactInAnchors++
        }

        val expectedPayloadByChain = payloadSpecs.associateBy { it.chain }
        val outValidPayloads = outSubchains.count { chain ->
            payloadRules.getValue(chain).singleOrNull()?.let { tokens ->
                expectedPayloadByChain[chain]?.let { expected ->
                    payloadMatches(tokens, expected, contract)
                }
            } == true
        }
        val inValidPayloads = inSubchains.count { chain ->
            payloadRules.getValue(chain).singleOrNull()?.let { tokens ->
                expectedPayloadByChain[chain]?.let { expected ->
                    payloadMatches(tokens, expected, contract)
                }
            } == true
        }
        val expectedOutRules = outSubchains.map { listOf("-A", identity.outChain, "-j", it) }
        val expectedInRules = inSubchains.map { listOf("-A", identity.inChain, "-j", it) }
        val declarationsValid = expectedChains.all { declarations[it] == 1 } &&
            declarations.keys.all { it in expectedChains }
        val referencesValid = if (expectedRuleCount == 0) {
            references.keys.none(::isOwnedName)
        } else {
            references[identity.outChain] == 1 && references[identity.inChain] == 1 &&
                expectedSubchains.all { references[it] == 1 } &&
                references.keys.filter(::isOwnedName).all { it in expectedChains }
        }
        val topologyVerified = !malformed && !foreignOwnedTopology && declarationsValid && referencesValid &&
            if (expectedRuleCount == 0) {
                !hasState
            } else {
                exactOutAnchors == 1 && exactInAnchors == 1 &&
                    mainRules.getValue(identity.outChain) == expectedOutRules &&
                    mainRules.getValue(identity.inChain) == expectedInRules &&
                    payloadRules.all { (chain, rules) ->
                        rules.size == 1 && expectedPayloadByChain[chain]?.let { expected ->
                            payloadMatches(rules.single(), expected, contract)
                        } == true
                    } &&
                    outValidPayloads + inValidPayloads == expectedRuleCount
            }

        return OwnedIptablesFamily(
            hasState = hasState,
            chainCount = expectedChains.sumOf { declarations[it] ?: 0 },
            anchorCount = exactOutAnchors + exactInAnchors,
            mainJumpCount = mainRules.values.sumOf { it.size },
            outNfqueueCount = outValidPayloads,
            inNfqueueCount = inValidPayloads,
            topologyVerified = topologyVerified,
        )
    }

    private fun expectedPayloads(contract: OwnedFirewallFamilyContract): List<PayloadSpec>? {
        if (contract.qnum !in 1..65535 || contract.pktOut !in 1..999_999_999 ||
            contract.pktIn !in 1..999_999_999 || contract.expectedRuleCount < 0 ||
            ProtocolMark.canonicalOrNull(contract.desyncMark) != contract.desyncMark ||
            !isCanonicalPortList(contract.portsTcp) || !isCanonicalPortList(contract.portsUdp) ||
            contract.stunPorts != "3478,5349,19302"
        ) {
            return null
        }
        if (!contract.active) {
            return if (contract.expectedRuleCount == 0) emptyList() else null
        }

        val specs = mutableListOf<PayloadSpec>()
        fun appendSide(
            side: String,
            direction: String,
            packetCount: Int,
            connbytesDirection: String,
        ) {
            var ordinal = 0
            listOf(
                "tcp" to contract.portsTcp,
                "udp" to contract.portsUdp,
                "udp" to contract.stunPorts,
            ).forEach { (protocol, portList) ->
                val groups = if (contract.multiport) listOf(portList) else portList.split(',')
                groups.forEach { ports ->
                    ordinal++
                    specs += PayloadSpec(
                        chain = "Z2R_${contract.identity.tag}_${side}$ordinal",
                        protocol = protocol,
                        portOption = when {
                            contract.multiport && direction == "out" -> "--dports"
                            contract.multiport -> "--sports"
                            direction == "out" -> "--dport"
                            else -> "--sport"
                        },
                        ports = ports,
                        packetCount = packetCount,
                        connbytesDirection = connbytesDirection,
                    )
                }
            }
        }
        appendSide("O", "out", contract.pktOut, "original")
        appendSide("I", "in", contract.pktIn, "reply")
        return specs
    }

    private fun payloadMatches(
        tokens: List<String>,
        expected: PayloadSpec,
        contract: OwnedFirewallFamilyContract,
    ): Boolean {
        if (tokens.size < 2 || tokens[0] != "-A" || tokens[1] != expected.chain) return false

        var protocol: String? = null
        var portOption: String? = null
        var ports: String? = null
        var connbytesRange: String? = null
        var connbytesDirection: String? = null
        var connbytesMode: String? = null
        var mark: String? = null
        var markNegated = false
        var jump: String? = null
        var queueNum: String? = null
        var queueBypassCount = 0
        var negationPending = false
        val modules = mutableMapOf<String, Int>()

        var index = 2
        while (index < tokens.size) {
            val token = tokens[index]
            if (negationPending && token != "--mark") return false
            when (token) {
                "-p" -> {
                    if (protocol != null) return false
                    protocol = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "-m" -> {
                    val module = tokens.getOrNull(index + 1) ?: return false
                    if (module !in setOf("tcp", "udp", "multiport", "connbytes", "mark")) return false
                    modules[module] = (modules[module] ?: 0) + 1
                    index += 2
                }
                "--dport", "--sport", "--dports", "--sports" -> {
                    if (portOption != null) return false
                    portOption = token
                    ports = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "--connbytes" -> {
                    if (connbytesRange != null) return false
                    connbytesRange = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "--connbytes-dir" -> {
                    if (connbytesDirection != null) return false
                    connbytesDirection = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "--connbytes-mode" -> {
                    if (connbytesMode != null) return false
                    connbytesMode = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "!" -> {
                    if (negationPending || markNegated) return false
                    negationPending = true
                    index++
                }
                "--mark" -> {
                    if (mark != null) return false
                    markNegated = negationPending
                    negationPending = false
                    mark = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "-j" -> {
                    if (jump != null) return false
                    jump = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "--queue-num" -> {
                    if (queueNum != null) return false
                    queueNum = tokens.getOrNull(index + 1) ?: return false
                    index += 2
                }
                "--queue-bypass" -> {
                    queueBypassCount++
                    index++
                }
                else -> return false
            }
        }
        if (negationPending || modules.values.any { it != 1 }) return false

        val protocolModuleCount = modules[expected.protocol] ?: 0
        val connbytesMatches = if (contract.connbytes) {
            modules["connbytes"] == 1 && connbytesRange == "1:${expected.packetCount}" &&
                connbytesDirection == expected.connbytesDirection && connbytesMode == "packets"
        } else {
            modules["connbytes"] == null && connbytesRange == null &&
                connbytesDirection == null && connbytesMode == null
        }
        val multiportMatches = if (contract.multiport) {
            modules["multiport"] == 1 && expected.portOption in setOf("--dports", "--sports")
        } else {
            modules["multiport"] == null && expected.portOption in setOf("--dport", "--sport")
        }
        val markMatches = if (contract.markCapability) {
            modules["mark"] == 1 && markNegated && normalizedMarkPair(mark) ==
                (contract.desyncMark to contract.desyncMark)
        } else {
            modules["mark"] == null && mark == null && !markNegated
        }
        val expectedModules = buildSet {
            if (protocolModuleCount == 1) add(expected.protocol)
            if (contract.multiport) add("multiport")
            if (contract.connbytes) add("connbytes")
            if (contract.markCapability) add("mark")
        }

        return protocol == expected.protocol && protocolModuleCount in 0..1 &&
            modules.keys == expectedModules && portOption == expected.portOption &&
            ports == expected.ports && connbytesMatches && multiportMatches && markMatches &&
            jump == "NFQUEUE" && queueNum == contract.qnum.toString() &&
            queueBypassCount == 1
    }

    private fun normalizedMarkPair(value: String?): Pair<String, String>? {
        val parts = value?.split('/') ?: return null
        if (parts.size != 2) return null
        val mark = ProtocolMark.canonicalOrNull(parts[0]) ?: return null
        val mask = ProtocolMark.canonicalOrNull(parts[1]) ?: return null
        return mark to mask
    }

    private fun isCanonicalPortList(value: String): Boolean {
        if (value.isEmpty() || value.startsWith(',') || value.endsWith(',') || ",," in value) return false
        return value.split(',').all { item ->
            val bounds = item.split(':')
            if (bounds.size !in 1..2 || bounds.any { !it.matches(Regex("0|[1-9][0-9]*")) }) {
                return@all false
            }
            val first = bounds[0].toIntOrNull() ?: return@all false
            val last = bounds.getOrElse(1) { bounds[0] }.toIntOrNull() ?: return@all false
            first in 0..65535 && last in first..65535
        }
    }
}

/**
 * Manages network statistics and monitoring for the Zapret2 app.
 * Provides information about:
 * - Current network type (WiFi/Mobile/None)
 * - iptables rules status
 * - NFQUEUE rules count
 */
class NetworkStatsManager(context: Context) {

    companion object {
        private const val TAG = "NetworkStatsManager"
    }

    // Use WeakReference to avoid memory leaks
    private val contextRef = WeakReference(context.applicationContext)

    // Lazy initialization with null safety
    private val connectivityManager: ConnectivityManager? by lazy {
        contextRef.get()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /**
     * Network type enumeration
     */
    enum class NetworkType {
        WIFI,
        MOBILE,
        ETHERNET,
        VPN,
        NONE
    }

    /**
     * Data class containing iptables diagnostic details from status file
     */
    data class IptablesDetail(
        val rulesOk: Int = 0,
        val rulesFail: Int = 0,
        val rulesTotal: Int = 0,
        val status: String = "unknown",  // "ok", "stopped", "error", "unknown"
        val ownPid: String = "",
        val pidVerified: Boolean = false,
        val ownPidStarttime: String = "",
        val ownerGeneration: String = "",
        val qnum: Int? = null,
        val ipv4Active: Boolean = false,
        val ipv6Active: Boolean = false,
        val chains: Int = 0,
        val anchors: Int = 0,
        val nfqueueSupported: Boolean = false,
        val queueBypassSupported: Boolean = false,
        val rulesExpected: Int = 0,
        val ipv4Rules: Int = 0,
        val ipv6Rules: Int = 0,
        val rulesetVerified: Boolean = false,
        val ownerMetadataVerified: Boolean = false,
        val metadataComplete: Boolean = false
    ) {
        val hasValidOwnerMetadata: Boolean
            get() =
                metadataComplete &&
                    pidVerified &&
                    ownerMetadataVerified &&
                    ownPid.matches(Regex("[1-9][0-9]*")) &&
                    ProtocolDecimal.isCanonicalNonNegativeLong(ownPidStarttime) &&
                    ownerGeneration.matches(Regex("[A-Za-z0-9._-]+")) &&
                    qnum in 1..65535

        val indicatesOwnedState: Boolean
            get() =
                status.equals("ok", ignoreCase = true) ||
                    status.equals("error", ignoreCase = true) ||
                    ownPid.isNotBlank() ||
                    ipv4Active ||
                    ipv6Active ||
                    chains > 0 ||
                    anchors > 0 ||
                    rulesTotal > 0 ||
                    rulesExpected > 0
    }

    /**
     * Data class containing network statistics
     */
    data class NetworkStats(
        val networkType: NetworkType,
        val iptablesActive: Boolean,
        val nfqueueRulesCount: Int,
        val hasOwnedIptablesState: Boolean = false,
        val iptablesDetail: IptablesDetail = IptablesDetail()
    )

    private data class OwnedIptablesState(
        val active: Boolean = false,
        val hasState: Boolean = false,
        val nfqueueRulesCount: Int = 0,
        val chainCount: Int = 0,
        val anchorCount: Int = 0,
    )

    /**
     * Gets the current network type
     */
    fun getNetworkType(): NetworkType {
        val cm = connectivityManager ?: return NetworkType.NONE

        return try {
            val activeNetwork = cm.activeNetwork ?: return NetworkType.NONE
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.NONE
            }
        } catch (e: Exception) {
            AppDebugLog.error(TAG, "Error getting network type", e)
            NetworkType.NONE
        }
    }

    private suspend fun getOwnedIptablesState(
        detail: IptablesDetail
    ): OwnedIptablesState = withContext(Dispatchers.IO) {
        try {
            val contract = readCurrentFirewallContract(detail)
                ?: return@withContext OwnedIptablesState(hasState = detail.indicatesOwnedState)
            val expectedIpv6Rules = if (detail.ipv6Active) detail.ipv6Rules else 0
            val ipv4 = readOwnedIptablesFamily("iptables", contract.ipv4)
            val ipv6 = readOwnedIptablesFamily("ip6tables", contract.ipv6)
            val families = listOf(ipv4, ipv6)
            val observedRules = families.sumOf { it.nfqueueRulesCount }
            val observedChains = families.sumOf { it.chainCount }
            val observedAnchors = families.sumOf { it.anchorCount }
            val expectedChains = detail.ipv4Rules + 2 + if (detail.ipv6Active) detail.ipv6Rules + 2 else 0
            val expectedAnchors = 2 + if (detail.ipv6Active) 2 else 0
            val statusConsistent =
                detail.metadataComplete &&
                    detail.status.equals("ok", ignoreCase = true) &&
                    detail.rulesetVerified &&
                    detail.nfqueueSupported &&
                    detail.queueBypassSupported &&
                    detail.ipv4Active &&
                    detail.rulesExpected > 0 &&
                    detail.rulesTotal == detail.rulesExpected &&
                    detail.rulesOk == detail.rulesExpected &&
                    detail.rulesFail == 0 &&
                    detail.ipv4Rules > 0 &&
                    (detail.ipv6Active || detail.ipv6Rules == 0) &&
                    contract.ipv4.expectedRuleCount == detail.ipv4Rules &&
                    contract.ipv6.expectedRuleCount == expectedIpv6Rules &&
                    detail.ipv4Rules + expectedIpv6Rules == detail.rulesExpected &&
                    ipv4.nfqueueRulesCount == detail.ipv4Rules &&
                    ipv6.nfqueueRulesCount == expectedIpv6Rules &&
                    observedRules == detail.rulesExpected &&
                    observedChains == expectedChains &&
                    observedAnchors == expectedAnchors
            OwnedIptablesState(
                active = statusConsistent && ipv4.topologyVerified && ipv6.topologyVerified,
                hasState = families.any { it.hasState } || detail.indicatesOwnedState,
                nfqueueRulesCount = observedRules,
                chainCount = observedChains,
                anchorCount = observedAnchors,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppDebugLog.error(TAG, "Error checking Zapret2 iptables state", e)
            OwnedIptablesState()
        }
    }

    private suspend fun readCurrentFirewallContract(detail: IptablesDetail): OwnedFirewallContract? {
        if (!detail.hasValidOwnerMetadata || !detail.rulesetVerified) return null
        val stateDirectory = RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)
        val ownerFile = RootFileIo.shellQuote(OwnerStateSchema.OWNER_FILE)
        val result = ServiceLifecycleController.executeRoot(
            """
            [ -d $stateDirectory ] && [ ! -L $stateDirectory ] || exit 1
            [ "${'$'}(stat -c %u $stateDirectory 2>/dev/null)" = 0 ] || exit 1
            [ "${'$'}(stat -c %a $stateDirectory 2>/dev/null)" = 700 ] || exit 1
            [ -f $ownerFile ] && [ ! -L $ownerFile ] && [ -r $ownerFile ] || exit 1
            z2_owner_meta=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $ownerFile 2>/dev/null) || exit 1
            IFS=: read -r z2_owner_device z2_owner_inode z2_owner_uid z2_owner_mode z2_owner_links z2_owner_size <<EOF
            ${'$'}z2_owner_meta
            EOF
            [ "${'$'}z2_owner_uid" = 0 ] && [ "${'$'}z2_owner_mode" = 600 ] &&
                [ "${'$'}z2_owner_links" = 1 ] || exit 1
            case "${'$'}z2_owner_size" in ''|*[!0-9]*) exit 1 ;; esac
            [ "${'$'}z2_owner_size" -gt 0 ] &&
                [ "${'$'}z2_owner_size" -le ${OwnerStateSchema.MAX_FILE_BYTES} ] || exit 1
            z2_owner_digest_before=${'$'}(sha256sum $ownerFile 2>/dev/null) || exit 1
            z2_owner_digest_before=${'$'}{z2_owner_digest_before%% *}
            [ "${'$'}{#z2_owner_digest_before}" -eq 64 ] || exit 1
            case "${'$'}z2_owner_digest_before" in *[!0-9A-Fa-f]*) exit 1 ;; esac
            IFS= read -r z2_boot_id < /proc/sys/kernel/random/boot_id || exit 1
            printf '__Z2_CURRENT_BOOT_ID__=%s\n' "${'$'}z2_boot_id"
            while IFS= read -r z2_owner_line || [ -n "${'$'}z2_owner_line" ]; do
                printf '__Z2_OWNER_LINE__=%s\n' "${'$'}z2_owner_line"
            done < $ownerFile
            z2_owner_digest_after=${'$'}(sha256sum $ownerFile 2>/dev/null) || exit 1
            z2_owner_digest_after=${'$'}{z2_owner_digest_after%% *}
            z2_owner_after=${'$'}(stat -c '%d:%i:%u:%a:%h:%s' $ownerFile 2>/dev/null) || exit 1
            [ "${'$'}z2_owner_after" = "${'$'}z2_owner_meta" ] &&
                [ "${'$'}z2_owner_digest_after" = "${'$'}z2_owner_digest_before" ]
            """.trimIndent()
        )
        if (!result.success) return null

        val bootPrefix = "__Z2_CURRENT_BOOT_ID__="
        val ownerPrefix = "__Z2_OWNER_LINE__="
        if (result.stdout.any { !it.startsWith(bootPrefix) && !it.startsWith(ownerPrefix) }) return null
        val bootIds = result.stdout.filter { it.startsWith(bootPrefix) }.map { it.removePrefix(bootPrefix) }
        if (bootIds.size != 1) return null
        val ownerLines = result.stdout.filter { it.startsWith(ownerPrefix) }.map { it.removePrefix(ownerPrefix) }
        val reconciliation = OwnerStateSchema.reconcile(ownerLines, bootIds.single())
        if (!reconciliation.isHealthyCandidate) return null
        val values = reconciliation.values
        val matchesStatus = values["pid"] == detail.ownPid &&
            values["starttime"] == detail.ownPidStarttime &&
            values["generation"] == detail.ownerGeneration &&
            values["qnum"] == detail.qnum.toString() &&
            values["ipv4_active"] == (if (detail.ipv4Active) "1" else "0") &&
            values["ipv6_active"] == (if (detail.ipv6Active) "1" else "0") &&
            values["ipv4_rules"] == detail.ipv4Rules.toString() &&
            values["ipv6_rules"] == detail.ipv6Rules.toString()
        if (!matchesStatus) return null

        val identity = OwnedFirewallIdentity(
            tag = values.getValue("firewall_tag"),
            outChain = values.getValue("out_chain"),
            inChain = values.getValue("in_chain"),
        )
        fun family(prefix: String) = OwnedFirewallFamilyContract(
            identity = identity,
            active = values.getValue("${prefix}_active") == "1",
            qnum = values.getValue("qnum").toInt(),
            portsTcp = values.getValue("ports_tcp"),
            portsUdp = values.getValue("ports_udp"),
            stunPorts = values.getValue("stun_ports"),
            pktOut = values.getValue("pkt_out").toInt(),
            pktIn = values.getValue("pkt_in").toInt(),
            desyncMark = values.getValue("desync_mark"),
            connbytes = values.getValue("${prefix}_connbytes") == "1",
            multiport = values.getValue("${prefix}_multiport") == "1",
            markCapability = values.getValue("${prefix}_mark") == "1",
            expectedRuleCount = values.getValue("${prefix}_rules").toInt(),
        )
        return OwnedFirewallContract(
            ipv4 = family("ipv4"),
            ipv6 = family("ipv6"),
        )
    }

    private suspend fun readOwnedIptablesFamily(
        binary: String,
        contract: OwnedFirewallFamilyContract,
    ): OwnedIptablesFamily {
        val result = ServiceLifecycleController.executeRoot(
            """
            if ! command -v $binary >/dev/null 2>&1; then
                printf '__Z2_AVAILABLE__=0\n'
                exit 0
            fi
            printf '__Z2_AVAILABLE__=1\n'
            $binary -t mangle -S
            """.trimIndent()
        )

        if (!result.success) return OwnedIptablesFamily()
        val availabilityLines = result.stdout.filter { it.startsWith("__Z2_AVAILABLE__=") }
        if (availabilityLines.size != 1 || result.stdout.firstOrNull() != availabilityLines.single()) {
            return OwnedIptablesFamily()
        }
        if (availabilityLines.single() == "__Z2_AVAILABLE__=0") {
            return OwnedIptablesFamily(topologyVerified = contract.expectedRuleCount == 0)
        }
        if (availabilityLines.single() != "__Z2_AVAILABLE__=1") return OwnedIptablesFamily()
        return OwnedIptablesTopologyVerifier.verify(
            lines = result.stdout.drop(1),
            contract = contract,
        )
    }

    /** Projects one authoritative machine-status snapshot into the firewall detail model. */
    internal fun getIptablesDetail(status: ServiceLifecycleController.ServiceStatus): IptablesDetail {
        val expectedChains = (if (status.ipv4Active) 2 else 0) + (if (status.ipv6Active) 2 else 0)
        return IptablesDetail(
            rulesOk = if (status.rulesetVerified) status.nfqueueRulesCount else 0,
            rulesFail = (status.expectedRulesCount - status.nfqueueRulesCount).coerceAtLeast(0),
            rulesTotal = status.nfqueueRulesCount,
            status = status.declaredStatus,
            ownPid = status.pid,
            pidVerified = status.pidVerified,
            ownPidStarttime = status.pidStarttime,
            ownerGeneration = status.ownerGeneration,
            qnum = status.qnum,
            ipv4Active = status.ipv4Active,
            ipv6Active = status.ipv6Active,
            chains = expectedChains,
            anchors = expectedChains,
            nfqueueSupported = status.nfqueueSupported,
            queueBypassSupported = status.queueBypassSupported,
            rulesExpected = status.expectedRulesCount,
            ipv4Rules = status.ipv4RulesCount,
            ipv6Rules = status.ipv6RulesCount,
            rulesetVerified = status.rulesetVerified,
            ownerMetadataVerified = status.ownerMetadataVerified,
            metadataComplete = status.metadataComplete,
        )
    }

    /**
     * Gets all network statistics at once
     * Must be called from IO dispatcher
     */
    suspend fun getNetworkStats(
        status: ServiceLifecycleController.ServiceStatus,
    ): NetworkStats = withContext(Dispatchers.IO) {
        val networkType = getNetworkType()
        val iptablesDetail = getIptablesDetail(status)
        val ownedIptablesState = getOwnedIptablesState(iptablesDetail)
        val observedDetail = iptablesDetail.copy(
            chains = ownedIptablesState.chainCount,
            anchors = ownedIptablesState.anchorCount,
        )

        NetworkStats(
            networkType = networkType,
            iptablesActive = ownedIptablesState.active,
            nfqueueRulesCount = ownedIptablesState.nfqueueRulesCount,
            hasOwnedIptablesState = ownedIptablesState.hasState,
            iptablesDetail = observedDetail
        )
    }

}
