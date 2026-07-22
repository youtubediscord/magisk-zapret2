#!/system/bin/sh
# Shared parser and validator for zapret2/runtime-manifest.tsv.
#
# The manifest separates immutable package files, mutable seed data, ABI
# binaries, generated installed files, catalog entries, and the exact declared
# dependency closure for compatible presets.  Mutable seeds are required in a
# fresh package but may be restored from the previous installation afterwards.

PACKAGE_CONTRACT_MANIFEST_REL="zapret2/runtime-manifest.tsv"
PACKAGE_CONTRACT_OWNER_PROTOCOL=6
PACKAGE_CONTRACT_MAX_MANIFEST_BYTES=262144
PACKAGE_CONTRACT_MAX_MODULE_PROP_BYTES=4096
PACKAGE_CONTRACT_MAX_SHELL_EXEC_BYTES=262144
PACKAGE_CONTRACT_MAX_CMDLINE_BYTES=262144
PACKAGE_CONTRACT_UPDATE_JSON="https://github.com/youtubediscord/magisk-zapret2/releases/latest/download/update.json"
PACKAGE_CONTRACT_CODE="OK"
PACKAGE_CONTRACT_DETAIL=""

package_contract_fail() {
    PACKAGE_CONTRACT_CODE="$1"
    PACKAGE_CONTRACT_DETAIL="${2:-}"
    return 1
}

package_contract_safe_relative_path() {
    local path="$1"
    [ -n "$path" ] || return 1
    [ "${path# }" = "$path" ] && [ "${path% }" = "$path" ] || return 1
    case "$path" in
        /*|*\\*|*//*|*/|../*|*/../*|*/..|.|./*|*/./*|*/.) return 1 ;;
    esac
    if LC_ALL=C printf '%s' "$path" | grep -q '[[:cntrl:]|]'; then return 1; fi
    package_contract_safe_path_component_lengths "$path" || return 1
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

package_contract_safe_preset_name() {
    local value="$1"
    [ -n "$value" ] && package_contract_safe_file_name_byte_length "$value" || return 1
    [ "${value# }" = "$value" ] && [ "${value% }" = "$value" ] || return 1
    case "$value" in
        _*|.|..|*/*|*\\*|*"'"*|*'"'*|*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT) return 1 ;;
        *.txt) ;;
        *) return 1 ;;
    esac
    if LC_ALL=C printf '%s' "$value" | grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

package_contract_safe_cmdline_name() {
    local value="$1" normalized
    [ -n "$value" ] && package_contract_safe_file_name_byte_length "$value" || return 1
    [ "$value" = "$(printf '%s' "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" ] || return 1
    case "$value" in .|..|*/*|*\\*) return 1 ;; esac
    if LC_ALL=C printf '%s' "$value" | grep -q "[\"']"; then return 1; fi
    if LC_ALL=C printf '%s' "$value" | grep -q '[[:cntrl:]]'; then return 1; fi
    normalized="$(LC_ALL=C printf '%s' "$value" | tr '[:upper:]' '[:lower:]')" || return 1
    case "$normalized" in
        runtime-manifest.tsv|upstream-zapret2.commit|strategies-tcp.ini|strategies-udp.ini|strategies-stun.ini|blobs.txt|config.sh|runtime.ini|categories.ini|hosts.ini|nfqws2|bin|lua|lists|presets|scripts) return 1 ;;
    esac
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

