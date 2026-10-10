package com.cosu.kiosk.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles automated background & manual on-demand updates by checking GitHub Releases:
 * https://api.github.com/repos/benjamin-brinkerhoff/android-cosu-kiosk/releases/latest
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "benjamin-brinkerhoff/android-cosu-kiosk"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    suspend fun checkForUpdatesAndInstall(context: Context, onStatusUpdate: ((String) -> Unit)? = null) {
        withContext(Dispatchers.IO) {
            try {
                onStatusUpdate?.invoke("Checking GitHub for updates...")
                val apkDownloadUrl = fetchLatestReleaseApkUrl()
                if (apkDownloadUrl == null) {
                    onStatusUpdate?.invoke("No new build release found.")
                    return@withContext
                }

                onStatusUpdate?.invoke("Downloading latest update...")
                val downloadedFile = downloadApk(context, apkDownloadUrl)
                if (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) {
                    onStatusUpdate?.invoke("Download failed. Check Wi-Fi connection.")
                    return@withContext
                }

                val sizeKb = downloadedFile.length() / 1024
                onStatusUpdate?.invoke("Installing update (${sizeKb} KB)...")
                installApk(context, downloadedFile)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/installing update", e)
                onStatusUpdate?.invoke("Update check error: ${e.localizedMessage}")
            }
        }
    }

    private fun fetchLatestReleaseApkUrl(): String? {
        val url = URL(RELEASES_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Android-COSU-Kiosk-Updater")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        return asset.optString("browser_download_url")
                    }
                }
            }
        }
        return null
    }

    private fun downloadApk(context: Context, downloadUrl: String): File? {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        val file = File(context.cacheDir, "kiosk_update.apk")
        if (file.exists()) file.delete()

        connection.inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            apkFile.inputStream().use { inputStream ->
                session.openWrite("package", 0, apkFile.length()).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
            }

            val intent = Intent(context, context.javaClass)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "Committed PackageInstaller session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Silent install session failed, falling back to prompt: ${e.message}")
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        }
    }
}
