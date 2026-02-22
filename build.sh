#!/bin/bash
##########################################################################################
# Zapret2 Magisk Module - Local Build Script
##########################################################################################

set -e

VERSION="${1:-}"
OUTPUT_DIR="${2:-dist}"

if [ ! -f "version.series" ]; then
    echo "ERROR: version.series file is missing"
    exit 1
fi

VERSION_SERIES=$(tr -d '[:space:]' < version.series)
if [[ ! "$VERSION_SERIES" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: invalid version.series value '$VERSION_SERIES' (expected MAJOR.MINOR)"
    exit 1
fi

if [ -n "$VERSION" ]; then
    VERSION="${VERSION#v}"
    VERSION_CODE=$(printf '%s' "$VERSION" | tr -cd '0-9' | head -c 9)
    if [ -z "$VERSION_CODE" ]; then
        VERSION_CODE=1
    fi
else
    COMMIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || true)
    if [ -z "$COMMIT_COUNT" ]; then
        COMMIT_COUNT=1
    fi
    VERSION_CODE=$((100000 + COMMIT_COUNT))
    VERSION="${VERSION_SERIES}.${VERSION_CODE}"
fi

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
