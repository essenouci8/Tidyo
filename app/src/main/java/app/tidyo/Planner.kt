package app.tidyo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class PlanItem(
    val id: Int,
    val kind: String, // MOVE, RENAME, DELETE
    val fromRel: String,
    val toFolder: String?,
    val newName: String?,
    val reason: String,
    val size: Long
)

object Planner {
    @Volatile var current: List<PlanItem> = emptyList()
    @Volatile var planId: Int = 0

    private const val SYSTEM_1 = """You organize files on an Android phone. For each input file decide: leave it, move it to a better folder, rename it, or send it to trash.
Return ONLY JSON: {"actions":[{"id":<int>,"op":"move"|"rename"|"delete","folder":"<relative folder, for move>","name":"<new file name including extension, optional>","reason":"<max 8 words>"}]}
Rules:
- Act only when clearly useful. Omit files that are fine where they are.
- Prefer existing folders from the folder list. Create new folders sparingly, with short human names such as "Documents/Invoices" or "Pictures/Screenshots".
- Folders are relative to the phone's main storage. Never use ".." and never start with "Android" or ".".
- Never change a file's extension. Do not invent facts you cannot infer from the path, name, size and age.
- "delete" only for obvious junk (temporary or cache files, thumbnails, old installers) or when a user rule asks for it. Deleted files stay in a 30-day trash, but be conservative with documents and photos.
- Input lines are: id|path|sizeKB|ageDays"""

    private const val SYSTEM_2 = """You name files on an Android phone from a small preview of each one. For every item, propose a short descriptive file name (max 50 characters, no extension, no slashes) based on what you see, and a destination folder relative to the phone's main storage (for example "Pictures/Travel" or "Documents/Contracts").
Return ONLY JSON: {"items":[{"id":<int>,"name":"...","folder":"..."}]}
Omit an item if you cannot tell what it is. Do not invent details such as places or people's names unless they are clearly visible."""

    suspend fun build(
        gemini: Gemini,
        rules: List<String>,
        paused: () -> Boolean,
        emit: (String, JSONObject) -> Unit
    ): JSONObject {
        val lib = Library
        val now = System.currentTimeMillis()
        val items = LinkedHashMap<Int, PlanItem>()

        // Exact duplicates are handled locally.
        val dupExtras = lib.dupExtras()
        for (g in lib.dupGroups) {
            val k = lib.keeper(g)
            for (r in g) if (r !== k) {
                items[r.id] = PlanItem(r.id, "DELETE", r.rel, null, null, "identical copy of ${k.name}", r.size)
            }
        }

        // Candidates the AI should look at.
        val cands = lib.files.filter { it.id !in items && (lib.isUnsorted(it) || lib.isBadName(it) || lib.isScreenshot(it) || lib.isChat(it)) }
            .sortedWith(compareBy({ if (lib.isUnsorted(it)) 0 else 1 }, { -it.mtime }))
            .take(1200)

        val imgExt = setOf("jpg", "jpeg", "png", "webp")
        val vision = cands.filter {
            lib.isBadName(it) && !lib.isScreenshot(it) && !lib.isChat(it) &&
                (it.ext in imgExt || it.ext == "pdf") && it.size in 1..(20L * 1024 * 1024)
        }.take(48)
        val visionIds = vision.map { it.id }.toHashSet()
        val nameOnly = cands.filter { it.id !in visionIds }

        val batches1 = nameOnly.chunked(250)
        val batches2 = vision.chunked(6)
        val totalCalls = batches1.size + batches2.size
        var doneCalls = 0
        var processed = 0
        val totalFiles = nameOnly.size + vision.size
        fun tick() {
            emit(
                "plan", JSONObject().put("n", processed).put("total", totalFiles)
                    .put("callsLeft", totalCalls - doneCalls)
            )
        }
        tick()

        val folderText = lib.folderList().joinToString("\n")
        val ruleText = if (rules.isEmpty()) "(none)" else rules.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")
        val sys1 = "$SYSTEM_1\n\nUser rules (apply when they match):\n$ruleText\n\nExisting folders:\n$folderText"

        for (b in batches1) {
            while (paused()) delay(150)
            val sb = StringBuilder()
            for (r in b) sb.append("${r.id}|${r.rel}|${r.size / 1024}|${(now - r.mtime) / 86_400_000}\n")
            val text = gemini.generate(sys1, JSONArray().put(JSONObject().put("text", sb.toString())))
            val arr = Gemini.parseJson(text)?.optJSONArray("actions")
            if (arr != null) for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val r = lib.byId[a.optInt("id", -1)] ?: continue
                if (r.id in items) continue
                toItem(r, a.optString("op"), a.optString("folder"), a.optString("name"), a.optString("reason"))?.let { items[r.id] = it }
            }
            processed += b.size; doneCalls++; tick()
        }

