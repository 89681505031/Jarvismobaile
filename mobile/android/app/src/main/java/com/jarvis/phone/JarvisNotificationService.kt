package com.jarvis.phone

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class JarvisNotificationService : NotificationListenerService() {
    companion object {
        private val messages = CopyOnWriteArrayList<IncomingMessage>()
        private const val MAX_MESSAGES = 100
        private const val PREFS = "jarvis_notifications"
        private const val KEY_MESSAGES = "messages"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val TELEGRAM = "org.telegram.messenger"

        fun isWhatsApp(packageName: String): Boolean =
            packageName == WHATSAPP || packageName == WHATSAPP_BUSINESS

        fun latest(limit: Int = 20): List<IncomingMessage> =
            messages.takeLast(limit.coerceAtLeast(0)).reversed()

        fun clear(context: Context) {
            messages.clear()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MESSAGES)
                .apply()
        }

        @Volatile
        private var instance: JarvisNotificationService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        loadMessages()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!isWhatsApp(packageName) && packageName != TELEGRAM) return

        val extras = sbn.notification.extras
        val fallbackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val fallbackText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        val styledMessage = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                ?.lastOrNull()
        } catch (_: Exception) {
            null
        }

        val title = styledMessage?.sender?.toString()?.trim().orEmpty().ifBlank { fallbackTitle }
        val text = styledMessage?.text?.toString()?.trim().orEmpty().ifBlank { fallbackText }
        if (title.isBlank() && text.isBlank()) return

        val notificationKey = "$packageName:${sbn.id}:${sbn.tag.orEmpty()}"
        messages.removeIf { it.notificationKey == notificationKey }
        messages.add(IncomingMessage(packageName, title, text, System.currentTimeMillis(), notificationKey))
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        persistMessages()
    }

    private fun loadMessages() {
        messages.clear()
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MESSAGES, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in maxOf(0, array.length() - MAX_MESSAGES) until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                messages.add(
                    IncomingMessage(
                        packageName = item.optString("packageName"),
                        title = item.optString("title"),
                        text = item.optString("text"),
                        timestamp = item.optLong("timestamp"),
                        notificationKey = item.optString("notificationKey")
                    )
                )
            }
        } catch (_: Exception) {
            messages.clear()
        }
    }

    private fun persistMessages() {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(JSONObject().apply {
                put("packageName", message.packageName)
                put("title", message.title)
                put("text", message.text)
                put("timestamp", message.timestamp)
                put("notificationKey", message.notificationKey)
            })
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGES, array.toString())
            .apply()
    }

    data class IncomingMessage(
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        val notificationKey: String = ""
    )
}
