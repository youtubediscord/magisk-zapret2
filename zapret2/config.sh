#!/system/bin/sh
##########################################################################################
# Zapret2 Configuration
##########################################################################################

# Enable autostart on boot (1 = enabled, 0 = disabled)
AUTOSTART=1

# NFQUEUE number (200-300 recommended)
QNUM=200

# Desync mark for avoiding loops
DESYNC_MARK=0x40000000

# Ports to intercept
PORTS_TCP="80,443"
PORTS_UDP="443"

# Packets to intercept (first N packets of connection)
PKT_OUT=20
PKT_IN=10

# Strategy preset (youtube, discord, all, custom)
# youtube - YouTube and Google Video
# discord - Discord TCP/UDP
# all     - All services from hostlists
# custom  - Use CUSTOM_STRATEGY below
STRATEGY_PRESET="youtube"

# Custom strategy (used when STRATEGY_PRESET=custom)
# Format: nfqws2 options (without --qnum)
CUSTOM_STRATEGY='
--filter-tcp=443
--payload=tls_client_hello
--lua-desync=send:repeats=2
--lua-desync=syndata:blob=tls_google
--lua-desync=multisplit:pos=midsld
'

# Enable logging to logcat (android) or file
# Options: android, syslog, file, none
LOG_MODE="android"

# Enable hostlist filtering (requires hostlist files in lists/)
USE_HOSTLIST=0

# Enable ipset filtering (requires ipset files in lists/)
USE_IPSET=0
