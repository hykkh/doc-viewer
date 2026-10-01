package com.hprograms.docviewer

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import java.util.UUID

class ViewerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private val main = Handler(Looper.getMainLooper())

    private var entry: DocEntry? = null
    private var served: File? = null
    private var loadedKind: String? = null
    private var triedOfficeFallback = false

    // Office conversion in flight: the job id we sent, and when the engine started it.
    private var jobId: String? = null
    private var jobStarted = 0L

    // The document finished loading in the page (HWP must be laid out before printing).
    private var ready = false
    // An HWP → PDF print is running; a second one would write the same file.
    private var printing = false

    private var readAloud: ReadAloud? = null

    // Back closes an open contents/thumbnail/slide view before the document.
    private val overlayBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            js("window.closeAny && window.closeAny()")
            isEnabled = false
        }
    }

    // Decrypted PDF of a password document; deleted when this viewer closes.
    private var lockedPdf: File? = null

    // The WebView renderer died (e.g. out of memory); the WebView must not be used again.
    private var webDead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        onBackPressedDispatcher.addCallback(this, overlayBack)

        val uri = incomingUri(intent)
        val id = intent.getStringExtra(EXTRA_ENTRY_ID)
        if (uri == null && id == null && intent.action == Intent.ACTION_SEND) {
            // Text or a link was shared, not a file.
            Toast.makeText(this, "파일만 열 수 있습니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (Updater.mustUpdate(Updater.known(this))) {
            // Below the owner's minimum version: the main screen asks for the update.
            Toast.makeText(this, "업데이트해야 쓸 수 있습니다", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        when {
            License.valid(this) -> openDocument(uri, id)
            // Recreated (e.g. rotated) while the approval screen is open: its result still comes here.
            savedInstanceState?.getBoolean("waitingLicense") == true -> waitingLicense = true
            else -> {
                waitingLicense = true
                licenseGate.launch(Intent(this, ActivationActivity::class.java))
            }
        }
    }

    private var waitingLicense = false
    private val licenseGate = ActivationActivity.gate(this) {
        waitingLicense = false
        openDocument(incomingUri(intent), intent.getStringExtra(EXTRA_ENTRY_ID))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("waitingLicense", waitingLicense)
    }

    private fun openDocument(uri: Uri?, id: String?) {
        lifecycleScope.launch {
            try {
                val e = withContext(Dispatchers.IO) {
                    when {
                        id != null -> Library.recent(this@ViewerActivity).firstOrNull { it.id == id && it.name == intent.getStringExtra(EXTRA_ENTRY_NAME) }
                            ?.also { Library.use(it.id, true); Library.touch(this@ViewerActivity, it) }
                            ?: throw IllegalStateException("최근 문서 목록에서 찾을 수 없습니다")
                        uri != null -> Library.import(this@ViewerActivity, checkUri(uri))
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

    /**
     * This activity is exported, so any app can hand it a file:// URI. Accept
     * file:// only for regular files on shared storage — never our own private
     * data, which we could otherwise be tricked into listing and sharing.
     */
    private fun checkUri(uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val f = File(uri.path ?: "").canonicalFile
        val shared = Environment.getExternalStorageDirectory().canonicalPath + File.separator
        if (!f.path.startsWith(shared) || !f.isFile) throw SecurityException("이 위치의 파일은 열 수 없습니다")
        return Uri.fromFile(f)
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

    private fun alive() = !isFinishing && !isDestroyed

    private fun setStatus(msg: String?, spinning: Boolean = true) {
        if (msg == null) {
            status.visibility = View.GONE
            return
        }
        status.visibility = View.VISIBLE
        status.getChildAt(0).visibility = if (spinning) View.VISIBLE else View.GONE
        statusText.text = msg
        status.setOnClickListener(null)
    }

    private fun showError(msg: String) = setStatus("열 수 없습니다\n\n$msg", spinning = false)

    private fun js(code: String) {
        if (!webDead && alive()) web.evaluateJavascript(code, null)
    }

    // ---- routing -----------------------------------------------------------------

    private fun show(e: DocEntry) {
        when (e.kind) {
            DocKind.PDF -> load(e.file(this), "pdf")
            DocKind.HWP -> load(e.file(this), "hwp")
            DocKind.IMAGE -> showImage(e)
            DocKind.TEXT -> load(e.file(this), "text")
            DocKind.OFFICE -> convertThenLoad(e)
            DocKind.ZIP -> showZip(e)
        }
    }

    /** A ZIP attachment: list what is inside; the chosen file opens in a new viewer. */
    private fun showZip(e: DocEntry) {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { ZipView.list(e.file(this@ViewerActivity)) } }
            if (!alive()) return@launch
            val list = items.getOrElse {
                showError("압축 파일을 읽지 못했습니다")
                return@launch
            }
            if (list.isEmpty()) {
                showError("압축 파일 안에 파일이 없습니다")
                return@launch
            }
            setStatus("압축 파일 안의 ${list.size}개 파일", spinning = false)
            val labels = list.map { "${it.name.substringAfterLast('/')}  (${android.text.format.Formatter.formatShortFileSize(this@ViewerActivity, it.size)})" }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle(e.name)
                .setItems(labels.toTypedArray()) { _, which -> openFromZip(e, list[which].name) }
                .setNegativeButton("닫기") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun openFromZip(e: DocEntry, entryName: String) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching { ZipView.extract(e.file(this@ViewerActivity), entryName, File(cacheDir, "zip/" + UUID.randomUUID())) }
            }
            if (!alive()) return@launch
            out.onSuccess { f ->
                val uri = FileProvider.getUriForFile(this@ViewerActivity, "$packageName.files", f)
                startActivity(Intent(this@ViewerActivity, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                // Come back to the list when that document is closed.
                showZip(e)
            }.onFailure { toast("풀지 못했습니다: ${it.message}") }
        }
    }

    /** WebView cannot decode HEIC/HEIF (iPhone photos); turn those into JPEG first. */
    private fun showImage(e: DocEntry) {
        val f = e.file(this)
        if (DocKinds.extOf(e.name) !in setOf("heic", "heif") && !DocKinds.isHeif(f)) {
            load(f, "image")
            return
        }
        lifecycleScope.launch {
            val jpg = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(Library.pdfDir(this@ViewerActivity), e.id.substringBefore('.') + ".jpg")
                    if (!out.exists()) {
                        val bmp = if (android.os.Build.VERSION.SDK_INT >= 28) {
                            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f))
                        } else {
                            android.graphics.BitmapFactory.decodeFile(f.path)
                        } ?: throw IllegalStateException("그림을 읽지 못했습니다")
                        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    }
                    out
                }
            }
            jpg.onSuccess { load(it, "image") }.onFailure { showError(it.message ?: "그림을 읽지 못했습니다") }
        }
    }

    private fun load(file: File, kind: String) {
        if (webDead || !alive()) return
        served = file
        loadedKind = kind
        setStatus("여는 중…")
        web.webViewClient = client
        val q = StringBuilder("kind=$kind&src=/doc/${Uri.encode(file.name)}")
        if (nightMode()) q.append("&night=1")
        entry?.let { e -> getSharedPreferences(PREFS_POS, MODE_PRIVATE).getString(e.id, null)?.let { q.append("&pos=").append(Uri.encode(it)) } }
        if (File(file.path + ".json").exists()) q.append("&meta=/doc/meta")
        web.loadUrl("https://$HOST/app/viewer.html?$q")
        invalidateOptionsMenu()
    }

    private fun convertThenLoad(e: DocEntry, password: String? = null) {
        // A document opened with a password is converted to a throwaway file:
        // a decrypted copy in the shared cache would open next time without asking.
        val pdf = if (password == null) {
            Library.pdfFor(this, e)
        } else {
            File(Library.pdfDir(this), "locked-${UUID.randomUUID()}.pdf").also { lockedPdf = it }
        }
        if (pdf.exists() && pdf.length() > 0) {
            load(pdf, "pdf")
            return
        }
        val id = UUID.randomUUID().toString()
        jobId = id
        jobStarted = 0L
        setStatus("문서 준비 중…")
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                if (jobId != id || !alive()) return
                if (resultCode == OfficeService.RESULT_STARTED) {
                    // Count the time limit from when the engine picks the job up,
                    // not from when it was queued behind someone else's file.
                    jobStarted = System.currentTimeMillis()
                    tickConvert(id)
                    return
                }
                jobId = null
                val err = data?.getString(OfficeService.KEY_ERROR) ?: "변환 실패"
                when {
                    resultCode == OfficeService.RESULT_OK -> load(pdf, "pdf")
                    data?.getBoolean(OfficeService.KEY_LOCKED) == true -> askPassword(err) { convertThenLoad(e, it) }
                    else -> showError(err)
                }
            }
        }
        val request = Intent(this, OfficeService::class.java)
            .putExtra(OfficeService.EXTRA_JOB, id)
            .putExtra(OfficeService.EXTRA_IN, e.file(this).absolutePath)
            .putExtra(OfficeService.EXTRA_OUT, pdf.absolutePath)
            .putExtra(OfficeService.EXTRA_RECEIVER, receiver)
            .putExtra(OfficeService.EXTRA_UNLOCK, password)
        if (runCatching { startService(request) }.isFailure) {
            jobId = null
            showError("문서 엔진을 시작하지 못했습니다. 다시 열어 주세요")
            return
        }
        watchQueue(id, request, System.currentTimeMillis(), resent = false)
    }

    /**
     * While our job waits in line, the engine process can die under it (another
     * file crashed it, a timeout killed it, the system reclaimed it). Then no
     * answer would ever come: resend once, and give up after that.
     */
    private fun watchQueue(id: String, request: Intent, since: Long, resent: Boolean) {
        main.postDelayed({
            if (jobId != id || !alive() || jobStarted != 0L) return@postDelayed
            when {
                System.currentTimeMillis() - since > QUEUE_LIMIT_MS -> {
                    jobId = null
                    showError("문서 엔진이 응답하지 않습니다")
                }
                officeProcessAlive(this) -> watchQueue(id, request, since, resent)
                !resent -> {
                    runCatching { startService(request) }
                    watchQueue(id, request, since, resent = true)
                }
                else -> {
                    jobId = null
                    showError("문서 엔진이 멈췄습니다. 다시 열어 주세요")
                }
            }
        }, 3000)
    }

    private fun askPassword(title: String, onCancel: () -> Unit = { finish() }, then: (String) -> Unit) {
        if (!alive()) return
        setStatus(title, spinning = false)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "비밀번호"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("열기") { _, _ -> then(input.text.toString()) }
            .setNegativeButton("취소") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()
    }

    private fun askPage(count: Int) {
        if (!alive()) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 ~ $count"
        }
        AlertDialog.Builder(this)
            .setTitle("이동할 쪽")
            .setView(input)
            .setPositiveButton("이동") { _, _ ->
                input.text.toString().toIntOrNull()?.takeIf { it in 1..count }?.let { js("window.gotoPage($it)") }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun tickConvert(id: String) {
        if (jobId != id || !alive()) return
        val sec = (System.currentTimeMillis() - jobStarted) / 1000
        if (sec >= CONVERT_TIMEOUT_SEC) {
            jobId = null
            killOfficeProcess(this)
            showError("문서 변환이 ${CONVERT_TIMEOUT_SEC}초 안에 끝나지 않았습니다")
            return
        }
        if (sec >= 3 && !officeProcessAlive(this)) {
            // The engine died on this file (native crash) without answering.
            jobId = null
            showError("문서 엔진이 이 파일을 읽다가 멈췄습니다")
            return
        }
        setStatus(if (sec < 2) "문서 준비 중…" else "문서 준비 중… ${sec}초")
        main.postDelayed({ tickConvert(id) }, 1000)
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
            // The WebView is unusable from here on. Drop it; a tap rebuilds the screen.
            webDead = true
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
            if (alive()) {
                setStatus("화면을 그리다 메모리가 부족했습니다\n\n눌러서 다시 열기", spinning = false)
                status.setOnClickListener { recreate() }
            }
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
        val f0 = served ?: return null
        // "meta": the sheet list written next to a converted spreadsheet.
        val f = if (path == "meta") File(f0.path + ".json") else f0
        if (path != "meta" && Uri.decode(path) != f.name) return null
        return runCatching { WebResourceResponse("application/octet-stream", null, FileInputStream(f)) }.getOrNull()
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

    /** Called by viewer.js. Every callback hops to the main thread and is dropped once we are closing. */
    inner class JsBridge {
        private fun ui(block: () -> Unit) {
            main.post { if (alive()) block() }
        }

        @JavascriptInterface
        fun askPassword(message: String) = ui {
            askPassword(message, onCancel = { reply(null) }) { reply(it) }
        }

        private fun reply(pw: String?) {
            val arg = if (pw == null) "null" else org.json.JSONObject.quote(pw)
            js("window.__pwResolve && window.__pwResolve($arg)")
        }

        @JavascriptInterface
        fun askPage(count: Int) = ui { this@ViewerActivity.askPage(count) }

        @JavascriptInterface
        fun onPrintProgress(done: Int, total: Int) = ui { setStatus("PDF 만드는 중… $done / $total") }

        @JavascriptInterface
        fun onPrintReady(widthPx: Double, heightPx: Double, mode: String) = ui { writeHwpPdf(widthPx, heightPx, mode) }

        @JavascriptInterface
        fun savePos(pos: String) {
            val e = entry ?: return
            getSharedPreferences(PREFS_POS, MODE_PRIVATE).edit().putString(e.id, pos).apply()
        }

        @JavascriptInterface
        fun saveText(text: String) {
            val e = entry ?: return
            Library.saveText(this@ViewerActivity, e.id, text)
        }

        @JavascriptInterface
        fun savePng(page: Int, base64: String) {
            val e = entry ?: return
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    runCatching {
                        File(cacheDir, "page.png").also { it.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)) }
                    }.getOrNull()
                }
                if (f != null && alive()) shareFile(f, "${e.name.substringBeforeLast('.', e.name)} ${page}쪽.png", send = true)
            }
        }

        @JavascriptInterface
        fun doubleTap(x: Float, y: Float) = ui { toggleZoom() }

        @JavascriptInterface
        fun onPageText(page: Int, text: String) = ui { readAloud?.speak(page, text) }

        @JavascriptInterface
        fun onOverlay(open: Boolean) = ui { overlayBack.isEnabled = open }

        @JavascriptInterface
        fun onPrintFail(message: String) = ui {
            printing = false
            setStatus(null)
            toast("PDF를 만들지 못했습니다: $message")
        }

        @JavascriptInterface
        fun onReady(pages: Int) = ui {
            ready = true
            setStatus(null)
            invalidateOptionsMenu()
        }

        @JavascriptInterface
        fun toast(message: String) = ui { this@ViewerActivity.toast(message) }

        @JavascriptInterface
        fun onFail(message: String) = ui {
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
        menu.add(0, MENU_FIND, 0, "찾기").setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        val paged = loadedKind == "pdf" || loadedKind == "hwp"
        if (paged) {
            menu.add(0, MENU_PAGE, 0, "쪽 이동")
            menu.add(0, MENU_OUTLINE, 0, "목차")
            menu.add(0, MENU_THUMBS, 0, "쪽 한눈에 보기")
            menu.add(0, MENU_SLIDES, 0, "슬라이드쇼")
        }
        if (loadedKind != "image") {
            menu.add(0, MENU_READ, 0, if (readAloud?.speaking == true) "읽기 멈춤" else "읽어 주기")
        }
        menu.add(0, MENU_NIGHT, 0, "야간 모드").setCheckable(true).setChecked(nightMode())
        menu.add(0, MENU_SHARE, 0, "공유")
        if (paged) {
            menu.add(0, MENU_SHARE_PDF, 0, "PDF로 공유")
            menu.add(0, MENU_SHARE_PAGE, 0, "이 쪽을 그림으로 공유")
        }
        menu.add(0, MENU_SAVE, 0, "폰에 저장")
        if (paged) {
            menu.add(0, MENU_SAVE_PDF, 0, "PDF로 폰에 저장")
            menu.add(0, MENU_PRINT, 0, "인쇄")
        }
        menu.add(0, MENU_OPEN_WITH, 0, "다른 앱으로 열기")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val e = entry
        when (item.itemId) {
            android.R.id.home -> finish()
            MENU_FIND -> js("window.showSearch && window.showSearch()")
            MENU_PAGE -> js("window.askJump && window.askJump()")
            MENU_OUTLINE -> js("window.showOutline && window.showOutline()")
            MENU_THUMBS -> js("window.showThumbs && window.showThumbs()")
            MENU_SLIDES -> js("window.startSlides && window.startSlides()")
            MENU_SHARE_PAGE -> js("window.sharePageImage && window.sharePageImage()")
            MENU_READ -> toggleReadAloud()
            MENU_NIGHT -> {
                val on = !nightMode()
                getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putBoolean("night", on).apply()
                js("window.setNight($on)")
                invalidateOptionsMenu()
            }
            MENU_SHARE -> if (e != null) shareFile(e.file(this), e.name, send = true)
            MENU_OPEN_WITH -> if (e != null) shareFile(e.file(this), e.name, send = false)
            MENU_SHARE_PDF -> if (e != null) makePdf(e, "pdf")
            MENU_SAVE_PDF -> if (e != null) makePdf(e, "save")
            MENU_PRINT -> if (e != null) makePdf(e, "print")
            MENU_SAVE -> if (e != null) saveCopy(e.file(this), e.name)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun nightMode() = getSharedPreferences(PREFS_UI, MODE_PRIVATE).getBoolean("night", false)

    // ---- zoom, read aloud ------------------------------------------------------------

    private var baseScale = 0f

    /** Double tap: zoom in to 2.5x around the page, or back out to fit. */
    @Suppress("DEPRECATION")
    private fun toggleZoom() {
        if (webDead) return
        val scale = web.scale
        if (baseScale == 0f) baseScale = scale
        if (scale > baseScale * 1.3f) web.zoomBy((baseScale / scale).coerceIn(0.02f, 1f)) else web.zoomBy(2.5f)
    }

    private fun toggleReadAloud() {
        val r = readAloud
        if (r != null && r.speaking) {
            r.stop()
            toast("읽기를 멈췄습니다")
            invalidateOptionsMenu()
            return
        }
        val reader = r ?: ReadAloud(this, onNeedPage = { p -> main.post { js("window.speakPage($p)") } }) {
            main.post { if (alive()) invalidateOptionsMenu() }
        }.also { readAloud = it }
        webValue("window.currentPage ? window.currentPage() : 0") { v ->
            val from = v.toIntOrNull() ?: 0
            toast("${from + 1}쪽부터 읽습니다")
            reader.start(from)
            invalidateOptionsMenu()
        }
    }

    private fun webValue(expr: String, then: (String) -> Unit) {
        if (webDead || !alive()) return
        web.evaluateJavascript(expr) { then(it?.trim('"') ?: "") }
    }

    // ---- saving to the phone ---------------------------------------------------------

    private fun saveCopy(file: File, name: String) {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            toast("저장 권한을 허용한 뒤 다시 눌러 주세요")
            return
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(DocKinds.extOf(name)) ?: "application/octet-stream"
                    saveToDownloads(this@ViewerActivity, file, safeFileName(name), mime)
                }
            }
            if (!alive()) return@launch
            r.onSuccess { toast("$it 에 저장했습니다") }.onFailure { toast("저장하지 못했습니다: ${it.message}") }
        }
    }

    // ---- sharing -------------------------------------------------------------------

    private fun pdfName(e: DocEntry) = e.name.substringBeforeLast('.', e.name) + ".pdf"

    /**
     * A name that is harmless as a file name and fits the 255-byte limit
     * (Korean takes 3 bytes a letter), keeping the extension.
     */
    private fun safeFileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifEmpty { "document" }
        val ext = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }?.let { ".$it" } ?: ""
        var base = clean.removeSuffix(ext)
        while (base.isNotEmpty() && (base + ext).toByteArray(Charsets.UTF_8).size > 200) base = base.dropLast(1)
        return base.ifEmpty { "document" } + ext
    }

    /** Hands [file] to other apps under the name [name]. The copy runs off the main thread. */
    private fun shareFile(file: File, name: String, send: Boolean) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val shareDir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                    File(shareDir, safeFileName(name)).also { file.copyTo(it, overwrite = true) }
                }.getOrNull()
            }
            if (out == null || !alive()) {
                if (alive()) toast("파일을 준비하지 못했습니다")
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@ViewerActivity, "$packageName.files", out)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(DocKinds.extOf(out.name)) ?: "application/octet-stream"
            val i = if (send) {
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(i, if (send) "공유" else "다른 앱으로 열기")) }
        }
    }

    /**
     * PDF of the document, then [mode]: "pdf" share it, "save" put it in
     * Download, "print" send it to the printer. Office documents already are a
     * PDF; HWP is laid out by the page and printed by the WebView.
     */
    private fun makePdf(e: DocEntry, mode: String) {
        val f = served
        when {
            f != null && loadedKind == "pdf" -> when (mode) {
                "save" -> saveCopy(f, pdfName(e))
                "print" -> printPdfFile(this, f, e.name)
                else -> shareFile(f, pdfName(e), send = true)
            }
            loadedKind == "hwp" -> when {
                !ready -> toast("문서를 여는 중입니다")
                printing -> toast("PDF를 만드는 중입니다")
                else -> {
                    printing = true
                    setStatus(if (mode == "print") "인쇄 준비 중…" else "PDF 만드는 중…")
                    js("window.preparePrint('$mode')")
                }
            }
            else -> toast("이 파일은 PDF로 만들 수 없습니다")
        }
    }

    /** The page has laid out every HWP page for printing; finish the job for [mode]. */
    private fun writeHwpPdf(widthPx: Double, heightPx: Double, mode: String) {
        val e = entry ?: return
        if (webDead) return
        val mils = { px: Double -> (px / 96.0 * 1000).toInt() }
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize("doc", "doc", mils(widthPx), mils(heightPx)))
            .setResolution(android.print.PrintAttributes.Resolution("r", "r", 600, 600))
            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
            .build()
        if (mode == "print") {
            // The system print dialog drives the WebView adapter itself.
            printing = false
            setStatus(null)
            val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
            pm.print(e.name, web.createPrintDocumentAdapter(e.name), attrs)
            return
        }
        // One print file, overwritten each time (shareFile/saveCopy copy it out).
        val out = File(Library.pdfDir(this), "print.pdf")
        val adapter = web.createPrintDocumentAdapter(e.name)
        android.print.PdfPrint.write(adapter, attrs, out) { err ->
            main.post {
                js("window.cleanupPrint && window.cleanupPrint()")
                printing = false
                if (!alive()) return@post
                setStatus(null)
                when {
                    err != null || out.length() == 0L -> toast("PDF를 만들지 못했습니다: $err")
                    mode == "save" -> saveCopy(out, pdfName(e))
                    else -> shareFile(out, pdfName(e), send = true)
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        // Tell the engine to skip our file if it has not started on it yet.
        jobId?.let {
            // May be refused while we are in the background; the engine's own limit covers that.
            runCatching { startService(Intent(this, OfficeService::class.java).setAction(OfficeService.ACTION_CANCEL).putExtra(OfficeService.EXTRA_JOB, it)) }
        }
        jobId = null
        entry?.let { Library.use(it.id, false) }
        lockedPdf?.delete()
        readAloud?.shutdown()
        if (!webDead) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_NAME = "entry_name"
        private const val HOST = "appassets.androidplatform.net"
        private const val CONVERT_TIMEOUT_SEC = 180
        private const val QUEUE_LIMIT_MS = 10 * 60_000L
        private const val MENU_SHARE = 1
        private const val MENU_OPEN_WITH = 2
        private const val MENU_FIND = 3
        private const val MENU_PAGE = 4
        private const val MENU_SHARE_PDF = 5
        private const val MENU_OUTLINE = 6
        private const val MENU_THUMBS = 7
        private const val MENU_SLIDES = 8
        private const val MENU_SHARE_PAGE = 9
        private const val MENU_READ = 10
        private const val MENU_NIGHT = 11
        private const val MENU_SAVE = 12
        private const val MENU_SAVE_PDF = 13
        private const val MENU_PRINT = 14
        private const val PREFS_POS = "positions"
        private const val PREFS_UI = "ui"

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
