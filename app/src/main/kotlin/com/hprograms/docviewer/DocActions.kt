package com.hprograms.docviewer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

/** Copies [file] into Download/Hoffice under [name]; returns where it went (for the toast). */
fun saveToDownloads(ctx: Context, file: File, name: String, mime: String): String {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hoffice")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("저장 위치를 만들지 못했습니다")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hoffice").apply { mkdirs() }
        var out = File(dir, name)
        var n = 1
        while (out.exists()) out = File(dir, name.substringBeforeLast('.', name) + " ($n)." + name.substringAfterLast('.', "")).also { n++ }
        file.copyTo(out)
    }
    return "다운로드/Hoffice"
}

/** Sends a finished PDF file to the system print dialog. */
fun printPdfFile(ctx: Context, file: File, jobName: String) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(jobName, object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
            try {
                file.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(destination).use { input.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                callback.onWriteFailed(t.message)
            }
        }
    }, null)
}

/**
 * Entries of a plain ZIP attachment. Names written by Windows "send to
 * compressed folder" are CP949 without the UTF-8 flag; macOS and 7-Zip
 * write UTF-8.
 */
object ZipView {
    data class Item(val name: String, val size: Long)

    /** CP949; the alias Android knows varies, and an unknown name throws. */
    val korean: Charset by lazy {
        listOf("MS949", "x-windows-949", "windows-949", "EUC-KR")
            .firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    // Android decodes every name with the charset given, even UTF-8-flagged ones:
    // CP949 throws on UTF-8 names, and UTF-8 turns CP949 names into U+FFFD.
    // So read as UTF-8 (macOS, 7-Zip) and fall back to CP949 (Windows).
    fun open(file: File): ZipFile {
        val z = ZipFile(file, Charsets.UTF_8)
        if (z.entries().asSequence().none { '\uFFFD' in it.name }) return z
        z.close()
        return ZipFile(file, korean)
    }

    fun list(file: File): List<Item> = open(file).use { z ->
        z.entries().asSequence()
            .filter { !it.isDirectory && !it.name.substringAfterLast('/').startsWith(".") && !it.name.startsWith("__MACOSX/") }
            .map { Item(it.name, it.size) }
            .toList()
    }

    /** Extracts one entry into [dir] and returns the file (names flattened). */
    fun extract(file: File, entryName: String, dir: File): File {
        dir.mkdirs()
        open(file).use { z ->
            val e = z.getEntry(entryName) ?: throw IllegalStateException("압축 안에서 파일을 찾지 못했습니다")
            if (e.size > 1L shl 30) throw IllegalStateException("파일이 너무 큽니다")
            val out = File(dir, entryName.substringAfterLast('/').ifBlank { "file" })
            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            return out
        }
    }
}

/** Reads text aloud page by page; asks for the next page when one is done. */
class ReadAloud(ctx: Context, private val onNeedPage: (Int) -> Unit, private val onFinished: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<Int, String>? = null
    var speaking = false
        private set
    private var page = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.KOREAN
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "end-$page" && speaking) onNeedPage(page + 1)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (speaking) onNeedPage(page + 1)
                    }
                })
                pending?.let { (p, t) -> speak(p, t) }
            } else {
                onFinished()
            }
        }
    }

    fun start(fromPage: Int) {
        speaking = true
        onNeedPage(fromPage)
    }

    /** Text of [p] arrived (p < 0: no more pages). */
    fun speak(p: Int, text: String) {
        if (!speaking) return
        if (p < 0) {
            stop()
            onFinished()
            return
        }
        if (!ready) {
            pending = p to text
            return
        }
        page = p
        val t = tts ?: return
        val chunks = text.replace(Regex("[ \\t]+"), " ").split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { it.chunked(minOf(TextToSpeech.getMaxSpeechInputLength() - 1, 1000)) }
        if (chunks.isEmpty()) {
            onNeedPage(p + 1)
            return
        }
        chunks.forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) "end-$p" else "c-$p-$i")
        }
    }

    fun stop() {
        speaking = false
        tts?.stop()
    }

    fun shutdown() {
        speaking = false
        tts?.shutdown()
        tts = null
    }
}
