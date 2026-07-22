#!/system/bin/sh
# Magisk action entry: open the supported Android control app when installed.

APP_PACKAGE="com.zapret2.app"
APP_COMPONENT="$APP_PACKAGE/.MainActivity"

if command -v pm >/dev/null 2>&1 && command -v am >/dev/null 2>&1 &&
    pm path "$APP_PACKAGE" >/dev/null 2>&1; then
    if am start --user current -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        -n "$APP_COMPONENT" >/dev/null 2>&1; then
        exit 0
    fi
fi

echo "Zapret2 Android app is not installed or could not be opened."
echo "Install the signed Zapret2 APK, or use the terminal commands:"
echo "  zapret2-start"
echo "  zapret2-stop"
echo "  zapret2-restart"
echo "  zapret2-status"
echo "  zapret2-full-rollback"
exit 1
