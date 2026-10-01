package com.hprograms.docviewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A document the app has opened; the bytes live in [Library.docsDir] under [id]. */
data class DocEntry(val id: String, val name: String, val kind: DocKind, val size: Long, val openedAt: Long) {
    fun file(ctx: Context) = File(Library.docsDir(ctx), id)
}

/**
 * Local copies of opened documents plus the recent-files list.
 *
 * Attachments from KakaoTalk, mail etc. come with a read grant that expires,
 * so every opened file is copied in; the recent list then always works.
 */
object Library {
    private const val PREFS = "library"
    private const val KEY = "recent"
    private const val MAX_RECENT = 50
    private const val MAX_BYTES = 1L shl 30 // 1GB of kept copies
    private const val MAX_IMPORT = 1L shl 30 // refuse single files above 1GB

    // Copies an open viewer is showing; trimming must not delete them.
    private val inUse = HashMap<String, Int>()

    fun docsDir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    fun pdfDir(ctx: Context) = File(ctx.cacheDir, "pdf").apply { mkdirs() }

    fun textDir(ctx: Context) = File(ctx.filesDir, "text").apply { mkdirs() }
    private fun textFile(ctx: Context, id: String) = File(textDir(ctx), id.substringBefore('.') + ".txt")

    /** The document's text, gathered by the viewer, for finding it by content later. */
    fun saveText(ctx: Context, id: String, text: String) {
        runCatching { textFile(ctx, id).writeText(text) }
    }

    /** Ids of kept documents whose text contains [q] (case-insensitive). */
    fun idsContaining(ctx: Context, q: String): Set<String> {
        val needle = q.lowercase()
        return recent(ctx).map { it.id }.distinct().filter { id ->
            val f = textFile(ctx, id)
            f.exists() && runCatching { f.readText().lowercase().contains(needle) }.getOrDefault(false)
        }.toSet()
    }

    // ---- favourites (kept regardless of the size budget) ----

    private const val KEY_FAV = "favorites"
    private fun favKey(e: DocEntry) = e.id + "|" + e.name

    @Synchronized
    fun favorites(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FAV, emptySet()) ?: emptySet()

    fun isFavorite(ctx: Context, e: DocEntry) = favKey(e) in favorites(ctx)

    @Synchronized
    fun setFavorite(ctx: Context, e: DocEntry, on: Boolean) {
        val set = favorites(ctx).toMutableSet()
        if (on) set.add(favKey(e)) else set.remove(favKey(e))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FAV, set).commit()
    }

    private fun favoriteIds(ctx: Context) = favorites(ctx).map { it.substringBefore('|') }.toSet()

    /** Where the converted PDF of an office document is cached. */
    fun pdfFor(ctx: Context, e: DocEntry) = File(pdfDir(ctx), e.id.substringBefore('.') + ".pdf")

    @Synchronized
    fun use(id: String, open: Boolean) {
        val n = (inUse[id] ?: 0) + if (open) 1 else -1
        if (n > 0) inUse[id] = n else inUse.remove(id)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "문서").name
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "문서"
    }

    /** Copies [uri] in, detects its kind and puts it at the top of the recent list. */
    fun import(ctx: Context, uri: Uri): DocEntry {
        val name = displayName(ctx, uri).ifBlank { "문서" }
        // A temp file per import, so two attachments opened at once cannot mix.
        val tmp = File.createTempFile("incoming", ".tmp", docsDir(ctx))
        try {
            val md = MessageDigest.getInstance("SHA-1")
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("파일을 열 수 없습니다")
            var size = 0L
            input.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val r = i.read(buf)
                        if (r < 0) break
                        size += r
                        if (size > MAX_IMPORT) throw IllegalStateException("파일이 너무 큽니다 (1GB 초과)")
                        md.update(buf, 0, r)
                        o.write(buf, 0, r)
                    }
                }
            }
            if (size == 0L) throw IllegalStateException("빈 파일입니다")
            val ext = DocKinds.extOf(name).takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            val id = if (ext != null) "$hash.$ext" else hash
            val dest = File(docsDir(ctx), id)
            synchronized(this) {
                // Claim it before it appears, so a concurrent import's trim cannot delete it.
                use(id, true)
                if (dest.exists() || !tmp.renameTo(dest)) tmp.delete()
            }
            // On success the claim passes to the caller, who releases it with use(id, false).
            try {
                val entry = DocEntry(id, name, DocKinds.detect(dest, name), size, System.currentTimeMillis())
                touch(ctx, entry)
                return entry
            } catch (t: Throwable) {
                use(id, false)
                throw t
            }
        } finally {
            tmp.delete()
        }
    }

    @Synchronized
    fun recent(ctx: Context): List<DocEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                DocEntry(o.getString("id"), o.getString("name"), DocKind.valueOf(o.getString("kind")), o.getLong("size"), o.getLong("at"))
            }.getOrNull()
        }.filter { it.file(ctx).exists() }
    }

    @Synchronized
    fun touch(ctx: Context, e: DocEntry) {
        val list = listOf(e.copy(openedAt = System.currentTimeMillis())) +
            recent(ctx).filter { it.id != e.id || it.name != e.name }
        val fav = favorites(ctx)
        // Keep the newest MAX_RECENT, plus any favourite that would fall off.
        save(ctx, list.filterIndexed { i, x -> i < MAX_RECENT || favKey(x) in fav })
        trim(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, e: DocEntry) {
        val rest = recent(ctx).filter { !(it.id == e.id && it.name == e.name) }
        save(ctx, rest)
        setFavorite(ctx, e, false)
        if (rest.none { it.id == e.id } && e.id !in inUse) {
            e.file(ctx).delete()
            pdfFor(ctx, e).delete()
            textFile(ctx, e.id).delete()
        }
    }

    private fun save(ctx: Context, list: List<DocEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name).put("size", it.size).put("at", it.openedAt))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }

    /**
     * Drops copies that fell off the recent list, then — file by file, oldest
     * first — copies beyond the size budget. The newest entry and anything an
     * open viewer is showing are never deleted.
     */
    private fun trim(ctx: Context) {
        var keep = recent(ctx)
        val keepIds = keep.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        docsDir(ctx).listFiles()?.forEach { f ->
            val id = f.name
            if (id.startsWith("incoming")) {
                // Left over by an import that was killed midway.
                if (now - f.lastModified() > 10 * 60_000L) f.delete()
            } else if (id !in keepIds && id !in inUse) {
                f.delete()
            }
        }
        // One size per file, whatever names point at it; newest use decides its age.
        val files = keep.groupBy { it.id }.map { (id, es) -> Triple(id, es.first().size, es.maxOf { it.openedAt }) }
        var total = files.sumOf { it.second }
        val newest = keep.firstOrNull()?.id
        val favIds = favoriteIds(ctx)
        for ((id, size, _) in files.sortedBy { it.third }) {
            if (total <= MAX_BYTES) break
            if (id == newest || id in inUse || id in favIds) continue
            File(docsDir(ctx), id).delete()
            File(pdfDir(ctx), id.substringBefore('.') + ".pdf").delete()
            textFile(ctx, id).delete()
            keep = keep.filter { it.id != id }
            total -= size
        }
        save(ctx, keep)
    }
}
