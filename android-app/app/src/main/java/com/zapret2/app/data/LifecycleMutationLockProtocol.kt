package com.zapret2.app.data

/**
 * Cross-process ownership protocol for ordinary Android-side module writes.
 *
 * The record intentionally remains readable by common.sh (pid/starttime/token), while the
 * additional exact fields let Android distinguish its own stale records from foreign lifecycle
 * owners. Unknown or malformed lifecycle locks are never removed by this protocol.
 */
internal object LifecycleMutationLockProtocol {

    private const val VERSION = "1"
    private const val KIND = "android-mutation"
    private const val COMMON_SCRIPT =
        "${ServiceLifecycleController.MODULE_DIR}/zapret2/scripts/common.sh"
    private val safeToken = Regex("[A-Za-z0-9._-]+")
    private val bootId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    data class Lease(
        val pid: String,
        val starttime: String,
        val bootId: String,
        val token: String,
    )

    fun buildAcquireCommand(pid: Int, token: String): String? {
        if (pid <= 0 || !token.matches(safeToken) || token.length > 128) return null
        return """
            module_dir=${RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)}
            common=${RootFileIo.shellQuote(COMMON_SCRIPT)}
            app_pid=${RootFileIo.shellQuote(pid.toString())}
            app_token=${RootFileIo.shellQuote(token)}
            [ -f "${'$'}common" ] && [ ! -L "${'$'}common" ] || exit 1
            SCRIPT_DIR="${'$'}module_dir/zapret2/scripts"
            ZAPRET_DIR="${'$'}module_dir/zapret2"
            MODDIR="${'$'}module_dir"
            LIFECYCLE_LOCK_WAIT_SECONDS=1
            . "${'$'}common" || exit 1
            [ "${'$'}STATE_DIR" = ${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)} ] || exit 1

            z2_read_android_mutation_owner() {
                local key value size links
                z2_owner_version=""; z2_owner_kind=""; z2_owner_pid=""; z2_owner_start=""
                z2_owner_boot=""; z2_owner_token=""; z2_owner_module=""
                z2_owner_order=""
                z2_seen_version=0; z2_seen_kind=0; z2_seen_pid=0; z2_seen_start=0
                z2_seen_boot=0; z2_seen_token=0; z2_seen_module=0
                state_dir_is_secure || return 1
                [ -d "${'$'}LIFECYCLE_LOCK" ] && [ ! -L "${'$'}LIFECYCLE_LOCK" ] || return 1
                path_uid_is_root "${'$'}LIFECYCLE_LOCK" || return 1
                [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK" 2>/dev/null)" = 700 ] || return 1
                [ -f "${'$'}LIFECYCLE_LOCK_OWNER" ] && [ ! -L "${'$'}LIFECYCLE_LOCK_OWNER" ] || return 1
                path_uid_is_root "${'$'}LIFECYCLE_LOCK_OWNER" || return 1
                [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 600 ] || return 1
                links=${'$'}(stat -c %h "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null) || return 1
                [ "${'$'}links" = 1 ] || return 1
                size=${'$'}(stat -c %s "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null) || return 1
                [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le 1024 ] || return 1
                while IFS='=' read -r key value; do
                    case "${'$'}key" in
                        version) [ "${'$'}z2_seen_version" = 0 ] || return 1; z2_owner_version="${'$'}value"; z2_seen_version=1; z2_owner_order="${'$'}{z2_owner_order}v" ;;
                        kind) [ "${'$'}z2_seen_kind" = 0 ] || return 1; z2_owner_kind="${'$'}value"; z2_seen_kind=1; z2_owner_order="${'$'}{z2_owner_order}k" ;;
                        pid) [ "${'$'}z2_seen_pid" = 0 ] || return 1; z2_owner_pid="${'$'}value"; z2_seen_pid=1; z2_owner_order="${'$'}{z2_owner_order}p" ;;
                        starttime) [ "${'$'}z2_seen_start" = 0 ] || return 1; z2_owner_start="${'$'}value"; z2_seen_start=1; z2_owner_order="${'$'}{z2_owner_order}s" ;;
                        boot_id) [ "${'$'}z2_seen_boot" = 0 ] || return 1; z2_owner_boot="${'$'}value"; z2_seen_boot=1; z2_owner_order="${'$'}{z2_owner_order}b" ;;
                        token) [ "${'$'}z2_seen_token" = 0 ] || return 1; z2_owner_token="${'$'}value"; z2_seen_token=1; z2_owner_order="${'$'}{z2_owner_order}t" ;;
                        module_dir) [ "${'$'}z2_seen_module" = 0 ] || return 1; z2_owner_module="${'$'}value"; z2_seen_module=1; z2_owner_order="${'$'}{z2_owner_order}m" ;;
                        *) return 1 ;;
                    esac
                done < "${'$'}LIFECYCLE_LOCK_OWNER"
                [ "${'$'}z2_seen_version:${'$'}z2_seen_kind:${'$'}z2_seen_pid:${'$'}z2_seen_start:${'$'}z2_seen_boot:${'$'}z2_seen_token:${'$'}z2_seen_module" = 1:1:1:1:1:1:1 ] || return 1
                [ "${'$'}z2_owner_order" = vkpsbtm ] || return 1
                [ "${'$'}z2_owner_version" = $VERSION ] && [ "${'$'}z2_owner_kind" = $KIND ] || return 1
                is_decimal "${'$'}z2_owner_pid" && [ "${'$'}z2_owner_pid" -gt 0 ] 2>/dev/null || return 1
                is_canonical_nonnegative_i64 "${'$'}z2_owner_start" || return 1
                is_valid_boot_id "${'$'}z2_owner_boot" || return 1
                is_safe_token "${'$'}z2_owner_token" && [ "${'$'}{#z2_owner_token}" -le 128 ] 2>/dev/null || return 1
                [ "${'$'}z2_owner_module" = "${'$'}module_dir" ]
            }

            z2_android_mutation_owner_state() {
                local actual
                z2_owner_state=ambiguous
                if [ "${'$'}z2_owner_boot" != "${'$'}current_boot" ]; then
                    z2_owner_state=stale
                    return 1
                fi
                if [ ! -e "/proc/${'$'}z2_owner_pid" ]; then
                    z2_owner_state=stale
                    return 1
                fi
                actual=${'$'}(proc_starttime "${'$'}z2_owner_pid") || return 2
                if [ "${'$'}actual" = "${'$'}z2_owner_start" ]; then
                    z2_owner_state=active
                    return 0
                fi
                z2_owner_state=stale
                return 1
            }

            z2_candidate=""
            z2_gate_held=0
            z2_gate_token="android.${'$'}app_token"
            z2_cleanup_acquire() {
                local rc=${'$'}?
                trap - EXIT HUP INT TERM
                if [ -n "${'$'}z2_candidate" ] && [ -d "${'$'}z2_candidate" ] && [ ! -L "${'$'}z2_candidate" ]; then
                    if [ -f "${'$'}z2_candidate/owner" ] && [ ! -L "${'$'}z2_candidate/owner" ]; then
                        rm -f "${'$'}z2_candidate/owner" 2>/dev/null || rc=1
                    fi
                    rmdir "${'$'}z2_candidate" 2>/dev/null || rc=1
                fi
                if [ "${'$'}z2_gate_held" = 1 ]; then
                    release_lifecycle_gate "${'$'}z2_gate_token" >/dev/null 2>&1 || rc=1
                fi
                exit "${'$'}rc"
            }
            trap z2_cleanup_acquire EXIT
            trap 'exit 1' HUP INT TERM

            ensure_state_dir || exit 1
            self_start=${'$'}(proc_starttime ${'$'}${'$'}) || exit 1
            app_start=${'$'}(proc_starttime "${'$'}app_pid") || exit 1
            IFS= read -r current_boot < /proc/sys/kernel/random/boot_id || exit 1
            is_valid_boot_id "${'$'}current_boot" || exit 1
            claim_lifecycle_gate "${'$'}self_start" "${'$'}z2_gate_token" || {
                echo "ERROR: Zapret2 lifecycle is busy; changes were not started" >&2
                exit 1
            }
            z2_gate_held=1

            if [ -e "${'$'}LIFECYCLE_LOCK" ] || [ -L "${'$'}LIFECYCLE_LOCK" ]; then
                z2_read_android_mutation_owner || {
                    echo "ERROR: foreign, malformed, or unknown lifecycle owner was preserved" >&2
                    exit 1
                }
                stale_pid="${'$'}z2_owner_pid"; stale_start="${'$'}z2_owner_start"; stale_boot="${'$'}z2_owner_boot"
                stale_token="${'$'}z2_owner_token"; stale_module="${'$'}z2_owner_module"
                z2_android_mutation_owner_state >/dev/null 2>&1 || true
                [ "${'$'}z2_owner_state" = stale ] || {
                    echo "ERROR: active or ambiguous lifecycle owner blocks mutation" >&2
                    exit 1
                }
                sleep 1
                z2_read_android_mutation_owner || exit 1
                [ "${'$'}z2_owner_pid" = "${'$'}stale_pid" ] &&
                    [ "${'$'}z2_owner_start" = "${'$'}stale_start" ] &&
                    [ "${'$'}z2_owner_boot" = "${'$'}stale_boot" ] &&
                    [ "${'$'}z2_owner_token" = "${'$'}stale_token" ] &&
                    [ "${'$'}z2_owner_module" = "${'$'}stale_module" ] || exit 1
                z2_android_mutation_owner_state >/dev/null 2>&1 || true
                [ "${'$'}z2_owner_state" = stale ] || exit 1
                entries=${'$'}(find "${'$'}LIFECYCLE_LOCK" -mindepth 1 -maxdepth 1 -print 2>/dev/null) || exit 1
                [ "${'$'}entries" = "${'$'}LIFECYCLE_LOCK_OWNER" ] || {
                    echo "ERROR: lifecycle lock contains unknown entries; it was preserved" >&2
                    exit 1
                }
                quarantine="${'$'}LIFECYCLE_LOCK_QUARANTINE.android.${'$'}${'$'}.${'$'}app_token"
                [ ! -e "${'$'}quarantine" ] && [ ! -L "${'$'}quarantine" ] || exit 1
                mv "${'$'}LIFECYCLE_LOCK" "${'$'}quarantine" || exit 1
                rm -f "${'$'}quarantine/owner" || exit 1
                rmdir "${'$'}quarantine" || exit 1
            fi

            z2_candidate="${'$'}LIFECYCLE_LOCK.candidate.android.${'$'}${'$'}.${'$'}app_token"
            [ ! -e "${'$'}z2_candidate" ] && [ ! -L "${'$'}z2_candidate" ] || exit 1
            mkdir "${'$'}z2_candidate" || exit 1
            chmod 0700 "${'$'}z2_candidate" || exit 1
            printf 'version=$VERSION\nkind=$KIND\npid=%s\nstarttime=%s\nboot_id=%s\ntoken=%s\nmodule_dir=%s\n' \
                "${'$'}app_pid" "${'$'}app_start" "${'$'}current_boot" "${'$'}app_token" "${'$'}module_dir" > "${'$'}z2_candidate/owner" || exit 1
            chmod 0600 "${'$'}z2_candidate/owner" || exit 1
            [ "${'$'}(stat -c %u "${'$'}z2_candidate" 2>/dev/null)" = 0 ] || exit 1
            [ "${'$'}(stat -c %u "${'$'}z2_candidate/owner" 2>/dev/null)" = 0 ] || exit 1
            [ "${'$'}(stat -c %h "${'$'}z2_candidate/owner" 2>/dev/null)" = 1 ] || exit 1
            candidate_size=${'$'}(stat -c %s "${'$'}z2_candidate/owner" 2>/dev/null) || exit 1
            [ "${'$'}candidate_size" -gt 0 ] && [ "${'$'}candidate_size" -le 1024 ] || exit 1
            candidate_entries=${'$'}(find "${'$'}z2_candidate" -mindepth 1 -maxdepth 1 -print 2>/dev/null) || exit 1
            [ "${'$'}candidate_entries" = "${'$'}z2_candidate/owner" ] || exit 1
            app_start_after=${'$'}(proc_starttime "${'$'}app_pid") || exit 1
            IFS= read -r boot_after < /proc/sys/kernel/random/boot_id || exit 1
            [ "${'$'}app_start_after" = "${'$'}app_start" ] && [ "${'$'}boot_after" = "${'$'}current_boot" ] || exit 1
            [ ! -e "${'$'}LIFECYCLE_LOCK" ] && [ ! -L "${'$'}LIFECYCLE_LOCK" ] || exit 1
            mv "${'$'}z2_candidate" "${'$'}LIFECYCLE_LOCK" || exit 1
            z2_candidate=""

            release_lifecycle_gate "${'$'}z2_gate_token" >/dev/null 2>&1 || true
            z2_gate_held=0
            trap - EXIT HUP INT TERM
            echo "Z2_MUTATION_LOCK_PID=${'$'}app_pid"
            echo "Z2_MUTATION_LOCK_START=${'$'}app_start"
            echo "Z2_MUTATION_LOCK_BOOT=${'$'}current_boot"
            echo "Z2_MUTATION_LOCK_TOKEN=${'$'}app_token"
            echo Z2_MUTATION_LOCK_COMPLETE=1
        """.trimIndent()
    }

