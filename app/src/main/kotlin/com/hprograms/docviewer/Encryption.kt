package com.hprograms.docviewer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet

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
        Cfb.open(file)?.use { cfb ->
            val names = cfb.streamNames()
            when {
                // Encrypted docx/xlsx/pptx: an OLE wrapper around EncryptedPackage.
                "EncryptionInfo" in names || "EncryptedPackage" in names -> true
                // PowerPoint 97-2003
                "EncryptedSummary" in names -> true
                "Current User" in names && pptCurrentUserEncrypted(cfb.read("Current User", 64)) -> true
                // Word 97-2003: FIB flag fEncrypted
                "WordDocument" in names -> wordEncrypted(cfb.read("WordDocument", 64))
                // Excel 97-2003: FILEPASS record in the workbook globals
                "Workbook" in names -> excelEncrypted(cfb.read("Workbook", 64 * 1024))
                "Book" in names -> excelEncrypted(cfb.read("Book", 64 * 1024))
                else -> false
            }
        } ?: false
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

    /**
     * Minimal read-only reader for the OLE Compound File Binary format ([MS-CFB]).
     * Reads sectors from disk on demand; every chain walk is bounded by the file's
     * sector count and stops on a repeated sector, so malformed files cannot loop
     * or blow up memory.
     */
    private class Cfb(private val raf: RandomAccessFile) : AutoCloseable {
        private val header = ByteArray(512).also { raf.seek(0); raf.readFully(it) }
        private val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorShift = hb.getShort(0x1E).toInt()
        private val sectorSize = 1 shl sectorShift.coerceIn(9, 12)
        private val miniSectorSize = 1 shl hb.getShort(0x20).toInt().coerceIn(6, 9)
        private val miniCutoff = hb.getInt(0x38)
        private val sectorCount = ((raf.length() - sectorSize) / sectorSize).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        private val fat: IntArray = readFat()
        private val entries = readDirectory()
        private val miniFat by lazy { ints(chain(hb.getInt(0x3C), MAX_TABLE_BYTES)) }
        private val miniStream by lazy { entries.firstOrNull()?.let { chain(it.start, MAX_MINI_STREAM) } ?: ByteArray(0) }

        class Entry(val name: String, val type: Int, val start: Int, val size: Long)

        override fun close() = raf.close()

        fun streamNames() = entries.filter { it.type == 2 }.map { it.name }.toSet()

        fun read(name: String, limit: Int): ByteArray? {
            val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
            val want = minOf(e.size, limit.toLong()).toInt()
            return if (e.size < miniCutoff) {
                val out = ByteArrayOutputStream()
                val seen = BitSet()
                var s = e.start
                while (s >= 0 && out.size() < want && !seen[s]) {
                    seen.set(s)
                    val off = s * miniSectorSize
                    if (off + miniSectorSize > miniStream.size) break
                    out.write(miniStream, off, miniSectorSize)
                    s = miniFat.getOrElse(s) { -2 }
                }
                out.toByteArray().copyOf(minOf(want, out.size()))
            } else {
                chain(e.start, want)
            }
        }

        private fun sector(s: Int): ByteArray? {
            if (s < 0 || s >= sectorCount) return null
            val b = ByteArray(sectorSize)
            raf.seek((s + 1).toLong() * sectorSize)
            raf.readFully(b)
            return b
        }

        private fun readFat(): IntArray {
            val difat = ArrayList<Int>()
            for (i in 0 until 109) difat.add(hb.getInt(0x4C + i * 4))
            var next = hb.getInt(0x44)
            val seen = BitSet()
            while (next >= 0 && !seen[next]) {
                seen.set(next)
                val b = sector(next) ?: break
                val w = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sectorSize / 4 - 1) difat.add(w.getInt(i * 4))
                next = w.getInt(sectorSize - 4)
            }
            // The FAT never needs more entries than the file has sectors.
            val out = IntArray(sectorCount) { -1 }
            var n = 0
            for (s in difat) {
                if (n >= out.size) break
                val b = sector(s) ?: continue
                val w = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sectorSize / 4) {
                    if (n >= out.size) break
                    out[n++] = w.getInt(i * 4)
                }
            }
            return out
        }

        private fun chain(start: Int, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val seen = BitSet()
            var s = start
            while (s >= 0 && out.size() < limit && !seen[s]) {
                seen.set(s)
                out.write(sector(s) ?: break)
                s = fat.getOrElse(s) { -2 }
            }
            return out.toByteArray()
        }

        private fun ints(b: ByteArray): IntArray {
            val w = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(b.size / 4) { w.getInt(it * 4) }
        }

        private fun readDirectory(): List<Entry> {
            val dir = chain(hb.getInt(0x30), MAX_TABLE_BYTES)
            val w = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until dir.size / 128).map { i ->
                val o = i * 128
                val nameLen = (w.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
                val name = String(dir, o, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
                Entry(name, dir[o + 0x42].toInt(), w.getInt(o + 0x74), w.getLong(o + 0x78))
            }
        }

        companion object {
            private const val MAX_TABLE_BYTES = 4 shl 20 // directory / mini FAT
            private const val MAX_MINI_STREAM = 8 shl 20
            private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

            fun open(file: File): Cfb? {
                if (file.length() < 1024) return null
                val raf = RandomAccessFile(file, "r")
                val head = ByteArray(8)
                raf.readFully(head)
                if (!head.contentEquals(MAGIC)) {
                    raf.close()
                    return null
                }
                return runCatching { Cfb(raf) }.getOrElse { raf.close(); null }
            }
        }
    }
}
