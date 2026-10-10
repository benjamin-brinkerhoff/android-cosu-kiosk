package com.cosu.kiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cosu.kiosk.ui.KioskLauncherActivity

/**
 * Receives boot and unlock broadcast events to ensure Gospel Library is immediately
 * launched and locked without leaving the user on a launcher screen.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received system broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_PRESENT
        ) {
            val launchIntent = Intent(context, KioskLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            context.startActivity(launchIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
