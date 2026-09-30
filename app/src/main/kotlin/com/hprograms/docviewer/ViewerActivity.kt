package com.hprograms.docviewer

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class ViewerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private val main = Handler(Looper.getMainLooper())

    private var entry: DocEntry? = null
    private var served: File? = null
    private var loadedKind: String? = null
    private var triedOfficeFallback = false
    private var convertStarted = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val uri = incomingUri(intent)
        val id = intent.getStringExtra(EXTRA_ENTRY_ID)
        lifecycleScope.launch {
            try {
                val e = withContext(Dispatchers.IO) {
                    when {
                        id != null -> Library.recent(this@ViewerActivity).firstOrNull { it.id == id && it.name == intent.getStringExtra(EXTRA_ENTRY_NAME) }
                            ?.also { Library.touch(this@ViewerActivity, it) }
                            ?: throw IllegalStateException("최근 문서 목록에서 찾을 수 없습니다")
                        uri != null -> Library.import(this@ViewerActivity, uri)
                        else -> throw IllegalStateException("열 파일이 없습니다")
                    }
                }
                entry = e
                supportActionBar?.title = e.name
                show(e)
            } catch (t: Throwable) {
                showError(t.message ?: t.toString())
            }
        }
    }

    private fun incomingUri(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_SEND -> i.getParcelableExtraCompat(Intent.EXTRA_STREAM)
        else -> i.data
    }

    // ---- UI --------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            setBackgroundColor(0xFFE6E6E6.toInt())
            webChromeClient = WebChromeClient()
            addJavascriptInterface(JsBridge(), "Android")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF4F4F4.toInt())
            addView(ProgressBar(context))
            statusText = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(40, 30, 40, 0)
                text = "여는 중…"
            }
            addView(statusText)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setStatus(msg: String?, spinning: Boolean = true) {
        if (msg == null) {
            status.visibility = View.GONE
            return
        }
        status.visibility = View.VISIBLE
        status.getChildAt(0).visibility = if (spinning) View.VISIBLE else View.GONE
        statusText.text = msg
    }

    private fun showError(msg: String) = setStatus("열 수 없습니다\n\n$msg", spinning = false)

    // ---- routing -----------------------------------------------------------------

    private fun show(e: DocEntry) {
        when (e.kind) {
            DocKind.PDF -> load(e.file(this), "pdf")
            DocKind.HWP -> load(e.file(this), "hwp")
            DocKind.IMAGE -> load(e.file(this), "image")
            DocKind.TEXT -> load(e.file(this), "text")
            DocKind.OFFICE -> convertThenLoad(e)
        }
    }

    private fun load(file: File, kind: String) {
        served = file
        loadedKind = kind
        setStatus("여는 중…")
        web.webViewClient = client
        web.loadUrl("https://$HOST/app/viewer.html?kind=$kind&src=/doc/${Uri.encode(file.name)}")
    }

    private fun convertThenLoad(e: DocEntry, password: String? = null) {
        val pdf = File(Library.pdfDir(this), e.id.substringBefore('.') + ".pdf")
        if (pdf.exists() && pdf.length() > 0) {
            load(pdf, "pdf")
            return
        }
        convertStarted = System.currentTimeMillis()
        tickConvert()
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                if (convertStarted == 0L) return
                convertStarted = 0L
                val err = data?.getString(OfficeService.KEY_ERROR) ?: "변환 실패"
                when {
                    resultCode == OfficeService.RESULT_OK -> load(pdf, "pdf")
                    data?.getBoolean(OfficeService.KEY_LOCKED) == true -> askPassword(err) { convertThenLoad(e, it) }
                    else -> showError(err)
                }
            }
        }
        startService(
            Intent(this, OfficeService::class.java)
                .putExtra(OfficeService.EXTRA_IN, e.file(this).absolutePath)
                .putExtra(OfficeService.EXTRA_OUT, pdf.absolutePath)
                .putExtra(OfficeService.EXTRA_RECEIVER, receiver)
                .putExtra(OfficeService.EXTRA_UNLOCK, password),
        )
    }

    private fun askPassword(title: String, onCancel: () -> Unit = { finish() }, then: (String) -> Unit) {
        setStatus(title, spinning = false)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "비밀번호"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("열기") { _, _ -> then(input.text.toString()) }
            .setNegativeButton("취소") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()
    }

    private fun tickConvert() {
        val started = convertStarted
        if (started == 0L) return
        val sec = (System.currentTimeMillis() - started) / 1000
        if (sec >= CONVERT_TIMEOUT_SEC) {
            convertStarted = 0L
            killOfficeProcess(this)
            showError("문서 변환이 ${CONVERT_TIMEOUT_SEC}초 안에 끝나지 않았습니다")
            return
        }
        if (sec >= 3 && !officeProcessAlive(this)) {
            // The engine died on this file (native crash) without answering.
            convertStarted = 0L
            showError("문서 엔진이 이 파일을 읽다가 멈췄습니다")
            return
        }
        setStatus(if (sec < 2) "문서 준비 중…" else "문서 준비 중… ${sec}초")
        main.postDelayed({ tickConvert() }, 1000)
    }

    // ---- WebView plumbing --------------------------------------------------------

    private val client = object : WebViewClientCompat() {
        private val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/app/") { path -> asset("web/$path") }
            .addPathHandler("/doc/") { path -> doc(path) }
            .build()

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // Out of memory on a huge page etc. — keep the app alive.
            showError("화면을 그리다 메모리가 부족했습니다")
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Links inside documents open in the browser.
            val u = request.url
            if (u.host == HOST) return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
            return true
        }
    }

    private fun asset(path: String): WebResourceResponse? = runCatching {
        WebResourceResponse(mimeOf(path), null, assets.open(path))
    }.getOrNull()

    private fun doc(path: String): WebResourceResponse? {
        val f = served ?: return null
        if (Uri.decode(path) != f.name) return null
        return WebResourceResponse("application/octet-stream", null, FileInputStream(f))
    }

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "wasm" -> "application/wasm"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    inner class JsBridge {
        @JavascriptInterface
        fun askPassword(message: String) = main.post {
            askPassword(message, onCancel = { reply(null) }) { reply(it) }
        }

        private fun reply(pw: String?) {
            val arg = if (pw == null) "null" else org.json.JSONObject.quote(pw)
            web.evaluateJavascript("window.__pwResolve && window.__pwResolve($arg)", null)
        }

        @JavascriptInterface
        fun onPrintProgress(done: Int, total: Int) = main.post { setStatus("PDF 만드는 중… $done / $total") }

        @JavascriptInterface
        fun onPrintReady(widthPx: Double, heightPx: Double) = main.post { writeHwpPdf(widthPx, heightPx) }

        @JavascriptInterface
        fun onPrintFail(message: String) = main.post {
            setStatus(null)
            toast("PDF를 만들지 못했습니다: $message")
        }

        @JavascriptInterface
        fun onReady(pages: Int) = main.post { setStatus(null) }

        @JavascriptInterface
        fun onFail(message: String) = main.post {
            val e = entry
            // rhwp covers HWP 5.0/HWPX; LibreOffice can still read some old HWP 97 files.
            if (e != null && e.kind == DocKind.HWP && !triedOfficeFallback && !message.contains("비밀번호")) {
                triedOfficeFallback = true
                convertThenLoad(e)
            } else {
                showError(message)
            }
        }
    }

    // ---- menu --------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 3, 0, "찾기").setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 4, 0, "쪽 이동")
        menu.add(0, 1, 0, "공유")
        menu.add(0, 5, 0, "PDF로 공유")
        menu.add(0, 2, 0, "다른 앱으로 열기")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val e = entry
        when (item.itemId) {
            android.R.id.home -> finish()
            3 -> web.evaluateJavascript("window.showSearch && window.showSearch()", null)
            4 -> web.evaluateJavascript("document.getElementById('pageNum').click()", null)
            1 -> if (e != null) shareFile(e.file(this), e.name, send = true)
            2 -> if (e != null) shareFile(e.file(this), e.name, send = false)
            5 -> if (e != null) sharePdf(e)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---- sharing -------------------------------------------------------------------

    private fun pdfName(e: DocEntry) = e.name.substringBeforeLast('.', e.name) + ".pdf"

    /** Hands [file] to other apps under the name [name]. */
    private fun shareFile(file: File, name: String, send: Boolean) {
        val shareDir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val out = File(shareDir, name.replace('/', '_'))
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(DocKinds.extOf(name)) ?: "application/octet-stream"
        val i = if (send) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, if (send) "공유" else "다른 앱으로 열기")) }
    }

    private fun sharePdf(e: DocEntry) {
        val f = served
        when {
            f != null && loadedKind == "pdf" -> shareFile(f, pdfName(e), send = true)
            loadedKind == "hwp" -> {
                setStatus("PDF 만드는 중…")
                web.evaluateJavascript("window.preparePrint()", null)
            }
            else -> toast("이 파일은 PDF로 만들 수 없습니다")
        }
    }

    /** Prints the HWP pages the page just laid out into a PDF, then shares it. */
    private fun writeHwpPdf(widthPx: Double, heightPx: Double) {
        val e = entry ?: return
        val mils = { px: Double -> (px / 96.0 * 1000).toInt() }
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize("doc", "doc", mils(widthPx), mils(heightPx)))
            .setResolution(android.print.PrintAttributes.Resolution("r", "r", 600, 600))
            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
            .build()
        val out = File(Library.pdfDir(this), "print-" + e.id.substringBefore('.') + ".pdf")
        val adapter = web.createPrintDocumentAdapter(e.name)
        android.print.PdfPrint.write(adapter, attrs, out) { err ->
            main.post {
                setStatus(null)
                if (err == null && out.length() > 0) shareFile(out, pdfName(e), send = true)
                else toast("PDF를 만들지 못했습니다: $err")
            }
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        convertStarted = 0L
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_NAME = "entry_name"
        private const val HOST = "appassets.androidplatform.net"
        private const val CONVERT_TIMEOUT_SEC = 180

        fun officeProcessAlive(ctx: Context): Boolean {
            val am = ctx.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            return am.runningAppProcesses?.any { it.processName.endsWith(":office") } == true
        }

        fun killOfficeProcess(ctx: Context) {
            val am = ctx.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.filter { it.processName.endsWith(":office") }?.forEach {
                android.os.Process.killProcess(it.pid)
            }
        }
    }
}
