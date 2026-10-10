#!/usr/bin/env bash
# ==============================================================================
# Kiosk State & Policy Verification Script
# ==============================================================================
set -euo pipefail

echo "=== Inspecting Kiosk State ==="
echo ""
echo "1. Current Lock Task Packages:"
adb shell dumpsys device_policy | grep -A 5 "lockTaskPackages" || echo "Unable to query lockTaskPackages"

echo ""
echo "2. Current Default Home Activity:"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

echo ""
echo "3. Active Device Owners:"
adb shell dumpsys device_policy | grep -A 2 "Device Owner:" || echo "No direct DO found (Dhizuku may be proxying)"

echo ""
echo "4. User Restrictions Summary:"
adb shell dumpsys user | grep -E "disallow_safe_boot|disallow_factory_reset|disallow_debugging_features|disallow_add_user" || echo "None set"

echo ""
echo "=== Inspection Finished ==="
