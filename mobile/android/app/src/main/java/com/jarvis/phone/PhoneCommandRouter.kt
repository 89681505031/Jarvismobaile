package com.jarvis.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import java.util.Locale

class PhoneCommandRouter(private val context: Context) {
    private val pm = context.packageManager
    private val allowedApps = AllowedAppStore(context)

    fun canHandle(raw: String): Boolean {
        val lower = normalizeCommand(raw)
        return lower == "открой браузер" || lower.contains("открой браузер") ||
            lower.contains("открой настройки") || lower.contains("открой wi-fi") || lower.contains("открой wifi") || lower.contains("открой вай фай") || lower.contains("открой bluetooth") || lower.contains("открой блютуз") || lower.contains("открой режим полета") || lower.contains("открой авиарежим") || lower.contains("открой камеру") ||
            lower.startsWith("найди в интернете") || lower.startsWith("позвони ") ||
            lower.contains("кто звонил") || lower.contains("пропущенные вызовы") ||
            lower.startsWith("открой ") ||
            lower.contains("включи фонарик") || lower.contains("выключи фонарик") ||
            lower.contains("включи свет") || lower.contains("выключи свет") ||
            lower.contains("увеличь громкость") || lower.contains("сделай громче") ||
            lower.contains("уменьши громкость") || lower.contains("сделай тише") ||
            lower.contains("выключи звук") || lower.contains("включи звук") ||
            lower == "назад" || lower.contains("вернись назад") || lower.contains("вернуться назад") || lower.contains("перейди назад") ||
            lower == "вперед" || lower == "вперёд" || lower.contains("идти вперед") || lower.contains("идти вперёд") || lower.contains("перейди вперед") || lower.contains("перейди вперёд") ||
            lower == "домой" || lower == "главный экран" || lower.contains("на главный экран") || lower.contains("перейди домой") ||
            lower.contains("открой последние приложения") || lower.contains("покажи последние приложения") ||
            lower == "включи музыку" || lower == "включи музыку яндекс" || lower == "открой яндекс музыку" ||
            lower.startsWith("включи песню ") || lower.startsWith("включи музыку ") ||
            lower == "стоп" || lower == "стоп музыка" || lower == "останови музыку" || lower == "пауза" ||
            lower == "следующая песня" || lower == "следующий трек" || lower == "дальше" ||
            lower == "предыдущая песня" || lower == "предыдущий трек" || lower == "назад песню" ||
            lower.contains("прочитай последнее сообщение в ватсап") || lower.contains("прочитай последнее сообщение whatsapp")
    }

