package app.tidyo

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { BAD_KEY, NETWORK, QUOTA, OTHER }
}

/** Minimal Gemini REST client. The key is only ever sent in the x-goog-api-key header. */
class Gemini(private val secrets: Secrets) {
    private val base = "https://generativelanguage.googleapis.com/v1beta"
    private var lastCall = 0L

    fun hasKey() = secrets.get("gemini_key") != null
    fun clearKey() {
        secrets.remove("gemini_key"); secrets.remove("gemini_model")
    }

    private fun http(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val retry = c.getHeaderField("Retry-After")
            c.disconnect()
            return code to (if (retry != null && code == 429) "RETRY:$retry|$text" else text)
        } catch (e: IOException) {
            throw GeminiException(GeminiException.Kind.NETWORK, e.message ?: "network")
        }
    }

    private fun errorMessage(text: String): String =
        try {
            JSONObject(text.substringAfter('|', text)).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            text.take(160)
        }

    /** Checks the key against the API and remembers it (and a model) when it works. */
    fun validate(key: String) {
        val (code, text) = http("GET", "$base/models?pageSize=200", key)
        when {
            code == 200 -> {
                val model = pickModel(JSONObject(text).optJSONArray("models") ?: JSONArray())
                secrets.put("gemini_key", key)
                secrets.put("gemini_model", model)
            }
            code == 400 || code == 401 || code == 403 ->
                throw GeminiException(GeminiException.Kind.BAD_KEY, errorMessage(text))
            code == 429 -> throw GeminiException(GeminiException.Kind.QUOTA, errorMessage(text))
            else -> throw GeminiException(GeminiException.Kind.OTHER, "HTTP $code ${errorMessage(text)}")
        }
    }

    private fun pickModel(models: JSONArray): String {
        val usable = ArrayList<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var ok = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
            if (ok) usable.add(m.getString("name"))
        }
        if (usable.contains("models/gemini-flash-latest")) return "models/gemini-flash-latest"
        val bad = listOf("lite", "image", "tts", "live", "preview", "exp", "thinking", "audio", "robotics", "computer", "embedding", "learnlm", "gemma")
        val flash = usable.filter { n -> n.contains("gemini") && n.contains("flash") && bad.none { n.contains(it) } }
        fun version(n: String): Double =
            Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return flash.maxByOrNull { version(it) }
            ?: usable.firstOrNull { it.contains("gemini") }
            ?: "models/gemini-2.5-flash"
    }

    /** Sends one request and returns the model's text answer (JSON text when asked for). */
    suspend fun generate(system: String, parts: JSONArray, temperature: Double = 0.2): String {
        val key = secrets.get("gemini_key") ?: throw GeminiException(GeminiException.Kind.BAD_KEY, "no key")
        var attempt = 0
        while (true) {
            attempt++
            // Stay under the free tier's requests-per-minute limit.
            val wait = 6_000 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()

            val model = secrets.get("gemini_model") ?: "models/gemini-flash-latest"
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", temperature).put("responseMimeType", "application/json")
                )
                .toString()
            val (code, text) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http("POST", "$base/$model:generateContent", key, body)
            }
            when {
                code == 200 -> return extractText(text)
                (code == 429 || code == 500 || code == 503) && attempt < 5 -> {
                    val retry = Regex("^RETRY:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
                    delay(((retry ?: (10L * attempt)) * 1000).coerceAtMost(70_000))
                }
                code == 429 -> throw GeminiException(GeminiException.Kind.QUOTA, errorMessage(text))
                code == 400 || code == 401 || code == 403 ->
                    throw GeminiException(GeminiException.Kind.BAD_KEY, errorMessage(text))
                code == 404 && attempt < 3 -> {
                    // The model name went away: look for a fresh one.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { validate(key) }
                }
                else -> throw GeminiException(GeminiException.Kind.OTHER, "HTTP $code ${errorMessage(text)}")
            }
        }
    }

    private fun extractText(raw: String): String {
        val parts = JSONObject(raw).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    companion object {
        /** Tolerant JSON parse: strips code fences if the model added them. */
        fun parseJson(text: String): JSONObject? {
            var t = text.trim()
            if (t.startsWith("```")) t = t.substringAfter('\n').substringBeforeLast("```")
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try { JSONObject(t.substring(start, end + 1)) } catch (e: Exception) { null }
        }
    }
}
