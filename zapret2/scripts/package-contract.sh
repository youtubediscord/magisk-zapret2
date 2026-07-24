#!/system/bin/sh
# Shared parser and validator for zapret2/runtime-manifest.tsv.
#
# The manifest separates immutable package files, mutable seed data, ABI
# binaries, generated installed files, catalog entries, and the exact declared
# dependency closure for compatible presets.  Mutable seeds are required in a
# fresh package but may be restored from the previous installation afterwards.

PACKAGE_CONTRACT_MANIFEST_REL="zapret2/runtime-manifest.tsv"
PACKAGE_CONTRACT_OWNER_PROTOCOL=7
PACKAGE_CONTRACT_MAX_MANIFEST_BYTES=262144
PACKAGE_CONTRACT_MAX_MODULE_PROP_BYTES=4096
PACKAGE_CONTRACT_MAX_SHELL_EXEC_BYTES=262144
PACKAGE_CONTRACT_UPDATE_JSON="https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json"
PACKAGE_CONTRACT_CODE="OK"
PACKAGE_CONTRACT_DETAIL=""

package_contract_fail() {
    PACKAGE_CONTRACT_CODE="$1"
    PACKAGE_CONTRACT_DETAIL="${2:-}"
    return 1
}

package_contract_safe_relative_path_syntax() {
    local path="$1"
    [ -n "$path" ] || return 1
    [ "${path# }" = "$path" ] && [ "${path% }" = "$path" ] || return 1
    case "$path" in
        *'|'*) return 1 ;;
        /*|*\\*|*//*|*/|../*|*/../*|*/..|.|./*|*/./*|*/.) return 1 ;;
    esac
    package_contract_safe_path_component_lengths "$path" || return 1
    return 0
}

package_contract_safe_relative_path() {
    local path="$1"
    package_contract_safe_relative_path_syntax "$path" || return 1
    if LC_ALL=C printf '%s' "$path" | grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

package_contract_safe_file_name_byte_length() {
    local value="$1"
    local LC_ALL=C
    [ "${#value}" -le 255 ] 2>/dev/null
}

package_contract_safe_path_component_lengths() {
    local remainder="$1" component
    while :; do
        case "$remainder" in
            */*) component="${remainder%%/*}"; remainder="${remainder#*/}" ;;
            *) component="$remainder"; remainder="" ;;
        esac
        package_contract_safe_file_name_byte_length "$component" || return 1
        [ -n "$remainder" ] || break
    done
    return 0
}

