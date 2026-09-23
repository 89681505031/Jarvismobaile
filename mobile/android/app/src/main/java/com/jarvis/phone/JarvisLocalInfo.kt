package com.jarvis.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot foreground location helper for weather and local-news lookup.
 * Only ACCESS_COARSE_LOCATION is requested. No background location permission,
 * no continuous tracking, and coordinates are rounded before network use.
 */
class JarvisLocalInfo(private val context: Context) {
    data class Place(
        val latitude: Double,
        val longitude: Double,
        val label: String,
        val countryCode: String
    )

    private val io = Executors.newSingleThreadExecutor()

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun requestPlace(callback: (Result<Place>) -> Unit) {
        if (!hasLocationPermission()) {
            callback(Result.failure(SecurityException("location_permission")))
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: run {
                callback(Result.failure(IllegalStateException("location_service")))
                return
            }

        @Suppress("MissingPermission")
        val fallback = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.NETWORK_PROVIDER
                runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                val delivered = AtomicBoolean(false)
                @Suppress("MissingPermission")
                manager.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    context.mainExecutor
                ) { location ->
                    if (delivered.compareAndSet(false, true)) {
                        val chosen = location ?: fallback
                        if (chosen == null) callback(Result.failure(IllegalStateException("location_unavailable")))
                        else resolvePlace(chosen, callback)
                    }
                }
                return
            }
        }

        if (fallback == null) callback(Result.failure(IllegalStateException("location_unavailable")))
        else resolvePlace(fallback, callback)
    }

    fun requestWeather(callback: (String) -> Unit) {
        requestPlace { placeResult ->
            val place = placeResult.getOrElse {
                callback(locationError(it))
                return@requestPlace
            }
            io.execute {
                callback(fetchWeather(place))
            }
        }
    }

    fun requestLocalNewsHeadlines(callback: (Result<Pair<Place, List<String>>>) -> Unit) {
        requestPlace { placeResult ->
            val place = placeResult.getOrElse {
                callback(Result.failure(it))
                return@requestPlace
            }
            io.execute {
                try {
                    callback(Result.success(place to fetchLocalHeadlines(place)))
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            }
        }
    }

    private fun resolvePlace(location: Location, callback: (Result<Place>) -> Unit) {
        io.execute {
            // Round to ~1 km before any external request: sufficient for weather
            // while avoiding needless precise-coordinate exposure.
            val lat = kotlin.math.round(location.latitude * 100.0) / 100.0
            val lon = kotlin.math.round(location.longitude * 100.0) / 100.0
            var label = "текущему району"
            var country = Locale.getDefault().country.uppercase(Locale.ROOT).ifBlank { "US" }
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val address = addresses?.firstOrNull()
                val city = address?.locality ?: address?.subAdminArea ?: address?.adminArea
                if (!city.isNullOrBlank()) label = city.take(80)
                val cc = address?.countryCode.orEmpty().uppercase(Locale.ROOT)
                if (cc.length == 2) country = cc
            } catch (_: Exception) {
                // Weather still works with coordinates if reverse geocoding fails.
            }
            callback(Result.success(Place(lat, lon, label, country)))
        }
    }

    private fun fetchWeather(place: Place): String {
        return try {
            val endpoint = buildString {
                append("https://api.open-meteo.com/v1/forecast?latitude=")
                append(place.latitude)
                append("&longitude=")
                append(place.longitude)
                append("&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m")
                append("&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                append("&forecast_days=1&timezone=auto")
            }
            val json = JSONObject(get(endpoint, "application/json"))
            val current = json.optJSONObject("current") ?: error("weather_empty")
            val daily = json.optJSONObject("daily")
            val temperature = current.optDouble("temperature_2m", Double.NaN)
            val feels = current.optDouble("apparent_temperature", Double.NaN)
            val precipitation = current.optDouble("precipitation", 0.0)
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            val rainChance = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1

            buildString {
                append("Погода по приблизительному местоположению")
                if (place.label.isNotBlank()) append(" — ").append(place.label)
                append(". ")
                if (!temperature.isNaN()) append("Сейчас ").append(round1(temperature)).append(" °C. ")
                append(weatherDescription(code)).append(". ")
                if (!feels.isNaN()) append("Ощущается как ").append(round1(feels)).append(" °C. ")
                if (!min.isNaN() && !max.isNaN()) {
                    append("Сегодня от ").append(round1(min)).append(" до ").append(round1(max)).append(" °C. ")
                }
                if (rainChance >= 0) append("Вероятность осадков до ").append(rainChance).append("%. ")
                if (precipitation > 0.0) append("Сейчас осадки ").append(round1(precipitation)).append(" мм. ")
                if (!wind.isNaN()) append("Ветер ").append(round1(wind)).append(" км/ч.")
            }.trim()
        } catch (_: Exception) {
            "Не удалось получить погоду. Проверьте интернет и разрешение примерного местоположения."
        }
    }

    private fun fetchLocalHeadlines(place: Place): List<String> {
        val query = URLEncoder.encode(place.label, "UTF-8")
        val language = Locale.getDefault().language.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "en"
        val cc = place.countryCode.takeIf { it.length == 2 } ?: "US"
        val endpoint = "https://news.google.com/rss/search?q=$query&hl=$language&gl=$cc&ceid=$cc:$language"
        val xml = get(endpoint, "application/rss+xml, application/xml, text/xml")
        return Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
            .findAll(xml)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val raw = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1).orEmpty()
                    .replace("<![CDATA[", "").replace("]]>", "").trim()
                android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY)
                    .toString().trim().takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(7)
            .toList()
    }

    private fun get(endpoint: String, accept: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "JARVIS-Android")
            conn.setRequestProperty("Accept", accept)
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_000_000) }
        } finally {
            conn.disconnect()
        }
    }

    private fun round1(value: Double): String =
        String.format(Locale.US, "%.1f", value).replace('.', ',')

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Ясно"
        1 -> "Преимущественно ясно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Ледяной дождь"
        71, 73, 75, 77 -> "Снег"
        80, 81, 82 -> "Ливни"
        85, 86 -> "Снежные заряды"
        95, 96, 99 -> "Гроза"
        else -> "Погодные условия меняются"
    }

    private fun locationError(error: Throwable): String = when (error.message) {
        "location_permission" -> "Разрешите JARVIS примерное местоположение для погоды."
        "location_unavailable" -> "Не удалось определить примерное местоположение. Включите геолокацию и повторите."
        else -> "Местоположение сейчас недоступно."
    }

    fun release() {
        io.shutdownNow()
    }
}
