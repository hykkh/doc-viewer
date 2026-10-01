package com.hprograms.docviewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    /** One line in the list, whatever it came from. */
    private class Row(
        val name: String,
        val sub: String,
        val star: Boolean,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null,
    )

    private enum class Tab(val label: String) { RECENT("최근 문서"), FAVORITE("즐겨찾기"), PHONE("폰의 문서") }

    /** Kind filter: label → extensions (empty = all). */
    private val kinds = listOf(
        "전체" to emptySet(),
        "한글" to setOf("hwp", "hwpx", "hwt", "hml"),
        "워드" to setOf("doc", "docx", "rtf", "odt"),
        "엑셀" to setOf("xls", "xlsx", "xlsm", "csv", "ods", "tsv"),
        "PPT" to setOf("ppt", "pptx", "pps", "ppsx", "odp"),
        "PDF" to setOf("pdf"),
    )

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var query: EditText
    private val tabViews = HashMap<Tab, TextView>()
    private val kindViews = ArrayList<TextView>()
    private val adapter = RowAdapter()
    private var tab = Tab.RECENT
    private var kind = 0
    private var refreshJob: Job? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startActivity(Intent(this, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            it.getString("tab")?.let { t -> tab = Tab.valueOf(t) }
            kind = it.getInt("kind", 0)
        }
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F5F7.toInt())
        }
        val open = Button(this).apply {
            text = "파일 열기"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF2F6FED.toInt()) }
            setOnClickListener { pick.launch(arrayOf("*/*")) }
        }
        root.addView(open, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(pad, pad, pad, dp(8)) })

        query = EditText(this).apply {
            hint = "이름이나 내용으로 찾기"
            textSize = 15f
            setSingleLine()
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dp(1), 0xFFD5D9E0.toInt()) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh(debounce = true)
            })
        }
        root.addView(query, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad, dp(8)) })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in Tab.entries) {
            val v = chip(t.label, 15f) { tab = t; refresh() }
            tabViews[t] = v
            tabs.addView(v, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad, dp(4)) })

        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kinds.forEachIndexed { i, (label, _) ->
            val v = chip(label, 13f) { kind = i; refresh() }
            v.setPadding(dp(14), 0, dp(14), 0)
            kindViews.add(v)
            kindRow.addView(v, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(kindRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad, dp(6)) })

        empty = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            setPadding(pad, dp(40), pad, pad)
        }
        root.addView(empty)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@MainActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        waitingLicense = savedInstanceState?.getBoolean("waitingLicense") == true
        if (!License.valid(this) && !waitingLicense) askLicense()
    }

    // Unlicensed: the approval screen opens over this one; leaving it unapproved closes the app.
    private var waitingLicense = false
    private val licenseGate = ActivationActivity.gate(this) { waitingLicense = false; refresh() }

    private fun askLicense() {
        waitingLicense = true
        licenseGate.launch(Intent(this, ActivationActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "정보")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        AlertDialog.Builder(this)
            .setTitle("Hoffice ${BuildConfig.VERSION_NAME}")
            .setMessage(
                (License.holder(this)?.let { "사용자: $it\n\n" } ?: "") +
                "본 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.\n\n" +
                    "사용한 오픈소스\n" +
                    "• rhwp (MIT) — 한글 문서\n" +
                    "• LibreOffice (MPL-2.0) — 워드·엑셀·PPT\n" +
                    "• pdf.js (Apache-2.0) — PDF",
            )
            .setPositiveButton("확인", null)
            .show()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        outState.putInt("kind", kind)
        outState.putBoolean("waitingLicense", waitingLicense)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Now and then ask whether this phone is still allowed (needs no network to keep working).
        if (License.valid(this)) lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { License.recheckIfDue(this@MainActivity) } && !waitingLicense) askLicense()
        }
    }

    private fun chip(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun paint(v: TextView, on: Boolean) {
        v.setTextColor(if (on) 0xFF2F6FED.toInt() else 0xFF777777.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (on) 0xFFE3EBFD.toInt() else 0x00000000)
        }
    }

    private fun refresh(debounce: Boolean = false) {
        Tab.entries.forEach { paint(tabViews.getValue(it), it == tab) }
        kindViews.forEachIndexed { i, v -> paint(v, i == kind) }
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            if (debounce) delay(300)
            when (tab) {
                Tab.RECENT, Tab.FAVORITE -> showLibrary(tab == Tab.FAVORITE)
                Tab.PHONE -> showPhone()
            }
        }
    }

    private fun matchesKind(name: String): Boolean {
        val exts = kinds[kind].second
        return exts.isEmpty() || DocKinds.extOf(name) in exts
    }

    private fun show(rows: List<Row>, emptyText: String) {
        adapter.items = rows
        adapter.notifyDataSetChanged()
        empty.text = emptyText
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEntry(e: DocEntry) = startActivity(
        Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_ENTRY_ID, e.id)
            .putExtra(ViewerActivity.EXTRA_ENTRY_NAME, e.name),
    )

    private suspend fun showLibrary(favOnly: Boolean) {
        val q = query.text.toString().trim()
        val (entries, byContent) = withContext(Dispatchers.IO) {
            val all = Library.recent(this@MainActivity)
            val favs = Library.favorites(this@MainActivity)
            val base = all.filter { (!favOnly || "${it.id}|${it.name}" in favs) && matchesKind(it.name) }
            val content = if (q.isEmpty()) emptySet() else Library.idsContaining(this@MainActivity, q)
            val shown = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) || it.id in content }
            shown to content
        }
        val rows = entries.map { e ->
            val fav = Library.isFavorite(this, e)
            val foundIn = if (q.isNotEmpty() && !e.name.contains(q, ignoreCase = true) && e.id in byContent) " · 내용에서 찾음" else ""
            Row(
                e.name,
                "${DateUtils.getRelativeTimeSpanString(e.openedAt)} · ${Formatter.formatShortFileSize(this, e.size)}$foundIn",
                star = fav,
                onClick = { openEntry(e) },
                onLongClick = {
                    AlertDialog.Builder(this)
                        .setTitle(e.name)
                        .setItems(arrayOf(if (fav) "즐겨찾기 해제" else "즐겨찾기에 추가", "최근 문서에서 지우기")) { _, which ->
                            if (which == 0) Library.setFavorite(this, e, !fav) else Library.remove(this, e)
                            refresh()
                        }
                        .show()
                },
            )
        }
        val none = when {
            q.isNotEmpty() -> "'$q' 에 맞는 문서가 없습니다."
            favOnly -> "즐겨찾기한 문서가 없습니다.\n\n문서를 길게 누르면 즐겨찾기에 넣을 수 있습니다."
            else -> "아직 연 문서가 없습니다.\n\n카톡·메일 첨부를 누르거나 위 '파일 열기'로 여세요.\nHWP·HWPX·워드·엑셀·PPT·PDF를 모두 볼 수 있습니다."
        }
        show(rows, none)
    }

    // ---- documents on the phone ----------------------------------------------------

    private fun hasAllFilesAccess() = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (tab == Tab.PHONE) refresh() }

    private suspend fun showPhone() {
        if (!hasAllFilesAccess()) {
            show(emptyList(), "폰에 있는 문서를 모아 보려면 '모든 파일 접근' 권한이 필요합니다.\n\n여기를 누르면 설정 화면이 열립니다.")
            empty.setOnClickListener {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    }
                } else {
                    askStorage.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            return
        }
        empty.setOnClickListener(null)
        // Show the last scan right away; refresh it in the background.
        phoneFiles?.let { showFiles(it) } ?: show(emptyList(), "문서를 찾는 중…")
        val found = withContext(Dispatchers.IO) { scanPhone() }
        phoneFiles = found
        if (tab == Tab.PHONE) showFiles(found)
    }

    private fun showFiles(files: List<File>) {
        val q = query.text.toString().trim()
        val rows = files.filter { matchesKind(it.name) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }.map { f ->
            Row(
                f.name,
                "${DateUtils.getRelativeTimeSpanString(f.lastModified())} · ${Formatter.formatShortFileSize(this, f.length())} · ${f.parentFile?.name ?: ""}",
                star = false,
                onClick = { startActivity(Intent(this, ViewerActivity::class.java).setData(Uri.fromFile(f))) },
                onLongClick = {
                    AlertDialog.Builder(this)
                        .setTitle(f.name)
                        .setItems(arrayOf("즐겨찾기에 추가")) { _, _ -> addFavorite(f) }
                        .show()
                },
            )
        }
        show(rows, if (q.isNotEmpty()) "'$q' 에 맞는 문서가 없습니다." else "폰에서 문서를 찾지 못했습니다.")
    }

    /** A phone file becomes a favourite by keeping a copy of it like an opened one. */
    private fun addFavorite(f: File) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Library.import(this@MainActivity, Uri.fromFile(f)).also {
                        Library.use(it.id, false) // import hands its claim to the caller
                        Library.setFavorite(this@MainActivity, it, true)
                    }
                }
            }
            Toast.makeText(this@MainActivity, if (r.isSuccess) "즐겨찾기에 넣었습니다" else "넣지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanPhone(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || out.size > 5000) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) {
                    // App-private data and caches are not the user's documents.
                    if (depth == 0 && c.name == "Android") continue
                    walk(c, depth + 1)
                } else if (DocKinds.extOf(c.name) in DOC_EXTS && c.length() > 0) {
                    out.add(c)
                }
            }
        }
        walk(root, 0)
        return out.sortedByDescending { it.lastModified() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var items: List<Row> = emptyList()

        inner class VH(val row: LinearLayout, val badge: TextView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val badge = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
            }
            val title = TextView(parent.context).apply { textSize = 16f; setTextColor(0xFF222222.toInt()); maxLines = 2 }
            val sub = TextView(parent.context).apply { textSize = 12f; setTextColor(0xFF888888.toInt()); maxLines = 1 }
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub)
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(0xFFFFFFFF.toInt())
                addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 1 }
            }
            return VH(row, badge, title, sub)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            val ext = DocKinds.extOf(r.name).uppercase().ifEmpty { "?" }
            h.badge.text = ext.take(4)
            h.badge.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(colorFor(ext)) }
            h.title.text = if (r.star) "★ ${r.name}" else r.name
            h.sub.text = r.sub
            h.row.setOnClickListener { r.onClick() }
            h.row.setOnLongClickListener { r.onLongClick?.invoke(); r.onLongClick != null }
        }

        private fun colorFor(ext: String): Int = when {
            ext.startsWith("HWP") || ext == "HML" -> 0xFF1E88E5.toInt()
            ext.startsWith("DOC") || ext == "RTF" || ext == "ODT" -> 0xFF2B579A.toInt()
            ext.startsWith("XL") || ext == "CSV" || ext == "ODS" -> 0xFF217346.toInt()
            ext.startsWith("PP") || ext == "ODP" -> 0xFFD24726.toInt()
            ext == "PDF" -> 0xFFE53935.toInt()
            ext == "ZIP" -> 0xFF8D6E63.toInt()
            else -> 0xFF757575.toInt()
        }
    }

    companion object {
        // Last scan of the phone's documents; kept across rotation and re-entry.
        private var phoneFiles: List<File>? = null

        private val DOC_EXTS = setOf(
            "hwp", "hwpx", "hwt", "hml", "pdf",
            "doc", "docx", "rtf", "odt", "xls", "xlsx", "xlsm", "csv", "ods",
            "ppt", "pptx", "pps", "ppsx", "odp", "txt", "zip",
        )
    }
}