package_contract_safe_preset_name_syntax() {
    local value="$1"
    [ -n "$value" ] && package_contract_safe_file_name_byte_length "$value" || return 1
    [ "${value# }" = "$value" ] && [ "${value% }" = "$value" ] || return 1
    case "$value" in
        _*|.|..|*/*|*\\*|*"'"*|*'"'*|*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT) return 1 ;;
        *.txt) ;;
        *) return 1 ;;
    esac
    return 0
}

package_contract_safe_preset_name() {
    local value="$1"
    package_contract_safe_preset_name_syntax "$value" || return 1
    if LC_ALL=C printf '%s' "$value" | grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

package_contract_runtime_core_value() {
    local root="${1%/}" key="$2" runtime
    runtime="$root/zapret2/runtime.ini"
    [ -f "$runtime" ] && [ ! -L "$runtime" ] || return 1
    awk -v wanted="$key" '
        function trim(v) { sub(/^[[:space:]]+/, "", v); sub(/[[:space:]]+$/, "", v); return v }
        {
            line=$0
            sub(/\r$/, "", line)
            normalized=trim(line)
        }
        normalized ~ /^\[[^]]+\]$/ { section=normalized; next }
        section == "[core]" && normalized !~ /^[#;]/ {
            separator=index(line, "=")
            if (separator == 0) next
            key=trim(substr(line, 1, separator - 1))
            if (key == wanted) {
                count++
                value=trim(substr(line, separator + 1))
                first=substr(value, 1, 1)
                last=substr(value, length(value), 1)
                if (first == "\"" || first == "\047") {
                    if (last != first) invalid=1
                    else value=substr(value, 2, length(value) - 2)
                } else if (last == "\"" || last == "\047") invalid=1
            }
        }
        END { if (invalid || count != 1 || value == "") exit 1; printf "%s", value }
    ' "$runtime"
}

package_contract_manifest_has_entry() {
    local manifest="$1"
    local expected="$2"
    local class=""
    local mode=""
    local path=""
    local extra=""
    local cr

    cr=$(printf '\r')
    while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
        class="${class%"$cr"}"
        mode="${mode%"$cr"}"
        path="${path%"$cr"}"
        extra="${extra%"$cr"}"
        [ -z "$extra" ] || continue
        [ "$class|$mode|$path" = "$expected" ] && return 0
    done < "$manifest"
    return 1
}

package_contract_validate_manifest_paths_file() {
    local paths="$1" failure="" code="" detail=""
    failure="$(LC_ALL=C awk '
        {
            path=$0
            if (seen[path]++) {
                printf "MANIFEST_DUPLICATE_PATH|%s\n", path
                failed=1
                exit
            }
            paths[++count]=path
        }
        END {
            if (failed) exit
            for (i=1; i<=count; i++) {
                path=paths[i]
                remainder=path
                prefix=""
                while ((separator=index(remainder, "/")) > 0) {
                    component=substr(remainder, 1, separator - 1)
                    remainder=substr(remainder, separator + 1)
                    prefix=(prefix == "" ? component : prefix "/" component)
                    if (prefix in seen) {
                        printf "MANIFEST_PATH_COLLISION|%s\n", path
                        exit
                    }
                }
            }
        }
    ' "$paths")" || {
        package_contract_fail "MANIFEST_TEMP_FAILED"
        return 1
    }
    [ -z "$failure" ] && return 0
    code="${failure%%|*}"
    detail="${failure#*|}"
    package_contract_fail "$code" "$detail"
}

package_contract_validate_manifest() {
    local root="$1"
    local manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    local seen="" entries="" required="" missing=""
    local class=""
    local mode=""
    local path=""
    local extra=""
    local schema_count=0
    local owner_protocol_count=0
    local metadata_stage=0
    local line_number=0
    local entry_count=0
    local required_entry=""
    local manifest_bytes=""
    local expanded_abi=""
    local expanded_path=""
    local cr

    PACKAGE_CONTRACT_CODE="OK"
    PACKAGE_CONTRACT_DETAIL=""
    [ -f "$manifest" ] && [ ! -L "$manifest" ] && [ -s "$manifest" ] && [ -r "$manifest" ] || {
        package_contract_fail "MANIFEST_MISSING" "$PACKAGE_CONTRACT_MANIFEST_REL"
        return 1
    }
    manifest_bytes="$(wc -c < "$manifest" 2>/dev/null)" || {
        package_contract_fail "MANIFEST_SIZE_UNREADABLE" "$PACKAGE_CONTRACT_MANIFEST_REL"
        return 1
    }
    case "$manifest_bytes" in ''|*[!0-9]*) package_contract_fail "MANIFEST_SIZE_UNREADABLE" "$manifest_bytes"; return 1 ;; esac
    [ "$manifest_bytes" -le "$PACKAGE_CONTRACT_MAX_MANIFEST_BYTES" ] || {
        package_contract_fail "MANIFEST_TOO_LARGE" "$manifest_bytes"
        return 1
    }
    LC_ALL=C awk '
        {
            line=$0
            sub(/\r$/, "", line)
            if (line ~ /[[:cntrl:]]/) exit 1
        }
    ' "$manifest" || {
        package_contract_fail "MANIFEST_CONTROL_CHARACTER" "$PACKAGE_CONTRACT_MANIFEST_REL"
        return 1
    }
    seen="$(mktemp)" || { package_contract_fail "MANIFEST_TEMP_FAILED"; return 1; }
    entries="$(mktemp)" || { rm -f "$seen"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1; }
    cr=$(printf '\r')
    while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
        line_number=$((line_number + 1))
        class="${class%"$cr"}"
        mode="${mode%"$cr"}"
        path="${path%"$cr"}"
        extra="${extra%"$cr"}"
        case "$class" in ""|'#'*) continue ;; esac
        [ -z "$extra" ] || { rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_FIELD_COUNT" "$class"; return 1; }
        if [ "$class" = "schema" ]; then
            [ "$metadata_stage" -eq 0 ] && [ "$line_number" -eq 1 ] || {
                rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_METADATA_ORDER" "schema"; return 1;
            }
            [ "$mode" = "1" ] && [ "$path" = "zapret2-runtime" ] || {
                rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_SCHEMA" "$mode|$path"; return 1;
            }
            schema_count=$((schema_count + 1))
            metadata_stage=1
            continue
        fi
        if [ "$class" = "owner_protocol" ]; then
            [ "$metadata_stage" -eq 1 ] && [ "$line_number" -eq 2 ] || {
                rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_METADATA_ORDER" "owner_protocol"; return 1;
            }
            [ "$mode" = "$PACKAGE_CONTRACT_OWNER_PROTOCOL" ] && [ "$path" = "zapret2-firewall" ] || {
                rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_OWNER_PROTOCOL" "$mode|$path"; return 1;
            }
            owner_protocol_count=$((owner_protocol_count + 1))
            metadata_stage=2
            continue
        fi
        [ "$metadata_stage" -eq 2 ] || {
            rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_METADATA_ORDER" "$class"; return 1;
        }
        case "$class" in
            immutable-file|mutable-seed|runtime-dependency-immutable|runtime-dependency-mutable-seed|preset-compatible|preset-quarantined)
                [ "$mode" = "0644" ] || { rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_MODE" "$class|$mode"; return 1; }
                ;;
            immutable-exec|abi-exec|installed-exec)
                [ "$mode" = "0755" ] || { rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_MODE" "$class|$mode"; return 1; }
                ;;
            *) rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_CLASS" "$class"; return 1 ;;
        esac
        package_contract_safe_relative_path_syntax "$path" || {
            rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_PATH" "$path"; return 1;
        }
        case "$class:$path" in
            abi-exec:zapret2/bin/'{abi}'/nfqws2) ;;
            abi-exec:*) rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_ABI_TEMPLATE" "$path"; return 1 ;;
            installed-exec:zapret2/nfqws2) ;;
            installed-exec:*) rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_INSTALLED_EXEC" "$path"; return 1 ;;
            preset-compatible:zapret2/presets/*.txt|preset-quarantined:zapret2/presets/*.txt)
                package_contract_safe_preset_name_syntax "${path##*/}" || {
                    rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_PRESET_PATH" "$path"; return 1;
                }
                ;;
            preset-compatible:*|preset-quarantined:*) rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_PRESET_PATH" "$path"; return 1 ;;
        esac
        if [ "$class" = "abi-exec" ]; then
            for expanded_abi in arm64-v8a armeabi-v7a; do
                expanded_path="zapret2/bin/$expanded_abi/nfqws2"
                printf '%s\n' "$expanded_path" >> "$seen" || {
                    rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1;
                }
            done
        else
            printf '%s\n' "$path" >> "$seen" || {
                rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1;
            }
        fi
        printf '%s|%s|%s\n' "$class" "$mode" "$path" >> "$entries" || {
            rm -f "$seen" "$entries"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1;
        }
        entry_count=$((entry_count + 1))
    done < "$manifest"
    if ! package_contract_validate_manifest_paths_file "$seen"; then
        rm -f "$seen" "$entries"
        return 1
    fi
    rm -f "$seen"
    [ "$schema_count" -eq 1 ] || { rm -f "$entries"; package_contract_fail "MANIFEST_SCHEMA_COUNT" "$schema_count"; return 1; }
    [ "$owner_protocol_count" -eq 1 ] || { rm -f "$entries"; package_contract_fail "MANIFEST_OWNER_PROTOCOL_COUNT" "$owner_protocol_count"; return 1; }
    [ "$entry_count" -gt 0 ] || { rm -f "$entries"; package_contract_fail "MANIFEST_EMPTY"; return 1; }
    required="$(mktemp)" || { rm -f "$entries"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1; }
    for required_entry in \
        "immutable-file|0644|module.prop" \
        "immutable-file|0644|zapret2/runtime-manifest.tsv" \
        "immutable-file|0644|zapret2/lifecycle-contract.version" \
        "immutable-file|0644|zapret2/upstream-zapret2.commit" \
        "immutable-file|0644|zapret2/strategy-catalogs/tcp.txt" \
        "immutable-file|0644|zapret2/strategy-catalogs/udp.txt" \
        "immutable-file|0644|zapret2/strategy-catalogs/voice.txt" \
        "immutable-file|0644|zapret2/strategy-catalogs/http80.txt" \
        "mutable-seed|0644|zapret2/runtime.ini" \
        "mutable-seed|0644|zapret2/hosts.ini" \
        "runtime-dependency-immutable|0644|zapret2/lua/custom_funcs.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-antidpi.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-auto.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-lib.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-obfs.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-pcap.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-tests.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-multishake.lua" \
        "immutable-exec|0755|customize.sh" \
        "immutable-exec|0755|service.sh" \
        "immutable-exec|0755|uninstall.sh" \
        "immutable-exec|0755|action.sh" \
        "immutable-exec|0755|zapret2/scripts/common.sh" \
        "immutable-exec|0755|zapret2/scripts/command-builder.sh" \
        "immutable-exec|0755|zapret2/scripts/package-contract.sh" \
        "immutable-exec|0755|zapret2/scripts/runtime-config.sh" \
        "immutable-exec|0755|zapret2/scripts/runtime-init.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-start.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-stop.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-restart.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-status.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-full-rollback.sh" \
        "immutable-exec|0755|zapret2/scripts/lifecycle/purge-contract.sh" \
        "immutable-exec|0755|zapret2/scripts/lifecycle/zapret-purge.sh" \
        "immutable-exec|0755|system/bin/zapret2-start" \
        "immutable-exec|0755|system/bin/zapret2-stop" \
        "immutable-exec|0755|system/bin/zapret2-status" \
        "immutable-exec|0755|system/bin/zapret2-restart" \
        "immutable-exec|0755|system/bin/zapret2-full-rollback" \
        "abi-exec|0755|zapret2/bin/{abi}/nfqws2" \
        "installed-exec|0755|zapret2/nfqws2" \
        "preset-compatible|0644|zapret2/presets/Default v1 (game filter).txt"
    do
        printf '%s\n' "$required_entry" >> "$required" || {
            rm -f "$entries" "$required"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1;
        }
    done
    missing="$(awk '
        NR == FNR { present[$0]=1; next }
        !($0 in present) { print; exit }
    ' "$entries" "$required")" || {
        rm -f "$entries" "$required"; package_contract_fail "MANIFEST_TEMP_FAILED"; return 1;
    }
    rm -f "$entries" "$required"
    [ -z "$missing" ] || { package_contract_fail "MANIFEST_REQUIRED_ENTRY" "$missing"; return 1; }
    return 0
}

