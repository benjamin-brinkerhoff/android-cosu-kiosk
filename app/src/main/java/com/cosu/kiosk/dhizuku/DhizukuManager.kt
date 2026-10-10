package com.cosu.kiosk.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.cosu.kiosk.admin.KioskAdminReceiver
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

/**
 * Manages Dhizuku privilege escalation proxy to interact with DevicePolicyManager.
 * Configures package allowlisting for:
 * 1. Kiosk launcher (com.cosu.kiosk)
 * 2. Gospel Library (org.lds.ldssa)
 * 3. The Book of Mormon (org.lds.bom)
 * 4. System Settings / Connectivity panels (com.android.settings, com.google.android.settings.intelligence)
 */
object DhizukuManager {
    private const val TAG = "DhizukuManager"

    const val PKG_GOSPEL_LIBRARY = "org.lds.ldssa"
    const val PKG_BOOK_OF_MORMON = "org.lds.bom"
    const val PKG_SETTINGS = "com.android.settings"

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
                resolveDhizukuDpm(context) ?: context.getSystemService(DevicePolicyManager::class.java)
            } else {
                context.getSystemService(DevicePolicyManager::class.java)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining Dhizuku DPM proxy, falling back to system DPM", e)
            context.getSystemService(DevicePolicyManager::class.java)
        }
    }

    private fun resolveDhizukuDpm(context: Context): DevicePolicyManager? {
        val dhizukuClass = Dhizuku::class.java

        try {
            val method = dhizukuClass.getMethod("getService", Class::class.java)
            val result = method.invoke(null, DevicePolicyManager::class.java)
            if (result is DevicePolicyManager) return result
        } catch (_: NoSuchMethodException) {}

        try {
            val method = dhizukuClass.getMethod("getSystemService", String::class.java)
            val result = method.invoke(null, Context.DEVICE_POLICY_SERVICE)
            if (result is DevicePolicyManager) return result
        } catch (_: NoSuchMethodException) {}

        try {
            val method = dhizukuClass.getMethod("getDevicePolicyManager")
            val result = method.invoke(null)
            if (result is DevicePolicyManager) return result
        } catch (_: NoSuchMethodException) {}

        try {
            val baseDpm = context.getSystemService(DevicePolicyManager::class.java)
            val field = baseDpm.javaClass.getDeclaredField("mService")
            field.isAccessible = true
            val oldInterface = field.get(baseDpm)
            if (oldInterface != null) {
                val asBinderMethod = oldInterface.javaClass.getMethod("asBinder")
                val oldBinder = asBinderMethod.invoke(oldInterface) as IBinder
                val binderWrapperMethod = dhizukuClass.getMethod("binderWrapper", IBinder::class.java)
                val newBinder = binderWrapperMethod.invoke(null, oldBinder) as IBinder
                val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                val newInterface = asInterfaceMethod.invoke(null, newBinder)
                field.set(baseDpm, newInterface)
                return baseDpm
            }
        } catch (_: Exception) {}

        return null
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
     * Configures the Lock Task allowlist for:
     * - Launcher (com.cosu.kiosk)
     * - Gospel Library (org.lds.ldssa)
     * - The Book of Mormon (org.lds.bom)
     * - Settings (com.android.settings) - required for Wi-Fi and Bluetooth connection activities
     *
     * Enables UI features:
     * - LOCK_TASK_FEATURE_SYSTEM_INFO: clock, battery
     * - LOCK_TASK_FEATURE_HOME: Home button to return to Study Library
     * - LOCK_TASK_FEATURE_GLOBAL_ACTIONS: power button menu (restart/power off)
     */
    fun configureLockTaskPolicies(context: Context): Result<Unit> {
        return runCatching {
            val dpm = getDevicePolicyManager(context)
            val admin = getAdminComponent(context)
            val myPackage = context.packageName

            val packages = arrayOf(
                myPackage,
                PKG_GOSPEL_LIBRARY,
                PKG_BOOK_OF_MORMON,
                PKG_SETTINGS
            )
            dpm.setLockTaskPackages(admin, packages)
            Log.i(TAG, "Allowlisted LockTaskPackages: ${packages.joinToString()}")

            val features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

            dpm.setLockTaskFeatures(admin, features)
            Log.i(TAG, "Configured LockTaskFeatures bitmask: $features")
        }
    }
}
