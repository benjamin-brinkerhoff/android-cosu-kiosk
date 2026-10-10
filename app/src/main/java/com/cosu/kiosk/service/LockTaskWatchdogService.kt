package com.cosu.kiosk.service

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cosu.kiosk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground service watchdog that monitors ActivityManager.lockTaskModeState.
 * If the locked application crashes, updates, or is circumscribed, the watchdog re-initiates
 * the Lock Task session automatically.
 * Automatically pauses monitoring while the diagnostic authorization prompt is open
 * so administrators can enter the unlock token without being interrupted or redirected.
 */
class LockTaskWatchdogService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var isWatchdogActive = false
    private var targetPackage: String = "org.lds.ldssa"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_WATCHDOG -> {
                Log.i(TAG, "Stopping LockTaskWatchdogService")
                isWatchdogActive = false
                isDiagnosticsActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_FOR_DIAGNOSTICS -> {
                Log.i(TAG, "Watchdog paused for diagnostic prompt")
                isDiagnosticsActive = true
                return START_STICKY
            }
            ACTION_RESUME_FROM_DIAGNOSTICS -> {
                Log.i(TAG, "Watchdog resumed after diagnostic prompt")
                isDiagnosticsActive = false
                return START_STICKY
            }
        }

        intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.let {
            targetPackage = it
        }

        if (!isWatchdogActive) {
            isWatchdogActive = true
            startMonitoringLoop()
        }

        return START_STICKY
    }

    private fun startMonitoringLoop() {
        scope.launch {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            while (isActive && isWatchdogActive) {
                try {
                    // Do not intervene while administrator is typing the diagnostic PIN
                    if (isDiagnosticsActive) {
                        delay(POLL_INTERVAL_DIAGNOSTIC_MS)
                        continue
                    }

                    val state = activityManager.lockTaskModeState
                    if (state == ActivityManager.LOCK_TASK_MODE_NONE) {
                        Log.w(TAG, "Watchdog detected LOCK_TASK_MODE_NONE. Re-asserting lock on $targetPackage")
                        relaunchTargetInLockTask()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking lockTaskModeState", e)
                }
                delay(POLL_INTERVAL_NORMAL_MS)
            }
        }
    }

    private fun relaunchTargetInLockTask() {
        if (isDiagnosticsActive) return

        var launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = targetPackage
            }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(queryIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(queryIntent, 0)
            }
            if (resolved.isNotEmpty()) {
                val activityInfo = resolved[0].activityInfo
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activityInfo.packageName, activityInfo.name)
                }
            }
        }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val options = ActivityOptions.makeBasic()
            options.setLockTaskEnabled(true)
            try {
                startActivity(launchIntent, options.toBundle())
                Log.i(TAG, "Successfully relaunched $targetPackage in Lock Task Mode")
            } catch (e: Exception) {
                try {
                    startActivity(launchIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Watchdog Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors dedicated device Lock Task state and ensures sandbox persistence"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dedicated Appliance Active")
            .setContentText("Watchdog is enforcing Lock Task sandbox for Gospel Library")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        isWatchdogActive = false
        isDiagnosticsActive = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockTaskWatchdog"
        private const val CHANNEL_ID = "kiosk_watchdog_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_NORMAL_MS = 1000L
        private const val POLL_INTERVAL_DIAGNOSTIC_MS = 1500L

        @Volatile
        var isDiagnosticsActive: Boolean = false

        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val ACTION_STOP_WATCHDOG = "com.cosu.kiosk.action.STOP_WATCHDOG"
        const val ACTION_PAUSE_FOR_DIAGNOSTICS = "com.cosu.kiosk.action.PAUSE_FOR_DIAGNOSTICS"
        const val ACTION_RESUME_FROM_DIAGNOSTICS = "com.cosu.kiosk.action.RESUME_FROM_DIAGNOSTICS"

        fun start(context: Context, targetPackage: String) {
            val intent = Intent(context, LockTaskWatchdogService::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            isDiagnosticsActive = false
            val intent = Intent(context, LockTaskWatchdogService::class.java).apply {
                action = ACTION_STOP_WATCHDOG
            }
            context.startService(intent)
        }

        fun pauseForDiagnostics(context: Context) {
            isDiagnosticsActive = true
            val intent = Intent(context, LockTaskWatchdogService::class.java).apply {
                action = ACTION_PAUSE_FOR_DIAGNOSTICS
            }
            context.startService(intent)
        }

        fun resumeFromDiagnostics(context: Context) {
            isDiagnosticsActive = false
            val intent = Intent(context, LockTaskWatchdogService::class.java).apply {
                action = ACTION_RESUME_FROM_DIAGNOSTICS
            }
            context.startService(intent)
        }
    }
}
