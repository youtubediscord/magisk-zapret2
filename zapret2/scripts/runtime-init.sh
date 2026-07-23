#!/system/bin/sh
# Compatibility entry point for package initialization. Runtime configuration
# ownership lives in runtime-config.sh.

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)"
exec sh "$SCRIPT_DIR/runtime-config.sh" "$@"