    fun parseAcquireOutput(lines: List<String>, pid: Int, token: String): Lease? {
        if (pid <= 0 || !token.matches(safeToken) || token.length > 128) return null
        val protocol = lines.map(String::trim)
        if (protocol.size != 5 || protocol.lastOrNull() != "Z2_MUTATION_LOCK_COMPLETE=1") return null
        val pairs = protocol.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val keys = setOf(
            "Z2_MUTATION_LOCK_PID",
            "Z2_MUTATION_LOCK_START",
            "Z2_MUTATION_LOCK_BOOT",
            "Z2_MUTATION_LOCK_TOKEN",
            "Z2_MUTATION_LOCK_COMPLETE",
        )
        val counts = pairs.groupingBy { it.first }.eachCount()
        val values = pairs.toMap()
        if (counts.keys != keys || keys.any { counts[it] != 1 } ||
            values["Z2_MUTATION_LOCK_COMPLETE"] != "1" ||
            values["Z2_MUTATION_LOCK_PID"] != pid.toString() ||
            values["Z2_MUTATION_LOCK_TOKEN"] != token ||
            !ProtocolDecimal.isCanonicalNonNegativeLong(values["Z2_MUTATION_LOCK_START"].orEmpty()) ||
            !values["Z2_MUTATION_LOCK_BOOT"].orEmpty().matches(bootId)
        ) return null
        return Lease(
            pid = pid.toString(),
            starttime = values.getValue("Z2_MUTATION_LOCK_START"),
            bootId = values.getValue("Z2_MUTATION_LOCK_BOOT"),
            token = token,
        )
    }

