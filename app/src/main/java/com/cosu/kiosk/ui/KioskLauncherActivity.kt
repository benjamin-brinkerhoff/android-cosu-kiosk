package com.cosu.kiosk.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cosu.kiosk.R
import com.cosu.kiosk.databinding.ActivityLauncherBinding
import com.cosu.kiosk.dhizuku.DhizukuManager
import com.cosu.kiosk.updater.AppUpdateManager
import kotlinx.coroutines.launch

/**
 * Dedicated Dual-App Kiosk Home Screen with Tabbed Settings.
 * - Library Tab: Gospel Library (org.lds.ldssa) & The Book of Mormon (org.lds.bom)
 * - Settings Tab: Wi-Fi, Bluetooth controls, Software Updates, and Administrator Unlock
 */
class KioskLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupAppCards()
        setupSettingsControls()
        setupDhizukuBanner()
        initializeAndLock()
        checkForBackgroundUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateCardsStatus()
        updateConnectivityStatus()
        updateDhizukuBannerStatus()
        ensureLocked()
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> {
                    binding.viewTabLibrary.visibility = View.VISIBLE
                    binding.viewTabSettings.visibility = View.GONE
                    binding.tvHeaderTitle.text = "Study Library"
                    true
                }
                R.id.nav_settings -> {
                    binding.viewTabLibrary.visibility = View.GONE
                    binding.viewTabSettings.visibility = View.VISIBLE
                    binding.tvHeaderTitle.text = "Settings"
                    updateConnectivityStatus()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAppCards() {
        binding.cardGospelLibrary.setOnClickListener {
            launchApp(DhizukuManager.PKG_GOSPEL_LIBRARY)
        }

        binding.cardBookOfMormon.setOnClickListener {
            launchApp(DhizukuManager.PKG_BOOK_OF_MORMON)
        }
    }

    private fun setupDhizukuBanner() {
        binding.btnGrantDhizukuBanner.setOnClickListener {
            DhizukuManager.requestPermission { granted ->
                runOnUiThread {
                    if (granted) {
                        Toast.makeText(this, "Dhizuku authorization granted", Toast.LENGTH_SHORT).show()
                        DhizukuManager.configureLockTaskPolicies(this)
                        updateDhizukuBannerStatus()
                    } else {
                        Toast.makeText(this, "Dhizuku permission required", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateDhizukuBannerStatus() {
        val dhizukuGranted = DhizukuManager.isPermissionGranted()
        binding.cardDhizukuSetupBanner.visibility = if (dhizukuGranted) View.GONE else View.VISIBLE
    }

    private fun setupSettingsControls() {
        val openWifiAction = View.OnClickListener {
            openWifiSettings()
        }
        binding.cardWifi.setOnClickListener(openWifiAction)
        binding.btnOpenWifi.setOnClickListener(openWifiAction)

        val openBluetoothAction = View.OnClickListener {
            openBluetoothSettings()
        }
        binding.cardBluetooth.setOnClickListener(openBluetoothAction)
        binding.btnOpenBluetooth.setOnClickListener(openBluetoothAction)

        binding.btnCheckUpdate.setOnClickListener {
            triggerManualUpdateCheck()
        }

        val unlockAction = View.OnClickListener {
            DiagnosticsController.showVerificationPrompt(
                context = this,
                onVerified = {
                    performUnlock()
                }
            )
        }
        binding.cardAdminUnlock.setOnClickListener(unlockAction)
        binding.btnTriggerUnlock.setOnClickListener(unlockAction)

        binding.ivHeaderLock.setOnClickListener {
            DiagnosticsController.showVerificationPrompt(
                context = this,
                onVerified = {
                    performUnlock()
                }
            )
        }
    }

    private fun triggerManualUpdateCheck() {
        binding.btnCheckUpdate.isEnabled = false
        binding.tvUpdateStatus.text = "Checking GitHub..."
        Toast.makeText(this, "Checking for latest release...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            AppUpdateManager.checkForUpdatesAndInstall(this@KioskLauncherActivity) { status ->
                runOnUiThread {
                    binding.tvUpdateStatus.text = status
                    Toast.makeText(this@KioskLauncherActivity, status, Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnCheckUpdate.isEnabled = true
        }
    }

    private fun openWifiSettings() {
        val intents = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else null,
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WifiSettingsActivity")
        ).filterNotNull()

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Wi-Fi intent failed: ${e.message}")
            }
        }

        Toast.makeText(this, "Ensure com.android.settings is allowlisted in OwnDroid", Toast.LENGTH_LONG).show()
    }

    private fun openBluetoothSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$BluetoothSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Bluetooth intent failed: ${e.message}")
            }
        }

        Toast.makeText(this, "Ensure com.android.settings is allowlisted in OwnDroid", Toast.LENGTH_LONG).show()
    }

    private fun updateConnectivityStatus() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val isWifiEnabled = wifiManager?.isWifiEnabled == true
            binding.tvWifiStatus.text = if (isWifiEnabled) "Wi-Fi is ON • Tap to manage networks" else "Wi-Fi is OFF • Tap to connect"

            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            val isBtEnabled = btAdapter?.isEnabled == true
            binding.tvBluetoothStatus.text = if (isBtEnabled) "Bluetooth is ON • Tap to pair audio" else "Bluetooth is OFF • Tap to pair"
        } catch (e: Exception) {
            Log.w(TAG, "Notice querying connectivity state: ${e.message}")
        }
    }

    private fun initializeAndLock() {
        DhizukuManager.init(this)

        if (DhizukuManager.isPermissionGranted()) {
            DhizukuManager.configureLockTaskPolicies(this)
        }

        ensureLocked()
        updateCardsStatus()
        updateDhizukuBannerStatus()
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
        if (binding.viewTabSettings.visibility == View.VISIBLE) {
            binding.bottomNavigation.selectedItemId = R.id.nav_library
        }
    }

    companion object {
        private const val TAG = "KioskLauncherActivity"
    }
}
