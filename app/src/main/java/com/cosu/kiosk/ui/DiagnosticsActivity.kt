package com.cosu.kiosk.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.cosu.kiosk.service.LockTaskWatchdogService

/**
 * Transparent diagnostic activity launched directly over Gospel Library
 * when the "System Sync" Quick Settings tile is clicked.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Pause watchdog immediately so the user can type the PIN without interruption
        LockTaskWatchdogService.pauseForDiagnostics(this)

        // 2. Display the verification dialog directly over the current screen
        DiagnosticsController.showVerificationPrompt(
            context = this,
            onVerified = {
                performUnlock()
            },
            onDismissed = {
                LockTaskWatchdogService.resumeFromDiagnostics(this)
                finish()
            }
        )
    }

    private fun performUnlock() {
        Log.i(TAG, "Authentication verified. Disengaging Lock Task Mode.")
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or not active on this task", e)
        }

        // Terminate watchdog service permanently
        LockTaskWatchdogService.stop(this)

        // Launch standard Android Settings
        val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(settingsIntent)
        finish()
    }

    override fun onDestroy() {
        if (LockTaskWatchdogService.isDiagnosticsActive) {
            LockTaskWatchdogService.resumeFromDiagnostics(this)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DiagnosticsActivity"
    }
}
