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
mkdir -p "$MODPATH/webroot"

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

# Set directory permissions (0755 = rwxr-xr-x)
find "$MODPATH" -type d -exec chmod 0755 {} \;

# Set file permissions (0644 = rw-r--r--)
find "$MODPATH" -type f -exec chmod 0644 {} \;

# Set executable permissions for scripts and binary
set_perm "$MODPATH/zapret2/nfqws2" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-start.sh" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-stop.sh" 0 0 0755
set_perm "$MODPATH/zapret2/scripts/zapret-status.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755

# Make sure bin and lua directories are accessible
chmod 0755 "$MODPATH/zapret2/bin"
chmod 0755 "$MODPATH/zapret2/lua"
chmod 0755 "$MODPATH/zapret2/lists"
chmod 0755 "$MODPATH/zapret2/scripts"
chmod 0755 "$MODPATH/zapret2"

# Set read permissions on all data files
chmod -R 0644 "$MODPATH/zapret2/bin/"*.bin 2>/dev/null || true
chmod -R 0644 "$MODPATH/zapret2/lua/"*.lua 2>/dev/null || true
chmod -R 0644 "$MODPATH/zapret2/lists/"*.txt 2>/dev/null || true

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

# Create user config if not exists (preserve user settings across updates)
USER_HOSTLISTS="/data/local/tmp/zapret2-hostlists.conf"
if [ ! -f "$USER_HOSTLISTS" ]; then
    ui_print "- Creating user hostlists config..."
    cp "$MODPATH/zapret2/hostlists-default.conf" "$USER_HOSTLISTS"
    chmod 644 "$USER_HOSTLISTS"
    ui_print "  [OK] User config created at $USER_HOSTLISTS"
else
    ui_print "- User hostlists config already exists"
    ui_print "  [OK] Preserving user settings at $USER_HOSTLISTS"
fi

ui_print ""
ui_print "===================================="
ui_print " Zapret2 installed successfully!"
ui_print "===================================="
ui_print ""
ui_print " WebUI: Install KSUWebUI app"
ui_print "        and select Zapret2 module"
ui_print ""
ui_print " Terminal commands:"
ui_print "   zapret2-start  - Start"
ui_print "   zapret2-stop   - Stop"
ui_print "   zapret2-status - Status"
ui_print ""
ui_print " Config files:"
ui_print "   Default: $MODPATH/zapret2/hostlists-default.conf"
ui_print "   User:    $USER_HOSTLISTS (preserved on updates)"
ui_print ""
