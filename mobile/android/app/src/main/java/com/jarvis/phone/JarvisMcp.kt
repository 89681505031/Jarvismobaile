package com.jarvis.phone

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Streamable HTTP MCP, bearer-token servers. OAuth-only servers need a gateway. */
class JarvisMcp(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_mcp", Context.MODE_PRIVATE)
    private val secrets = ConnectorSecrets(context)
    private var session: String? = null
    private var protocol = "2025-03-26"
    private var ready = false
    fun configure(endpoint: String, token: String) {
        if (endpoint.isBlank()) { prefs.edit().clear().apply(); secrets.set("mcp", ""); session = null; ready = false; return }
        val url = URL(endpoint)
        require(url.protocol == "https" && url.userInfo == null && url.ref == null &&
            url.host.contains('.') && !url.host.matches(Regex("[0-9.]+")) && !url.host.endsWith(".local")) { "Нужен публичный HTTPS URL MCP-сервера" }
        prefs.edit().putString("endpoint", endpoint).apply(); secrets.set("mcp", token)
        session = null; ready = false
    }
    fun configured() = prefs.getString("endpoint", "").orEmpty().isNotEmpty()
    private fun rpc(method: String, params: JSONObject, notification: Boolean = false): JSONObject {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        require(endpoint.isNotEmpty()) { "Подключите MCP-сервер" }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000; conn.readTimeout = 45000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("MCP-Protocol-Version", protocol)
            session?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            secrets.get("mcp").takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val id = UUID.randomUUID().toString()
            val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            if (!notification) body.put("id", id)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            require(conn.responseCode in 200..299) { "MCP: HTTP ${conn.responseCode}. Проверьте адрес и авторизацию." }
            conn.getHeaderField("Mcp-Session-Id")?.let { session = it }
            if (notification) return JSONObject()
            val response = if (conn.contentType.orEmpty().contains("text/event-stream")) {
                conn.inputStream.bufferedReader().use { reader ->
                    var matched: JSONObject? = null; var consumed = 0; val event = StringBuilder()
                    while (matched == null) {
                        val line = reader.readLine() ?: break; consumed += line.length
                        require(consumed <= 1024 * 1024) { "Ответ MCP слишком большой" }
                        if (line.startsWith("data:")) event.append(line.substringAfter(':').trimStart()).append('\n')
                        if (line.isEmpty() && event.isNotEmpty()) {
                            val obj = JSONObject(event.toString()); event.setLength(0)
                            if (obj.optString("id") == id) matched = obj
                        }
                    }
                    matched ?: error("MCP не вернул ответ операции")
                }
            } else conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = input.read(buf); if (n < 0) break
                    require(out.size() + n <= 1024 * 1024) { "Ответ MCP слишком большой" }; out.write(buf, 0, n) }
                JSONObject(out.toString("UTF-8"))
            }
            if (response.has("error")) error("MCP отклонил операцию: " + response.getJSONObject("error").optString("message").take(500))
            return response.optJSONObject("result") ?: JSONObject().put("result", response.opt("result"))
        } finally { conn.disconnect() }
    }
    @Synchronized fun execute(action: String, args: JSONObject): JSONObject {
        if (!ready) {
            val init = rpc("initialize", JSONObject().put("protocolVersion", protocol).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Jarvis-Mobile").put("version", "0.4")))
            protocol = init.optString("protocolVersion", protocol)
            rpc("notifications/initialized", JSONObject(), true); ready = true
        }
        return when (action) {
            "mcp.list" -> {
                val tools = JSONArray(); var cursor = ""; var pages = 0
                do {
                    val page = rpc("tools/list", JSONObject().apply { if (cursor.isNotEmpty()) put("cursor", cursor) })
                    val items = page.optJSONArray("tools") ?: JSONArray()
                    for (i in 0 until items.length()) tools.put(items.get(i))
                    cursor = page.optString("nextCursor"); pages++
                } while (cursor.isNotEmpty() && pages < 10)
                JSONObject().put("tools", tools).put("truncated", cursor.isNotEmpty())
            }
            "mcp.call" -> {
                require(args.optBoolean("confirmed")) { "Подтвердите вызов инструмента" }
                rpc("tools/call", JSONObject().put("name", args.getString("name")).put("arguments", args.getJSONObject("arguments")))
            }
            else -> error("Неизвестная операция MCP")
        }
    }
}
