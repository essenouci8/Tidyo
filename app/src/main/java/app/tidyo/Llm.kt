package app.tidyo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlmException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { BAD_KEY, NETWORK, QUOTA, OTHER }
}

enum class Api { GEMINI, ANTHROPIC, COMPAT }

data class Provider(val id: String, val label: String, val api: Api, val base: String, val gapMs: Long, val batch: Int)

/** Talks to whichever AI provider the user's key belongs to. */
class Llm(private val secrets: Secrets) {
    companion object {
        val PROVIDERS = listOf(
            Provider("gemini", "Gemini", Api.GEMINI, "https://generativelanguage.googleapis.com/v1beta", 6000, 250),
            Provider("anthropic", "Claude", Api.ANTHROPIC, "https://api.anthropic.com/v1", 1200, 150),
            Provider("openai", "OpenAI", Api.COMPAT, "https://api.openai.com/v1", 800, 150),
            Provider("openrouter", "OpenRouter", Api.COMPAT, "https://openrouter.ai/api/v1", 1500, 120),
            Provider("groq", "Groq", Api.COMPAT, "https://api.groq.com/openai/v1", 2500, 60),
            Provider("xai", "xAI", Api.COMPAT, "https://api.x.ai/v1", 1000, 150),
            Provider("mistral", "Mistral", Api.COMPAT, "https://api.mistral.ai/v1", 1000, 150),
            Provider("deepseek", "DeepSeek", Api.COMPAT, "https://api.deepseek.com", 1000, 100),
        )

        fun byId(id: String) = PROVIDERS.first { it.id == id }

        /** Which providers could this key belong to, most likely first. */
        fun candidates(key: String): List<Provider> {
            val order = when {
                key.startsWith("AIza") -> listOf("gemini")
                key.startsWith("sk-ant-") -> listOf("anthropic")
                key.startsWith("sk-or-") -> listOf("openrouter")
                key.startsWith("gsk_") -> listOf("groq")
                key.startsWith("xai-") -> listOf("xai")
                key.startsWith("sk-") -> listOf("openai", "deepseek")
                else -> listOf("mistral", "openai", "gemini", "anthropic", "groq", "xai", "openrouter", "deepseek")
            }
            return order.map { byId(it) }
        }

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

    private var lastCall = 0L
    private var jsonMode = true

    private fun provider(): Provider? = secrets.get("provider")?.let { id -> PROVIDERS.firstOrNull { it.id == id } }
    private fun key(): String? = secrets.get("key")

    fun hasKey() = key() != null && provider() != null
    fun providerLabel(): String = provider()?.label ?: ""
    fun modelLabel(): String = secrets.get("model")?.substringAfterLast('/') ?: ""
    val batch: Int get() = provider()?.batch ?: 100
    val vision: Boolean get() = secrets.get("vision") == "1"

    fun clearKey() {
        listOf("key", "provider", "model", "vision").forEach { secrets.remove(it) }
    }

    private class Resp(val code: Int, val text: String, val retryAfter: Long?)

    private fun http(method: String, url: String, headers: Map<String, String>, body: String? = null): Resp {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val retry = c.getHeaderField("Retry-After")?.toLongOrNull()
            c.disconnect()
            return Resp(code, text, retry)
        } catch (e: IOException) {
            throw LlmException(LlmException.Kind.NETWORK, e.message ?: "network")
        }
    }

    private fun headers(p: Provider, key: String): Map<String, String> = when (p.api) {
        Api.GEMINI -> mapOf("x-goog-api-key" to key)
        Api.ANTHROPIC -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        Api.COMPAT -> mapOf("Authorization" to "Bearer $key")
    }

