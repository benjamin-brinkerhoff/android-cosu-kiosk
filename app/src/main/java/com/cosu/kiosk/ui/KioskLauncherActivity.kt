package com.cosu.kiosk.ui

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
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
 * and immediately launches Gospel Library directly into Lock Task Mode.
 * The setup launcher screen is only visible if setup is incomplete or target is missing.
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
        // Seamless auto-launch: if setup is complete, keep user encapsulated in Gospel Library
        if (DhizukuManager.isPermissionGranted() && isAppInstalled(targetPackage)) {
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
        }
    }

    private fun requestDhizukuPermission() {
        DhizukuManager.requestPermission { granted ->
            runOnUiThread {
                if (granted) {
                    Toast.makeText(this, "Dhizuku authorization confirmed", Toast.LENGTH_SHORT).show()
                    configurePoliciesAndAutoLaunch()
                } else {
                    Toast.makeText(this, "Dhizuku permission required", Toast.LENGTH_LONG).show()
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
            updateStatus()
        }
    }

    private fun launchTargetInLockTask() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            updateStatus()
            return
        }

        val options = ActivityOptions.makeBasic()
        options.setLockTaskEnabled(true)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        try {
            startActivity(launchIntent, options.toBundle())
            Log.i(TAG, "Dispatched LockTask launch intent for $targetPackage")
            LockTaskWatchdogService.start(this, targetPackage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $targetPackage in Lock Task Mode", e)
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
        // Suppress back key
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