    fun execute(raw: String): String {
        val command = raw.trim().replace(Regex("[.!?,;:]+$"), "").trim()
        val lower = normalizeCommand(command)
        return when {
            lower == "открой браузер" || lower.contains("открой браузер") -> openBrowser()
            lower.contains("открой wi-fi") || lower.contains("открой wifi") || lower.contains("открой вай фай") -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю настройки Wi-Fi."
            }
            lower.contains("открой bluetooth") || lower.contains("открой блютуз") -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю настройки Bluetooth."
            }
            lower.contains("открой режим полета") || lower.contains("открой авиарежим") -> {
                context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю настройки режима полёта."
            }
            lower.contains("открой настройки") -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю настройки телефона."
            }
            lower.contains("открой камеру") -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermission(Manifest.permission.CAMERA)
                    return "Разрешите доступ к камере и повторите команду."
                }
                context.startActivity(Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Открываю камеру."
            }
            lower.startsWith("найди в интернете") -> searchWeb(command.drop(17).trim())
            lower.startsWith("позвони ") -> callContact(command.drop(8).trim())
            lower.contains("кто звонил") || lower.contains("пропущенные вызовы") -> missedCalls()
            lower.contains("включи фонарик") || lower.contains("включи свет") -> setFlashlight(true)
            lower.contains("выключи фонарик") || lower.contains("выключи свет") -> setFlashlight(false)
            lower.contains("увеличь громкость") || lower.contains("сделай громче") -> changeVolume(true)
            lower.contains("уменьши громкость") || lower.contains("сделай тише") -> changeVolume(false)
            lower.contains("выключи звук") -> setMute(true)
            lower.contains("включи звук") -> setMute(false)
            lower == "назад" || lower.contains("вернись назад") || lower.contains("вернуться назад") || lower.contains("перейди назад") -> goBack()
            lower == "вперед" || lower == "вперёд" || lower.contains("идти вперед") || lower.contains("идти вперёд") || lower.contains("перейди вперед") || lower.contains("перейди вперёд") -> goForward()
            lower == "домой" || lower == "главный экран" || lower.contains("на главный экран") || lower.contains("перейди домой") -> goHome()
            lower.contains("открой последние приложения") || lower.contains("покажи последние приложения") -> accessibilityAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Открываю последние приложения.")
            lower == "включи музыку" || lower == "продолжи музыку" ||
                lower == "воспроизведи музыку" || lower == "играй музыку" ->
                if (context is Activity) openYandexMusic("") else musicControl("play")
            lower == "включи музыку яндекс" || lower == "открой яндекс музыку" -> openYandexMusic("")
            lower.startsWith("включи песню ") -> playYandexSong(command.drop(13).trim())
            lower.startsWith("включи музыку ") -> playYandexSong(command.drop(14).trim())
            lower == "стоп" || lower == "стоп музыка" || lower == "останови музыку" || lower == "пауза" -> musicControl("stop")
            lower == "следующая песня" || lower == "следующий трек" || lower == "дальше" -> musicControl("next")
            lower == "предыдущая песня" || lower == "предыдущий трек" || lower == "назад песню" -> musicControl("previous")
            lower.contains("прочитай последнее сообщение в ватсап") || lower.contains("прочитай последнее сообщение whatsapp") -> openWhatsAppForReading()
            lower.startsWith("открой ") -> openAllowedApp(command.drop(7).trim())
            else -> "Команда PHONE MODE пока не подключена: $command"
        }
    }

    private fun openYandexMusic(query: String): String {
        val packageName = "ru.yandex.music"
        return try {
            if (query.isBlank()) {
                if (!openPackage(packageName)) return "Яндекс Музыка не установлена."
                Handler(Looper.getMainLooper()).postDelayed({ sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 900)
                "Открываю Яндекс Музыку."
            } else {
                val deepLink = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("yandexmusic://search?text=" + Uri.encode(query))
                ).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    context.startActivity(deepLink)
                } catch (_: Exception) {
                    if (!openPackage(packageName)) return "Яндекс Музыка не установлена."
                }

                val handler = Handler(Looper.getMainLooper())
                listOf(1100L, 2200L, 3400L).forEachIndexed { index, delay ->
                    handler.postDelayed({
                        val service = JarvisAccessibilityService.instance
                        val clicked = service?.clickAnyText(
                            query,
                            "Слушать",
                            "Воспроизвести",
                            "Play",
                            "▶"
                        ) == true
                        if (clicked || index == 2) {
                            handler.postDelayed({ sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 450)
                        }
                    }, delay)
                }
                "Ищу «$query» и включаю найденный трек."
            }
        } catch (_: Exception) {
            "Не удалось открыть Яндекс Музыку. Проверьте, что приложение установлено."
        }
    }

    private fun playYandexSong(query: String): String {
        if (query.isBlank()) return "Назовите песню."
        return openYandexMusic(query)
    }

    private fun musicControl(action: String): String {
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        if (sendMediaKey(keyCode)) {
            return when (action) {
                "play" -> "Продолжаю воспроизведение музыки."
                "stop" -> "Музыка поставлена на паузу."
                "next" -> "Переключаю на следующий трек."
                else -> "Переключаю на предыдущий трек."
            }
        }

        val service = JarvisAccessibilityService.instance
        val clicked = when (action) {
            "play" -> service?.clickAnyText("Воспроизвести", "Продолжить", "Play", "▶") == true
            "stop" -> service?.clickAnyText("Пауза", "Pause", "Стоп", "Stop") == true
            "next" -> service?.clickAnyText("Следующий трек", "Следующая песня", "Дальше", "Next") == true
            else -> service?.clickAnyText("Предыдущий трек", "Предыдущая песня", "Назад", "Previous") == true
        }
        return if (clicked) {
            when (action) {
                "play" -> "Продолжаю воспроизведение музыки."
                "stop" -> "Музыка поставлена на паузу."
                "next" -> "Переключаю на следующий трек."
                else -> "Переключаю на предыдущий трек."
            }
        } else {
            "Не удалось управлять воспроизведением. Запустите Яндекс Музыку хотя бы один раз."
        }
    }

    private fun sendMediaKey(keyCode: Int): Boolean {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return false
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openWhatsAppForReading(): String {
        val preferredPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
        val packageName = preferredPackages.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: launcherApps()
                .firstOrNull { normalizeAppName(it.label).contains("whatsapp") }
                ?.packageName
            ?: return "WhatsApp не установлен."
        return if (openPackage(packageName)) {
            "Открываю WhatsApp и читаю последнее сообщение."
        } else {
            "Не удалось открыть WhatsApp."
        }
    }

    private fun openBrowser(): String {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Открываю браузер."
    }

    private fun searchWeb(query: String): String {
        if (query.isBlank()) return "Что найти в интернете, сэр?"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Ищу в интернете: $query"
    }

    private fun callContact(name: String): String {
        if (name.isBlank()) return "Назовите имя контакта."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CONTACTS)
            return "Запрашиваю доступ к контактам Android."
        }
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, arrayOf("%$name%"), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val number = cursor.getString(0)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermission(Manifest.permission.CALL_PHONE)
                    return "Запрашиваю доступ к телефону для выполнения вызова."
                }
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Звоню контакту $name."
            }
        }
        return "Контакт $name не найден."
    }

    private fun missedCalls(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CALL_LOG)
            return "Запрашиваю доступ к журналу вызовов Android."
        }
        context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf("number", "date", "type"), "type = ?", arrayOf("3"), "date DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) return "Пропущенных вызовов не найдено."
            return "Последний пропущенный вызов: ${cursor.getString(0) ?: "неизвестный номер"}."
        }
        return "Не удалось прочитать журнал вызовов."
    }

    private fun setFlashlight(enabled: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CAMERA)
            return "Для фонарика нужен доступ к камере. Разрешите его и повторите команду."
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return "Фонарик недоступен на этом телефоне."
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return "Фонарик недоступен на этом телефоне."
        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            if (enabled) "Фонарик включён." else "Фонарик выключен."
        } catch (_: Exception) {
            "Не удалось изменить состояние фонарика."
        }
    }

    private fun changeVolume(increase: Boolean): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "Не удалось получить управление громкостью."
        val direction = if (increase) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI)
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) current * 100 / max else 0
        return "Громкость: $percent%."
    }

    private fun setMute(muted: Boolean): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return "Не удалось получить управление звуком."
        return try {
            val direction = if (muted) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
            if (muted) "Звук выключен." else "Звук включён."
        } catch (_: Exception) {
            "Не удалось изменить состояние звука."
        }
    }

    private fun goBack(): String {
        val service = JarvisAccessibilityService.instance
        if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
            return "Возвращаюсь назад."
        }
        val activity = context as? Activity
        if (activity != null) {
            activity.runOnUiThread { activity.onBackPressed() }
            return "Возвращаюсь назад."
        }
        return "Не удалось выполнить команду «назад». Включите J.A.R.V.I.S. в специальных возможностях Android."
    }

    private fun goHome(): String {
        val service = JarvisAccessibilityService.instance
        if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
            return "Переход на главный экран."
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Переход на главный экран."
        } catch (_: Exception) {
            "Не удалось перейти на главный экран."
        }
    }

    private fun goForward(): String {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            if (service.clickText("Вперёд") || service.clickText("Вперед") || service.clickText("Forward")) {
                return "Вперёд."
            }
        }
        return "Команда «вперёд» зависит от приложения. Включите J.A.R.V.I.S. в специальных возможностях Android, чтобы я мог нажать кнопку «Вперёд» в поддерживаемом приложении."
    }

    private fun accessibilityAction(action: Int, successMessage: String): String {
        val service = JarvisAccessibilityService.instance
            ?: return "Для этой команды включите J.A.R.V.I.S. в специальных возможностях Android."
        return if (service.performGlobalAction(action)) {
            successMessage
        } else {
            "Не удалось выполнить системное действие."
        }
    }

    private fun requestPermission(permission: String) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            ActivityCompat.requestPermissions(activity, arrayOf(permission), 100)
        }
    }

    private fun normalizeCommand(value: String): String =
        value.trim().lowercase(Locale("ru", "RU")).replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "").replace(Regex("\\s+"), " ").trim()

    private fun normalizeAppName(value: String): String =
        value.trim().lowercase(Locale("ru", "RU")).replace(Regex("[^a-zа-яё0-9]+"), " ").trim()

    private fun openAllowedApp(name: String): String {
        if (name.isBlank()) return "Назовите приложение."
        val wanted = normalizeAppName(name)
        val match = launcherApps().firstOrNull {
            val label = normalizeAppName(it.label)
            label == wanted || label.contains(wanted) || wanted.contains(label)
        }
        if (match == null) return "Приложение «$name» не найдено. Откройте раздел «Приложение», найдите его и добавьте в JARVIS."
        if (!allowedApps.isAllowed(match.packageName)) return "Приложение «" + match.label + "» найдено, но не подключено. Добавьте его в разделе «Приложение»."
        return if (openPackage(match.packageName)) "Открываю " + match.label + "." else "Не удалось открыть " + match.label + "."
    }

    data class LaunchableApp(val label: String, val packageName: String)

    fun launcherApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun setAppAllowed(packageName: String, allowed: Boolean) = allowedApps.setAllowed(packageName, allowed)
    fun isAppAllowed(packageName: String): Boolean = allowedApps.isAllowed(packageName)

    fun openPackage(packageName: String): Boolean {
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }
}
