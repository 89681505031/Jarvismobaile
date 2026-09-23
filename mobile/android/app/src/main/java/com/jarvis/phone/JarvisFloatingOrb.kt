package com.jarvis.phone

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Visible, user-controlled overlay shortcut. It never accesses the mic itself. */
class JarvisFloatingOrb : Service() {
    private var manager: WindowManager? = null
    private var floating: LinearLayout? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this) ||
            !getSharedPreferences("jarvis_features", MODE_PRIVATE).getBoolean("floating_orb", false)) {
            stopSelf(); return
        }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(11, 24, 42))
                cornerRadius = 70f
                setStroke(2, Color.rgb(0, 190, 255))
            }
        }
        fun make(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 21f
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER
        }
        val open = make("J")
        val close = make("×")
        open.contentDescription = "Открыть JARVIS"
        close.contentDescription = "Убрать плавающую кнопку JARVIS"
        open.setOnClickListener {
            val pending = PendingIntent.getActivity(
                this, 811, Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try { pending.send() } catch (_: Exception) {}
        }
        close.setOnClickListener {
            getSharedPreferences("jarvis_features", MODE_PRIVATE).edit()
                .putBoolean("floating_orb", false).apply()
            stopSelf()
        }
        root.addView(open); root.addView(close)
        floating = root
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 160 }
        try { manager?.addView(root, params) } catch (_: Exception) { stopSelf() }
    }
    override fun onDestroy() {
        floating?.let { try { manager?.removeView(it) } catch (_: Exception) {} }
        floating = null
        super.onDestroy()
    }
}