        val sys2 = "$SYSTEM_2\n\nExisting folders:\n$folderText"
        for (b in batches2) {
            while (paused()) delay(150)
            val parts = JSONArray()
            var any = false
            for (r in b) {
                val jpeg = withContext(Dispatchers.IO) { thumb(r) } ?: continue
                any = true
                parts.put(JSONObject().put("text", "Item id ${r.id} (current name: ${r.name})"))
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg)))
            }
            if (any) {
                val text = gemini.generate(sys2, parts, 0.3)
                val arr = Gemini.parseJson(text)?.optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val r = lib.byId[a.optInt("id", -1)] ?: continue
                    if (r.id in items) continue
                    val name = a.optString("name").trim()
                    if (name.isEmpty()) continue
                    toItem(r, "move", a.optString("folder"), name + "." + r.ext, "named from its preview")?.let { items[r.id] = it }
                }
            }
            processed += b.size; doneCalls++; tick()
        }

        current = items.values.toList()
        planId = (planId + 1)
        return planJson()
    }

    fun planJson(): JSONObject {
        val arr = JSONArray()
        var delBytes = 0L
        for (p in current) {
            if (p.kind == "DELETE") delBytes += p.size
            arr.put(
                JSONObject().put("id", p.id).put("kind", p.kind).put("from", p.fromRel)
                    .put("toFolder", p.toFolder ?: JSONObject.NULL).put("newName", p.newName ?: JSONObject.NULL)
                    .put("reason", p.reason).put("size", p.size)
            )
        }
        return JSONObject().put("planId", planId).put("items", arr).put("deleteBytes", delBytes)
            .put("filesRead", Library.files.size)
    }

    private fun toItem(r: FileRec, op: String, folderRaw: String, nameRaw: String, reason: String): PlanItem? {
        val why = reason.take(80)
        when (op.lowercase()) {
            "delete" -> return PlanItem(r.id, "DELETE", r.rel, null, null, why, r.size)
            "move", "rename" -> {
                val folder = cleanFolder(folderRaw)
                val name = cleanName(nameRaw, r.ext)
                val newName = if (name != null && name != r.name) name else null
                val toFolder = if (folder != null && folder != r.parentRel) folder else null
                return when {
                    toFolder != null -> PlanItem(r.id, "MOVE", r.rel, toFolder, newName, why, r.size)
                    newName != null -> PlanItem(r.id, "RENAME", r.rel, null, newName, why, r.size)
                    else -> null
                }
            }
        }
        return null
    }

    fun cleanFolder(raw: String): String? {
        val segs = raw.replace('\\', '/').split('/').map { clean(it) }.filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segs.isEmpty() || segs.size > 4) return null
        if (segs[0].equals("Android", true)) return null
        return segs.joinToString("/")
    }

    fun cleanName(raw: String, ext: String): String? {
        var n = clean(raw.substringAfterLast('/'))
        if (n.isEmpty()) return null
        if (n.length > 120) n = n.take(120).trim()
        if (ext.isNotEmpty() && !n.lowercase().endsWith(".$ext")) {
            n = n.substringBeforeLast('.', n).trim() + "." + ext
        }
        return n
    }

    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimStart('.').trim()

    private fun thumb(r: FileRec): String? = try {
        val bmp: Bitmap? = if (r.ext == "pdf") renderPdf(r.file) else decode(r.file)
        if (bmp == null) null else {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Throwable) {
        null
    }

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val m = maxOf(b.width, b.height)
        if (m <= 512) return b
        val k = 512f / m
        val sc = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        if (sc !== b) b.recycle()
        return sc
    }

    private fun renderPdf(f: File): Bitmap? {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { rd ->
                if (rd.pageCount == 0) return null
                rd.openPage(0).use { page ->
                    val w = 512
                    val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(64, 1024)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }
}