    /** Probes only the exact record this app may have published after an ambiguous command result. */
    fun buildOwnedLeaseProbeCommand(pid: Int, token: String): String? {
        if (pid <= 0 || !token.matches(safeToken) || token.length > 128) return null
        return """
            module_dir=${RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)}
            common=${RootFileIo.shellQuote(COMMON_SCRIPT)}
            expected_pid=${RootFileIo.shellQuote(pid.toString())}
            expected_token=${RootFileIo.shellQuote(token)}
            [ -f "${'$'}common" ] && [ ! -L "${'$'}common" ] || exit 1
            SCRIPT_DIR="${'$'}module_dir/zapret2/scripts"
            ZAPRET_DIR="${'$'}module_dir/zapret2"
            MODDIR="${'$'}module_dir"
            . "${'$'}common" || exit 1
            [ "${'$'}STATE_DIR" = ${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)} ] || exit 1
            state_dir_is_secure || exit 1
            [ -d "${'$'}LIFECYCLE_LOCK" ] && [ ! -L "${'$'}LIFECYCLE_LOCK" ] || exit 1
            path_uid_is_root "${'$'}LIFECYCLE_LOCK" || exit 1
            [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK" 2>/dev/null)" = 700 ] || exit 1
            [ -f "${'$'}LIFECYCLE_LOCK_OWNER" ] && [ ! -L "${'$'}LIFECYCLE_LOCK_OWNER" ] || exit 1
            path_uid_is_root "${'$'}LIFECYCLE_LOCK_OWNER" || exit 1
            [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 600 ] || exit 1
            [ "${'$'}(stat -c %h "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 1 ] || exit 1
            [ "${'$'}(wc -l < "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 7 ] || exit 1
            before=${'$'}(sha256sum "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }') || exit 1
            {
                IFS= read -r version_line || exit 1
                IFS= read -r kind_line || exit 1
                IFS= read -r pid_line || exit 1
                IFS= read -r start_line || exit 1
                IFS= read -r boot_line || exit 1
                IFS= read -r token_line || exit 1
                IFS= read -r module_line || exit 1
            } < "${'$'}LIFECYCLE_LOCK_OWNER"
            [ "${'$'}version_line" = 'version=$VERSION' ] && [ "${'$'}kind_line" = 'kind=$KIND' ] || exit 1
            [ "${'$'}pid_line" = "pid=${'$'}expected_pid" ] && [ "${'$'}token_line" = "token=${'$'}expected_token" ] || exit 1
            [ "${'$'}module_line" = "module_dir=${'$'}module_dir" ] || exit 1
            start=${'$'}{start_line#starttime=}; boot=${'$'}{boot_line#boot_id=}
            [ "${'$'}start_line" = "starttime=${'$'}start" ] && [ "${'$'}boot_line" = "boot_id=${'$'}boot" ] || exit 1
            is_canonical_nonnegative_i64 "${'$'}start" && is_valid_boot_id "${'$'}boot" || exit 1
            IFS= read -r current_boot < /proc/sys/kernel/random/boot_id || exit 1
            [ "${'$'}current_boot" = "${'$'}boot" ] || exit 1
            actual=${'$'}(proc_starttime "${'$'}expected_pid") || exit 1
            [ "${'$'}actual" = "${'$'}start" ] || exit 1
            entries=${'$'}(find "${'$'}LIFECYCLE_LOCK" -mindepth 1 -maxdepth 1 -print 2>/dev/null) || exit 1
            [ "${'$'}entries" = "${'$'}LIFECYCLE_LOCK_OWNER" ] || exit 1
            after=${'$'}(sha256sum "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null | awk 'NR == 1 { print ${'$'}1 }') || exit 1
            [ "${'$'}after" = "${'$'}before" ] || exit 1
            echo "Z2_MUTATION_LOCK_PID=${'$'}expected_pid"
            echo "Z2_MUTATION_LOCK_START=${'$'}start"
            echo "Z2_MUTATION_LOCK_BOOT=${'$'}boot"
            echo "Z2_MUTATION_LOCK_TOKEN=${'$'}expected_token"
            echo Z2_MUTATION_LOCK_COMPLETE=1
        """.trimIndent()
    }

