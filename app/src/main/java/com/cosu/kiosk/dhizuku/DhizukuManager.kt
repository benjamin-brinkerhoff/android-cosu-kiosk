package com.cosu.kiosk.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.cosu.kiosk.admin.KioskAdminReceiver
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

/**
 * Manages Dhizuku privilege escalation proxy to interact with DevicePolicyManager
 * without requiring direct factory-reset Device Owner provisioning on the launcher itself.
 */
object DhizukuManager {
    private const val TAG = "DhizukuManager"

    fun init(context: Context): Boolean {
        return try {
            Dhizuku.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dhizuku", e)
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Dhizuku.isPermissionGranted()
        } catch (e: Exception) {
            Log.w(TAG, "Dhizuku.isPermissionGranted check failed", e)
            false
        }
    }

    fun requestPermission(callback: (granted: Boolean) -> Unit) {
        try {
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    Log.i(TAG, "Dhizuku permission result: $granted ($grantResult)")
                    callback(granted)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Dhizuku permission", e)
            callback(false)
        }
    }

    fun getDevicePolicyManager(context: Context): DevicePolicyManager {
        return try {
            if (isPermissionGranted()) {
                Dhizuku.getDevicePolicyManager() ?: (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            } else {
                context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining Dhizuku DPM proxy, falling back to system DPM", e)
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }
    }

    fun getAdminComponent(context: Context): ComponentName {
        return try {
            if (isPermissionGranted()) {
                Dhizuku.getOwnerComponent() ?: ComponentName(context, KioskAdminReceiver::class.java)
            } else {
                ComponentName(context, KioskAdminReceiver::class.java)
            }
        } catch (e: Exception) {
            ComponentName(context, KioskAdminReceiver::class.java)
        }
    }

    /**
     * Configures the Lock Task allowlist and UI feature bitmask.
     * Enforces the Quick Settings paradox:
     * - LOCK_TASK_FEATURE_NOTIFICATIONS + LOCK_TASK_FEATURE_SYSTEM_INFO enables pull-down quick toggles (Wi-Fi, Bluetooth).
     * - Excluding com.android.settings from allowlist ensures tapping the gear icon is silently blocked by ActivityManagerService.
     */
    fun configureLockTaskPolicies(context: Context, targetPackage: String): Result<Unit> {
        return runCatching {
            val dpm = getDevicePolicyManager(context)
            val admin = getAdminComponent(context)
            val myPackage = context.packageName

            // Allowlist launcher and target app
            val packages = arrayOf(myPackage, targetPackage)
            dpm.setLockTaskPackages(admin, packages)
            Log.i(TAG, "Allowlisted LockTaskPackages: ${packages.joinToString()}")

            // Feature bitmask: Enable Notifications (Quick Settings) & System Info (clock, battery, Wi-Fi/BT indicators)
            // Keep Home mapped to the allowlisted kiosk launcher
            val features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

            dpm.setLockTaskFeatures(admin, features)
            Log.i(TAG, "Configured LockTaskFeatures bitmask: $features")
        }
    }
}
