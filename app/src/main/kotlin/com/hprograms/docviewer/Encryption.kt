package com.hprograms.docviewer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tells whether an MS Office file is password-encrypted, without opening it
 * in LibreOffice.
 *
 * OfficeService loads documents with LibreOffice's "Batch" option so damaged
 * or unusual files cannot hang on an invisible dialog — but Batch also
 * silences the password prompt. Encrypted files are therefore routed to the
 * interactive load path, and this is how we recognise them.
 */
object Encryption {

    fun isEncrypted(file: File): Boolean = runCatching {
        val cfb = Cfb.open(file) ?: return false // not an OLE compound file (zip/ooxml etc. are never encrypted as zip)
        val names = cfb.streamNames()
        when {
            // Encrypted docx/xlsx/pptx: an OLE wrapper around EncryptedPackage.
            "EncryptionInfo" in names || "EncryptedPackage" in names -> true
            // PowerPoint 97-2003
            "EncryptedSummary" in names -> true
            "Current User" in names && pptCurrentUserEncrypted(cfb.read("Current User")) -> true
            // Word 97-2003: FIB flag fEncrypted
            "WordDocument" in names -> wordEncrypted(cfb.read("WordDocument"))
            // Excel 97-2003: FILEPASS record in the workbook globals
            "Workbook" in names -> excelEncrypted(cfb.read("Workbook"))
            "Book" in names -> excelEncrypted(cfb.read("Book"))
            else -> false
        }
    }.getOrDefault(false)

    private fun pptCurrentUserEncrypted(b: ByteArray?): Boolean {
        if (b == null || b.size < 16) return false
        // CurrentUserAtom.headerToken: 0xF3D1C4DF when the document is encrypted.
        val token = ByteBuffer.wrap(b, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return token == 0xF3D1C4DF.toInt()
    }

    private fun wordEncrypted(b: ByteArray?): Boolean {
        if (b == null || b.size < 12) return false
        val flags = (b[10].toInt() and 0xFF) or ((b[11].toInt() and 0xFF) shl 8)
        return flags and 0x0100 != 0
    }

    private fun excelEncrypted(b: ByteArray?): Boolean {
        if (b == null) return false
        var pos = 0
        // FILEPASS (0x002F) follows BOF within the first records of the globals substream.
        repeat(64) {
            if (pos + 4 > b.size) return false
            val type = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            val len = (b[pos + 2].toInt() and 0xFF) or ((b[pos + 3].toInt() and 0xFF) shl 8)
            if (type == 0x002F) return true
            if (type == 0x000A) return false // EOF of globals
            pos += 4 + len
        }
        return false
    }

    /** Minimal read-only reader for the OLE Compound File Binary format ([MS-CFB]). */
    private class Cfb(private val data: ByteArray) {
        private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize = 1 shl bb.getShort(0x1E).toInt()
        private val miniSectorSize = 1 shl bb.getShort(0x20).toInt()
        private val miniCutoff = bb.getInt(0x38)
        private val fat = readFat()
        private val entries = readDirectory()
        private val miniFat by lazy { chainBytes(bb.getInt(0x3C)).let { ints(it) } }
        private val miniStream by lazy { entries.firstOrNull()?.let { chainBytes(it.start) } ?: ByteArray(0) }

        class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        fun streamNames() = entries.filter { it.type == 2 }.map { it.name }.toSet()

        fun read(name: String): ByteArray? {
            val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
            val limit = minOf(e.size, 1L shl 20).toInt() // headers only; never need more than 1MB
            return if (e.size < miniCutoff) {
                val out = java.io.ByteArrayOutputStream()
                var s = e.start
                var guard = 0
                while (s >= 0 && out.size() < limit && guard++ < 100_000) {
                    val off = s * miniSectorSize
                    if (off + miniSectorSize > miniStream.size) break
                    out.write(miniStream, off, miniSectorSize)
                    s = miniFat.getOrElse(s) { -2 }
                }
                out.toByteArray().copyOf(minOf(limit, out.size()))
            } else {
                chainBytes(e.start, limit)
            }
        }

        private fun sectorOffset(s: Int) = (s + 1).toLong() * sectorSize

        private fun readFat(): IntArray {
            val difat = ArrayList<Int>()
            for (i in 0 until 109) difat.add(bb.getInt(0x4C + i * 4))
            var next = bb.getInt(0x44)
            var guard = 0
            while (next >= 0 && guard++ < 10_000) {
                val off = sectorOffset(next).toInt()
                if (off + sectorSize > data.size) break
                for (i in 0 until sectorSize / 4 - 1) difat.add(bb.getInt(off + i * 4))
                next = bb.getInt(off + sectorSize - 4)
            }
            val out = ArrayList<Int>()
            for (s in difat) {
                if (s < 0) continue
                val off = sectorOffset(s).toInt()
                if (off + sectorSize > data.size) continue
                for (i in 0 until sectorSize / 4) out.add(bb.getInt(off + i * 4))
            }
            return out.toIntArray()
        }

        private fun chainBytes(start: Int, limit: Int = Int.MAX_VALUE): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && out.size() < limit && guard++ < 1_000_000) {
                val off = sectorOffset(s).toInt()
                if (off + sectorSize > data.size) break
                out.write(data, off, sectorSize)
                s = fat.getOrElse(s) { -2 }
            }
            return out.toByteArray()
        }

        private fun ints(b: ByteArray): IntArray {
            val w = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(b.size / 4) { w.getInt(it * 4) }
        }

        private fun readDirectory(): List<Entry> {
            val dir = chainBytes(bb.getInt(0x30))
            val w = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until dir.size / 128).map { i ->
                val o = i * 128
                val nameLen = (w.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
                val name = String(dir, o, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
                Entry(name, dir[o + 0x42].toInt(), w.getInt(o + 0x74), w.getLong(o + 0x78))
            }
        }

        companion object {
            private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

            fun open(file: File): Cfb? {
                if (file.length() < 512 || file.length() > 200L * 1024 * 1024) return null
                val head = ByteArray(8)
                RandomAccessFile(file, "r").use { it.readFully(head) }
                if (!head.contentEquals(MAGIC)) return null
                return Cfb(file.readBytes())
            }
        }
    }
}