package_contract_check_regular() {
    local file="$1"
    local relative="$2"
    local links=""
    if [ -L "$file" ]; then package_contract_fail "PACKAGE_SYMLINK" "$relative"; return 1; fi
    if [ ! -f "$file" ]; then package_contract_fail "PACKAGE_MISSING" "$relative"; return 1; fi
    if [ ! -s "$file" ]; then package_contract_fail "PACKAGE_EMPTY" "$relative"; return 1; fi
    if [ ! -r "$file" ]; then package_contract_fail "PACKAGE_UNREADABLE" "$relative"; return 1; fi
    links="$(stat -c %h "$file" 2>/dev/null)" || { package_contract_fail "PACKAGE_LINK_COUNT" "$relative"; return 1; }
    [ "$links" = 1 ] || { package_contract_fail "PACKAGE_LINK_COUNT" "$relative:$links"; return 1; }
    return 0
}

package_contract_validate_module_prop() {
    local root="$1"
    local file="$root/module.prop"
    local file_bytes=""
    local without_nul_bytes=""

    package_contract_check_regular "$file" "module.prop" || return 1
    file_bytes="$(wc -c < "$file" 2>/dev/null)"
    case "$file_bytes" in ''|*[!0-9]*) package_contract_fail "MODULE_PROP_SIZE_UNREADABLE"; return 1 ;; esac
    [ "$file_bytes" -le "$PACKAGE_CONTRACT_MAX_MODULE_PROP_BYTES" ] || {
        package_contract_fail "MODULE_PROP_TOO_LARGE" "$file_bytes"
        return 1
    }
    without_nul_bytes="$(tr -d '\000' < "$file" | wc -c 2>/dev/null)"
    case "$without_nul_bytes" in ''|*[!0-9]*) package_contract_fail "MODULE_PROP_INVALID_DATA"; return 1 ;; esac
    [ "$without_nul_bytes" = "$file_bytes" ] || {
        package_contract_fail "MODULE_PROP_INVALID_DATA" "NUL"
        return 1
    }
    if LC_ALL=C grep -q "$(printf '\r')" "$file"; then
        package_contract_fail "MODULE_PROP_INVALID_DATA" "CR"
        return 1
    fi
    LC_ALL=C awk \
        -v expected_update_json="$PACKAGE_CONTRACT_UPDATE_JSON" '
        function trim(value) {
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            return value
        }
        function canonical_long(value) {
            if (value == "0") return 1
            if (value !~ /^[1-9][0-9]*$/ || length(value) > 19) return 0
            return length(value) < 19 || ("x" value <= "x9223372036854775807")
        }
        function canonical_version_code(value) {
            if (value !~ /^[1-9][0-9]*$/ || length(value) > 10) return 0
            return length(value) < 10 || ("x" value <= "x2100000000")
        }
        {
            line = trim($0)
            if (line == "" || substr(line, 1, 1) == "#") next
            separator = index(line, "=")
            if (separator <= 1) { invalid = 1; next }
            key = trim(substr(line, 1, separator - 1))
            value = trim(substr(line, separator + 1))
            if (key !~ /^[A-Za-z][A-Za-z0-9_]*$/ || value ~ /[[:cntrl:]]/ || seen[key]++) {
                invalid = 1
                next
            }
            properties[key] = value
        }
        END {
            split("id name version versionCode author description updateJson", required, " ")
            for (i in required) if (!(required[i] in properties)) invalid = 1
            if (invalid) exit 1
            if ("webRoot" in properties) exit 1
            if (properties["id"] != "zapret2" || properties["name"] == "" ||
                properties["author"] == "" || properties["description"] == "" ||
                properties["updateJson"] != expected_update_json) exit 1
            version = properties["version"]
            if (substr(version, 1, 1) != "v") exit 1
            version = substr(version, 2)
            if (split(version, parts, ".") != 3 || !canonical_long(parts[1]) ||
                !canonical_long(parts[2]) || !canonical_long(parts[3]) ||
                parts[1] + 0 < 1 || parts[1] + 0 > 2100 ||
                parts[2] + 0 > 99 || parts[3] + 0 > 9999 ||
                !canonical_version_code(properties["versionCode"])) exit 1
            expected_code = sprintf("%.0f",
                (parts[1] + 0) * 1000000 + (parts[2] + 0) * 10000 + (parts[3] + 0))
            if (properties["versionCode"] != expected_code) exit 1
        }
    ' "$file" || {
        package_contract_fail "MODULE_PROP_INVALID" "module.prop"
        return 1
    }
    return 0
}

