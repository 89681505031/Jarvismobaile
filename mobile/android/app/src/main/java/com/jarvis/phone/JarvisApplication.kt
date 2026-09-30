package com.jarvis.phone

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Voice recognition is owned by MainActivity.
        // Starting a second microphone service here causes two recognizers
        // to compete for the microphone and the background service has no
        // receiver in the current activity to consume its wake broadcast.
    }
}
