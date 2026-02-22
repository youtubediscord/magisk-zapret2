#!/system/bin/sh
# Zapret2 Settings

# Start on boot (1=yes, 0=no)
AUTOSTART=1

# Only work on WiFi (1=yes, 0=no)
WIFI_ONLY=0

# Debug logging (1=yes, 0=no)
DEBUG=0

# Packets to intercept per connection
# --out-range uses this value (e.g. -d20 = first 20 packets)
# Higher value = more packets processed, better bypass but more CPU
# Lower value = fewer packets, less CPU but may miss some connections
PKT_OUT=20
PKT_IN=10

# Strategy preset to use
STRATEGY_PRESET=syndata_multisplit_tls_google_700

# Runtime mode:
# - categories: build command from categories.ini + strategies-*.ini
# - file: load full command blocks from presets/*.txt (Windows-style preset files)
PRESET_MODE=categories

# Preset file name in zapret2/presets/ (used when PRESET_MODE=file)
PRESET_FILE="Default.txt"

# Privilege drop for nfqws2.
# Default: run as root on Android to avoid hostlist access issues.
# Set to "1:3003" if you want privilege drop and your filesystem labels allow it.
NFQWS_UID=""
