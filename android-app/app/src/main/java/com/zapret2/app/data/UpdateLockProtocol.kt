package com.zapret2.app.data

/** Exact Android-side implementation of the boot-bound update.lock protocol shared with common.sh. */
internal object UpdateLockProtocol {

    const val VERSION = "2"
    const val LEGACY_VERSION = "1"
    const val REAPER = "${ModuleMutationCoordinator.STATE_DIR}/update.lock.reaper"
    const val QUARANTINE_PREFIX = "${ModuleMutationCoordinator.STATE_DIR}/update.lock.quarantine"
    const val UPDATE_GUARD = "${ServiceLifecycleController.MODULE_DIR}/zapret2/scripts/zapret-update-guard.sh"

    internal val fields = setOf(
        "version",
        "pid",
        "starttime",
        "created_epoch",
        "boot_id",
        "token",
        "module_dir",
    )

    data class Record(
        val pid: String,
        val starttime: String,
        val createdEpoch: String,
        val bootId: String,
        val token: String,
    )

    enum class State {
        ABSENT,
        ACTIVE,
        STALE,
        MALFORMED,
    }

    fun parse(lines: List<String>): Record? {
        if (lines.size != fields.size) return null
        val pairs = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val counts = pairs.groupingBy { it.first }.eachCount()
        if (counts.keys != fields || fields.any { counts[it] != 1 }) return null
        val values = pairs.toMap()
        val token = Regex("[A-Za-z0-9._-]+")
        if (values["version"] != VERSION ||
            values["pid"]?.matches(Regex("[1-9][0-9]*")) != true ||
            values["pid"]?.toIntOrNull() == null ||
            ProtocolDecimal.isCanonicalNonNegativeLong(values["starttime"].orEmpty()).not() ||
            ProtocolDecimal.isCanonicalNonNegativeLong(values["created_epoch"].orEmpty()).not() ||
            isValidBootId(values["boot_id"].orEmpty()).not() ||
            values["token"]?.matches(token) != true ||
            values["module_dir"] != ServiceLifecycleController.MODULE_DIR
        ) return null
        return Record(
            pid = values.getValue("pid"),
            starttime = values.getValue("starttime"),
            createdEpoch = values.getValue("created_epoch"),
            bootId = values.getValue("boot_id"),
            token = values.getValue("token"),
        )
    }

