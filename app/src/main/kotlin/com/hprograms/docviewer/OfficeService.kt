package com.hprograms.docviewer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.util.concurrent.Executors

/**
 * Converts office documents to PDF with LibreOfficeKit.
 *
 * Runs in its own process (":office", see the manifest): LibreOffice is one
 * big native library that can crash or hang on a broken file, and that must
 * not take the viewer down with it. The caller watches for a timeout and
 * kills this process if it stops answering.
 */
open class OfficeService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private var office: Office? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent?.getStringExtra(EXTRA_IN)
        val password = intent?.getStringExtra(EXTRA_UNLOCK)
        val output = intent?.getStringExtra(EXTRA_OUT)
        val receiver = intent?.getParcelableExtraCompat<ResultReceiver>(EXTRA_RECEIVER)
        if (intent?.getBooleanExtra(EXTRA_BATCH, false) == true) {
            val shard = intent.getIntExtra(EXTRA_SHARD, 0)
            val shards = intent.getIntExtra(EXTRA_SHARDS, 1)
            val pw = intent.getStringExtra(EXTRA_UNLOCK)
            val timeout = intent.getLongExtra(EXTRA_TIMEOUT_MS, BATCH_TIMEOUT_MS)
            worker.execute { runBatch(shard, shards, pw, timeout) }
            return START_NOT_STICKY
        }
        if (input == null || output == null) return START_NOT_STICKY

        worker.execute {
            val t0 = System.currentTimeMillis()
            val result = Bundle()
            var line: String
            try {
                convert(File(input), File(output), password)
                result.putLong(KEY_MS, System.currentTimeMillis() - t0)
                receiver?.send(RESULT_OK, result)
                line = "OK\t${System.currentTimeMillis() - t0}\t$input"
            } catch (e: Throwable) {
                Log.e(TAG, "convert failed: $input", e)
                result.putString(KEY_ERROR, e.message ?: e.toString())
                result.putBoolean(KEY_LOCKED, e is PasswordNeeded)
                receiver?.send(RESULT_FAIL, result)
                line = "FAIL\t${System.currentTimeMillis() - t0}\t$input\t${e.message}"
            }
            // Batch testing from adb (no receiver): append the outcome to a log.
            if (receiver == null) File(filesDir, "batch.log").appendText(line + "\n")
        }
        return START_NOT_STICKY
    }

    /**
     * Test harness (started from adb): converts every file listed in
     * files/batch/order.txt, appending "name<TAB>result<TAB>ms<TAB>detail" to
     * files/batch/result.tsv. A file that hangs is cut off after
     * BATCH_TIMEOUT_MS by killing this process; one that crashes it is found
     * via current.txt on the next start. adb just restarts us until done.
     */
    private fun runBatch(shard: Int, shards: Int, password: String?, timeoutMs: Long) {
        val dir = File(filesDir, "batch")
        val results = File(dir, "result.tsv")
        val current = File(dir, "current$shard.txt")
        val done = if (results.exists()) results.readLines().map { it.substringBefore('\t') }.toMutableSet() else mutableSetOf()
        if (current.exists()) {
            val parts = current.readText().split('\t')
            val name = parts[0]
            val started = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (name !in done) {
                val ms = System.currentTimeMillis() - started
                val what = if (ms >= timeoutMs) "TIMEOUT" else "CRASH"
                results.appendText("$name\t$what\t$ms\t\n")
                done.add(name)
            }
            current.delete()
        }
        val watchdog = Handler(Looper.getMainLooper())
        val order = File(dir, "order.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, name) in order.withIndex()) {
            if (index % shards != shard || name in done) continue
            val t0 = System.currentTimeMillis()
            current.writeText("$name\t$t0")
            val kill = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
            watchdog.postDelayed(kill, timeoutMs)
            val line = try {
                convert(File(dir, "in/$name"), File(dir, "out$shard.pdf"), password)
                "OK\t${System.currentTimeMillis() - t0}\t"
            } catch (e: Throwable) {
                "FAIL\t${System.currentTimeMillis() - t0}\t${e.message?.replace('\n', ' ')}"
            }
            watchdog.removeCallbacks(kill)
            results.appendText("$name\t$line\n")
            current.delete()
        }
        File(dir, "finished$shard").writeText("1")
    }

    private fun ensureOffice(): Office {
        office?.let { return it }
        unpackAssets(this)
        LibreOfficeKit.putenv("SAL_LOG=-WARN-INFO")
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(this)
        val o = Office(LibreOfficeKit.getLibreOfficeKitHandle())
        o.setOptionalFeatures(Document.LOK_FEATURE_DOCUMENT_PASSWORD)
        // LibreOffice asks for the password from inside documentLoad; answer
        // right there with the one we were given (null = cancel).
        o.setMessageCallback { type, _ ->
            if (type == Document.CALLBACK_DOCUMENT_PASSWORD || type == Document.CALLBACK_DOCUMENT_PASSWORD_TO_MODIFY) {
                // Answer with the URL we loaded: for binary .doc LibreOffice reports
                // only the file name, and answering to that aborts the process.
                // Give our password once; on a repeat ask (wrong password) cancel,
                // or LibreOffice keeps asking forever.
                val answer = if (type == Document.CALLBACK_DOCUMENT_PASSWORD && passwordAsks == 0) currentPassword else null
                if (type == Document.CALLBACK_DOCUMENT_PASSWORD) passwordAsks++
                o.setDocumentPassword(currentUrl, answer)
            }
        }
        office = o
        return o
    }

    @Volatile private var currentPassword: String? = null
    @Volatile private var passwordAsks = 0
    @Volatile private var currentUrl: String? = null

    private fun convert(input: File, output: File, password: String?) {
        val o = ensureOffice()
        output.delete()
        currentPassword = password
        passwordAsks = 0
        val url = input.toURI().toString()
        currentUrl = url
        // Batch=true makes LibreOffice answer its own dialogs instead of hanging
        // on them, but it also silences the password request — so encrypted
        // files take the interactive path, where our callback supplies it.
        val doc = if (Encryption.isEncrypted(input)) {
            o.documentLoad(url)
        } else {
            LokExtra.documentLoadWithOptions(LibreOfficeKit.getLibreOfficeKitHandle(), url, "Batch=true")?.let { Document(it) }
        }
        if (doc == null) {
            if (passwordAsks > 0) throw PasswordNeeded(if (password == null) "비밀번호가 걸린 문서입니다" else "비밀번호가 틀렸습니다")
            throw IllegalStateException("문서를 읽지 못했습니다 (${o.error?.takeIf { it.isNotBlank() } ?: "형식 인식 실패"})")
        }
        try {
            // Spreadsheets: one PDF page per sheet holding the whole sheet, so
            // wide tables are not chopped into print-sized strips — unless the
            // sheet is huge, where one giant page exhausts memory.
            val singlePage = doc.documentType == Document.DOCTYPE_SPREADSHEET &&
                doc.documentWidth * doc.documentHeight < MAX_SINGLE_PAGE_TWIPS2
            val options = if (singlePage) {
                """{"SinglePageSheets":{"type":"boolean","value":"true"}}"""
            } else {
                ""
            }
            doc.saveAs(output.toURI().toString(), "pdf", options)
        } finally {
            doc.destroy()
        }
        if (!output.exists() || output.length() == 0L) {
            throw IllegalStateException("PDF 변환 결과가 비어 있습니다")
        }
    }

    companion object {
        private const val TAG = "OfficeService"
        const val EXTRA_IN = "in"
        const val EXTRA_OUT = "out"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_UNLOCK = "doc_unlock"
        const val EXTRA_BATCH = "batch"
        const val EXTRA_SHARD = "shard"
        const val EXTRA_SHARDS = "shards"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        // About 40 A4 pages of area (A4 ≈ 11906 × 16838 twips).
        private const val MAX_SINGLE_PAGE_TWIPS2 = 40L * 11906 * 16838
        private const val BATCH_TIMEOUT_MS = 30_000L
        const val KEY_LOCKED = "doc_locked"
        const val RESULT_OK = 1
        const val RESULT_FAIL = 2
        const val KEY_ERROR = "error"
        const val KEY_MS = "ms"

        /** LibreOffice expects assets/unpack copied into the data dir; redo it once per app version. */
        private fun unpackAssets(ctx: Context) {
            val prefs = ctx.getSharedPreferences("lo", Context.MODE_PRIVATE)
            if (prefs.getInt("unpacked", -1) == BuildConfig.VERSION_CODE) return
            if (copyTree(ctx.assets, "unpack", File(ctx.applicationInfo.dataDir))) {
                prefs.edit().putInt("unpacked", BuildConfig.VERSION_CODE).apply()
            }
        }

        private fun copyTree(am: AssetManager, from: String, to: File): Boolean {
            val children = am.list(from) ?: return false
            var ok = true
            to.mkdirs()
            for (c in children) {
                val path = "$from/$c"
                val sub = am.list(path)
                if (sub.isNullOrEmpty()) {
                    ok = ok and runCatching {
                        am.open(path).use { i -> File(to, c).outputStream().use { i.copyTo(it) } }
                    }.isSuccess
                } else {
                    ok = ok and copyTree(am, path, File(to, c))
                }
            }
            return ok
        }
    }
}

/** Extra LibreOffice processes, used only to run batch tests in parallel. */
class OfficeService2 : OfficeService()
class OfficeService3 : OfficeService()

class PasswordNeeded(message: String) : Exception(message)

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)
