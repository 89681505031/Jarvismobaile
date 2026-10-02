package com.jarvis.phone

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Native-only, allowlisted API operations. No arbitrary URL receives provider tokens. */
class JarvisConnectors(private val context: Context) {
    private val secrets = ConnectorSecrets(context)
    fun configure(provider: String, token: String) {
        require(provider in setOf("github", "vercel", "openai")) { "Неизвестное подключение" }
        secrets.set(provider, token)
    }
    fun status() = JSONObject().apply {
        for (id in listOf("github", "vercel", "openai")) put(id, secrets.get(id).isNotBlank())
    }.toString()
    private fun segment(value: String): String {
        require(value.isNotBlank() && value.length <= 250) { "Заполните идентификатор" }
        return Uri.encode(value)
    }
    private fun request(provider: String, path: String, method: String = "GET", body: ByteArray? = null,
                        contentType: String = "application/json"): String {
        val host = mapOf("github" to "api.github.com", "vercel" to "api.vercel.com", "openai" to "api.openai.com").getValue(provider)
        val token = secrets.get(provider)
        require(token.isNotBlank()) { "Подключите $provider в настройках" }
        val conn = URL("https://$host$path").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000; conn.readTimeout = if (provider == "openai") 180000 else 45000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (provider == "github") conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true; conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("$provider: HTTP $code. Проверьте ключ, права и лимиты сервиса.")
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 24 * 1024 * 1024) { "Ответ слишком большой" }; out.write(buffer, 0, n) }
                out.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }
    fun execute(action: String, args: JSONObject): JSONObject {
        val result: String = when(action) {
            "github.repos" -> request("github", "/user/repos?per_page=50&sort=updated")
            "github.file", "github.write" -> {
                val repo = args.getString("repo").split('/')
                require(repo.size == 2) { "Репозиторий: владелец/название" }
                val file = args.getString("path").split('/').joinToString("/") { segment(it) }
                val endpoint = "/repos/${segment(repo[0])}/${segment(repo[1])}/contents/$file"
                val branch = args.optString("branch", "main")
                if (action == "github.file") request("github", "$endpoint?ref=${segment(branch)}")
                else {
                    require(args.optBoolean("confirmed")) { "Подтвердите запись файла" }
                    val body = JSONObject().put("message", args.optString("message", "Update from Jarvis Mobile"))
                        .put("branch", branch).put("content", Base64.encodeToString(args.getString("content").toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    val sha = args.optString("sha")
                    if (sha.isNotBlank()) body.put("sha", sha)
                    request("github", endpoint, "PUT", body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            "vercel.projects" -> request("vercel", "/v9/projects?limit=50" + team(args))
            "vercel.deployments" -> request("vercel", "/v6/deployments?limit=20&projectId=${segment(args.getString("project"))}" + team(args))
            "vercel.deploy" -> {
                require(args.optBoolean("confirmed")) { "Подтвердите создание preview-развёртывания" }
                val body = JSONObject().put("name", args.getString("name")).put("project", args.getString("project"))
                    .put("gitSource", JSONObject().put("type", "github").put("repoId", args.getString("repoId"))
                        .put("ref", args.optString("branch", "main")))
                request("vercel", "/v13/deployments?forceNew=1" + team(args), "POST", body.toString().toByteArray(Charsets.UTF_8))
            }
            "image.generate", "image.edit" -> {
                val prompt = args.getString("prompt").trim()
                require(prompt.isNotEmpty() && prompt.length <= 4000) { "Описание: 1–4000 символов" }
                if (action == "image.generate") {
                    val body = JSONObject().put("model", "gpt-image-1").put("prompt", prompt).put("size", "1024x1024").put("n", 1)
                    request("openai", "/v1/images/generations", "POST", body.toString().toByteArray(Charsets.UTF_8))
                } else {
                    val file = java.io.File(context.filesDir, "attachments/source.png")
                    require(file.isFile) { "Сначала прикрепите фото" }
                    val boundary = "Jarvis" + java.util.UUID.randomUUID().toString()
                    val out = ByteArrayOutputStream()
                    fun part(name: String, value: String) { out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8)) }
                    part("model", "gpt-image-1"); part("prompt", prompt); part("size", "1024x1024")
                    out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").toByteArray())
                    out.write(file.readBytes()); out.write("\r\n--$boundary--\r\n".toByteArray())
                    request("openai", "/v1/images/edits", "POST", out.toByteArray(), "multipart/form-data; boundary=$boundary")
                }
            }
            else -> throw IllegalArgumentException("Неизвестная операция")
        }
        if (action.startsWith("image.")) {
            val encoded = JSONObject(result).getJSONArray("data").getJSONObject(0).getString("b64_json")
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val dir = java.io.File(context.filesDir, "generated").apply { mkdirs() }
            val file = java.io.File(dir, "jarvis-${System.currentTimeMillis()}.png").apply { writeBytes(bytes) }
            return JSONObject().put("text", "Изображение готово").put("image", "data:image/png;base64,$encoded").put("file", file.name)
        }
        if (action == "github.file") {
            val obj = JSONObject(result)
            if (obj.optString("encoding") == "base64") obj.put("decoded", String(Base64.decode(obj.getString("content"), Base64.DEFAULT), Charsets.UTF_8))
            return obj
        }
        return JSONObject().put("text", result)
    }
    private fun team(args: JSONObject): String = args.optString("team").let { if (it.isBlank()) "" else "&teamId=${segment(it)}" }
}
