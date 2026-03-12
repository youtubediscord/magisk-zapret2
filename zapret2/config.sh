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
# - cmdline: load raw nfqws2 options from CUSTOM_CMDLINE_FILE
PRESET_MODE=categories

# Preset file name in zapret2/presets/ (used when PRESET_MODE=file)
PRESET_FILE="Default.txt"

# Raw nfqws2 options file (used when PRESET_MODE=cmdline)
# Relative paths are resolved from zapret2/ directory
CUSTOM_CMDLINE_FILE="cmdline.txt"

# UID:GID for nfqws2.
# NOTE: nfqws2 has internal default 2147483647:2147483647 when --uid is not passed,
# so keep this set explicitly.
# Recommended on Android: 0:0 (avoids hostlist permission issues).
# Alternative hardened mode: 1:3003
NFQWS_UID="0:0"

# Logging mode: android, file, syslog, none
LOG_MODE=none
