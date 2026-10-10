package com.cosu.kiosk.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.cosu.kiosk.ui.DiagnosticsActivity

/**
 * Custom Quick Settings Tile disguised as "System Sync".
 * Appears in the pull-down notification shade alongside Wi-Fi and Bluetooth.
 * When tapped while inside Gospel Library, it launches DiagnosticsActivity directly
 * over the locked application to allow authenticated escape.
 */
class KioskTileService : TileService() {

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Quick Settings tile tapped")

        val intent = Intent(this, DiagnosticsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "System Sync"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        private const val TAG = "KioskTileService"
    }
}
