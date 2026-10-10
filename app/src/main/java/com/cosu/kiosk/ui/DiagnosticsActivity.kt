package com.cosu.kiosk.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.cosu.kiosk.dhizuku.DhizukuManager
import com.cosu.kiosk.service.LockTaskWatchdogService

/**
 * Transparent diagnostic activity launched directly over Gospel Library
 * when the "System Sync" Quick Settings tile is clicked.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Display the disguised verification dialog over the current screen
        DiagnosticsController.showVerificationPrompt(this) {
            performUnlock()
        }
    }

    private fun performUnlock() {
        Log.i(TAG, "Authentication verified. Disengaging Lock Task Mode.")
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or not active on this task", e)
        }

        // Also stop Lock Task on proxy DPM if needed
        try {
            val dpm = DhizukuManager.getDevicePolicyManager(this)
            val admin = DhizukuManager.getAdminComponent(this)
            // Empty allowlist drops Lock Task session immediately
            dpm.setLockTaskPackages(admin, arrayOf())
        } catch (e: Exception) {
            Log.w(TAG, "Resetting lock task packages via DPM proxy failed", e)
        }

        // Terminate watchdog service
        LockTaskWatchdogService.stop(this)

        // Launch standard Android Settings
        val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(settingsIntent)
        finish()
    }

    companion object {
        private const val TAG = "DiagnosticsActivity"
    }
}
