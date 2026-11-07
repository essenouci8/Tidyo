package app.tidyo

import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class FileRec(val id: Int, val file: File, val rel: String, val size: Long, val mtime: Long) {
    val name: String get() = file.name
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val parentRel: String get() = rel.substringBeforeLast('/', "")
}

/** In-memory index of the phone's files plus the numbers the home screen shows. */
object Library {
    val root: File = Environment.getExternalStorageDirectory()
    @Volatile var files: List<FileRec> = emptyList()
    @Volatile var byId: Map<Int, FileRec> = emptyMap()
    @Volatile var dupGroups: List<List<FileRec>> = emptyList()

    private val badName = Regex(
        "^(img|vid|pxl|dsc|dscn|mvimg|pano|burst|photo|image|video|scan|screenshot|screen[ _]?shot|document|untitled|new|file|download|wa|signal|received|copy of)[-_ ]?[\\d(]|^\\d{6,}|^[0-9a-f]{8}-[0-9a-f]{4}-| \\(\\d+\\)\\.[a-z0-9]+$",
        RegexOption.IGNORE_CASE
    )
    private val chatWords = listOf("whatsapp", "telegram", "signal", "messenger", "viber")

    fun relOf(f: File): String = f.path.removePrefix(root.path).trimStart('/')
    fun isBadName(r: FileRec) = badName.containsMatchIn(r.name)
    fun isScreenshot(r: FileRec) = r.rel.contains("screenshot", true) || r.rel.contains("screen shot", true)
    fun isChat(r: FileRec) = chatWords.any { r.rel.contains(it, true) }
    fun isUnsorted(r: FileRec) = r.parentRel.lowercase() in setOf("", "download", "downloads", "documents")

    suspend fun scan(
        paused: () -> Boolean,
        emit: (String, JSONObject) -> Unit
    ): JSONObject = withContext(Dispatchers.IO) {
        suspend fun gate() {
            while (paused()) delay(150)
        }

        // 1. walk the tree and collect files
        val found = ArrayList<File>(8192)
        val stack = ArrayDeque<File>()
        stack.add(root)
        var lastEmit = 0L
        while (stack.isNotEmpty()) {
            gate()
            val dir = stack.removeLast()
            val list = dir.listFiles() ?: continue
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    val rel = relOf(f)
                    if (rel == "Android/data" || rel == "Android/obb") continue
                    stack.add(f)
                } else if (f.isFile) found.add(f)
            }
            val now = System.currentTimeMillis()
            if (now - lastEmit > 120) {
                lastEmit = now
                emit("scan", JSONObject().put("phase", "walk").put("n", found.size).put("name", found.lastOrNull()?.name ?: ""))
            }
        }

        // 2. read size and date
        val recs = ArrayList<FileRec>(found.size)
        for ((i, f) in found.withIndex()) {
            if (i % 200 == 0) {
                gate()
                emit("scan", JSONObject().put("phase", "read").put("n", i).put("total", found.size).put("name", f.name))
            }
            recs.add(FileRec(i, f, relOf(f), f.length(), f.lastModified()))
        }
        emit("scan", JSONObject().put("phase", "read").put("n", found.size).put("total", found.size))

        // 3. exact duplicates: same size, then same content
        val sizeGroups = recs.filter { it.size >= 1024 }.groupBy { it.size }.values.filter { it.size > 1 }
        val toHash = sizeGroups.sumOf { it.size }
        var hashed = 0
        val dups = ArrayList<List<FileRec>>()
        for (g in sizeGroups) {
            gate()
            val partial = HashMap<String, MutableList<FileRec>>()
            for (r in g) {
                val h = hash(r.file, 65536) ?: continue
                partial.getOrPut(h) { ArrayList() }.add(r)
                hashed++
            }
            for (pg in partial.values) {
                if (pg.size < 2) continue
                val full = HashMap<String, MutableList<FileRec>>()
                for (r in pg) {
                    if (r.size > 400L * 1024 * 1024) continue
                    val h = hash(r.file, Long.MAX_VALUE) ?: continue
                    full.getOrPut(h) { ArrayList() }.add(r)
                }
                full.values.map { grp -> grp.filter { !it.rel.startsWith("Android/") && !isChat(it) } }
                    .filter { it.size > 1 }.forEach { dups.add(it) }
            }
            emit("scan", JSONObject().put("phase", "dupes").put("n", hashed).put("total", toHash))
        }

        files = recs
        byId = recs.associateBy { it.id }
        dupGroups = dups
        stats()
    }

    private fun hash(f: File, limit: Long): String? = try {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(65536)
            var left = limit
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /** Picks which copy of a duplicate group to keep. */
    fun keeper(g: List<FileRec>): FileRec = g.minWith(
        compareBy<FileRec>(
            { if (Regex(" \\(\\d+\\)|copy", RegexOption.IGNORE_CASE).containsMatchIn(it.name)) 1 else 0 },
            { if (isUnsorted(it)) 1 else 0 },
            { it.name.length },
            { it.mtime }
        )
    )

    fun dupExtras(): List<FileRec> = dupGroups.flatMap { g -> val k = keeper(g); g.filter { it !== k } }

    fun stats(): JSONObject {
        val all = files
        val extras = dupExtras()
        val extraIds = extras.map { it.id }.toHashSet()
        val shots = all.filter { isScreenshot(it) }
        val chats = all.filter { isChat(it) }
        val unsorted = all.filter { isUnsorted(it) }
        val bad = all.filter { isBadName(it) }
        val messy = HashSet<Int>()
        extraIds.forEach { messy.add(it) }
        (shots + chats + unsorted + bad).forEach { messy.add(it.id) }
        val imgExt = setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
        val photoBytes = extras.filter { it.ext in imgExt }.sumOf { it.size }
        val dupBytes = extras.sumOf { it.size }
        return JSONObject()
            .put("total", all.size)
            .put("mess", if (all.isEmpty()) 0 else (100.0 * messy.size / all.size).toInt().coerceIn(0, 100))
            .put("dupCount", extras.size).put("dupBytes", dupBytes)
            .put("dupPhotos", dupBytes > 0 && photoBytes * 10 >= dupBytes * 6)
            .put("shotCount", shots.size).put("shotBytes", shots.sumOf { it.size })
            .put("chatCount", chats.size).put("chatBytes", chats.sumOf { it.size })
            .put("unsorted", unsorted.size)
            .put("badNames", bad.size)
    }

    fun folderList(limit: Int = 60): List<String> =
        files.map { it.parentRel }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(limit).map { it.key }

    fun search(q: String): List<FileRec> {
        val stop = setOf("the", "a", "an", "my", "that", "from", "of", "in", "file", "files", "document", "one", "for", "to")
        val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        var month = -1
        val tokens = ArrayList<String>()
        for (t in q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }) {
            val mi = months.indexOfFirst { it == t || (t.length == 3 && it.startsWith(t)) }
            if (mi >= 0) month = mi else tokens.add(t)
        }
        if (tokens.isEmpty() && month < 0) return emptyList()
        val cal = java.util.Calendar.getInstance()
        return files.asSequence().filter { r ->
            val low = r.rel.lowercase()
            tokens.all { low.contains(it) } && (month < 0 || run {
                cal.timeInMillis = r.mtime; cal.get(java.util.Calendar.MONTH) == month
            })
        }.sortedByDescending { it.mtime }.take(40).toList()
    }
}