    private fun errorMessage(text: String): String = try {
        val o = JSONObject(text)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message", text.take(160))
            is String -> e
            else -> text.take(160)
        }
    } catch (e: Exception) {
        text.take(160)
    }

    /** Works out which provider the key belongs to, checks it, and remembers it. */
    fun validate(rawKey: String): Provider {
        val key = rawKey.trim()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.BAD_KEY, "empty key")
        var quota: LlmException? = null
        for (p in candidates(key)) {
            val url = when (p.api) {
                Api.GEMINI -> "${p.base}/models?pageSize=200"
                Api.ANTHROPIC -> "${p.base}/models?limit=100"
                Api.COMPAT -> "${p.base}/models"
            }
            val r = http("GET", url, headers(p, key))
            when {
                r.code in 200..299 -> {
                    val (model, vision) = pickModel(p, r.text)
                    secrets.put("key", key); secrets.put("provider", p.id)
                    secrets.put("model", model); secrets.put("vision", if (vision) "1" else "0")
                    jsonMode = true
                    return p
                }
                r.code == 429 -> quota = LlmException(LlmException.Kind.QUOTA, errorMessage(r.text))
                r.code in listOf(400, 401, 403, 404) -> Unit // not this provider, try the next
                else -> if (candidates(key).size == 1) throw LlmException(LlmException.Kind.OTHER, "HTTP ${r.code} ${errorMessage(r.text)}")
            }
        }
        throw quota ?: LlmException(LlmException.Kind.BAD_KEY, "key rejected")
    }

    private fun ids(p: Provider, json: String): List<String> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<String>()
        if (p.api == Api.GEMINI) {
            val arr = o.optJSONArray("models") ?: return out
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var ok = false
                for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                if (ok) out.add(m.getString("name").removePrefix("models/"))
            }
        } else {
            val arr = o.optJSONArray("data") ?: return out
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun pickModel(p: Provider, json: String): Pair<String, Boolean> {
        val all = ids(p, json)
        fun first(vararg prefs: String): String? {
            for (pref in prefs) all.firstOrNull { it == pref }?.let { return it }
            for (pref in prefs) all.firstOrNull { it.startsWith(pref) }?.let { return it }
            return null
        }
        val bad = listOf("lite", "image", "tts", "live", "preview", "exp", "thinking", "audio", "robotics", "computer", "embedding", "learnlm", "gemma", "realtime", "transcribe", "moderation", "whisper", "search", "instruct", "codex", "davinci", "babbage", "guard", "vision-preview", "dall", "sora")
        fun clean(l: List<String>) = l.filter { n -> bad.none { n.contains(it) } }
        return when (p.id) {
            "gemini" -> {
                val flash = clean(all).filter { it.startsWith("gemini") && it.contains("flash") }
                val v = { n: String -> Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
                val m = if (all.contains("gemini-flash-latest")) "gemini-flash-latest"
                else flash.maxByOrNull(v) ?: all.firstOrNull { it.startsWith("gemini") } ?: "gemini-2.5-flash"
                m to true
            }
            "anthropic" -> {
                val m = all.firstOrNull { it.contains("haiku") } ?: all.firstOrNull { it.contains("sonnet") } ?: all.firstOrNull() ?: "claude-3-5-haiku-latest"
                m to true
            }
            "openai" -> {
                val m = first("gpt-4o-mini", "gpt-4.1-mini", "gpt-5-mini", "gpt-4o", "gpt-4.1")
                    ?: clean(all).firstOrNull { it.startsWith("gpt-") } ?: "gpt-4o-mini"
                m to true
            }
            "openrouter" -> "openrouter/auto" to true
            "groq" -> {
                val vis = all.firstOrNull { it.contains("llama-4-scout") || it.contains("llama-4-maverick") }
                val m = first("llama-3.3-70b-versatile") ?: vis ?: clean(all).firstOrNull() ?: "llama-3.3-70b-versatile"
                m to (vis != null)
            }
            "xai" -> {
                val m = clean(all).filter { it.startsWith("grok") }.let { l -> l.firstOrNull { it.contains("mini") } ?: l.firstOrNull() } ?: "grok-3-mini"
                m to true
            }
            "mistral" -> (first("mistral-small-latest", "mistral-medium-latest") ?: clean(all).firstOrNull() ?: "mistral-small-latest") to true
            else -> (first("deepseek-chat") ?: all.firstOrNull() ?: "deepseek-chat") to false
        }
    }

    /**
     * Sends one request and returns the model's text answer.
     * [parts] use a neutral shape: {"text": "..."} or {"inlineData": {"mimeType": "...", "data": "<base64>"}}.
     */
    suspend fun generate(system: String, parts: JSONArray, temperature: Double = 0.2): String {
        val key = key() ?: throw LlmException(LlmException.Kind.BAD_KEY, "no key")
        val p = provider() ?: throw LlmException(LlmException.Kind.BAD_KEY, "no provider")
        var attempt = 0
        while (true) {
            attempt++
            val wait = p.gapMs - (System.currentTimeMillis() - lastCall)
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()

            val model = secrets.get("model") ?: ""
            val (url, body) = request(p, model, system, parts, temperature)
            val r = withContext(Dispatchers.IO) { http("POST", url, headers(p, key), body) }
            val msg = errorMessage(r.text)
            when {
                r.code in 200..299 -> return extract(p, r.text)
                r.code == 402 || (r.code == 429 && Regex("insufficient_quota|billing|credit|exceeded your current quota", RegexOption.IGNORE_CASE).containsMatchIn(r.text) && p.api != Api.GEMINI) ->
                    throw LlmException(LlmException.Kind.QUOTA, "No credit or quota left on this key. $msg")
                (r.code == 429 || r.code == 500 || r.code == 502 || r.code == 503 || r.code == 529) && attempt < 5 ->
                    delay(((r.retryAfter ?: (10L * attempt)) * 1000).coerceIn(2_000, 70_000))
                r.code == 429 -> throw LlmException(LlmException.Kind.QUOTA, msg)
                r.code == 401 || r.code == 403 -> throw LlmException(LlmException.Kind.BAD_KEY, msg)
                r.code == 400 && p.api == Api.GEMINI && msg.contains("API key", true) -> throw LlmException(LlmException.Kind.BAD_KEY, msg)
                r.code == 400 && p.api == Api.COMPAT && jsonMode && msg.contains("response_format", true) -> jsonMode = false
                r.code == 404 && attempt < 3 -> withContext(Dispatchers.IO) { validate(key) }
                else -> throw LlmException(LlmException.Kind.OTHER, "HTTP ${r.code}: $msg")
            }
        }
    }

    private fun request(p: Provider, model: String, system: String, parts: JSONArray, temp: Double): Pair<String, String> {
        when (p.api) {
            Api.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("temperature", temp).put("responseMimeType", "application/json"))
                return "${p.base}/models/$model:generateContent" to body.toString()
            }
            Api.ANTHROPIC -> {
                val content = JSONArray()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("text")) content.put(JSONObject().put("type", "text").put("text", part.getString("text")))
                    else {
                        val d = part.getJSONObject("inlineData")
                        content.put(
                            JSONObject().put("type", "image").put(
                                "source", JSONObject().put("type", "base64").put("media_type", d.getString("mimeType")).put("data", d.getString("data"))
                            )
                        )
                    }
                }
                val body = JSONObject().put("model", model).put("max_tokens", 8192).put("temperature", temp)
                    .put("system", system).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                return "${p.base}/messages" to body.toString()
            }
            Api.COMPAT -> {
                var hasImage = false
                for (i in 0 until parts.length()) if (parts.getJSONObject(i).has("inlineData")) hasImage = true
                val content: Any = if (!hasImage) {
                    (0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).getString("text") }
                } else {
                    val arr = JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) arr.put(JSONObject().put("type", "text").put("text", part.getString("text")))
                        else {
                            val d = part.getJSONObject("inlineData")
                            arr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${d.getString("mimeType")};base64,${d.getString("data")}")))
                        }
                    }
                    arr
                }
                val body = JSONObject().put("model", model).put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", content))
                )
                if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
                return "${p.base}/chat/completions" to body.toString()
            }
        }
    }

    private fun extract(p: Provider, raw: String): String {
        val o = JSONObject(raw)
        return when (p.api) {
            Api.GEMINI -> {
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            Api.ANTHROPIC -> {
                val c = o.optJSONArray("content") ?: return ""
                (0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") }
            }
            Api.COMPAT -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
        }
    }
}