package_contract_validate_lifecycle_contract() {
    local root="$1"
    local relative="zapret2/lifecycle-contract.version"
    local file="$root/$relative"
    package_contract_check_regular "$file" "$relative" || return 1
    [ "$(wc -l < "$file" 2>/dev/null)" = 1 ] &&
        [ "$(cat "$file" 2>/dev/null)" = 6 ] || {
        package_contract_fail "LIFECYCLE_CONTRACT_INVALID" "$relative"
        return 1
    }
    return 0
}

package_contract_for_each_path() {
    # Usage: package_contract_for_each_path <root> <profile> <callback>
    local root="$1"
    local profile="$2"
    local callback="$3"
    local manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    local class="" mode="" path="" extra="" abi=""
    while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
        case "$class" in ""|'#'*|schema|owner_protocol) continue ;; esac
        if [ "$class" = "installed-exec" ] && [ "$profile" != "installed" ]; then continue; fi
        # Magisk sources customize.sh from the package and then removes it from
        # the installed module, so installer-only code is excluded from the
        # canonical installed profile.
        if [ "$profile" = "installed" ] && [ "$path" = "customize.sh" ]; then continue; fi
        if [ "$class" = "abi-exec" ]; then
            for abi in arm64-v8a armeabi-v7a; do
                "$callback" "$root" "$class" "$mode" "zapret2/bin/$abi/nfqws2" || return 1
            done
        else
            "$callback" "$root" "$class" "$mode" "$path" || return 1
        fi
    done < "$manifest"
    return 0
}

package_contract_allowlist_callback() {
    local root="$1" class="$2" mode="$3" path="$4" parent=""
    printf '%s\n' "$path" >> "$PACKAGE_CONTRACT_ALLOWED_FILES" || return 1
    case "$path" in
        */*) parent="${path%/*}" ;;
        *) parent="" ;;
    esac
    while [ -n "$parent" ]; do
        printf '%s\n' "$parent" >> "$PACKAGE_CONTRACT_ALLOWED_DIRECTORIES" || return 1
        case "$parent" in
            */*) parent="${parent%/*}" ;;
            *) parent="" ;;
        esac
    done
    return 0
}

package_contract_build_allowlist() {
    local root="$1" profile="$2" files="$3" directories="$4" result=0
    : > "$files" && : > "$directories" || {
        package_contract_fail "ALLOWLIST_TEMP_FAILED"
        return 1
    }
    PACKAGE_CONTRACT_ALLOWED_FILES="$files"
    PACKAGE_CONTRACT_ALLOWED_DIRECTORIES="$directories"
    package_contract_for_each_path "$root" "$profile" package_contract_allowlist_callback
    result=$?
    unset PACKAGE_CONTRACT_ALLOWED_FILES PACKAGE_CONTRACT_ALLOWED_DIRECTORIES
    [ "$result" -eq 0 ] || { package_contract_fail "ALLOWLIST_TEMP_FAILED"; return "$result"; }
    LC_ALL=C sort -u "$files" -o "$files" && LC_ALL=C sort -u "$directories" -o "$directories" || {
        package_contract_fail "ALLOWLIST_TEMP_FAILED"
        return 1
    }
    return 0
}

package_contract_validate_exact_tree() {
    local root="${1%/}" profile="${2:-package}" meta_policy="${3:-no-meta}"
    local files="" directories="" listing="" actual="" allowed_records=""
    local entry="" relative="" unsafe="" hardlinked="" unexpected=""
    [ -n "$root" ] && [ -d "$root" ] && [ ! -L "$root" ] || {
        package_contract_fail "PACKAGE_ROOT_INVALID" "$root"
        return 1
    }
    package_contract_validate_manifest "$root" || return 1
    files="$(mktemp)" || { package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    directories="$(mktemp)" || { rm -f "$files"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    listing="$(mktemp)" || {
        rm -f "$files" "$directories"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    actual="$(mktemp)" || {
        rm -f "$files" "$directories" "$listing"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    allowed_records="$(mktemp)" || {
        rm -f "$files" "$directories" "$listing" "$actual"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    package_contract_build_allowlist "$root" "$profile" "$files" "$directories" || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"; return 1;
    }
    find "$root" -mindepth 1 -print > "$listing" 2>/dev/null || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
    }
    unsafe="$(LC_ALL=C awk -v prefix="$root/" '
        { relative=substr($0, length(prefix) + 1) }
        relative ~ /[[:cntrl:]|]/ { print relative; exit }
    ' "$listing")" || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
    }
    [ -z "$unsafe" ] || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_UNSAFE_ENTRY" "$unsafe"; return 1;
    }
    hardlinked="$(find "$root" -type f ! -links 1 -print -quit 2>/dev/null)" || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
    }
    [ -z "$hardlinked" ] || {
        relative="${hardlinked#"$root"/}"
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_LINK_COUNT" "$relative"; return 1;
    }
    while IFS= read -r entry || [ -n "$entry" ]; do
        relative="${entry#"$root"/}"
        package_contract_safe_relative_path_syntax "$relative" || {
            rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
            package_contract_fail "PACKAGE_UNSAFE_ENTRY" "$relative"; return 1;
        }
        [ ! -L "$entry" ] || {
            rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
            package_contract_fail "PACKAGE_SYMLINK" "$relative"; return 1;
        }
        if [ -f "$entry" ]; then
            printf 'file|%s\n' "$relative" >> "$actual" || {
                rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
                package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
            }
        elif [ -d "$entry" ]; then
            printf 'directory|%s\n' "$relative" >> "$actual" || {
                rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
                package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
            }
        else
            rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
            package_contract_fail "PACKAGE_UNDECLARED_ENTRY" "$relative"; return 1
        fi
    done < "$listing"
    awk '{ print "file|" $0 }' "$files" > "$allowed_records" &&
        awk '{ print "directory|" $0 }' "$directories" >> "$allowed_records" || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    unexpected="$(awk -F '|' -v profile="$profile" -v meta_policy="$meta_policy" '
        NR == FNR { allowed[$0]=1; next }
        {
            record=$0
            separator=index(record, "|")
            kind=substr(record, 1, separator - 1)
            path=substr(record, separator + 1)
            if (record in allowed) next
            if (profile == "installed") {
                if (kind == "file" && (path == "disable" || path == "zapret2/install-generation.meta" ||
                    index(path, "zapret2/lists/") == 1 || index(path, "zapret2/presets/") == 1)) next
                if (kind == "directory" && index(path, "zapret2/lists/") == 1) next
            }
            if (meta_policy == "allow-meta" && (path == "META-INF" || index(path, "META-INF/") == 1)) next
            print path
            exit
        }
    ' "$allowed_records" "$actual")" || {
        rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
        package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
    }
    rm -f "$files" "$directories" "$listing" "$actual" "$allowed_records"
    [ -z "$unexpected" ] || { package_contract_fail "PACKAGE_UNDECLARED_ENTRY" "$unexpected"; return 1; }
    return 0
}

