package com.cosu.kiosk.ui

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cosu.kiosk.R
import com.cosu.kiosk.databinding.ActivityLauncherBinding
import com.cosu.kiosk.dhizuku.DhizukuManager
import com.cosu.kiosk.service.LockTaskWatchdogService

/**
 * Bespoke Kiosk Launcher activity serving as the default HOME activity.
 * Intercepts Home key navigation, configures Lock Task allowlisting, launches the target
 * application (org.lds.ldssa) directly into LOCK_TASK_MODE_LOCKED, and contains a hidden
 * multi-tap egress trigger.
 */
class KioskLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private val targetPackage = "org.lds.ldssa" // Gospel Library

    private var secretTapCount = 0
    private var lastTapTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        verifyAndInitialize()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupUI() {
        binding.btnAction.setOnClickListener {
            if (!DhizukuManager.isPermissionGranted()) {
                requestDhizukuPermission()
            } else {
                launchTargetInLockTask()
            }
        }

        // Hidden egress trigger: 5 rapid taps within 2.5 seconds on the secret hotspot
        binding.secretAdminHotspot.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTapTimestamp > 2500) {
                secretTapCount = 0
            }
            lastTapTimestamp = now
            secretTapCount++

            if (secretTapCount >= 5) {
                secretTapCount = 0
                AdminEgressDialog.show(this) {
                    performAdminEgress()
                }
            }
        }
    }

    private fun verifyAndInitialize() {
        if (!DhizukuManager.init(this)) {
            Log.w(TAG, "Dhizuku init returned false")
        }

        if (DhizukuManager.isPermissionGranted()) {
            configurePoliciesAndAutoLaunch()
        } else {
            updateStatus()
        }
    }

    private fun requestDhizukuPermission() {
        DhizukuManager.requestPermission { granted ->
            runOnUiThread {
                if (granted) {
                    Toast.makeText(this, "Dhizuku permission granted", Toast.LENGTH_SHORT).show()
                    configurePoliciesAndAutoLaunch()
                } else {
                    Toast.makeText(this, "Dhizuku permission denied. Grant via Dhizuku app.", Toast.LENGTH_LONG).show()
                    updateStatus()
                }
            }
        }
    }

    private fun configurePoliciesAndAutoLaunch() {
        val result = DhizukuManager.configureLockTaskPolicies(this, targetPackage)
        result.onSuccess {
            updateStatus()
            launchTargetInLockTask()
        }.onFailure { e ->
            Log.e(TAG, "Policy configuration failed", e)
            Toast.makeText(this, "Failed to apply Lock Task policy: ${e.message}", Toast.LENGTH_LONG).show()
            updateStatus()
        }
    }

    private fun launchTargetInLockTask() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            Toast.makeText(this, "Gospel Library ($targetPackage) is not installed!", Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }

        // Configure ActivityOptions to enter Lock Task Mode directly
        val options = ActivityOptions.makeBasic()
        options.setLockTaskEnabled(true)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        try {
            startActivity(launchIntent, options.toBundle())
            Log.i(TAG, "Dispatched LockTask launch intent for $targetPackage")

            // Start watchdog service to guard against crashes or task dropouts
            LockTaskWatchdogService.start(this, targetPackage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $targetPackage in Lock Task Mode", e)
            Toast.makeText(this, "Error starting Lock Task Mode: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performAdminEgress() {
        try {
            // Stop lock task on launcher if active
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask call ignored or not locked", e)
        }

        // Stop watchdog so it does not pull the device back into Lock Task
        LockTaskWatchdogService.stop(this)

        // Open device settings for administrative maintenance
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
            "• Lock Task Policies: Configured (Quick Settings Enabled)"
        } else {
            "• Lock Task Policies: Pending Authorization"
        }

        if (!dhizukuOk) {
            binding.btnAction.text = getString(R.string.btn_request_dhizuku)
        } else if (!isTargetInstalled) {
            binding.btnAction.text = "Install Gospel Library"
            binding.btnAction.setOnClickListener {
                openPlayStoreForPackage(targetPackage)
            }
        } else {
            binding.btnAction.text = getString(R.string.btn_launch)
            binding.btnAction.setOnClickListener {
                launchTargetInLockTask()
            }
        }
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
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
        // Intercept and swallow back press to preserve kiosk integrity
        // In Android 13+, Back Invoked Callback handles this, but in Lock Task Mode the OS suppresses back events.
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
