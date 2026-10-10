package com.cosu.kiosk.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent diagnostic activity launched directly when the "System Sync"
 * Quick Settings tile is clicked. Prompts for the authorization token.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticsController.showVerificationPrompt(
            context = this,
            onVerified = {
                performUnlock()
            },
            onDismissed = {
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
