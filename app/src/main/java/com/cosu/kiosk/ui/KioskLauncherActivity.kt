package com.cosu.kiosk.ui

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cosu.kiosk.R
import com.cosu.kiosk.databinding.ActivityLauncherBinding
import com.cosu.kiosk.dhizuku.DhizukuManager
import com.cosu.kiosk.updater.AppUpdateManager
import kotlinx.coroutines.launch

/**
 * Dedicated Dual-App Kiosk Home Screen.
 * Displays two prominent cards:
 * 1. Gospel Library (org.lds.ldssa)
 * 2. The Book of Mormon (org.lds.bom)
 *
 * Automatically checks GitHub releases for updates in the background on startup,
 * updating the launcher seamlessly.
 */
class KioskLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    private var tapSequenceCount = 0
    private var lastSequenceTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializeAndLock()
        checkForBackgroundUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateCardsStatus()
        ensureLocked()
    }

    private fun setupUI() {
        // App 1: Gospel Library Card
        binding.cardGospelLibrary.setOnClickListener {
            launchApp(DhizukuManager.PKG_GOSPEL_LIBRARY)
        }

        // App 2: The Book of Mormon Card
        binding.cardBookOfMormon.setOnClickListener {
            launchApp(DhizukuManager.PKG_BOOK_OF_MORMON)
        }

        // Hidden 5-tap trigger on the title for Admin PIN unlock
        binding.tvKioskTitle.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSequenceTimestamp > 2500) {
                tapSequenceCount = 0
            }
            lastSequenceTimestamp = now
            tapSequenceCount++

            if (tapSequenceCount >= 5) {
                tapSequenceCount = 0
                DiagnosticsController.showVerificationPrompt(
                    context = this,
                    onVerified = {
                        performUnlock()
                    }
                )
            }
        }
    }

    private fun initializeAndLock() {
        DhizukuManager.init(this)

        if (DhizukuManager.isPermissionGranted()) {
            DhizukuManager.configureLockTaskPolicies(this)
        }

        ensureLocked()
        updateCardsStatus()
    }

    private fun checkForBackgroundUpdates() {
        lifecycleScope.launch {
            try {
                AppUpdateManager.checkForUpdatesAndInstall(this@KioskLauncherActivity)
            } catch (e: Exception) {
                Log.w(TAG, "Background update check notice: ${e.message}")
            }
        }
    }

    private fun ensureLocked() {
        try {
            startLockTask()
            Log.i(TAG, "startLockTask engaged on KioskLauncherActivity")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask on launcher notice: ${e.message}")
        }
    }

    private fun launchApp(pkg: String) {
        if (!isAppInstalled(pkg)) {
            openPlayStoreForPackage(pkg)
            return
        }

        val pm = packageManager
        var launchIntent = pm.getLaunchIntentForPackage(pkg)

        if (launchIntent == null) {
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = pkg
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
            Toast.makeText(this, "Unable to launch $pkg", Toast.LENGTH_SHORT).show()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        try {
            val options = ActivityOptions.makeBasic()
            options.setLockTaskEnabled(true)
            startActivity(launchIntent, options.toBundle())
            return
        } catch (_: Exception) {}

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
            Toast.makeText(this, "Launch error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performUnlock() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed or not active", e)
        }

        val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(settingsIntent)
    }

    private fun updateCardsStatus() {
        val glInstalled = isAppInstalled(DhizukuManager.PKG_GOSPEL_LIBRARY)
        binding.tvStatusGospelLibrary.text = if (glInstalled) {
            getString(R.string.status_installed)
        } else {
            getString(R.string.status_not_installed)
        }
        binding.tvStatusGospelLibrary.setTextColor(
            if (glInstalled) getColor(R.color.status_green) else getColor(R.color.status_amber)
        )

        val bomInstalled = isAppInstalled(DhizukuManager.PKG_BOOK_OF_MORMON)
        binding.tvStatusBookOfMormon.text = if (bomInstalled) {
            getString(R.string.status_installed)
        } else {
            getString(R.string.status_not_installed)
        }
        binding.tvStatusBookOfMormon.setTextColor(
            if (bomInstalled) getColor(R.color.status_green) else getColor(R.color.status_amber)
        )
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

    private fun openPlayStoreForPackage(pkg: String) {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$pkg")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(webIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to open Play Store for $pkg", e)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Handled on Home Screen
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