package_contract_copy_callback() {
    local root="$1" class="$2" mode="$3" path="$4"
    local source="$root/$path" target="$PACKAGE_CONTRACT_ASSEMBLY_ROOT/$path" parent=""
    package_contract_check_regular "$source" "$path" || return 1
    parent="${target%/*}"
    mkdir -p "$parent" || { package_contract_fail "ASSEMBLY_MKDIR" "$path"; return 1; }
    cp "$source" "$target" || { package_contract_fail "ASSEMBLY_COPY" "$path"; return 1; }
    chmod "$mode" "$target" || { package_contract_fail "ASSEMBLY_CHMOD" "$path"; return 1; }
}

package_contract_assemble_package() {
    local source_root="${1%/}" destination_root="${2%/}" result=0
    [ -n "$source_root" ] && [ -d "$source_root" ] && [ ! -L "$source_root" ] || {
        package_contract_fail "PACKAGE_ROOT_INVALID" "$source_root"; return 1;
    }
    [ -n "$destination_root" ] && [ -d "$destination_root" ] && [ ! -L "$destination_root" ] || {
        package_contract_fail "ASSEMBLY_ROOT_INVALID" "$destination_root"; return 1;
    }
    if find "$destination_root" -mindepth 1 -print -quit | grep -q .; then
        package_contract_fail "ASSEMBLY_ROOT_NOT_EMPTY" "$destination_root"
        return 1
    fi
    package_contract_validate_manifest "$source_root" || return 1
    package_contract_for_each_path "$source_root" package package_contract_content_callback || return 1
    PACKAGE_CONTRACT_ASSEMBLY_ROOT="$destination_root"
    package_contract_for_each_path "$source_root" package package_contract_copy_callback
    result=$?
    unset PACKAGE_CONTRACT_ASSEMBLY_ROOT
    [ "$result" -eq 0 ] || return "$result"
    package_contract_validate_exact_tree "$destination_root" package || return 1
    package_contract_validate_modes "$destination_root" package || return 1
    return 0
}

package_contract_content_callback() {
    local root="$1" class="$2" mode="$3" path="$4"
    package_contract_check_regular "$root/$path" "$path"
}

package_contract_compare_release_callback() {
    local source_root="$1" class="$2" mode="$3" path="$4"
    local source="$source_root/$path"
    local target="$PACKAGE_CONTRACT_COMPARE_TARGET/$path"
    case "$class" in
        immutable-file|immutable-exec|runtime-dependency-immutable|abi-exec|preset-compatible|preset-quarantined) ;;
        *) return 0 ;;
    esac
    [ "$path" != customize.sh ] || return 0
    package_contract_check_regular "$source" "$path" || return 1
    package_contract_check_regular "$target" "$path" || return 1
    cmp -s "$source" "$target" || {
        package_contract_fail "PACKAGE_GENERATION_MISMATCH" "$path"
        return 1
    }
}

# Constant-size generation comparison for release qualification and offline
# diagnostics. APK releases delegate publication exclusively to Magisk staging.
package_contract_compare_release() {
    local source_root="${1%/}" target_root="${2%/}" path
    [ -n "$source_root" ] && [ -d "$source_root" ] && [ ! -L "$source_root" ] || {
        package_contract_fail "PACKAGE_ROOT_INVALID" "$source_root"; return 1;
    }
    [ -n "$target_root" ] && [ -d "$target_root" ] && [ ! -L "$target_root" ] || {
        package_contract_fail "PACKAGE_ROOT_INVALID" "$target_root"; return 1;
    }
    package_contract_validate_manifest "$source_root" || return 1
    package_contract_validate_manifest "$target_root" || return 1
    cmp -s "$source_root/$PACKAGE_CONTRACT_MANIFEST_REL" \
        "$target_root/$PACKAGE_CONTRACT_MANIFEST_REL" || {
        package_contract_fail "PACKAGE_GENERATION_MISMATCH" "$PACKAGE_CONTRACT_MANIFEST_REL"
        return 1
    }
    for path in \
        module.prop service.sh uninstall.sh action.sh \
        system/bin/zapret2-start system/bin/zapret2-stop \
        system/bin/zapret2-status system/bin/zapret2-restart \
        system/bin/zapret2-full-rollback \
        zapret2/lifecycle-contract.version \
        zapret2/scripts/common.sh zapret2/scripts/command-builder.sh \
        zapret2/scripts/package-contract.sh zapret2/scripts/runtime-config.sh \
        zapret2/scripts/runtime-init.sh zapret2/scripts/zapret-start.sh \
        zapret2/scripts/zapret-stop.sh zapret2/scripts/zapret-status.sh
    do
        package_contract_check_regular "$source_root/$path" "$path" || return 1
        package_contract_check_regular "$target_root/$path" "$path" || return 1
        cmp -s "$source_root/$path" "$target_root/$path" || {
            package_contract_fail "PACKAGE_GENERATION_MISMATCH" "$path"
            return 1
        }
    done
    return 0
}

# Exhaustive immutable-byte comparison belongs to release qualification and
# offline diagnostics, never to the device publication/status hot path.
package_contract_compare_release_all() {
    local source_root="${1%/}" target_root="${2%/}" result=0
    package_contract_compare_release "$source_root" "$target_root" || return 1
    PACKAGE_CONTRACT_COMPARE_TARGET="$target_root"
    package_contract_for_each_path "$source_root" installed package_contract_compare_release_callback
    result=$?
    unset PACKAGE_CONTRACT_COMPARE_TARGET
    return "$result"
}

