package com.cosu.kiosk

import android.app.Application
import android.util.Log
import com.cosu.kiosk.dhizuku.DhizukuManager

class KioskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing KioskApplication and Dhizuku API client")
        val initialized = DhizukuManager.init(this)
        if (!initialized) {
            Log.w(TAG, "Dhizuku service unreachable during Application.onCreate. Will retry in Activity.")
        }
    }

    companion object {
        private const val TAG = "KioskApplication"
    }
}
