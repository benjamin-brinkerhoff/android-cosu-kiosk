# Android Dedicated Device (COSU) Kiosk Framework

A production-grade, dedicated device framework for modern Android smartphones (tested on **Samsung Galaxy A15 5G**, **Android 14–16**, and **Samsung One UI 8.5**).

This project transforms consumer Android hardware into a dedicated Corporate-Owned, Single-Use (COSU) appliance locked strictly to **Gospel Library** (`org.lds.ldssa`), while solving the **Quick Settings Paradox**: enabling Wi-Fi and Bluetooth toggles in the drop-down shade while permanently neutralizing access to the underlying system Settings application.

---

## Architecture Overview

A resilient dedicated device environment rests upon three interdependent layers:

1. **Target Application (`org.lds.ldssa`)**: The primary user application (Gospel Library), encapsulated via `ActivityOptions.setLockTaskEnabled(true)`.
2. **Custom Kiosk Launcher (`com.cosu.kiosk`)**: Acts as the default HOME activity, intercepts navigation, manages the Lock Task allowlist and UI feature bitmask, runs a persistent watchdog service, and hosts a hidden multi-tap administrative egress gateway.
3. **Privilege Proxy (Dhizuku & Shizuku)**: Obtains and delegates Device Owner (DO) privileges via Wireless Debugging/ADB without requiring a factory reset, simultaneously sharing the `DevicePolicyManager` binder with both the custom launcher and OwnDroid.
4. **Hardening Suite (OwnDroid / UserManager)**: Enforces critical OS-level restrictions (`DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_ADD_USER`, `DISALLOW_MOUNT_PHYSICAL_MEDIA`).

---

## Key Architectural Solutions

### 1. The Quick Settings Paradox
- **Requirement**: Users must be able to swipe down the notification shade to toggle Wi-Fi and pair Bluetooth audio peripherals for Gospel Library reading and listening.
- **Problem**: Swiping down Quick Settings exposes the **gear icon** (shortcut to `com.android.settings`), which would break the sandbox.
- **Solution**:
  1. Configure `setLockTaskFeatures(admin, LOCK_TASK_FEATURE_NOTIFICATIONS | LOCK_TASK_FEATURE_SYSTEM_INFO)`.
  2. Maintain `setLockTaskPackages(admin, ["com.cosu.kiosk", "org.lds.ldssa"])`.
  3. Because `com.android.settings` is strictly omitted from the allowlist, Android's `ActivityManagerService` intercepts and silently drops any intent fired by tapping the gear icon. Quick toggles function, while device settings remain permanently unreachable.

### 2. Privilege Escalation Without Factory Reset
- Conventional Device Owner provisioning requires a complete factory reset (`dpm set-device-owner`).
- Using **Shizuku** (via Wireless Debugging) and **Dhizuku**, DO permissions are established and delegated directly to both the **Custom Kiosk Launcher** and **OwnDroid** without wiping consumer data.

### 3. Continuous Watchdog & State Recovery
- A persistent foreground service (`LockTaskWatchdogService`) polls `ActivityManager.lockTaskModeState` every 2.5 seconds.
- If Gospel Library crashes, updates, or transitions to `LOCK_TASK_MODE_NONE`, the watchdog immediately re-launches the application with `ActivityOptions.setLockTaskEnabled(true)` and `FLAG_ACTIVITY_REORDER_TO_FRONT`.

### 4. Authenticated Administrative Egress
- The launcher embeds an invisible touch target in the upper-right corner.
- Rapidly tapping the hotspot 5 times triggers an authenticated PIN dialog (`AdminEgressDialog`).
- Once validated, the launcher calls `stopLockTask()`, halts the watchdog service, and opens the system settings for maintenance.

---

## Step-by-Step Deployment Guide (Samsung A15 5G)

### Step 1: Install Required Packages
1. Install **Gospel Library** (`org.lds.ldssa`) from the Google Play Store.
2. Install **Shizuku** and **Dhizuku** from GitHub / F-Droid.
3. Install **OwnDroid** (for visual toggle management).
4. Install this **Kiosk Launcher** (`com.cosu.kiosk`).

### Step 2: Activate Shizuku & Dhizuku
1. On the device, navigate to **Settings > Developer options > Wireless debugging**.
2. Pair and start Shizuku.
3. Open Dhizuku, activate it via Shizuku, and grant Device Owner proxy authority.
4. In Dhizuku, grant permissions to both **Dedicated Kiosk** (`com.cosu.kiosk`) and **OwnDroid**.

### Step 3: Set Default Launcher
Run via ADB or device settings:
```bash
adb shell cmd package set-home-activity com.cosu.kiosk/.ui.KioskLauncherActivity
```

### Step 4: Apply OS Hardening via OwnDroid
Open OwnDroid and activate:
- `DISALLOW_SAFE_BOOT`: Neutralizes volume-down Safe Mode boot exploits.
- `DISALLOW_FACTORY_RESET`: Prevents unauthorized device wipes.
- `DISALLOW_ADD_USER`: Disables guest and secondary user profiles.
- `DISALLOW_MOUNT_PHYSICAL_MEDIA`: Disables OTG flash drives and rogue USB payloads.
- `DISALLOW_DEBUGGING_FEATURES`: Disables USB debugging once provisioning is finalized.

---

## Security Mitigations Matrix

| Vector | Exploitation Method | Hardening Mitigation |
| :--- | :--- | :--- |
| **Boot Interrupt** | Hold Volume-Down during boot for Safe Mode | `UserManager.DISALLOW_SAFE_BOOT` instructs bootloader to ignore hardware interrupt |
| **Settings Gear** | Tap Settings icon in Quick Settings shade | `com.android.settings` omitted from `setLockTaskPackages`; OS blocks intent |
| **Task Switcher** | Rapidly tap Recent Apps or Alt+Tab | `LOCK_TASK_FEATURE_HOME` only; Overview button suppressed by WindowManager |
| **Crashing App** | Intentional memory pressure crash | `LockTaskWatchdogService` detects `LOCK_TASK_MODE_NONE` and re-asserts lock |
| **Physical OTG** | Malicious USB flash drive or HID injection | `DISALLOW_MOUNT_PHYSICAL_MEDIA` disables mass storage mounting |

---

## License
This project is licensed under the Apache License 2.0.
