package com.hprograms.docviewer

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Which engine draws a file. */
enum class DocKind {
    PDF,    // pdf.js
    HWP,    // rhwp (HWP 5.0, HWP 3.x, HWPX, HWPML)
    OFFICE, // LibreOffice → PDF → pdf.js
    IMAGE,
    TEXT,
}

object DocKinds {
    private val officeExt = setOf(
        "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "odt", "ott", "fodt", "wps", "wpd", "pages", "lwp", "abw", "sxw",
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm", "ods", "ots", "fods", "numbers", "sxc", "et", "dbf", "slk",
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "otp", "fodp", "key", "sxi", "dps",
        "odg", "vsd", "vsdx", "pub", "xps", "emf", "wmf", "svg", "cdr", "csv",
    )
    private val hwpExt = setOf("hwp", "hwpx", "hwt", "hwtx", "hml")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico")
    private val textExt = setOf("txt", "log", "md", "json", "xml", "ini", "cfg", "conf", "yaml", "yml", "java", "kt", "py", "js", "c", "cpp", "h", "sh", "bat", "html", "htm", "srt", "smi")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Sniff the content first (attachments often arrive with a wrong or missing name), then fall back to the extension. */
    fun detect(file: File, name: String): DocKind {
        val ext = extOf(name)
        val head = ByteArray(32)
        val n = RandomAccessFile(file, "r").use { it.read(head) }
        fun startsWith(s: String) = n >= s.length && String(head, 0, s.length, Charsets.ISO_8859_1) == s

        if (startsWith("%PDF")) return DocKind.PDF
        if (startsWith("HWP Document File")) return DocKind.HWP // HWP 3.x
        if (n >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) {
            // OLE compound file: HWP 5.0, or doc/xls/ppt/…
            return if (oleIsHwp(file) || ext in hwpExt) DocKind.HWP else DocKind.OFFICE
        }
        if (startsWith("PK")) {
            return if (zipIsHwpx(file) || ext == "hwpx" || ext == "hwtx") DocKind.HWP else DocKind.OFFICE
        }
        if (startsWith("<?xml") && ext == "hml") return DocKind.HWP
        if (ext in imageExt || isImageMagic(head, n)) return DocKind.IMAGE
        if (ext in hwpExt) return DocKind.HWP
        if (ext in textExt) return DocKind.TEXT
        if (ext in officeExt) return DocKind.OFFICE
        // Unknown: LibreOffice has the widest type detection.
        return DocKind.OFFICE
    }

    private fun isImageMagic(h: ByteArray, n: Int): Boolean {
        if (n < 12) return false
        val b = h.map { it.toInt() and 0xFF }
        return (b[0] == 0xFF && b[1] == 0xD8) ||                              // jpeg
            (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) || // png
            (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) ||                 // gif
            (b[0] == 0x52 && b[1] == 0x49 && b[8] == 0x57 && b[9] == 0x45) || // webp
            (b[0] == 0x42 && b[1] == 0x4D)                                    // bmp
    }

    // The HWP 5.0 FileHeader stream begins with this signature; looking for it
    // in the raw compound file is enough to tell HWP from MS Office binaries.
    private fun oleIsHwp(file: File): Boolean {
        val sig = "HWP Document File".toByteArray(Charsets.ISO_8859_1)
        val limit = minOf(file.length(), 4L * 1024 * 1024).toInt()
        val buf = ByteArray(limit)
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        outer@ for (i in 0..buf.size - sig.size) {
            for (j in sig.indices) if (buf[i + j] != sig[j]) continue@outer
            return true
        }
        return false
    }

    private fun zipIsHwpx(file: File): Boolean = try {
        ZipFile(file).use { z ->
            val m = z.getEntry("mimetype") ?: return@use z.getEntry("Contents/header.xml") != null
            z.getInputStream(m).use { String(it.readBytes()).contains("hwp", ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }
}