    private fun isValidBootId(value: String): Boolean =
        value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    /** Defines z2_read_update_lock and z2_update_lock_owner_alive in the generated root shell. */
    fun shellParser(): String = """
        z2_valid_nonnegative_i64() {
            local value="${'$'}1" digits first rest
            case "${'$'}value" in ''|*[!0-9]*|0?*) return 1 ;; esac
            digits=${'$'}{#value}
            [ "${'$'}digits" -lt 19 ] && return 0
            [ "${'$'}digits" -eq 19 ] || return 1
            first=${'$'}{value%"${'$'}{value#?}"}
            rest=${'$'}{value#?}
            [ "${'$'}first" -lt 9 ] && return 0
            [ "${'$'}first" -eq 9 ] && [ "${'$'}rest" -le 223372036854775807 ] 2>/dev/null
        }
        z2_read_update_lock() {
            local path="${'$'}1" key value version=""
            local seen_version=0 seen_pid=0 seen_start=0 seen_created=0 seen_boot=0 seen_token=0 seen_module=0
            z2_lock_pid=""; z2_lock_start=""; z2_lock_created=""; z2_lock_boot=""; z2_lock_token=""; z2_lock_module=""; z2_lock_legacy=0
            [ "${'$'}path" = "${'$'}lock" ] || return 1
            [ -d "${'$'}state_dir" ] && [ ! -L "${'$'}state_dir" ] || return 1
            [ "${'$'}(stat -c %u "${'$'}state_dir" 2>/dev/null)" = 0 ] || return 1
            [ "${'$'}(stat -c %a "${'$'}state_dir" 2>/dev/null)" = 700 ] || return 1
            [ -f "${'$'}path" ] && [ ! -L "${'$'}path" ] && [ -r "${'$'}path" ] || return 1
            [ "${'$'}(stat -c %u "${'$'}path" 2>/dev/null)" = 0 ] || return 1
            [ "${'$'}(stat -c %a "${'$'}path" 2>/dev/null)" = 600 ] || return 1
            [ "${'$'}(stat -c %h "${'$'}path" 2>/dev/null)" = 1 ] || return 1
            z2_lock_size=${'$'}(stat -c %s "${'$'}path" 2>/dev/null) || return 1
            [ "${'$'}z2_lock_size" -gt 0 ] && [ "${'$'}z2_lock_size" -le 1024 ] || return 1
            while IFS='=' read -r key value; do
                case "${'$'}key" in
                    version) [ "${'$'}seen_version" = 0 ] || return 1; version="${'$'}value"; seen_version=1 ;;
                    pid) [ "${'$'}seen_pid" = 0 ] || return 1; z2_lock_pid="${'$'}value"; seen_pid=1 ;;
                    starttime) [ "${'$'}seen_start" = 0 ] || return 1; z2_lock_start="${'$'}value"; seen_start=1 ;;
                    created_epoch) [ "${'$'}seen_created" = 0 ] || return 1; z2_lock_created="${'$'}value"; seen_created=1 ;;
                    boot_id) [ "${'$'}seen_boot" = 0 ] || return 1; z2_lock_boot="${'$'}value"; seen_boot=1 ;;
                    token) [ "${'$'}seen_token" = 0 ] || return 1; z2_lock_token="${'$'}value"; seen_token=1 ;;
                    module_dir) [ "${'$'}seen_module" = 0 ] || return 1; z2_lock_module="${'$'}value"; seen_module=1 ;;
                    *) return 1 ;;
                esac
            done < "${'$'}path"
            case "${'$'}version:${'$'}seen_version:${'$'}seen_pid:${'$'}seen_start:${'$'}seen_created:${'$'}seen_boot:${'$'}seen_token:${'$'}seen_module" in
                $VERSION:1:1:1:1:1:1:1) ;;
                $LEGACY_VERSION:1:1:1:1:0:1:1) z2_lock_legacy=1 ;;
                *) return 1 ;;
            esac
            case "${'$'}z2_lock_pid" in ''|0*|*[!0-9]*) return 1 ;; esac
            case "${'$'}z2_lock_start" in ''|*[!0-9]*) return 1 ;; esac
            case "${'$'}z2_lock_created" in ''|*[!0-9]*) return 1 ;; esac
            z2_valid_nonnegative_i64 "${'$'}z2_lock_start" || return 1
            z2_valid_nonnegative_i64 "${'$'}z2_lock_created" || return 1
            if [ "${'$'}z2_lock_legacy" = 0 ]; then
                case "${'$'}z2_lock_boot" in
                    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
                    *) return 1 ;;
                esac
            fi
            case "${'$'}z2_lock_token" in ''|*[!A-Za-z0-9._-]*) return 1 ;; esac
            [ "${'$'}z2_lock_module" = "${ServiceLifecycleController.MODULE_DIR}" ] || return 1
        }
        z2_update_lock_owner_state() {
            local stat_line stat_tail actual_start current_boot
            z2_lock_owner_state=ambiguous
            [ "${'$'}z2_lock_legacy" = 0 ] || return 2
            IFS= read -r current_boot < /proc/sys/kernel/random/boot_id 2>/dev/null || return 2
            if [ "${'$'}current_boot" != "${'$'}z2_lock_boot" ]; then z2_lock_owner_state=stale; return 1; fi
            if [ ! -e "/proc/${'$'}z2_lock_pid" ]; then z2_lock_owner_state=stale; return 1; fi
            stat_line=${'$'}(cat "/proc/${'$'}z2_lock_pid/stat" 2>/dev/null) || return 2
            [ -n "${'$'}stat_line" ] || return 2
            stat_tail=${'$'}{stat_line##*) }
            set -- ${'$'}stat_tail
            actual_start="${'$'}{20}"
            [ -n "${'$'}actual_start" ] || return 2
            if [ "${'$'}actual_start" = "${'$'}z2_lock_start" ]; then z2_lock_owner_state=active; return 0; fi
            z2_lock_owner_state=stale
            return 1
        }
        z2_update_lock_owner_alive() {
            z2_update_lock_owner_state
            [ "${'$'}z2_lock_owner_state" = active ]
        }
    """.trimIndent()