package_contract_configured_cmdline_relative() {
    local root="${1%/}" name
    name="$(package_contract_runtime_core_value "$root" custom_cmdline_file)" || return 1
    package_contract_safe_cmdline_name "$name" || return 1
    printf 'zapret2/%s\n' "$name"
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

package_contract_file_path_collides() {
    local seen="$1"
    local candidate="$2"
    local existing=""
    while IFS= read -r existing || [ -n "$existing" ]; do
        case "$candidate" in "$existing"/*) return 0 ;; esac
        case "$existing" in "$candidate"/*) return 0 ;; esac
    done < "$seen"
    return 1
}

package_contract_record_manifest_file_path() {
    local seen="$1"
    local candidate="$2"
    local detail="$3"
    if grep -Fqx -e "$candidate" "$seen"; then
        package_contract_fail "MANIFEST_DUPLICATE_PATH" "$detail"
        return 1
    fi
    if package_contract_file_path_collides "$seen" "$candidate"; then
        package_contract_fail "MANIFEST_PATH_COLLISION" "$detail"
        return 1
    fi
    printf '%s\n' "$candidate" >> "$seen" || {
        package_contract_fail "MANIFEST_TEMP_FAILED"
        return 1
    }
}

package_contract_validate_manifest() {
    local root="$1"
    local manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    local seen=""
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
    seen="$(mktemp)" || { package_contract_fail "MANIFEST_TEMP_FAILED"; return 1; }
    cr=$(printf '\r')
    while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
        line_number=$((line_number + 1))
        class="${class%"$cr"}"
        mode="${mode%"$cr"}"
        path="${path%"$cr"}"
        extra="${extra%"$cr"}"
        case "$class" in ""|'#'*) continue ;; esac
        [ -z "$extra" ] || { rm -f "$seen"; package_contract_fail "MANIFEST_FIELD_COUNT" "$class"; return 1; }
        if [ "$class" = "schema" ]; then
            [ "$metadata_stage" -eq 0 ] && [ "$line_number" -eq 1 ] || {
                rm -f "$seen"; package_contract_fail "MANIFEST_METADATA_ORDER" "schema"; return 1;
            }
            [ "$mode" = "1" ] && [ "$path" = "zapret2-runtime" ] || {
                rm -f "$seen"; package_contract_fail "MANIFEST_SCHEMA" "$mode|$path"; return 1;
            }
            schema_count=$((schema_count + 1))
            metadata_stage=1
            continue
        fi
        if [ "$class" = "owner_protocol" ]; then
            [ "$metadata_stage" -eq 1 ] && [ "$line_number" -eq 2 ] || {
                rm -f "$seen"; package_contract_fail "MANIFEST_METADATA_ORDER" "owner_protocol"; return 1;
            }
            [ "$mode" = "$PACKAGE_CONTRACT_OWNER_PROTOCOL" ] && [ "$path" = "zapret2-firewall" ] || {
                rm -f "$seen"; package_contract_fail "MANIFEST_OWNER_PROTOCOL" "$mode|$path"; return 1;
            }
            owner_protocol_count=$((owner_protocol_count + 1))
            metadata_stage=2
            continue
        fi
        [ "$metadata_stage" -eq 2 ] || {
            rm -f "$seen"; package_contract_fail "MANIFEST_METADATA_ORDER" "$class"; return 1;
        }
        case "$class" in
            immutable-file|mutable-seed|runtime-dependency-immutable|runtime-dependency-mutable-seed|preset-compatible|preset-quarantined)
                [ "$mode" = "0644" ] || { rm -f "$seen"; package_contract_fail "MANIFEST_MODE" "$class|$mode"; return 1; }
                ;;
            immutable-exec|abi-exec|installed-exec)
                [ "$mode" = "0755" ] || { rm -f "$seen"; package_contract_fail "MANIFEST_MODE" "$class|$mode"; return 1; }
                ;;
            *) rm -f "$seen"; package_contract_fail "MANIFEST_CLASS" "$class"; return 1 ;;
        esac
        package_contract_safe_relative_path "$path" || {
            rm -f "$seen"; package_contract_fail "MANIFEST_PATH" "$path"; return 1;
        }
        case "$class:$path" in
            abi-exec:zapret2/bin/'{abi}'/nfqws2) ;;
            abi-exec:*) rm -f "$seen"; package_contract_fail "MANIFEST_ABI_TEMPLATE" "$path"; return 1 ;;
            installed-exec:zapret2/nfqws2) ;;
            installed-exec:*) rm -f "$seen"; package_contract_fail "MANIFEST_INSTALLED_EXEC" "$path"; return 1 ;;
            preset-compatible:zapret2/presets/*.txt|preset-quarantined:zapret2/presets/*.txt)
                package_contract_safe_preset_name "${path##*/}" || {
                    rm -f "$seen"; package_contract_fail "MANIFEST_PRESET_PATH" "$path"; return 1;
                }
                ;;
            preset-compatible:*|preset-quarantined:*) rm -f "$seen"; package_contract_fail "MANIFEST_PRESET_PATH" "$path"; return 1 ;;
        esac
        if [ "$class" = "abi-exec" ]; then
            for expanded_abi in arm64-v8a armeabi-v7a; do
                expanded_path="zapret2/bin/$expanded_abi/nfqws2"
                package_contract_record_manifest_file_path "$seen" "$expanded_path" "$path" || {
                    rm -f "$seen"
                    return 1
                }
            done
        else
            package_contract_record_manifest_file_path "$seen" "$path" "$path" || {
                rm -f "$seen"
                return 1
            }
        fi
        entry_count=$((entry_count + 1))
    done < "$manifest"
    rm -f "$seen"
    [ "$schema_count" -eq 1 ] || { package_contract_fail "MANIFEST_SCHEMA_COUNT" "$schema_count"; return 1; }
    [ "$owner_protocol_count" -eq 1 ] || { package_contract_fail "MANIFEST_OWNER_PROTOCOL_COUNT" "$owner_protocol_count"; return 1; }
    [ "$entry_count" -gt 0 ] || { package_contract_fail "MANIFEST_EMPTY"; return 1; }
    for required_entry in \
        "immutable-file|0644|module.prop" \
        "immutable-file|0644|zapret2/runtime-manifest.tsv" \
        "immutable-file|0644|zapret2/upstream-zapret2.commit" \
        "immutable-file|0644|zapret2/strategies-tcp.ini" \
        "immutable-file|0644|zapret2/strategies-udp.ini" \
        "immutable-file|0644|zapret2/strategies-stun.ini" \
        "immutable-file|0644|zapret2/blobs.txt" \
        "mutable-seed|0644|zapret2/config.sh" \
        "mutable-seed|0644|zapret2/runtime.ini" \
        "mutable-seed|0644|zapret2/categories.ini" \
        "mutable-seed|0644|zapret2/hosts.ini" \
        "runtime-dependency-immutable|0644|zapret2/lua/custom_funcs.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-antidpi.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-auto.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-lib.lua" \
        "runtime-dependency-immutable|0644|zapret2/lua/zapret-multishake.lua" \
        "immutable-exec|0755|customize.sh" \
        "immutable-exec|0755|service.sh" \
        "immutable-exec|0755|uninstall.sh" \
        "immutable-exec|0755|action.sh" \
        "immutable-exec|0755|zapret2/scripts/common.sh" \
        "immutable-exec|0755|zapret2/scripts/command-builder.sh" \
        "immutable-exec|0755|zapret2/scripts/package-contract.sh" \
        "immutable-exec|0755|zapret2/scripts/runtime-migrate.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-start.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-stop.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-restart.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-status.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-update-guard.sh" \
        "immutable-exec|0755|zapret2/scripts/zapret-full-rollback.sh" \
        "immutable-exec|0755|system/bin/zapret2-start" \
        "immutable-exec|0755|system/bin/zapret2-stop" \
        "immutable-exec|0755|system/bin/zapret2-status" \
        "immutable-exec|0755|system/bin/zapret2-restart" \
        "immutable-exec|0755|system/bin/zapret2-full-rollback" \
        "abi-exec|0755|zapret2/bin/{abi}/nfqws2" \
        "installed-exec|0755|zapret2/nfqws2"
    do
        package_contract_manifest_has_entry "$manifest" "$required_entry" || {
            package_contract_fail "MANIFEST_REQUIRED_ENTRY" "$required_entry"
            return 1
        }
    done
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
                !canonical_long(parts[2]) || !canonical_version_code(parts[3]) ||
                properties["versionCode"] != parts[3]) exit 1
        }
    ' "$file" || {
        package_contract_fail "MODULE_PROP_INVALID" "module.prop"
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
    if ! grep -Fqx -e "$path" "$PACKAGE_CONTRACT_ALLOWED_FILES"; then
        printf '%s\n' "$path" >> "$PACKAGE_CONTRACT_ALLOWED_FILES" || return 1
    fi
    case "$path" in
        */*) parent="${path%/*}" ;;
        *) parent="" ;;
    esac
    while [ -n "$parent" ]; do
        if ! grep -Fqx -e "$parent" "$PACKAGE_CONTRACT_ALLOWED_DIRECTORIES"; then
            printf '%s\n' "$parent" >> "$PACKAGE_CONTRACT_ALLOWED_DIRECTORIES" || return 1
        fi
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
    [ "$result" -eq 0 ] || package_contract_fail "ALLOWLIST_TEMP_FAILED"
    return "$result"
}

package_contract_validate_exact_tree() {
    local root="${1%/}" profile="${2:-package}" meta_policy="${3:-no-meta}"
    local files="" directories="" listing="" entry="" relative="" allowed=0 configured_cmdline=""
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
    package_contract_build_allowlist "$root" "$profile" "$files" "$directories" || {
        rm -f "$files" "$directories" "$listing"; return 1;
    }
    if [ "$profile" = installed ]; then
        configured_cmdline="$(package_contract_configured_cmdline_relative "$root")" || {
            rm -f "$files" "$directories" "$listing"
            package_contract_fail "CONFIGURED_CMDLINE_BINDING_INVALID"
            return 1
        }
    fi
    find "$root" -mindepth 1 -print > "$listing" 2>/dev/null || {
        rm -f "$files" "$directories" "$listing"; package_contract_fail "PACKAGE_ENUMERATION_FAILED"; return 1;
    }
    while IFS= read -r entry || [ -n "$entry" ]; do
        relative="${entry#"$root"/}"
        package_contract_safe_relative_path "$relative" || {
            rm -f "$files" "$directories" "$listing"; package_contract_fail "PACKAGE_UNSAFE_ENTRY" "$relative"; return 1;
        }
        [ ! -L "$entry" ] || {
            rm -f "$files" "$directories" "$listing"; package_contract_fail "PACKAGE_SYMLINK" "$relative"; return 1;
        }
        allowed=0
        if [ -f "$entry" ]; then
            [ "$(stat -c %h "$entry" 2>/dev/null)" = 1 ] || {
                rm -f "$files" "$directories" "$listing"; package_contract_fail "PACKAGE_LINK_COUNT" "$relative"; return 1;
            }
            grep -Fqx -e "$relative" "$files" && allowed=1
            if [ "$allowed" -eq 0 ] && [ "$profile" = installed ]; then
                case "$relative" in
                    disable|zapret2/install-generation.meta|zapret2/lists/*) allowed=1 ;;
                esac
                [ "$relative" = "$configured_cmdline" ] && allowed=1
            fi
            if [ "$allowed" -eq 0 ] && [ "$meta_policy" = allow-meta ]; then
                case "$relative" in META-INF/*) allowed=1 ;; esac
            fi
        elif [ -d "$entry" ]; then
            grep -Fqx -e "$relative" "$directories" && allowed=1
            if [ "$allowed" -eq 0 ] && [ "$profile" = installed ]; then
                case "$relative" in zapret2/lists/*) allowed=1 ;; esac
            fi
            if [ "$allowed" -eq 0 ] && [ "$meta_policy" = allow-meta ]; then
                case "$relative" in META-INF|META-INF/*) allowed=1 ;; esac
            fi
        fi
        [ "$allowed" -eq 1 ] || {
            rm -f "$files" "$directories" "$listing"; package_contract_fail "PACKAGE_UNDECLARED_ENTRY" "$relative"; return 1;
        }
    done < "$listing"
    rm -f "$files" "$directories" "$listing"
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

package_contract_mode_callback() {
    local root="$1" class="$2" expected="$3" path="$4" actual=""
    actual="$(stat -c '%a' "$root/$path" 2>/dev/null)" || {
        package_contract_fail "PACKAGE_MODE_UNREADABLE" "$path"; return 1;
    }
    [ "0$actual" = "$expected" ] || {
        package_contract_fail "PACKAGE_MODE" "$path:$actual:$expected"; return 1;
    }
    return 0
}

package_contract_chmod_callback() {
    local root="$1" class="$2" mode="$3" path="$4"
    chmod "$mode" "$root/$path" || {
        package_contract_fail "PACKAGE_CHMOD" "$path"; return 1;
    }
}

package_contract_validate_configured_cmdline() {
    local root="${1%/}" relative path size mode file_mode
    mode="$(package_contract_runtime_core_value "$root" preset_mode)" || {
        package_contract_fail "RUNTIME_PRESET_MODE_INVALID"
        return 1
    }
    case "$mode" in categories|file|preset|txt|cmdline) ;; *) package_contract_fail "RUNTIME_PRESET_MODE_INVALID" "$mode"; return 1 ;; esac
    relative="$(package_contract_configured_cmdline_relative "$root")" || {
        package_contract_fail "CONFIGURED_CMDLINE_BINDING_INVALID"
        return 1
    }
    path="$root/$relative"
    if [ ! -e "$path" ] && [ ! -L "$path" ]; then
        [ "$mode" != cmdline ] && return 0
        package_contract_fail "CONFIGURED_CMDLINE_MISSING" "$relative"
        return 1
    fi
    [ -f "$path" ] && [ ! -L "$path" ] || {
        package_contract_fail "CONFIGURED_CMDLINE_UNSAFE" "$relative"
        return 1
    }
    [ "$(stat -c %u "$path" 2>/dev/null)" = 0 ] &&
        [ "$(stat -c %h "$path" 2>/dev/null)" = 1 ] || {
        package_contract_fail "CONFIGURED_CMDLINE_OWNER" "$relative"
        return 1
    }
    size="$(wc -c < "$path" 2>/dev/null)" || {
        package_contract_fail "CONFIGURED_CMDLINE_SIZE" "$relative"
        return 1
    }
    case "$size" in ''|*[!0-9]*) package_contract_fail "CONFIGURED_CMDLINE_SIZE" "$relative"; return 1 ;; esac
    [ "$size" -le "$PACKAGE_CONTRACT_MAX_CMDLINE_BYTES" ] || {
        package_contract_fail "CONFIGURED_CMDLINE_TOO_LARGE" "$relative:$size"
        return 1
    }
    if [ "$mode" = cmdline ] && [ "$size" -eq 0 ]; then
        package_contract_fail "CONFIGURED_CMDLINE_EMPTY" "$relative"
        return 1
    fi
    file_mode="$(stat -c %a "$path" 2>/dev/null)" || {
        package_contract_fail "CONFIGURED_CMDLINE_MODE" "$relative"
        return 1
    }
    case "$file_mode" in
        600|644) ;;
        *)
            package_contract_fail "CONFIGURED_CMDLINE_MODE" "$relative:$file_mode"
            return 1
            ;;
    esac
    return 0
}

package_contract_validate_runtime_selection() {
    local root="${1%/}" mode preset relative path manifest
    package_contract_validate_configured_cmdline "$root" || return 1
    mode="$(package_contract_runtime_core_value "$root" preset_mode)" || {
        package_contract_fail "RUNTIME_PRESET_MODE_INVALID"
        return 1
    }
    case "$mode" in
        categories|cmdline) return 0 ;;
        file|preset|txt) ;;
        *) package_contract_fail "RUNTIME_PRESET_MODE_INVALID" "$mode"; return 1 ;;
    esac
    preset="$(package_contract_runtime_core_value "$root" preset_file)" || {
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
    package_contract_manifest_has_entry "$manifest" "preset-compatible|0644|$relative" || {
        package_contract_fail "RUNTIME_PRESET_NOT_COMPATIBLE" "$relative"
        return 1
    }
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
    local root="$1" profile="${2:-package}"
    package_contract_validate_manifest "$root" || return 1
    package_contract_for_each_path "$root" "$profile" package_contract_content_callback || return 1
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

package_contract_zip_callback() {
    local root="$1" class="$2" mode="$3" path="$4" count=""
    count="$(grep -Fxc -e "$path" "$PACKAGE_CONTRACT_ZIP_NAMES" 2>/dev/null)"
    [ "$count" -eq 1 ] || {
        package_contract_fail "ZIP_ENTRY_COUNT" "$path:$count"; return 1;
    }
}

package_contract_archive_path_collides() {
    local records="$1"
    local candidate="$2"
    local candidate_kind="$3"
    local existing_kind=""
    local existing_path=""
    while IFS='|' read -r existing_kind existing_path || [ -n "$existing_kind$existing_path" ]; do
        [ -n "$existing_path" ] || continue
        if [ "$existing_kind" = file ]; then
            case "$candidate" in "$existing_path"/*) return 0 ;; esac
        fi
        if [ "$candidate_kind" = file ]; then
            case "$existing_path" in "$candidate"/*) return 0 ;; esac
        fi
    done < "$records"
    return 1
}

package_contract_validate_zip_names() {
    local root="$1" names="$2" result=0 files="" directories="" seen=""
    local raw="" path="" kind="" allowed=0
    package_contract_validate_manifest "$root" || return 1
    [ -f "$names" ] && [ ! -L "$names" ] && [ -s "$names" ] || { package_contract_fail "ZIP_LIST_MISSING"; return 1; }
    PACKAGE_CONTRACT_ZIP_NAMES="$names"
    export PACKAGE_CONTRACT_ZIP_NAMES
    package_contract_for_each_path "$root" package package_contract_zip_callback
    result=$?
    unset PACKAGE_CONTRACT_ZIP_NAMES
    [ "$result" -eq 0 ] || return "$result"
    files="$(mktemp)" || { package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    directories="$(mktemp)" || { rm -f "$files"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    seen="$(mktemp)" || { rm -f "$files" "$directories"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1; }
    package_contract_build_allowlist "$root" package "$files" "$directories" || {
        rm -f "$files" "$directories" "$seen"; return 1;
    }
    while IFS= read -r raw || [ -n "$raw" ]; do
        case "$raw" in
            */) path="${raw%/}"; kind=directory ;;
            *) path="$raw"; kind=file ;;
        esac
        package_contract_safe_relative_path "$path" || {
            rm -f "$files" "$directories" "$seen"; package_contract_fail "ZIP_UNSAFE_ENTRY" "$raw"; return 1;
        }
        if grep -Fqx -e "file|$path" -e "directory|$path" "$seen"; then
            rm -f "$files" "$directories" "$seen"; package_contract_fail "ZIP_DUPLICATE_ENTRY" "$path"; return 1
        fi
        if package_contract_archive_path_collides "$seen" "$path" "$kind"; then
            rm -f "$files" "$directories" "$seen"; package_contract_fail "ZIP_PATH_COLLISION" "$path"; return 1
        fi
        printf '%s|%s\n' "$kind" "$path" >> "$seen" || {
            rm -f "$files" "$directories" "$seen"; package_contract_fail "ALLOWLIST_TEMP_FAILED"; return 1;
        }
        allowed=0
        case "$path" in
            META-INF|META-INF/*) allowed=1 ;;
        esac
        if [ "$allowed" -eq 0 ] && [ "$kind" = file ]; then
            grep -Fqx -e "$path" "$files" && allowed=1
        elif [ "$allowed" -eq 0 ]; then
            grep -Fqx -e "$path" "$directories" && allowed=1
        fi
        [ "$allowed" -eq 1 ] || {
            rm -f "$files" "$directories" "$seen"; package_contract_fail "ZIP_UNDECLARED_ENTRY" "$path"; return 1;
        }
    done < "$names"
    rm -f "$files" "$directories" "$seen"
    return 0
}

package_contract_apply_modes() {
    local root="$1" profile="${2:-package}"
    package_contract_validate_tree "$root" "$profile" || return 1
    package_contract_for_each_path "$root" "$profile" package_contract_chmod_callback || return 1
}

package_contract_validate_modes() {
    local root="$1" profile="${2:-package}"
    package_contract_for_each_path "$root" "$profile" package_contract_mode_callback
}

package_contract_validate_blob_catalog() {
    local root="$1"
    local manifest="$root/$PACKAGE_CONTRACT_MANIFEST_REL"
    local blobs="$root/zapret2/blobs.txt"
    local line="" raw="" relative="" cr
    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        case "$line" in ""|'#'*|';'*) continue ;; esac
        case "$line" in
            --blob=*:@bin/*)
                raw="${line##*:@bin/}"
                case "$raw" in ""|*/*|*\\*|*"'"*|*'"'*) package_contract_fail "BLOB_PATH" "$raw"; return 1 ;; esac
                relative="zapret2/bin/$raw"
                grep -Fqx "runtime-dependency-immutable|0644|$relative" "$manifest" || {
                    package_contract_fail "BLOB_NOT_DECLARED" "$relative"; return 1;
                }
                ;;
            --blob=*:0x*) ;;
            *) package_contract_fail "BLOB_CATALOG_LINE" "$line"; return 1 ;;
        esac
    done < "$blobs"
    return 0
}

