package com.wisewalk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // Check if user has configured the app
            val prefs = context.getSharedPreferences("wisewalk_prefs", Context.MODE_PRIVATE)
            val hasProfile = prefs.contains("profile_sex") && prefs.contains("profile_height_cm")
            
            if (hasProfile) {
                startTrackingService(context)
            }
        }
    }
    
    private fun startTrackingService(context: Context) {
        val serviceIntent = Intent(context, StepTrackingService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