    suspend fun inspect(): State {
        val command = """
            state_dir=${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)}
            lock=${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)}
            ${shellParser()}
            if [ ! -e "${'$'}lock" ] && [ ! -L "${'$'}lock" ]; then
                echo Z2_LOCK_STATE=absent
            elif ! z2_read_update_lock "${'$'}lock"; then
                echo Z2_LOCK_STATE=malformed
            else
                z2_update_lock_owner_state >/dev/null 2>&1 || true
                if [ "${'$'}z2_lock_owner_state" = active ]; then
                echo Z2_LOCK_STATE=active
                elif [ "${'$'}z2_lock_owner_state" = stale ]; then
                echo Z2_LOCK_STATE=stale
                else
                echo Z2_LOCK_STATE=malformed
                fi
            fi
        """.trimIndent()
        val result = ServiceLifecycleController.executeRoot(command)
        if (!result.success) return State.MALFORMED
        val records = result.stdout.map(String::trim).filter { it.startsWith("Z2_LOCK_STATE=") }
        if (records.size != 1) return State.MALFORMED
        return when (records.single().substringAfter('=')) {
            "absent" -> State.ABSENT
            "active" -> State.ACTIVE
            "stale" -> State.STALE
            else -> State.MALFORMED
        }
    }

    /** Normal updates must acquire ownership through the shell lifecycle lock. */
    suspend fun acquireForUpdate(pid: Int, token: String): Result<Record> {
        val command = buildGuardAcquireCommand(pid, token)
            ?: return Result.failure(IllegalArgumentException("Invalid update-lock owner metadata"))
        val result = ServiceLifecycleController.executeRoot(command)
        if (!result.success) {
            return Result.failure(
                IllegalStateException(result.diagnosticText().ifBlank { "Unable to acquire serialized update marker" })
            )
        }
        return parseAcquireOutput(result.stdout, pid, token)
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Update guard returned invalid owner metadata"))
    }

