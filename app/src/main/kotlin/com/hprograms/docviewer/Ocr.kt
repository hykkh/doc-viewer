package com.hprograms.docviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Text recognition for scanned PDFs (pages that are pictures only), on the phone
 * with ML Kit's bundled Korean model (reads Latin too) — no network.
 *
 * Each page becomes lines of { t: text, x, y, w, h } in page fractions (0..1),
 * which viewer.js lays over the page as an invisible text layer so find,
 * select/copy, read-aloud and the content index work. Results are kept in
 * files/ocr/<id>.json so a document is read only once.
 */
object Ocr {
    private const val TARGET_W = 2000 // px across the page: small print stays readable

    fun cacheFile(ctx: Context, id: String) = File(File(ctx.filesDir, "ocr").apply { mkdirs() }, id.substringBefore('.') + ".json")

    fun cached(ctx: Context, id: String): JSONObject? =
        cacheFile(ctx, id).takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /**
     * Reads every page of [pdf], calling [onPage] with each page's lines as soon as it is done,
     * and saves the result for [id]. Runs on the caller's (background) dispatcher; cancellable
     * between pages. A page that fails is left empty.
     */
    suspend fun run(ctx: Context, pdf: File, id: String, onPage: (Int, JSONArray) -> Unit) {
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val all = JSONObject()
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        coroutineContext.ensureActive()
                        val lines = runCatching { readPage(renderer, i, recognizer) }.getOrElse { JSONArray() }
                        all.put(i.toString(), lines)
                        onPage(i, lines)
                    }
                }
            }
            cacheFile(ctx, id).writeText(all.toString())
        } finally {
            recognizer.close()
        }
    }

    private fun readPage(renderer: PdfRenderer, i: Int, recognizer: com.google.mlkit.vision.text.TextRecognizer): JSONArray {
        val bmp = renderer.openPage(i).use { page ->
            val w = TARGET_W
            val h = (TARGET_W.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 4 * TARGET_W)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE) // scans render on transparent otherwise
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val out = JSONArray()
            val w = bmp.width.toDouble()
            val h = bmp.height.toDouble()
            for (block in text.textBlocks) for (line in block.lines) {
                val b = line.boundingBox ?: continue
                if (line.text.isBlank()) continue
                out.put(JSONObject()
                    .put("t", line.text)
                    .put("x", b.left / w).put("y", b.top / h)
                    .put("w", b.width() / w).put("h", b.height() / h))
            }
            return out
        } finally {
            bmp.recycle()
        }
    }
}
