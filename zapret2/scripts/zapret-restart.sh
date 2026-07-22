#!/system/bin/sh
# start --replace owns the single lifecycle lock and performs the transaction.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/zapret-start.sh" --replace "$@"
