package com.jarvis.phone

import android.content.Context
import android.os.BatteryManager

/** Foreground service can fail closed according to user-configured limits. */
object WakeBatteryPolicy {
    fun remainingAllowedMinutes(prefs: android.content.SharedPreferences): Int =
        prefs.getInt("background_minutes", 0).takeIf { it in setOf(15, 30, 60, 120, 0) } ?: 0

    fun batteryTooLow(context: Context, threshold: Int = 15): Boolean {
        val mgr = context.getSystemService(BatteryManager::class.java) ?: return false
        val battery = mgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return battery in 1 until threshold
    }
}