package_contract_validate_category_dependencies() {
    local root="$1" declared="$2"
    local categories="$root/zapret2/categories.ini" references="" name="" relative=""
    package_contract_check_regular "$categories" "zapret2/categories.ini" || return 1
    references="$(mktemp)" || { package_contract_fail "CATEGORY_TEMP_FAILED"; return 1; }
    awk -F '=' '
        $1 == "hostlist" || $1 == "ipset" {
            value = substr($0, index($0, "=") + 1)
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            if (value != "") print value
        }
    ' "$categories" > "$references" || {
        rm -f "$references"; package_contract_fail "CATEGORY_SCAN_FAILED"; return 1;
    }
    while IFS= read -r name || [ -n "$name" ]; do
        case "$name" in */*|*\\*|.|..) rm -f "$references"; package_contract_fail "CATEGORY_DEPENDENCY_PATH" "$name"; return 1 ;; esac
        relative="zapret2/lists/$name"
        package_contract_safe_relative_path "$relative" || {
            rm -f "$references"; package_contract_fail "CATEGORY_DEPENDENCY_PATH" "$name"; return 1;
        }
        grep -Fqx -e "$relative" "$declared" || {
            rm -f "$references"; package_contract_fail "CATEGORY_DEPENDENCY_NOT_DECLARED" "$relative"; return 1;
        }
        package_contract_check_regular "$root/$relative" "$relative" || { rm -f "$references"; return 1; }
    done < "$references"
    rm -f "$references"
    return 0
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

    package_contract_validate_category_dependencies "$root" "$declared" || {
        rm -rf "$temp_root"
        return 1
    }

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

    awk -F '\t' '$1 == "Z2_PRESET" { print $4 "\t" $2 }' "$scan" | LC_ALL=C sort > "$actual"
    LC_ALL=C sort "$expected" -o "$expected"
    cmp -s "$expected" "$actual" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_CLASSIFICATION_MISMATCH"; return 1;
    }
    [ "$(grep -c '^Z2_PRESET_SUMMARY[[:space:]]' "$scan")" -eq 1 ] || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_SUMMARY"; return 1;
    }

    for file in "$root/zapret2/presets"/*.txt; do
        [ -e "$file" ] || [ -L "$file" ] || continue
        [ -f "$file" ] && [ ! -L "$file" ] || {
            rm -rf "$temp_root"; package_contract_fail "CATALOG_UNSAFE_ENTRY" "${file##*/}"; return 1;
        }
        printf '%s\n' "${file##*/}" >> "$catalog_actual"
    done
    LC_ALL=C sort "$catalog_expected" -o "$catalog_expected"
    LC_ALL=C sort "$catalog_actual" -o "$catalog_actual"
    cmp -s "$catalog_expected" "$catalog_actual" || {
        rm -rf "$temp_root"; package_contract_fail "CATALOG_CONTENT_MISMATCH"; return 1;
    }

    package_contract_validate_blob_catalog "$root" || { rm -rf "$temp_root"; return 1; }
    rm -rf "$temp_root"
    return 0
}

package_contract_validate_all() {
    local root="$1" profile="${2:-package}"
    package_contract_validate_tree "$root" "$profile" || return 1
    package_contract_validate_module_prop "$root" || return 1
    package_contract_for_each_path "$root" "$profile" package_contract_shell_exec_callback || return 1
    package_contract_validate_catalog "$root" || return 1
    return 0
}