    internal fun buildGuardAcquireCommand(pid: Int, token: String): String? {
        if (pid <= 0 || !token.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return "/system/bin/sh ${RootFileIo.shellQuote(UPDATE_GUARD)} acquire " +
            "--pid $pid --token ${RootFileIo.shellQuote(token)} " +
            "--module-dir ${RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)}"
    }

    suspend fun acquire(
        pid: Int,
        token: String,
        requireTransaction: Boolean,
    ): Result<Record> {
        if (!token.matches(Regex("[A-Za-z0-9._-]+"))) {
            return Result.failure(IllegalArgumentException("Invalid update-lock token"))
        }
        val tempPath = "${ModuleMutationCoordinator.STATE_DIR}/.update.lock.$pid.$token"
        val transactionGate = if (requireTransaction) {
            """
                [ -f "${'$'}transaction" ] && [ ! -L "${'$'}transaction" ] &&
                    [ "${'$'}(stat -c %u "${'$'}transaction" 2>/dev/null)" = 0 ] &&
                    [ "${'$'}(stat -c %a "${'$'}transaction" 2>/dev/null)" = 600 ] &&
                    [ "${'$'}(stat -c %h "${'$'}transaction" 2>/dev/null)" = 1 ] &&
                    transaction_size=${'$'}(stat -c %s "${'$'}transaction" 2>/dev/null) &&
                    [ "${'$'}transaction_size" -gt 0 ] && [ "${'$'}transaction_size" -le 4096 ] || {
                    echo "Interrupted update transaction is not a secure regular state file" >&2
                    exit 1
                }
            """.trimIndent()
        } else {
            """
                if [ -e "${'$'}transaction" ] || [ -L "${'$'}transaction" ]; then
                    echo "An update transaction already requires recovery" >&2
                    exit 1
                fi
            """.trimIndent()
        }
        val command = """
            state_dir=${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)}
            lock=${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)}
            transaction=${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_TRANSACTION)}
            reaper=${RootFileIo.shellQuote(REAPER)}
            quarantine_prefix=${RootFileIo.shellQuote(QUARANTINE_PREFIX)}
            tmp=${RootFileIo.shellQuote(tempPath)}
            if [ ! -e "${'$'}state_dir" ] && [ ! -L "${'$'}state_dir" ]; then
                umask 077
                mkdir "${'$'}state_dir" || exit 1
                chmod 0700 "${'$'}state_dir" || exit 1
            fi
            [ -d "${'$'}state_dir" ] && [ ! -L "${'$'}state_dir" ] || exit 1
            [ "${'$'}(stat -c %u "${'$'}state_dir" 2>/dev/null)" = 0 ] || exit 1
            [ "${'$'}(stat -c %a "${'$'}state_dir" 2>/dev/null)" = 700 ] || exit 1
            $transactionGate
            ${shellParser()}
            if [ -e "${'$'}lock" ] || [ -L "${'$'}lock" ]; then
                if ! z2_read_update_lock "${'$'}lock"; then
                    echo "Malformed Zapret2 update lock requires manual repair; it was preserved: ${'$'}lock" >&2
                    exit 1
                fi
                if [ "${'$'}z2_lock_legacy" = 1 ]; then
                    echo "Legacy bootless Zapret2 update lock was preserved for authoritative manual recovery: ${'$'}lock" >&2
                    exit 1
                fi
                z2_update_lock_owner_state >/dev/null 2>&1 || true
                if [ "${'$'}z2_lock_owner_state" = active ]; then
                    echo "Active Zapret2 update marker: pid=${'$'}z2_lock_pid created_epoch=${'$'}z2_lock_created" >&2
                    exit 1
                fi
                if [ "${'$'}z2_lock_owner_state" != stale ]; then
                    echo "Zapret2 update-lock boot/process ownership is ambiguous; evidence was preserved" >&2
                    exit 1
                fi
                stale_pid="${'$'}z2_lock_pid"; stale_start="${'$'}z2_lock_start"
                stale_created="${'$'}z2_lock_created"; stale_boot="${'$'}z2_lock_boot"; stale_legacy="${'$'}z2_lock_legacy"
                stale_token="${'$'}z2_lock_token"; stale_module="${'$'}z2_lock_module"
                if [ -e "${'$'}reaper" ] || [ -L "${'$'}reaper" ]; then
                    sleep 1
                    if z2_read_update_lock "${'$'}lock"; then z2_update_lock_owner_state >/dev/null 2>&1 || true; fi
                    if [ "${'$'}z2_lock_owner_state" = stale ] &&
                       [ "${'$'}z2_lock_pid" = "${'$'}stale_pid" ] && [ "${'$'}z2_lock_start" = "${'$'}stale_start" ] &&
                       [ "${'$'}z2_lock_created" = "${'$'}stale_created" ] && [ "${'$'}z2_lock_token" = "${'$'}stale_token" ] &&
                       [ "${'$'}z2_lock_boot" = "${'$'}stale_boot" ] && [ "${'$'}z2_lock_legacy" = "${'$'}stale_legacy" ] &&
                       [ "${'$'}z2_lock_module" = "${'$'}stale_module" ]; then
                        rmdir "${'$'}reaper" 2>/dev/null || { echo "Stale update-lock reaper requires repair" >&2; exit 1; }
                    else
                        echo "Update lock changed while stale recovery was pending" >&2
                        exit 1
                    fi
                fi
                mkdir "${'$'}reaper" 2>/dev/null || { echo "Cannot claim stale update-lock recovery" >&2; exit 1; }
                if z2_read_update_lock "${'$'}lock"; then z2_update_lock_owner_state >/dev/null 2>&1 || true; else z2_lock_owner_state=ambiguous; fi
                if [ "${'$'}z2_lock_owner_state" != stale ] ||
                   [ "${'$'}z2_lock_pid" != "${'$'}stale_pid" ] || [ "${'$'}z2_lock_start" != "${'$'}stale_start" ] ||
                   [ "${'$'}z2_lock_created" != "${'$'}stale_created" ] || [ "${'$'}z2_lock_token" != "${'$'}stale_token" ] ||
                   [ "${'$'}z2_lock_boot" != "${'$'}stale_boot" ] || [ "${'$'}z2_lock_legacy" != "${'$'}stale_legacy" ] ||
                   [ "${'$'}z2_lock_module" != "${'$'}stale_module" ]; then
                    rmdir "${'$'}reaper" 2>/dev/null
                    echo "Update lock changed during stale recovery" >&2
                    exit 1
                fi
                quarantine="${'$'}quarantine_prefix.${'$'}${'$'}.${'$'}stale_token"
                if [ -e "${'$'}quarantine" ] || [ -L "${'$'}quarantine" ] || ! mv "${'$'}lock" "${'$'}quarantine"; then
                    rmdir "${'$'}reaper" 2>/dev/null
                    echo "Cannot atomically quarantine stale update lock" >&2
                    exit 1
                fi
                rm -f "${'$'}quarantine" || { rmdir "${'$'}reaper" 2>/dev/null; exit 1; }
            fi
            stat_line=${'$'}(cat /proc/$pid/stat 2>/dev/null) || exit 1
            stat_tail=${'$'}{stat_line##*) }
            set -- ${'$'}stat_tail
            start="${'$'}{20}"
            z2_valid_nonnegative_i64 "${'$'}start" || exit 1
            created=${'$'}(date +%s 2>/dev/null) || exit 1
            z2_valid_nonnegative_i64 "${'$'}created" || exit 1
            IFS= read -r boot < /proc/sys/kernel/random/boot_id 2>/dev/null || exit 1
            case "${'$'}boot" in
                [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
                *) exit 1 ;;
            esac
            umask 077
            rm -f "${'$'}tmp"
            printf 'version=$VERSION\npid=%s\nstarttime=%s\ncreated_epoch=%s\nboot_id=%s\ntoken=%s\nmodule_dir=%s\n' \
                '$pid' "${'$'}start" "${'$'}created" "${'$'}boot" '$token' '${ServiceLifecycleController.MODULE_DIR}' > "${'$'}tmp" || exit 1
            chmod 0600 "${'$'}tmp" || { rm -f "${'$'}tmp"; exit 1; }
            ln "${'$'}tmp" "${'$'}lock" 2>/dev/null || { rm -f "${'$'}tmp"; rmdir "${'$'}reaper" 2>/dev/null; exit 1; }
            rm -f "${'$'}tmp"
            if [ -d "${'$'}reaper" ] && [ ! -L "${'$'}reaper" ]; then rmdir "${'$'}reaper" || exit 1; fi
            sync || exit 1
            echo "Z2_LOCK_START=${'$'}start"
            echo "Z2_LOCK_CREATED=${'$'}created"
            echo "Z2_LOCK_BOOT=${'$'}boot"
            echo Z2_LOCK_COMPLETE=1
        """.trimIndent()
        val result = ServiceLifecycleController.executeRoot(command)
        if (!result.success) {
            return Result.failure(
                IllegalStateException(result.diagnosticText().ifBlank { "Unable to acquire update marker" })
            )
        }
        return parseAcquireOutput(result.stdout, pid, token)
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Update marker returned invalid owner metadata"))
    }

    internal fun parseAcquireOutput(lines: List<String>, pid: Int, token: String): Record? {
        if (pid <= 0 || !token.matches(Regex("[A-Za-z0-9._-]+"))) return null
        val protocol = lines.map(String::trim).filter { it.startsWith("Z2_LOCK_") }
        if (protocol.size != 4 || protocol.lastOrNull() != "Z2_LOCK_COMPLETE=1") {
            return null
        }
        val pairs = protocol.mapNotNull {
            val separator = it.indexOf('=')
            if (separator <= 0) null else it.substring(0, separator) to it.substring(separator + 1)
        }
        val values = pairs.toMap()
        val counts = pairs.groupingBy { it.first }.eachCount()
        val keys = setOf("Z2_LOCK_START", "Z2_LOCK_CREATED", "Z2_LOCK_BOOT", "Z2_LOCK_COMPLETE")
        if (counts.keys != keys || keys.any { counts[it] != 1 } || values["Z2_LOCK_COMPLETE"] != "1") {
            return null
        }
        val start = values["Z2_LOCK_START"].orEmpty()
            .takeIf(ProtocolDecimal::isCanonicalNonNegativeLong)
            ?: return null
        val created = values["Z2_LOCK_CREATED"].orEmpty()
            .takeIf(ProtocolDecimal::isCanonicalNonNegativeLong)
            ?: return null
        val boot = values["Z2_LOCK_BOOT"].orEmpty().takeIf(::isValidBootId) ?: return null
        return Record(pid.toString(), start, created, boot, token)
    }

    suspend fun release(record: Record): ServiceLifecycleController.CommandResult {
        val command = """
            state_dir=${RootFileIo.shellQuote(ModuleMutationCoordinator.STATE_DIR)}
            lock=${RootFileIo.shellQuote(ModuleMutationCoordinator.UPDATE_LOCK)}
            quarantine=${RootFileIo.shellQuote("$QUARANTINE_PREFIX.release.${record.pid}.${record.token}")}
            ${shellParser()}
            z2_read_update_lock "${'$'}lock" || exit 1
            [ "${'$'}z2_lock_pid" = ${RootFileIo.shellQuote(record.pid)} ] &&
            [ "${'$'}z2_lock_start" = ${RootFileIo.shellQuote(record.starttime)} ] &&
            [ "${'$'}z2_lock_created" = ${RootFileIo.shellQuote(record.createdEpoch)} ] &&
            [ "${'$'}z2_lock_boot" = ${RootFileIo.shellQuote(record.bootId)} ] &&
            [ "${'$'}z2_lock_token" = ${RootFileIo.shellQuote(record.token)} ] &&
            [ "${'$'}z2_lock_module" = ${RootFileIo.shellQuote(ServiceLifecycleController.MODULE_DIR)} ] || exit 1
            [ ! -e "${'$'}quarantine" ] && [ ! -L "${'$'}quarantine" ] || exit 1
            mv "${'$'}lock" "${'$'}quarantine" || exit 1
            rm -f "${'$'}quarantine" && sync
        """.trimIndent()
        return ServiceLifecycleController.executeRoot(command)
    }
}
