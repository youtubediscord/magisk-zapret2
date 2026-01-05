#!/bin/bash
##########################################################################################
# Zapret2 Magisk Module - Local Build Script
##########################################################################################

set -e

VERSION="${1:-1.0.0}"
OUTPUT_DIR="${2:-dist}"

echo "Building Zapret2 Magisk Module v$VERSION"
echo "=========================================="

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Check for binaries
MISSING_BIN=0
for arch in arm64-v8a armeabi-v7a; do
    if [ ! -f "zapret2/bin/$arch/nfqws2" ]; then
        echo "WARNING: Missing binary for $arch"
        MISSING_BIN=1
    fi
done

if [ $MISSING_BIN -eq 1 ]; then
    echo ""
    echo "Some binaries are missing. Options:"
    echo "1. Build with Android NDK (see docs/BUILD.md)"
    echo "2. Download from zapret releases"
    echo "3. Continue without binaries (module will require manual binary installation)"
    echo ""
    read -p "Continue anyway? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Update version in module.prop
VERSION_CODE=$(echo "$VERSION" | tr -d '.' | head -c 6)
sed -i "s/^version=.*/version=v$VERSION/" module.prop
sed -i "s/^versionCode=.*/versionCode=$VERSION_CODE/" module.prop

# Make scripts executable
chmod +x customize.sh service.sh uninstall.sh action.sh 2>/dev/null || true
chmod +x zapret2/scripts/*.sh 2>/dev/null || true

# Create ZIP
ZIP_NAME="zapret2-magisk-v$VERSION.zip"

echo "Creating $ZIP_NAME..."

zip -r "$OUTPUT_DIR/$ZIP_NAME" \
    META-INF \
    module.prop \
    customize.sh \
    service.sh \
    uninstall.sh \
    action.sh \
    zapret2 \
    README.md \
    -x "*.git*" \
    -x "CLAUDE.md" \
    -x ".github/*" \
    -x "build.sh" \
    -x "docs/*"

echo ""
echo "Build complete: $OUTPUT_DIR/$ZIP_NAME"
echo ""

# Show file size
ls -lh "$OUTPUT_DIR/$ZIP_NAME"

# Verify ZIP structure
echo ""
echo "ZIP contents:"
unzip -l "$OUTPUT_DIR/$ZIP_NAME" | head -30