package_contract_shell_exec_callback() {
    local root="$1" class="$2" mode="$3" path="$4" file="$1/$4" bytes=""
    [ "$class" = immutable-exec ] || return 0
    bytes="$(wc -c < "$file" 2>/dev/null)"
    case "$bytes" in ''|*[!0-9]*) package_contract_fail "SHELL_EXEC_SIZE_UNREADABLE" "$path"; return 1 ;; esac
    [ "$bytes" -le "$PACKAGE_CONTRACT_MAX_SHELL_EXEC_BYTES" ] || {
        package_contract_fail "SHELL_EXEC_TOO_LARGE" "$path:$bytes"; return 1;
    }
    [ "$(sed -n '1p' "$file")" = '#!/system/bin/sh' ] || {
        package_contract_fail "SHELL_EXEC_SHEBANG" "$path"; return 1;
    }
    if LC_ALL=C grep -q "$(printf '\r')" "$file"; then
        package_contract_fail "SHELL_EXEC_CR" "$path"
        return 1
    fi
    return 0
}

package_contract_validate_entrypoints() {
    local root="${1%/}" command_name="" wrapper="" target="" expected=""
    expected="$(mktemp)" || {
        package_contract_fail "ENTRYPOINT_TEMP_FAILED"
        return 1
    }
    for command_name in start stop status restart full-rollback; do
        wrapper="system/bin/zapret2-$command_name"
        target="/data/adb/modules/zapret2/zapret2/scripts/zapret-$command_name.sh"
        package_contract_check_regular "$root/$wrapper" "$wrapper" || {
            rm -f "$expected"
            return 1
        }
        {
            printf '%s\n' '#!/system/bin/sh'
            printf 'exec %s "$@"\n' "$target"
        } > "$expected" || {
            rm -f "$expected"
            package_contract_fail "ENTRYPOINT_TEMP_FAILED"
            return 1
        }
        cmp -s "$expected" "$root/$wrapper" || {
            rm -f "$expected"
            package_contract_fail "PACKAGE_ENTRYPOINT_BYTES" "$wrapper"
            return 1
        }
    done
    rm -f "$expected"
    return 0
}

package_contract_mode_record_callback() {
    local root="$1" class="$2" expected="$3" path="$4"
    printf '%s|%s\n' "$expected" "$path" >> "$PACKAGE_CONTRACT_EXPECTED_MODES" || {
        package_contract_fail "PACKAGE_MODE_TEMP_FAILED"; return 1;
    }
}

package_contract_chmod_callback() {
    local root="$1" class="$2" mode="$3" path="$4"
    chmod "$mode" "$root/$path" || {
        package_contract_fail "PACKAGE_CHMOD" "$path"; return 1;
    }
}

# The Magisk installer first assigns 0644 to every staged regular file in one
# batched find invocation. Only manifest executables need another chmod pass.
package_contract_chmod_executable_callback() {
    local root="$1" class="$2" mode="$3" path="$4"
    [ "$mode" = 0755 ] || return 0
    chmod 0755 "$root/$path" || {
        package_contract_fail "PACKAGE_CHMOD" "$path"; return 1;
    }
}

package_contract_apply_executable_modes() {
    local root="$1" profile="${2:-package}"
    package_contract_for_each_path "$root" "$profile" package_contract_chmod_executable_callback
}

package_contract_validate_runtime_selection() {
    local root="${1%/}" preset relative path manifest
    preset="$(package_contract_runtime_core_value "$root" active_preset)" || {
        package_contract_fail "RUNTIME_PRESET_BINDING_INVALID"
        return 1
    }
    package_contract_safe_preset_name "$preset" || {
        package_contract_fail "RUNTIME_PRESET_BINDING_INVALID" "$preset"
        return 1
    }
    relative="zapret2/presets/$preset"
    path="$root/$relative"
    manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    # Installed custom presets are intentionally outside the immutable package
    # manifest; the same strict scanner below still has to classify them valid.
    [ -f "$path" ] && [ ! -L "$path" ] && [ -s "$path" ] &&
        [ "$(stat -c %u "$path" 2>/dev/null)" = 0 ] &&
        [ "$(stat -c %h "$path" 2>/dev/null)" = 1 ] &&
        [ "$(stat -c %a "$path" 2>/dev/null)" = 644 ] || {
        package_contract_fail "RUNTIME_PRESET_UNSAFE" "$relative"
        return 1
    }
    return 0
}

package_contract_validate_tree() {
    local root="$1" profile="${2:-package}" files="" directories="" relative="" file="" hardlinked=""
    package_contract_validate_manifest "$root" || return 1
    files="$(mktemp)" || { package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    directories="$(mktemp)" || { rm -f "$files"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    package_contract_build_allowlist "$root" "$profile" "$files" "$directories" || {
        rm -f "$files" "$directories"
        return 1
    }
    while IFS= read -r relative || [ -n "$relative" ]; do
        file="$root/$relative"
        if [ -L "$file" ]; then
            rm -f "$files" "$directories"; package_contract_fail "PACKAGE_SYMLINK" "$relative"; return 1
        fi
        if [ ! -f "$file" ]; then
            rm -f "$files" "$directories"; package_contract_fail "PACKAGE_MISSING" "$relative"; return 1
        fi
        if [ ! -s "$file" ]; then
            rm -f "$files" "$directories"; package_contract_fail "PACKAGE_EMPTY" "$relative"; return 1
        fi
        if [ ! -r "$file" ]; then
            rm -f "$files" "$directories"; package_contract_fail "PACKAGE_UNREADABLE" "$relative"; return 1
        fi
    done < "$files"
    hardlinked="$(find "$root" -type f ! -links 1 -print -quit 2>/dev/null)" || {
        rm -f "$files" "$directories"; package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1
    }
    rm -f "$files" "$directories"
    [ -z "$hardlinked" ] || {
        relative="${hardlinked#"$root"/}"
        package_contract_fail "PACKAGE_LINK_COUNT" "$relative"; return 1
    }
    if [ "$profile" = installed ]; then
        package_contract_validate_runtime_selection "$root" || return 1
    fi
    return 0
}

package_contract_extract_zip_names() {
    local listing="$1" output="$2"
    awk '
        /^[[:space:]]*--------/ {
            separators++
            if (separators == 2) exit
            next
        }
        separators == 1 {
            line=$0
            sub(/^[[:space:]]*[0-9]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "", line)
            if (line != "") print line
        }
    ' "$listing" > "$output" || {
        package_contract_fail "ZIP_LIST_PARSE"; return 1;
    }
    [ -s "$output" ] || { package_contract_fail "ZIP_LIST_EMPTY"; return 1; }
}

package_contract_validate_zip_topology_file() {
    local records="$1" failure="" code="" detail=""
    failure="$(LC_ALL=C awk -F '|' '
        {
            record=$0
            separator=index(record, "|")
            kind=substr(record, 1, separator - 1)
            path=substr(record, separator + 1)
            if (path in kinds) {
                printf "ZIP_DUPLICATE_ENTRY|%s\n", path
                failed=1
                exit
            }
            kinds[path]=kind
            paths[++count]=path
        }
        END {
            if (failed) exit
            for (i=1; i<=count; i++) {
                path=paths[i]
                remainder=path
                prefix=""
                while ((separator=index(remainder, "/")) > 0) {
                    component=substr(remainder, 1, separator - 1)
                    remainder=substr(remainder, separator + 1)
                    prefix=(prefix == "" ? component : prefix "/" component)
                    if ((prefix in kinds) && kinds[prefix] == "file") {
                        printf "ZIP_PATH_COLLISION|%s\n", path
                        exit
                    }
                }
            }
        }
    ' "$records")" || {
        package_contract_fail "ZIP_TOPOLOGY_SCAN_FAILED"
        return 1
    }
    [ -z "$failure" ] && return 0
    code="${failure%%|*}"
    detail="${failure#*|}"
    package_contract_fail "$code" "$detail"
}

