#!/usr/bin/env bash
# ==============================================================================
# Provisioning Helper for Dhizuku & Android COSU Kiosk
# ==============================================================================
set -euo pipefail
KIOSK_PKG="com.cosu.kiosk"
TARGET_PKG="org.lds.ldssa"
DHIZUKU_PKG="com.rosan.dhizuku"

echo "=== Android COSU Kiosk Provisioning Script ==="

if ! command -v adb &> /dev/null; then
    echo "Error: adb is not installed or not in PATH."
    exit 1
fi

echo "Checking connected devices..."
adb devices

echo ""
echo "[1/4] Setting Dhizuku as Device Owner..."
adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver || {
    echo "Notice: If accounts exist on the device, activate Dhizuku via Shizuku instead."
}

echo ""
echo "[2/4] Setting $KIOSK_PKG as default Home launcher..."
adb shell cmd package set-home-activity "$KIOSK_PKG/.ui.KioskLauncherActivity"

echo ""
echo "[3/4] Granting runtime permissions to Kiosk Launcher..."
adb shell pm grant "$KIOSK_PKG" android.permission.POST_NOTIFICATIONS || true

echo ""
echo "[4/4] Verifying Target Application ($TARGET_PKG)..."
if adb shell pm list packages | grep -q "$TARGET_PKG"; then
    echo "✓ $TARGET_PKG is installed."
else
    echo "⚠ $TARGET_PKG is NOT installed. Please install Gospel Library from Google Play Store."
fi

echo ""
echo "=== Provisioning Complete ==="
echo "Launch the Kiosk app and approve the Dhizuku binder prompt."
