package com.jarvis.phone

import android.content.Context

class AllowedAppStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_allowed_apps", Context.MODE_PRIVATE)

    fun isAllowed(packageName: String): Boolean = prefs.getBoolean(packageName, false)

    fun setAllowed(packageName: String, allowed: Boolean) {
        prefs.edit().putBoolean(packageName, allowed).apply()
    }
}