    fun buildReleaseCommand(lease: Lease, releaseToken: String): String? {
        if (!isValid(lease) || !releaseToken.matches(safeToken) || releaseToken.length > 128) return null
        return """
            module_dir=${RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)}
            common=${RootFileIo.shellQuote(COMMON_SCRIPT)}
            [ -f "${'$'}common" ] && [ ! -L "${'$'}common" ] || exit 1
            SCRIPT_DIR="${'$'}module_dir/zapret2/scripts"
            ZAPRET_DIR="${'$'}module_dir/zapret2"
            MODDIR="${'$'}module_dir"
            LIFECYCLE_LOCK_WAIT_SECONDS=1
            . "${'$'}common" || exit 1
            [ "${'$'}STATE_DIR" = ${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)} ] || exit 1
            expected_pid=${RootFileIo.shellQuote(lease.pid)}
            expected_start=${RootFileIo.shellQuote(lease.starttime)}
            expected_boot=${RootFileIo.shellQuote(lease.bootId)}
            expected_token=${RootFileIo.shellQuote(lease.token)}
            gate_token=${RootFileIo.shellQuote("android-release.$releaseToken")}

            z2_read_exact_owner() {
                local key value size entries version kind pid start boot token module owner_order
                local seen_version seen_kind seen_pid seen_start seen_boot seen_token seen_module
                version=""; kind=""; pid=""; start=""; boot=""; token=""; module=""
                owner_order=""
                seen_version=0; seen_kind=0; seen_pid=0; seen_start=0; seen_boot=0; seen_token=0; seen_module=0
                state_dir_is_secure || return 1
                [ -d "${'$'}LIFECYCLE_LOCK" ] && [ ! -L "${'$'}LIFECYCLE_LOCK" ] || return 1
                path_uid_is_root "${'$'}LIFECYCLE_LOCK" || return 1
                [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK" 2>/dev/null)" = 700 ] || return 1
                [ -f "${'$'}LIFECYCLE_LOCK_OWNER" ] && [ ! -L "${'$'}LIFECYCLE_LOCK_OWNER" ] || return 1
                path_uid_is_root "${'$'}LIFECYCLE_LOCK_OWNER" || return 1
                [ "${'$'}(stat -c %a "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 600 ] || return 1
                [ "${'$'}(stat -c %h "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null)" = 1 ] || return 1
                size=${'$'}(stat -c %s "${'$'}LIFECYCLE_LOCK_OWNER" 2>/dev/null) || return 1
                [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le 1024 ] || return 1
                while IFS='=' read -r key value; do
                    case "${'$'}key" in
                        version) [ "${'$'}seen_version" = 0 ] || return 1; version="${'$'}value"; seen_version=1; owner_order="${'$'}{owner_order}v" ;;
                        kind) [ "${'$'}seen_kind" = 0 ] || return 1; kind="${'$'}value"; seen_kind=1; owner_order="${'$'}{owner_order}k" ;;
                        pid) [ "${'$'}seen_pid" = 0 ] || return 1; pid="${'$'}value"; seen_pid=1; owner_order="${'$'}{owner_order}p" ;;
                        starttime) [ "${'$'}seen_start" = 0 ] || return 1; start="${'$'}value"; seen_start=1; owner_order="${'$'}{owner_order}s" ;;
                        boot_id) [ "${'$'}seen_boot" = 0 ] || return 1; boot="${'$'}value"; seen_boot=1; owner_order="${'$'}{owner_order}b" ;;
                        token) [ "${'$'}seen_token" = 0 ] || return 1; token="${'$'}value"; seen_token=1; owner_order="${'$'}{owner_order}t" ;;
                        module_dir) [ "${'$'}seen_module" = 0 ] || return 1; module="${'$'}value"; seen_module=1; owner_order="${'$'}{owner_order}m" ;;
                        *) return 1 ;;
                    esac
                done < "${'$'}LIFECYCLE_LOCK_OWNER"
                [ "${'$'}seen_version:${'$'}seen_kind:${'$'}seen_pid:${'$'}seen_start:${'$'}seen_boot:${'$'}seen_token:${'$'}seen_module" = 1:1:1:1:1:1:1 ] || return 1
                [ "${'$'}owner_order" = vkpsbtm ] || return 1
                [ "${'$'}version" = $VERSION ] && [ "${'$'}kind" = $KIND ] && [ "${'$'}module" = "${'$'}module_dir" ] || return 1
                [ "${'$'}pid" = "${'$'}expected_pid" ] && [ "${'$'}start" = "${'$'}expected_start" ] &&
                    [ "${'$'}boot" = "${'$'}expected_boot" ] && [ "${'$'}token" = "${'$'}expected_token" ] || return 1
                entries=${'$'}(find "${'$'}LIFECYCLE_LOCK" -mindepth 1 -maxdepth 1 -print 2>/dev/null) || return 1
                [ "${'$'}entries" = "${'$'}LIFECYCLE_LOCK_OWNER" ]
            }

            gate_held=0
            z2_cleanup_release() {
                local rc=${'$'}?
                trap - EXIT HUP INT TERM
                if [ "${'$'}gate_held" = 1 ]; then
                    release_lifecycle_gate "${'$'}gate_token" >/dev/null 2>&1 || rc=1
                fi
                exit "${'$'}rc"
            }
            trap z2_cleanup_release EXIT
            trap 'exit 1' HUP INT TERM
            self_start=${'$'}(proc_starttime ${'$'}${'$'}) || exit 1
            attempts=0
            while [ "${'$'}attempts" -lt 3 ]; do
                claim_lifecycle_gate "${'$'}self_start" "${'$'}gate_token" && break
                attempts=${'$'}((attempts + 1))
                sleep 1
            done
            [ "${'$'}attempts" -lt 3 ] || exit 1
            gate_held=1
            z2_read_exact_owner || {
                release_lifecycle_gate "${'$'}gate_token" >/dev/null 2>&1 || true
                gate_held=0
                echo "ERROR: lifecycle ownership changed; foreign evidence was preserved" >&2
                exit 1
            }
            quarantine="${'$'}LIFECYCLE_LOCK_QUARANTINE.release.android.${'$'}${'$'}.${'$'}gate_token"
            [ ! -e "${'$'}quarantine" ] && [ ! -L "${'$'}quarantine" ] || exit 1
            mv "${'$'}LIFECYCLE_LOCK" "${'$'}quarantine" || exit 1
            rm -f "${'$'}quarantine/owner" || exit 1
            rmdir "${'$'}quarantine" || exit 1
            release_lifecycle_gate "${'$'}gate_token" >/dev/null 2>&1 || true
            gate_held=0
            trap - EXIT HUP INT TERM
            echo Z2_MUTATION_LOCK_RELEASED=1
        """.trimIndent()
    }

    fun parseReleaseOutput(lines: List<String>): Boolean {
        if (lines.size != 1) return false
        return lines.single().trim() == "Z2_MUTATION_LOCK_RELEASED=1"
    }

    private fun isValid(lease: Lease): Boolean {
        return lease.pid.matches(Regex("[1-9][0-9]*")) &&
            ProtocolDecimal.isCanonicalNonNegativeLong(lease.starttime) &&
            lease.bootId.matches(bootId) &&
            lease.token.matches(safeToken) && lease.token.length <= 128
    }
}
