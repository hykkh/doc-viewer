package com.hprograms.docviewer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Long spreadsheets (too big for one PDF page per sheet) are printed with
 * every column on one page width and pages split only downwards — the way
 * Excel's "fit all columns on one page" prints. Otherwise A4 pages chop the
 * right-hand columns off onto pages of their own.
 *
 * LibreOfficeKit cannot change page styles, so the workbook is saved as xlsx,
 * its sheets' page setup rewritten here, and the copy loaded again.
 * The PDF's bookmarks (one per sheet) then give where each sheet starts.
 */
object SheetFit {
    private val sheetXml = Regex("""xl/worksheets/[^/]+\.xml""")

    /** Rewrites [xlsx] in place so every sheet prints one page wide. */
    fun fitColumnsToWidth(xlsx: File) {
        val out = File(xlsx.path + ".fit")
        ZipInputStream(xlsx.inputStream().buffered()).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var bytes = zin.readBytes()
                    if (sheetXml.matches(e.name)) bytes = fit(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    zout.putNextEntry(ZipEntry(e.name))
                    zout.write(bytes)
                    zout.closeEntry()
                }
            }
        }
        if (!out.renameTo(xlsx)) { xlsx.delete(); out.renameTo(xlsx) }
    }

    internal fun fit(xml: String): String {
        // Very wide sheets (dozens of columns, or formatting spread far right) would shrink to
        // unreadable dots on one page width: those keep the normal page-sized print.
        val width = usedWidthChars(xml)
        android.util.Log.i("SheetFit", "used width ${width.toInt()} chars, fit=${width <= MAX_FIT_CHARS}")
        if (width > MAX_FIT_CHARS) return xml
        var s = xml
        // Manual column breaks would still split the width.
        s = s.replace(Regex("""<colBreaks(\s[^>]*)?/>|<colBreaks[\s>][\s\S]*?</colBreaks>"""), "")
        // <sheetPr><pageSetUpPr fitToPage="1"/></sheetPr> (sheetPr is the first child, pageSetUpPr its last).
        val fitPr = """<pageSetUpPr fitToPage="1"/>"""
        val pageSetUpPr = Regex("""<pageSetUpPr(\s[^>]*)?/>""")
        val emptySheetPr = Regex("""<sheetPr(\s[^>]*)?/>""")
        s = when {
            pageSetUpPr.containsMatchIn(s) -> s.replace(pageSetUpPr, fitPr)
            emptySheetPr.containsMatchIn(s) -> emptySheetPr.first(s) { "<sheetPr${it.groupValues[1]}>$fitPr</sheetPr>" }
            s.contains("</sheetPr>") -> s.replaceFirst("</sheetPr>", "$fitPr</sheetPr>")
            else -> Regex("""<worksheet(\s[^>]*)?>""").first(s) { it.value + "<sheetPr>$fitPr</sheetPr>" }
        }
        // <pageSetup fitToWidth="1" fitToHeight="0"/>: one page wide, as many pages down as it takes.
        // Wider than a portrait page: landscape, so the print is not shrunk into a strip at the top.
        val fitSetup = """ fitToWidth="1" fitToHeight="0"""" + (if (width > PORTRAIT_CHARS) """ orientation="landscape"""" else "")
        val pageSetup = Regex("""<pageSetup(\s[^>]*)?/>""")
        val m = pageSetup.find(s)
        s = if (m != null) {
            val attrs = (m.groupValues[1]).replace(Regex("""\s(fitToWidth|fitToHeight|scale|orientation)="[^"]*""""), "")
            s.replaceRange(m.range, "<pageSetup$attrs$fitSetup/>")
        } else {
            Regex("""<pageMargins(\s[^>]*)?/>""").first(s) { it.value + """<pageSetup paperSize="9"$fitSetup/>""" }
        }
        return s
    }

    // A4 portrait inside the default margins holds about 100 standard character widths (≈19cm).
    private const val PORTRAIT_CHARS = 100.0
    // About six landscape pages wide: printed at about a sixth of full size, which the
    // viewer's 10x zoom (redrawn sharp at the zoomed size) still brings to readable print.
    private const val MAX_FIT_CHARS = 800.0

    /** Width of the used columns (the sheet's <dimension>) in character widths, as Excel measures columns. */
    private fun usedWidthChars(s: String): Double {
        val ref = Regex("""<dimension\s+ref="([^"]+)"""").find(s)?.groupValues?.get(1) ?: return 0.0
        val last = Regex("""([A-Z]+)\d*$""").find(ref.substringAfter(':'))?.groupValues?.get(1) ?: return 0.0
        val lastCol = last.fold(0) { n, c -> n * 26 + (c - 'A' + 1) }
        val default = Regex("""<sheetFormatPr[^>]*\sdefaultColWidth="([\d.]+)"""").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 9.14
        val widths = DoubleArray(lastCol + 1) { default }
        for (c in Regex("""<col\s[^>]*/>""").findAll(s)) {
            val t = c.value
            fun attr(n: String) = Regex("""\s$n="([^"]*)"""").find(t)?.groupValues?.get(1)
            val min = attr("min")?.toIntOrNull() ?: continue
            val max = attr("max")?.toIntOrNull() ?: min
            val hidden = attr("hidden").let { it == "1" || it == "true" }
            val w = if (hidden) 0.0 else attr("width")?.toDoubleOrNull() ?: default
            for (i in min..minOf(max, lastCol)) widths[i] = w
        }
        return (1..lastCol).sumOf { widths[it] }
    }

    private fun Regex.first(s: String, f: (MatchResult) -> String) = find(s)?.let { s.replaceRange(it.range, f(it)) } ?: s

    /** The PDF's top-level bookmarks as (title, page index), in page order. Empty when there are none. */
    fun bookmarks(pdf: File): List<Pair<String, Int>> {
        val t = String(pdf.readBytes(), Charsets.ISO_8859_1)
        // Pages in order: the /Kids of the page tree, else the page objects in file order.
        val kids = Regex("""/Type\s*/Pages\b[\s\S]{0,200}?/Kids\s*\[([^\]]*)]""").find(t)
            ?: Regex("""/Kids\s*\[([^\]]*)][\s\S]{0,200}?/Type\s*/Pages\b""").find(t)
        val pageIds = kids?.let { Regex("""(\d+)\s+0\s+R""").findAll(it.groupValues[1]).map { r -> r.groupValues[1] }.toList() }
            ?.takeIf { it.isNotEmpty() }
            ?: Regex("""(\d+)\s+0\s+obj\s*<<\s*/Type\s*/Page(?![s\w])""").findAll(t).map { it.groupValues[1] }.toList()
        val index = pageIds.withIndex().associate { (i, id) -> id to i }
        val item = Regex("""/Title\s*(<[0-9A-Fa-f\s]*>|\((?:\\.|[^\\)])*\))[\s\S]{0,600}?/D(?:est)?\s*\[\s*(\d+)\s+0\s+R""")
        return item.findAll(t).mapNotNull { r ->
            val page = index[r.groupValues[2]] ?: return@mapNotNull null
            decode(r.groupValues[1]) to page
        }.toList().sortedBy { it.second }
    }

    private fun decode(s: String): String {
        val bytes: ByteArray = if (s.startsWith("<")) {
            val hex = s.substring(1, s.length - 1).filter { !it.isWhitespace() }
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            val b = java.io.ByteArrayOutputStream()
            var i = 1
            val end = s.length - 1
            while (i < end) {
                val c = s[i]
                if (c == '\\' && i + 1 < end) {
                    val n = s[i + 1]
                    when {
                        n in '0'..'7' -> {
                            var j = i + 1
                            var v = 0
                            while (j < end && j < i + 4 && s[j] in '0'..'7') { v = v * 8 + (s[j] - '0'); j++ }
                            b.write(v); i = j; continue
                        }
                        n == 'n' -> b.write('\n'.code)
                        n == 'r' -> b.write('\r'.code)
                        n == 't' -> b.write('\t'.code)
                        else -> b.write(n.code)
                    }
                    i += 2
                } else { b.write(c.code); i++ }
            }
            b.toByteArray()
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else String(bytes, Charsets.ISO_8859_1)
    }
}