package_contract_validate_zip_names() {
    local root="$1" names="$2" files="" directories="" actual="" allowed_records=""
    local raw="" path="" kind="" unsafe="" missing="" unexpected=""
    package_contract_validate_manifest "$root" || return 1
    [ -f "$names" ] && [ ! -L "$names" ] && [ -s "$names" ] || { package_contract_fail "ZIP_LIST_MISSING"; return 1; }
    unsafe="$(LC_ALL=C awk '$0 ~ /[[:cntrl:]|]/ { print; exit }' "$names")" || {
        package_contract_fail "ZIP_LIST_PARSE"
        return 1
    }
    [ -z "$unsafe" ] || { package_contract_fail "ZIP_UNSAFE_ENTRY" "$unsafe"; return 1; }
    files="$(mktemp)" || { package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    directories="$(mktemp)" || { rm -f "$files"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    actual="$(mktemp)" || { rm -f "$files" "$directories"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    allowed_records="$(mktemp)" || {
        rm -f "$files" "$directories" "$actual"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    package_contract_build_allowlist "$root" package "$files" "$directories" || {
        rm -f "$files" "$directories" "$actual" "$allowed_records"; return 1;
    }
    while IFS= read -r raw || [ -n "$raw" ]; do
        case "$raw" in
            */) path="${raw%/}"; kind="directory" ;;
            *) path="$raw"; kind="file" ;;
        esac
        package_contract_safe_relative_path_syntax "$path" || {
            rm -f "$files" "$directories" "$actual" "$allowed_records"
            package_contract_fail "ZIP_UNSAFE_ENTRY" "$raw"; return 1;
        }
        printf '%s|%s\n' "$kind" "$path" >> "$actual" || {
            rm -f "$files" "$directories" "$actual" "$allowed_records"
            package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
        }
    done < "$names"

    missing="$(awk -F '|' '
        NR == FNR {
            if ($1 == "file") {
                separator=index($0, "|")
                counts[substr($0, separator + 1)]++
            }
            next
        }
        counts[$0] != 1 { printf "%s:%d\n", $0, counts[$0] + 0; exit }
    ' "$actual" "$files")" || {
        rm -f "$files" "$directories" "$actual" "$allowed_records"
        package_contract_fail "ZIP_LIST_PARSE"; return 1;
    }
    [ -z "$missing" ] || {
        rm -f "$files" "$directories" "$actual" "$allowed_records"
        package_contract_fail "ZIP_ENTRY_COUNT" "$missing"; return 1;
    }

    if ! package_contract_validate_zip_topology_file "$actual"; then
        rm -f "$files" "$directories" "$actual" "$allowed_records"
        return 1
    fi

    awk '{ print "file|" $0 }' "$files" > "$allowed_records" &&
        awk '{ print "directory|" $0 }' "$directories" >> "$allowed_records" || {
        rm -f "$files" "$directories" "$actual" "$allowed_records"
        package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
    }
    unexpected="$(awk -F '|' '
        NR == FNR { allowed[$0]=1; next }
        {
            record=$0
            separator=index(record, "|")
            path=substr(record, separator + 1)
            if (record in allowed || path == "META-INF" || index(path, "META-INF/") == 1) next
            print path
            exit
        }
    ' "$allowed_records" "$actual")" || {
        rm -f "$files" "$directories" "$actual" "$allowed_records"
        package_contract_fail "ZIP_LIST_PARSE"; return 1;
    }
    rm -f "$files" "$directories" "$actual" "$allowed_records"
    [ -z "$unexpected" ] || { package_contract_fail "ZIP_UNDECLARED_ENTRY" "$unexpected"; return 1; }
    return 0
}

package_contract_apply_modes() {
    local root="$1" profile="${2:-package}"
    package_contract_validate_tree "$root" "$profile" || return 1
    # One batched filesystem walk replaces hundreds of per-manifest chmod
    # subprocesses. Installer-owned state is outside the release manifest and
    # retains its stricter private mode.
    find "$root" -type f \
        ! -path "$root/zapret2/install-generation.meta" \
        ! -path "$root/disable" \
        -exec chmod 0644 {} + || {
        package_contract_fail "PACKAGE_CHMOD" "$root"
        return 1
    }
    package_contract_apply_executable_modes "$root" "$profile" || return 1
}

package_contract_validate_modes() {
    local root="${1%/}" profile="${2:-package}" expected="" actual="" failure="" code="" detail=""
    expected="$(mktemp)" || { package_contract_fail "PACKAGE_MODE_TEMP_FAILED"; return 1; }
    actual="$(mktemp)" || { rm -f "$expected"; package_contract_fail "PACKAGE_MODE_TEMP_FAILED"; return 1; }
    PACKAGE_CONTRACT_EXPECTED_MODES="$expected"
    package_contract_for_each_path "$root" "$profile" package_contract_mode_record_callback || {
        unset PACKAGE_CONTRACT_EXPECTED_MODES
        rm -f "$expected" "$actual"
        return 1
    }
    unset PACKAGE_CONTRACT_EXPECTED_MODES
    find "$root" -type f -exec stat -c '%a|%n' {} + > "$actual" 2>/dev/null || {
        rm -f "$expected" "$actual"; package_contract_fail "PACKAGE_MODE_UNREADABLE"; return 1
    }
    failure="$(awk -F '|' -v prefix="$root/" '
        NR == FNR {
            separator=index($0, "|")
            mode=substr($0, 1, separator - 1)
            absolute=substr($0, separator + 1)
            if (index(absolute, prefix) == 1) actual[substr(absolute, length(prefix) + 1)]=mode
            next
        }
        {
            separator=index($0, "|")
            expected=substr($0, 1, separator - 1)
            path=substr($0, separator + 1)
            if (!(path in actual)) {
                printf "PACKAGE_MODE_UNREADABLE|%s\n", path
                exit
            }
            if ("0" actual[path] != expected) {
                printf "PACKAGE_MODE|%s:%s:%s\n", path, actual[path], expected
                exit
            }
        }
    ' "$actual" "$expected")" || {
        rm -f "$expected" "$actual"; package_contract_fail "PACKAGE_MODE_TEMP_FAILED"; return 1
    }
    rm -f "$expected" "$actual"
    [ -z "$failure" ] && return 0
    code="${failure%%|*}"
    detail="${failure#*|}"
    package_contract_fail "$code" "$detail"
}

