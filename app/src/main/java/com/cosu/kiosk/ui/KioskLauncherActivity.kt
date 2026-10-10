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
 * If LockTaskMode fails due to missing DO allowlisting in OwnDroid, it gracefully falls back
 * to standard launch so the user is never stuck on the launcher screen.
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
                DiagnosticsController.showVerificationPrompt(this) {
                    performUnlock()
                }
            }
        }
    }

    private fun verifyAndAutoLaunch() {
        DhizukuManager.init(this)

        if (DhizukuManager.isPermissionGranted()) {
            configurePoliciesAndAutoLaunch()
        } else {
            updateStatus()
            // Even if Dhizuku is still pending, if the app is installed, attempt launch
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
            Log.w(TAG, "Local policy configuration notice: ${e.message}. Will rely on OwnDroid allowlist.")
            updateStatus()
        }
        // Always proceed to launch target
        launchTargetInLockTask()
    }

    private fun launchTargetInLockTask() {
        val pm = packageManager
        var launchIntent = pm.getLaunchIntentForPackage(targetPackage)

        // Fallback: Resolve MAIN + LAUNCHER activity explicitly if generic lookup is filtered
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

        // 1. First attempt: Launch with LockTaskMode enabled
        try {
            val options = ActivityOptions.makeBasic()
            options.setLockTaskEnabled(true)
            startActivity(launchIntent, options.toBundle())
            Log.i(TAG, "Dispatched LockTask launch intent for $targetPackage")
            LockTaskWatchdogService.start(this, targetPackage)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "LockTaskMode SecurityException (package not yet allowlisted in OwnDroid/DO): ${e.message}")
            Toast.makeText(
                this,
                "LockTask pending allowlist in OwnDroid. Opening Gospel Library...",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "LockTaskMode failed: ${e.message}, falling back to standard launch")
        }

        // 2. Fallback: Standard launch so user is never stuck on launcher screen
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
        // 1. Try standard getPackageInfo
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (e: Exception) {
            Log.w(TAG, "Error checking getPackageInfo for $pkg", e)
        }

        // 2. Try getLaunchIntentForPackage
        if (packageManager.getLaunchIntentForPackage(pkg) != null) {
            return true
        }

        // 3. Try queryIntentActivities with explicit package filter
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

    private fun openPlayStoreForPackage(pkg: String) {
        val uri = android.net.Uri.parse("market://details?id=$pkg")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            startActivity(webIntent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Suppress back key
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
