#!/system/bin/sh

##########################################################################################
# Zapret2 Magisk Module - Installation Script
##########################################################################################

SKIPUNZIP=1

# Detect architecture
ARCH=$(getprop ro.product.cpu.abi)
ui_print "- Detected architecture: $ARCH"

case "$ARCH" in
    arm64-v8a|arm64*)
        ARCH_DIR="arm64-v8a"
        ;;
    armeabi-v7a|armeabi*)
        ARCH_DIR="armeabi-v7a"
        ;;
    x86_64)
        ARCH_DIR="x86_64"
        ;;
    x86)
        ARCH_DIR="x86"
        ;;
    *)
        abort "! Unsupported architecture: $ARCH"
        ;;
esac

ui_print "- Installing Zapret2 for $ARCH_DIR"

# Create directories
mkdir -p "$MODPATH/zapret2/bin"
mkdir -p "$MODPATH/zapret2/lua"
mkdir -p "$MODPATH/zapret2/lists"
mkdir -p "$MODPATH/zapret2/scripts"
mkdir -p "$MODPATH/system/bin"

# Extract all files
ui_print "- Extracting module files..."
unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2

# Copy architecture-specific binary
if [ -f "$MODPATH/zapret2/bin/$ARCH_DIR/nfqws2" ]; then
    cp "$MODPATH/zapret2/bin/$ARCH_DIR/nfqws2" "$MODPATH/zapret2/nfqws2"
    ui_print "- Copied nfqws2 binary for $ARCH_DIR"
else
    ui_print "! Warning: nfqws2 binary not found for $ARCH_DIR"
    ui_print "! Please download from GitHub releases"
fi

# Set permissions
ui_print "- Setting permissions..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zapret2/nfqws2" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-start.sh" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-stop.sh" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-status.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# Check kernel requirements
ui_print "- Checking kernel requirements..."

# Check for NFQUEUE support
if [ -f /proc/net/netfilter/nf_queue ]; then
    ui_print "  [OK] NFQUEUE support found"
else
    ui_print "  [!] NFQUEUE might not be supported"
    ui_print "      Module may not work on this kernel"
fi

# Check iptables
if command -v iptables >/dev/null 2>&1; then
    ui_print "  [OK] iptables found"
else
    ui_print "  [!] iptables not found"
fi

# Create symlink for easy access
ln -sf "$MODPATH/zapret2/scripts/zapret-start.sh" "$MODPATH/system/bin/zapret2-start"
ln -sf "$MODPATH/zapret2/scripts/zapret-stop.sh" "$MODPATH/system/bin/zapret2-stop"
ln -sf "$MODPATH/zapret2/scripts/zapret-status.sh" "$MODPATH/system/bin/zapret2-status"

ui_print ""
ui_print "===================================="
ui_print " Zapret2 installed successfully!"
ui_print "===================================="
ui_print ""
ui_print " Commands available after reboot:"
ui_print "   zapret2-start  - Start DPI bypass"
ui_print "   zapret2-stop   - Stop DPI bypass"
ui_print "   zapret2-status - Check status"
ui_print ""
ui_print " Edit config at:"
ui_print "   /data/adb/modules/zapret2/zapret2/config.sh"
ui_print ""
ui_print " Logs at:"
ui_print "   /data/local/tmp/zapret2.log"
ui_print "   logcat -s Zapret2"
ui_print ""