package_contract_validate_catalog() {
    local root="$1"
    local manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    local builder="$root/zapret2/scripts/command-builder.sh"
    local temp_root="" expected="" actual="" declared="" scan="" catalog_expected="" catalog_actual=""
    local strategy_scan="" strategy_expected=""
    local class="" mode="" path="" extra="" file="" name=""

    temp_root="$(mktemp -d)" || { package_contract_fail "CATALOG_TEMP_FAILED"; return 1; }
    expected="$temp_root/expected"
    actual="$temp_root/actual"
    declared="$temp_root/declared"
    scan="$temp_root/scan"
    catalog_expected="$temp_root/catalog.expected"
    catalog_actual="$temp_root/catalog.actual"
    strategy_scan="$temp_root/strategies.scan"
    strategy_expected="$temp_root/strategies.expected"
    : > "$expected"; : > "$declared"; : > "$catalog_expected" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_TEMP_FAILED"; return 1;
    }
    printf 'Z2_STRATEGIES\tOK\n' > "$strategy_expected" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_TEMP_FAILED"; return 1;
    }

    while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
        case "$class" in
            preset-compatible)
                name="${path##*/}"
                printf '%s\tVALID\n' "$name" >> "$expected"
                printf '%s\n' "$name" >> "$catalog_expected"
                ;;
            preset-quarantined)
                name="${path##*/}"
                printf '%s\tQUARANTINED\n' "$name" >> "$expected"
                printf '%s\n' "$name" >> "$catalog_expected"
                ;;
            runtime-dependency-immutable|runtime-dependency-mutable-seed)
                printf '%s\n' "$path" >> "$declared"
                ;;
            immutable-file)
                case "$path" in zapret2/presets/*.txt) printf '%s\n' "${path##*/}" >> "$catalog_expected" ;; esac
                ;;
        esac
    done < "$manifest"

    if ! /system/bin/sh "$builder" --validate-strategies-machine "$root/zapret2" > "$strategy_scan" 2>/dev/null; then
        if ! sh "$builder" --validate-strategies-machine "$root/zapret2" > "$strategy_scan" 2>/dev/null; then
            rm -rf "$temp_root"
            package_contract_fail "STRATEGY_CATALOG_INVALID"
            return 1
        fi
    fi
    cmp -s "$strategy_expected" "$strategy_scan" || {
        rm -rf "$temp_root"; package_contract_fail "STRATEGY_CATALOG_PROTOCOL"; return 1;
    }

    PRESET_ALLOWED_DEPENDENCIES_FILE="$declared"
    export PRESET_ALLOWED_DEPENDENCIES_FILE
    if ! /system/bin/sh "$builder" --scan-presets-machine "$root/zapret2" > "$scan" 2>/dev/null; then
        if ! sh "$builder" --scan-presets-machine "$root/zapret2" > "$scan" 2>/dev/null; then
            unset PRESET_ALLOWED_DEPENDENCIES_FILE
            rm -rf "$temp_root"
            package_contract_fail "CATALOG_SCAN_FAILED"
            return 1
        fi
    fi
    unset PRESET_ALLOWED_DEPENDENCIES_FILE

    awk -F '\t' 'NR==FNR { wanted[$1]=1; next } $1 == "Z2_PRESET" && ($4 in wanted) { print $4 "\t" $2 }' \
        "$catalog_expected" "$scan" | LC_ALL=C sort > "$actual"
    LC_ALL=C sort "$expected" -o "$expected"
    cmp -s "$expected" "$actual" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_CLASSIFICATION_MISMATCH"; return 1;
    }
    [ "$(grep -c '^Z2_PRESET_SUMMARY[[:space:]]' "$scan")" -eq 1 ] || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_SUMMARY"; return 1;
    }
    if grep -q '^Z2_PRESET[[:space:]]QUARANTINED[[:space:]]' "$scan"; then
        file="$(awk -F '\t' '$1=="Z2_PRESET" && $2=="QUARANTINED" { print $4; exit }' "$scan")"
        rm -rf "$temp_root"; package_contract_fail "PRESET_INVALID" "$file"; return 1
    fi

    for file in "$root/zapret2/presets"/*.txt; do
        [ -e "$file" ] || [ -L "$file" ] || continue
        [ -f "$file" ] && [ ! -L "$file" ] || {
            rm -rf "$temp_root"; package_contract_fail "CATALOG_UNSAFE_ENTRY" "${file##*/}"; return 1;
        }
        name="${file##*/}"
        grep -Fqx "$name" "$catalog_expected" || continue
        printf '%s\n' "$name" >> "$catalog_actual"
    done
    LC_ALL=C sort "$catalog_expected" -o "$catalog_expected"
    LC_ALL=C sort "$catalog_actual" -o "$catalog_actual"
    cmp -s "$catalog_expected" "$catalog_actual" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_CONTENT_MISMATCH"; return 1;
    }

    rm -rf "$temp_root"
    return 0
}

package_contract_validate_all() {
    local root="$1" profile="${2:-package}"
    package_contract_validate_tree "$root" "$profile" || return 1
    package_contract_validate_module_prop "$root" || return 1
    package_contract_validate_lifecycle_contract "$root" || return 1
    package_contract_for_each_path "$root" "$profile" package_contract_shell_exec_callback || return 1
    package_contract_validate_entrypoints "$root" || return 1
    return 0
}

# Catalog parsing executes nfqws2 dry-runs and scans every shipped preset. It is
# a release qualification boundary, not a device publication or status check.
package_contract_validate_release_all() {
    local root="$1" profile="${2:-package}"
    package_contract_validate_all "$root" "$profile" || return 1
    package_contract_validate_catalog "$root" || return 1
    return 0
}
