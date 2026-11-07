package app.tidyo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The only door between the web UI and the phone. Every call is async. */
class Bridge(private val activity: MainActivity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secrets = Secrets(activity)
    private val gemini = Gemini(secrets)
    private val exec = Executor(activity)
    private val paused = AtomicBoolean(false)
    private var job: Job? = null

    fun emit(name: String, data: JSONObject) {
        activity.js("window.__emit(${JSONObject.quote(name)}, ${JSONObject.quote(data.toString())})")
    }

    private fun reply(id: Int, ok: Boolean, payload: JSONObject) {
        activity.js("window.__cb($id, $ok, ${JSONObject.quote(payload.toString())})")
    }

    private fun fail(id: Int, e: Throwable) {
        if (e is CancellationException) {
            reply(id, false, JSONObject().put("kind", "CANCELLED").put("message", "cancelled"))
            return
        }
        val kind = (e as? GeminiException)?.kind?.name ?: "OTHER"
        reply(id, false, JSONObject().put("kind", kind).put("message", e.message ?: e.javaClass.simpleName))
    }

    @JavascriptInterface
    fun call(id: Int, method: String, argsJson: String) {
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val long = method in setOf("scan", "plan", "apply")
        val j = scope.launch {
            try {
                reply(id, true, handle(method, args))
            } catch (e: Throwable) {
                fail(id, e)
            }
        }
        if (long) job = j
    }

    private suspend fun handle(method: String, a: JSONObject): JSONObject = when (method) {
        "init" -> {
            withContext(Dispatchers.IO) { exec.purgeOld() }
            JSONObject().put("hasKey", gemini.hasKey()).put("storage", storageGranted())
        }
        "validateKey" -> {
            withContext(Dispatchers.IO) { gemini.validate(a.getString("key").trim()) }
            JSONObject().put("ok", true)
        }
        "clearKey" -> { gemini.clearKey(); JSONObject().put("ok", true) }
        "storageGranted" -> JSONObject().put("granted", storageGranted())
        "requestStorage" -> { requestStorage(); JSONObject().put("ok", true) }
        "scan" -> {
            paused.set(false)
            Library.scan({ paused.get() }) { n, d -> emit(n, d) }
        }
        "pause" -> { paused.set(a.getBoolean("on")); JSONObject().put("paused", paused.get()) }
        "cancel" -> { job?.cancel(); JSONObject().put("ok", true) }
        "plan" -> {
            paused.set(false)
            val rules = a.optJSONArray("rules") ?: JSONArray()
            Planner.build(gemini, (0 until rules.length()).map { rules.getString(it) }, { paused.get() }) { n, d -> emit(n, d) }
        }
        "apply" -> {
            val idsArr = a.getJSONArray("ids")
            val ids = (0 until idsArr.length()).map { idsArr.getInt(it) }.toHashSet()
            withContext(Dispatchers.IO) {
                exec.apply(ids) { i, t -> emit("apply", JSONObject().put("n", i).put("total", t)) }
            }
        }
        "undo" -> JSONObject().put("n", withContext(Dispatchers.IO) { exec.undo(a.getLong("id")) })
        "restoreTrash" -> JSONObject().put("n", withContext(Dispatchers.IO) { exec.undo(a.getLong("id"), true) })
        "sessions" -> JSONObject().put("items", withContext(Dispatchers.IO) { exec.sessions() })
        "trashInfo" -> withContext(Dispatchers.IO) { exec.trashInfo() }
        "emptyTrash" -> { withContext(Dispatchers.IO) { exec.emptyTrash() }; JSONObject().put("ok", true) }
        "parseRule" -> parseRule(a.getString("text"))
        "search" -> {
            val out = JSONArray()
            Library.search(a.getString("q")).forEach {
                out.put(JSONObject().put("path", it.rel).put("name", it.name).put("size", it.size).put("mtime", it.mtime).put("abs", it.file.path))
            }
            JSONObject().put("items", out)
        }
        "open" -> { openFile(a.getString("path")); JSONObject().put("ok", true) }
        "openUrl" -> {
            val url = a.getString("url")
            if (url.startsWith("https://")) {
                activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            JSONObject().put("ok", true)
        }
        "bar" -> {
            activity.runOnUiThread { activity.setBars(a.getString("bg"), a.getBoolean("light")) }
            JSONObject().put("ok", true)
        }
        "exit" -> { activity.runOnUiThread { activity.finish() }; JSONObject().put("ok", true) }
        else -> throw IllegalArgumentException("unknown method $method")
    }

    private fun storageGranted() = Environment.isExternalStorageManager()

    private fun requestStorage() {
        activity.runOnUiThread {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
            try { activity.startActivity(i) } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun openFile(path: String) {
        val f = File(path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.runOnUiThread {
            try { activity.startActivity(i) } catch (e: Exception) { /* no app can open it */ }
        }
    }

    private suspend fun parseRule(text: String): JSONObject {
        val sys = """You turn a user's plain-language file cleanup rule into a structured rule for a file organizer app.
Return ONLY JSON: {"ok":true,"instruction":"<one clear imperative sentence a file-organizing AI can follow, with folder names and time thresholds>","chips":[{"t":"text","v":"when a"},{"t":"chip","v":"PDF"},{"t":"text","v":"mentions"},{"t":"chip","v":"electricity"}, ...]}
The chips array re-tells the rule as a short sentence in English. Use {"t":"chip"} for the key entities (file type, keyword, destination folder, naming scheme, time threshold) and {"t":"text"} for the connecting words. At most 12 items, chips short (1-2 words, keep file types uppercase).
If the text is not a file organizing rule, return {"ok":false}."""
        val out = gemini.generate(sys, JSONArray().put(JSONObject().put("text", text)), 0.1)
        return Gemini.parseJson(out) ?: JSONObject().put("ok", false)
    }
}
