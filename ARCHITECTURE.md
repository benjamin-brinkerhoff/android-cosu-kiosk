# Technical Architecture & System Hardening Specification

## 1. Executive Summary
This document outlines the system architecture for creating a dedicated Corporate-Owned, Single-Use (COSU) appliance on modern Android platforms (specifically targeted at Android 14–16 with Samsung One UI 8.5 on the Galaxy A15 5G). The system enforces single-purpose containment within Gospel Library (`org.lds.ldssa`) while facilitating peripheral connectivity and preventing user egress.

---

## 2. Security Enforcement Model
Let A be the universe of installed application packages on the Android system.
Let L be the allowlisted set of packages registered with DevicePolicyManager.setLockTaskPackages():
L = { "com.cosu.kiosk", "org.lds.ldssa" }

For any task execution request T initiated by user interaction or deep links:
- Allow(T) if and only if Package(T) is in L.
- When a user pulls down the notification shade and taps the Settings gear:
  Package(T_gear) = "com.android.settings" which is not in L.
  Therefore, ActivityManagerService automatically and silently blocks the intent.

---

## 3. UI Policy and the Quick Settings Paradox
The `DevicePolicyManager.setLockTaskFeatures(ComponentName admin, int flags)` bitmask controls permitted SystemUI behaviors:

```kotlin
val features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
               DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
               DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
               DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
```

- **`LOCK_TASK_FEATURE_SYSTEM_INFO`**: Renders clock, battery percentage, cellular/Wi-Fi icons, and Bluetooth connection state.
- **`LOCK_TASK_FEATURE_NOTIFICATIONS`**: Restores the pull-down shade so users can interact with quick toggles (airplane mode, Wi-Fi, Bluetooth).
- **`LOCK_TASK_FEATURE_HOME`**: Enables the hardware/software Home button, routing exclusively back to `com.cosu.kiosk.ui.KioskLauncherActivity`.
- **`LOCK_TASK_FEATURE_GLOBAL_ACTIONS`**: Allows the user to restart or power off the device via long-press of the power button.

### One UI 8.5 Nuance:
On Samsung One UI 8.5 firmware running on Android 16, a known OS framework race condition can cause certain secondary notification dialogs to bleed through if `LOCK_TASK_FEATURE_NOTIFICATIONS` is active. To prevent deep-link bypasses:
1. `LockTaskWatchdogService` validates the foreground task continuously.
2. In OwnDroid, set `DISALLOW_CREATE_WINDOWS` to suppress unauthorized overlay windows.

---

## 4. Dhizuku Architecture & Dual DO Delegation
Android enforces a strict single-Device-Owner rule. If OwnDroid is provisioned directly via ADB as DO, the custom launcher cannot register its LockTask policies.

```
       [ Android OS Framework / Kernel ]
                       │
             (Primary Device Owner)
                       ▼
                 [ Dhizuku ]
                /           \
     (IPC Binder)            (IPC Binder)
              ▼                        ▼
      [ OwnDroid ]           [ Custom Kiosk Launcher ]
(System Restrictions)          (Lock Task Engine)
```

Dhizuku acts as the primary Device Owner, sharing the privileged `DevicePolicyManager` binder with both consumer-facing applications:
1. **OwnDroid** applies device-wide restrictions (`UserManager`).
2. **Kiosk Launcher** applies activity window management (`setLockTaskPackages` & `setLockTaskFeatures`).

---

## 5. Watchdog Engine Specifications
The `LockTaskWatchdogService` runs in the foreground as an Android `specialUse` service.

```kotlin
while (isActive && isWatchdogActive) {
    val state = activityManager.lockTaskModeState
    if (state == ActivityManager.LOCK_TASK_MODE_NONE) {
        // App has dropped out of kiosk mode
        relaunchTargetInLockTask()
    }
    delay(2500L)
}
```

- **Poll Frequency**: 2500ms (balanced between rapid containment recovery and battery preservation).
- **State Check**: Evaluates `ActivityManager.getLockTaskModeState()`. If not `LOCK_TASK_MODE_LOCKED`, dispatches `Intent(ACTION_MAIN)` with `ActivityOptions.setLockTaskEnabled(true)`.
