package com.cosu.kiosk.ui

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cosu.kiosk.R
import com.cosu.kiosk.databinding.ActivityLauncherBinding
import com.cosu.kiosk.dhizuku.DhizukuManager
import com.cosu.kiosk.service.LockTaskWatchdogService

/**
 * Bespoke Kiosk Launcher activity serving as the default HOME activity.
 * Whenever launched (boot, unlock, or Home button press), it automatically checks policies
 * and launches Gospel Library directly into Lock Task Mode.
 * Invokes startLockTask() on itself to lock the task stack at the window manager level.
 */
class KioskLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private val targetPackage = "org.lds.ldssa" // Gospel Library

    private var tapSequenceCount = 0
    private var lastSequenceTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        verifyAndAutoLaunch()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (isAppInstalled(targetPackage)) {
            launchTargetInLockTask()
        }
    }

    private fun setupUI() {
        binding.btnAction.setOnClickListener {
            if (!DhizukuManager.isPermissionGranted()) {
                requestDhizukuPermission()
            } else {
                launchTargetInLockTask()
            }
        }

        // Backup unlock trigger: Tapping the title 5 times rapidly
        binding.tvKioskTitle.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSequenceTimestamp > 2500) {
                tapSequenceCount = 0
            }
            lastSequenceTimestamp = now
            tapSequenceCount++

            if (tapSequenceCount >= 5) {
                tapSequenceCount = 0
                LockTaskWatchdogService.pauseForDiagnostics(this)
                DiagnosticsController.showVerificationPrompt(
                    context = this,
                    onVerified = {
                        performUnlock()
                    },
                    onDismissed = {
                        LockTaskWatchdogService.resumeFromDiagnostics(this)
                    }
                )
            }
        }
    }

    private fun verifyAndAutoLaunch() {
        DhizukuManager.init(this)

        if (DhizukuManager.isPermissionGranted()) {
            configurePoliciesAndAutoLaunch()
        } else {
            updateStatus()
            if (isAppInstalled(targetPackage)) {
                launchTargetInLockTask()
            }
        }
    }

    private fun requestDhizukuPermission() {
        DhizukuManager.requestPermission { granted ->
            runOnUiThread {
                if (granted) {
                    Toast.makeText(this, "Dhizuku authorization confirmed", Toast.LENGTH_SHORT).show()
                    configurePoliciesAndAutoLaunch()
                } else {
                    Toast.makeText(this, "Dhizuku permission required for system policies", Toast.LENGTH_LONG).show()
                    updateStatus()
                }
            }
        }
    }

    private fun configurePoliciesAndAutoLaunch() {
        val result = DhizukuManager.configureLockTaskPolicies(this, targetPackage)
        result.onSuccess {
            updateStatus()
        }.onFailure { e ->
            Log.w(TAG, "Local policy configuration notice: ${e.message}. Relying on OwnDroid allowlist.")
            updateStatus()
        }
        launchTargetInLockTask()
    }

    private fun launchTargetInLockTask() {
        // Attempt to lock task directly on launcher to freeze navigation buttons
        try {
            startLockTask()
            Log.i(TAG, "KioskLauncherActivity engaged startLockTask()")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask on launcher notice: ${e.message}")
        }

        val pm = packageManager
        var launchIntent = pm.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = targetPackage
            }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(queryIntent, 0)
            }
            if (resolved.isNotEmpty()) {
                val activityInfo = resolved[0].activityInfo
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activityInfo.packageName, activityInfo.name)
                }
            }
        }

        if (launchIntent == null) {
            Log.w(TAG, "Unable to resolve launch intent for $targetPackage")
            Toast.makeText(this, "Gospel Library ($targetPackage) not found", Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        // 1. Try launching with LockTask options
        try {
            val options = ActivityOptions.makeBasic()
            options.setLockTaskEnabled(true)
            startActivity(launchIntent, options.toBundle())
            Log.i(TAG, "Dispatched LockTask launch intent for $targetPackage")
            LockTaskWatchdogService.start(this, targetPackage)
            return
        } catch (_: SecurityException) {
            Log.w(TAG, "LockTask launch unpermitted, falling back to standard launch")
        } catch (_: Exception) {}

        // 2. Standard launch fallback
        try {
            startActivity(launchIntent)
            LockTaskWatchdogService.start(this, targetPackage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed standard launch for $targetPackage", e)
            Toast.makeText(this, "Launch error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performUnlock() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or not active", e)
        }

        LockTaskWatchdogService.stop(this)

        val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(settingsIntent)
    }

    private fun updateStatus() {
        val dhizukuOk = DhizukuManager.isPermissionGranted()
        val isTargetInstalled = isAppInstalled(targetPackage)

        binding.tvDhizukuStatus.text = if (dhizukuOk) {
            "• Dhizuku Privilege Proxy: Active (Granted)"
        } else {
            "• Dhizuku Privilege Proxy: Disconnected / Not Granted"
        }

        binding.tvAppInstallStatus.text = if (isTargetInstalled) {
            "• Target App ($targetPackage): Installed"
        } else {
            "• Target App ($targetPackage): NOT Installed (Install from Play Store)"
        }

        binding.tvPolicyStatus.text = if (dhizukuOk) {
            "• Lock Task Policies: Active / Managed by OwnDroid"
        } else {
            "• Lock Task Policies: Pending Authorization"
        }

        if (!isTargetInstalled) {
            binding.btnAction.text = "Install Gospel Library"
            binding.btnAction.setOnClickListener {
                openPlayStoreForPackage(targetPackage)
            }
        } else if (!dhizukuOk) {
            binding.btnAction.text = getString(R.string.btn_request_dhizuku)
            binding.btnAction.setOnClickListener {
                requestDhizukuPermission()
            }
        } else {
            binding.btnAction.text = getString(R.string.btn_launch)
            binding.btnAction.setOnClickListener {
                launchTargetInLockTask()
            }
        }
    }

    private fun isAppInstalled(pkg: String): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {}

        if (packageManager.getLaunchIntentForPackage(pkg) != null) {
            return true
        }

        val queryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = pkg
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(queryIntent, 0)
        }

        return resolved.isNotEmpty()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Suppress back key
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
