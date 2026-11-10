package com.hprograms.docviewer

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * LibreOffice's one-page-per-sheet PDF export starts each sheet where its view
 * was scrolled to when the file was saved: everything above and left of that
 * was left out. Here an .ods copy gets every sheet's scroll position back to
 * the top-left (settings.xml); frozen rows and columns stay frozen.
 */
object SheetView {
    /**
     * Whether [f] was saved with some sheet scrolled away from the top-left. Read
     * straight from the file (xlsx sheet XML, xls WINDOW2/PANE records); anything
     * it cannot read (encrypted, other formats, damaged) counts as yes.
     */
    fun scrolled(f: File): Boolean = runCatching {
        val head = f.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
        when {
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> xlsxScrolled(f)
            head.copyOf(4).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) -> xlsScrolled(f)
            else -> true
        }
    }.getOrDefault(true)

    private fun xlsxScrolled(f: File): Boolean {
        java.util.zip.ZipFile(f).use { z ->
            val sheets = z.entries().toList().filter { Regex("""xl/worksheets/[^/]+\.xml""").matches(it.name) }
            if (sheets.isEmpty()) return true // not a workbook (ods etc.)
            for (e in sheets) {
                // <sheetViews> comes before the cell data: the head of the part is enough.
                val text = z.getInputStream(e).use { s ->
                    val buf = ByteArray(16 * 1024)
                    var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    String(buf, 0, n, Charsets.UTF_8)
                }
                val views = text.substringBefore("<sheetData").substringBefore("<cols")
                val view = Regex("""<sheetView\b[^>]*>""").find(views)?.value ?: continue
                attr(view, "topLeftCell")?.let { if (cell(it) != 0 to 0) return true }
                val pane = Regex("""<pane\b[^>]*>""").find(views)?.value ?: continue
                val tl = attr(pane, "topLeftCell") ?: continue
                if (attr(pane, "state") !in setOf("frozen", "frozenSplit")) return true
                val xs = attr(pane, "xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                val ys = attr(pane, "ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                val (c, r) = cell(tl)
                if (c > xs || r > ys) return true
            }
        }
        return false
    }

    private fun attr(tag: String, n: String) = Regex("""\s$n="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** "C12" → (2, 11), zero-based column and row. */
    private fun cell(ref: String): Pair<Int, Int> {
        val m = Regex("""\$?([A-Za-z]+)\$?(\d+)""").find(ref) ?: return 0 to 0
        val col = m.groupValues[1].uppercase().fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1
        return col to (m.groupValues[2].toInt() - 1)
    }

    /** Binary Excel: the Workbook stream's WINDOW2 (top row / left column) and PANE records. */
    private fun xlsScrolled(f: File): Boolean {
        if (f.length() > 64L shl 20) return true
        val b = f.readBytes()
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        val shift = u16(0x1E)
        val size = 1 shl shift
        fun sec(i: Long) = ((i + 1) shl shift).toInt()
        // FAT sectors: 109 in the header, the rest in the DIFAT chain.
        val fatSecs = ArrayList<Long>()
        for (k in 0 until 109) { val s = u32(0x4C + 4 * k); if (s < 0xFFFFFFFAL) fatSecs.add(s) }
        var difat = u32(0x44)
        var guard = 0
        while (difat < 0xFFFFFFFAL && guard++ < 10_000) {
            val o = sec(difat)
            for (k in 0 until size / 4 - 1) { val s = u32(o + 4 * k); if (s < 0xFFFFFFFAL) fatSecs.add(s) }
            difat = u32(o + size - 4)
        }
        fun fat(i: Long): Long { val per = size / 4; return u32(sec(fatSecs[(i / per).toInt()]) + 4 * (i % per).toInt()) }
        fun chain(start: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var n = 0
            while (s < 0xFFFFFFFAL && n++ < 1_000_000) { out.write(b, sec(s), size); s = fat(s) }
            return out.toByteArray()
        }
        val dir = chain(u32(0x30))
        var root = -1L
        var start = -1L
        var len = 0L
        for (o in 0 until dir.size / 128) {
            val e = o * 128
            fun d16(p: Int) = (dir[e + p].toInt() and 0xFF) or ((dir[e + p + 1].toInt() and 0xFF) shl 8)
            fun d32(p: Int) = d16(p).toLong() or (d16(p + 2).toLong() shl 16)
            val nameLen = d16(0x40)
            if (nameLen < 2 || nameLen > 64) continue
            val name = String(dir, e, nameLen - 2, Charsets.UTF_16LE)
            when (dir[e + 0x42].toInt()) {
                5 -> root = d32(0x74)
                2 -> if (name == "Workbook" || name == "Book") { start = d32(0x74); len = d32(0x78) }
            }
        }
        if (start < 0) return true
        val stream = if (len >= u32(0x38)) chain(start) else {
            // Small stream: 64-byte sectors inside the root's mini stream.
            val mini = chain(root)
            val miniFat = chain(u32(0x3C))
            fun mf(i: Long) = (miniFat[(4 * i).toInt()].toLong() and 0xFF) or ((miniFat[(4 * i + 1).toInt()].toLong() and 0xFF) shl 8) or
                ((miniFat[(4 * i + 2).toInt()].toLong() and 0xFF) shl 16) or ((miniFat[(4 * i + 3).toInt()].toLong() and 0xFF) shl 24)
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var n = 0
            while (s < 0xFFFFFFFAL && n++ < 100_000) { out.write(mini, (s * 64).toInt(), 64); s = mf(s) }
            out.toByteArray()
        }
        val end = minOf(len.toInt(), stream.size)
        fun s16(o: Int) = (stream[o].toInt() and 0xFF) or ((stream[o + 1].toInt() and 0xFF) shl 8)
        var p = 0
        var frozen = false
        while (p + 4 <= end) {
            val type = s16(p)
            val rl = s16(p + 2)
            val d = p + 4
            if (d + rl > end) break
            when (type) {
                0x002F -> return true // FILEPASS: encrypted, cannot read on
                0x023E -> if (rl >= 6) {
                    frozen = s16(d) and 0x0008 != 0
                    if (s16(d + 2) != 0 || s16(d + 4) != 0) return true
                }
                0x0041 -> if (rl >= 8) {
                    val x = s16(d); val y = s16(d + 2); val top = s16(d + 4); val left = s16(d + 6)
                    if (!frozen) { if (top != 0 || left != 0) return true }
                    else if (top > y || left > x) return true
                }
            }
            p = d + rl
        }
        return false
    }

    private val entry = Regex("""<config:config-item-map-entry config:name="[^"]*">[\s\S]*?</config:config-item-map-entry>""")

    fun scrollToTop(ods: File) {
        val out = File(ods.path + ".view")
        ZipInputStream(ods.inputStream().buffered()).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var bytes = zin.readBytes()
                    if (e.name == "settings.xml") bytes = patch(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    val ze = ZipEntry(e.name)
                    if (e.name == "mimetype") {
                        // Must stay first and uncompressed.
                        ze.method = ZipEntry.STORED
                        ze.size = bytes.size.toLong()
                        ze.crc = CRC32().apply { update(bytes) }.value
                    }
                    zout.putNextEntry(ze)
                    zout.write(bytes)
                    zout.closeEntry()
                }
            }
        }
        if (!out.renameTo(ods)) { ods.delete(); out.renameTo(ods) }
    }

    internal fun patch(xml: String): String {
        val tables = xml.indexOf("""config:name="Tables"""")
        if (tables < 0) return xml
        val head = xml.substring(0, tables)
        val body = entry.replace(xml.substring(tables)) { m ->
            var s = m.value
            fun item(n: String) = Regex("""<config:config-item config:name="$n" config:type="\w+">(-?\d+)</config:config-item>""")
            fun value(n: String) = item(n).find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            fun set(n: String, v: Int) {
                s = item(n).replace(s) { it.value.replace(">${it.groupValues[1]}<", ">$v<") }
            }
            // Split mode 2 = frozen: the second pane starts right after the frozen columns/rows.
            val frozenCols = if (value("HorizontalSplitMode") == 2) value("HorizontalSplitPosition") else 0
            val frozenRows = if (value("VerticalSplitMode") == 2) value("VerticalSplitPosition") else 0
            set("PositionLeft", 0)
            set("PositionRight", frozenCols)
            set("PositionTop", 0)
            set("PositionBottom", frozenRows)
            s
        }
        return head + body
    }
}
