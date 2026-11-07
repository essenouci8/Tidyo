package app.tidyo

import android.content.Context
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Applies plans, keeps a journal of every move and runs the 30-day trash. */
class Executor(private val ctx: Context) {
    private val root = Library.root
    private val trashRoot = File(root, ".Tidyo/trash")
    private val journalDir = File(ctx.filesDir, "journal").also { it.mkdirs() }
    private val keepDays = 30L

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun move(from: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = false)
            if (from.delete()) true else { to.delete(); false }
        } catch (e: Exception) {
            to.delete(); false
        }
    }

    private fun scan(paths: Collection<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(ctx, paths.take(2000).toTypedArray(), null, null)
    }

    fun apply(
        ids: Set<Int>,
        emit: (Int, Int) -> Unit
    ): JSONObject {
        val started = System.currentTimeMillis()
        val sessionId = started
        val sessionTrash = File(trashRoot, sessionId.toString())
        val entries = JSONArray()
        val touched = ArrayList<String>()
        val newFolders = HashSet<String>()
        val perFolder = LinkedHashMap<String, Int>()
        val examples = ArrayList<String>()
        var moved = 0; var renamed = 0; var deleted = 0; var skipped = 0; var freed = 0L
        val items = Planner.current.filter { it.id in ids }
        for ((i, p) in items.withIndex()) {
            if (i % 10 == 0) emit(i, items.size)
            val rec = Library.byId[p.id]
            val src = rec?.file
            if (src == null || !src.exists()) { skipped++; continue }
            try {
                when (p.kind) {
                    "DELETE" -> {
                        sessionTrash.mkdirs()
                        File(trashRoot, ".nomedia").let { if (!it.exists()) it.createNewFile() }
                        val dest = unique(sessionTrash, "${i}_${src.name}")
                        if (move(src, dest)) {
                            deleted++; freed += p.size
                            entries.put(entry("DELETE", src, dest, p.size))
                            touched.add(src.path)
                        } else skipped++
                    }
                    "MOVE", "RENAME" -> {
                        val dir = if (p.kind == "MOVE") File(root, p.toFolder!!) else src.parentFile!!
                        if (!dir.exists()) newFolders.add(p.toFolder ?: "")
                        val dest = unique(dir, p.newName ?: src.name)
                        if (move(src, dest)) {
                            if (p.kind == "MOVE") {
                                moved++
                                val top = p.toFolder!!.substringAfterLast('/')
                                perFolder[top] = (perFolder[top] ?: 0) + 1
                            } else renamed++
                            if (examples.size < 5) examples.add(src.name)
                            entries.put(entry(p.kind, src, dest, p.size))
                            touched.add(src.path); touched.add(dest.path)
                        } else skipped++
                    }
                }
            } catch (e: Exception) {
                skipped++
            }
        }
        emit(items.size, items.size)
        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(1)
        val session = JSONObject().put("id", sessionId).put("time", started).put("seconds", seconds)
            .put("moved", moved).put("renamed", renamed).put("deleted", deleted)
            .put("freed", freed).put("entries", entries).put("undone", false)
        File(journalDir, "$sessionId.json").writeText(session.toString())
        scan(touched)

        val top = perFolder.entries.sortedByDescending { it.value }.take(4)
        val topArr = JSONArray()
        top.forEach { topArr.put(JSONObject().put("name", it.key).put("count", it.value)) }
        val fmt = SimpleDateFormat("MMM d", Locale.ENGLISH)
        return JSONObject().put("sessionId", sessionId).put("done", moved + renamed + deleted)
            .put("attempted", items.size).put("seconds", seconds).put("moved", moved)
            .put("renamed", renamed).put("deleted", deleted).put("skipped", skipped)
            .put("freed", freed).put("newFolders", newFolders.size).put("top", topArr)
            .put("examples", JSONArray(examples))
            .put("until", fmt.format(Date(started + keepDays * 86_400_000)))
    }

    private fun entry(op: String, from: File, to: File, size: Long) =
        JSONObject().put("op", op).put("from", from.path).put("to", to.path).put("size", size)
            .put("restored", false).put("purged", false)

    private fun load(id: Long): JSONObject? = try { JSONObject(File(journalDir, "$id.json").readText()) } catch (e: Exception) { null }
    private fun save(s: JSONObject) = File(journalDir, "${s.getLong("id")}.json").writeText(s.toString())

    private fun all(): List<JSONObject> =
        (journalDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            try { JSONObject(f.readText()) } catch (e: Exception) { null }
        }.sortedByDescending { it.getLong("time") }

    /** Reverses a session. Returns how many files went back. */
    fun undo(id: Long, deletesOnly: Boolean = false): Int {
        val s = load(id) ?: return 0
        val entries = s.getJSONArray("entries")
        val touched = ArrayList<String>()
        var n = 0
        for (i in entries.length() - 1 downTo 0) {
            val e = entries.getJSONObject(i)
            if (e.optBoolean("restored") || e.optBoolean("purged")) continue
            if (deletesOnly && e.getString("op") != "DELETE") continue
            val cur = File(e.getString("to"))
            if (!cur.exists()) continue
            val orig = File(e.getString("from"))
            val dest = unique(orig.parentFile ?: root, orig.name)
            if (move(cur, dest)) {
                e.put("restored", true); n++
                touched.add(cur.path); touched.add(dest.path)
            }
        }
        var left = false
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (!e.optBoolean("restored") && !e.optBoolean("purged")) left = true
        }
        if (!left) s.put("undone", true)
        save(s)
        scan(touched)
        return n
    }

    fun sessions(): JSONArray {
        val out = JSONArray()
        for (s in all()) {
            val entries = s.getJSONArray("entries")
            var inTrash = 0; var trashBytes = 0L; var active = 0
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val gone = e.optBoolean("restored") || e.optBoolean("purged")
                if (!gone) active++
                if (e.getString("op") == "DELETE" && !gone) { inTrash++; trashBytes += e.optLong("size") }
            }
            out.put(
                JSONObject().put("id", s.getLong("id")).put("time", s.getLong("time"))
                    .put("moved", s.optInt("moved")).put("renamed", s.optInt("renamed"))
                    .put("deleted", s.optInt("deleted")).put("undone", s.optBoolean("undone"))
                    .put("active", active).put("inTrash", inTrash).put("trashBytes", trashBytes)
            )
        }
        return out
    }

    fun trashInfo(): JSONObject {
        var count = 0; var bytes = 0L; var oldest = Long.MAX_VALUE
        for (s in all()) {
            val entries = s.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.getString("op") == "DELETE" && !e.optBoolean("restored") && !e.optBoolean("purged")) {
                    count++; bytes += e.optLong("size"); oldest = minOf(oldest, s.getLong("time"))
                }
            }
        }
        return JSONObject().put("count", count).put("bytes", bytes)
            .put("until", if (count == 0) JSONObject.NULL else oldest + keepDays * 86_400_000)
    }

    fun emptyTrash() { purge(Long.MAX_VALUE) }

    /** Permanently deletes trash older than 30 days. */
    fun purgeOld() { purge(System.currentTimeMillis() - keepDays * 86_400_000) }

    private fun purge(olderThan: Long) {
        for (s in all()) {
            if (s.getLong("time") >= olderThan && olderThan != Long.MAX_VALUE) continue
            val entries = s.getJSONArray("entries")
            var changed = false
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.getString("op") == "DELETE" && !e.optBoolean("restored") && !e.optBoolean("purged")) {
                    File(e.getString("to")).delete()
                    e.put("purged", true); changed = true
                }
            }
            if (changed) {
                save(s)
                File(trashRoot, s.getLong("id").toString()).delete()
            }
        }
    }
}
