package com.hprograms.docviewer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The update screens on the main screen. On each start: when a newer version
 * exists it is downloaded right away on Wi-Fi (asked first on mobile data),
 * then handed to the installer. Below the owner's minimum version the app
 * cannot be used until it is updated.
 */
class UpdateUi(private val act: AppCompatActivity) {
    private var busy = false
    private var declined = -1 // version code the user put off this session

    fun check(manual: Boolean = false) {
        if (busy) return
        if (manual) Updater.forgetCheck(act)
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                Updater.cleanStaleSessions(act)
                Updater.checkIfDue(act)
            }
            if (act.isFinishing) return@launch
            when {
                r == null || !Updater.isNewer(r) -> if (manual) toast("최신 버전입니다 (${BuildConfig.VERSION_NAME})")
                Updater.mustUpdate(r) -> offer(r, forced = true)
                manual || r.code != declined -> if (Updater.onWifi(act) && Updater.canInstall(act)) start(r) else offer(r, forced = false)
            }
        }
    }

    private fun offer(r: Updater.Release, forced: Boolean) {
        val msg = (if (forced) "이 버전은 더 이상 쓸 수 없습니다. 업데이트해 주세요.\n\n" else "") +
            "새 버전 ${r.version}이 있습니다 (지금 ${BuildConfig.VERSION_NAME}, 약 ${r.sizeMb}MB)." +
            (if (Updater.onWifi(act)) "" else "\n모바일 데이터로 받습니다.")
        val b = AlertDialog.Builder(act)
            .setTitle("Hoffice 업데이트")
            .setMessage(msg)
            .setCancelable(!forced)
            .setPositiveButton("업데이트") { _, _ -> start(r) }
            .setNeutralButton("브라우저로 받기") { _, _ -> browser(r, forced) }
        if (forced) b.setNegativeButton("닫기") { _, _ -> act.finish() }
        else b.setNegativeButton("나중에") { _, _ -> declined = r.code }
        b.show()
    }

    private fun start(r: Updater.Release) {
        if (!Updater.canInstall(act)) {
            AlertDialog.Builder(act)
                .setTitle("설치 허용이 필요합니다")
                .setMessage("업데이트를 앱이 직접 설치하려면 한 번만 ‘이 출처의 앱 허용’을 켜 주세요. 켠 뒤 돌아오면 이어서 받습니다.")
                .setPositiveButton("설정 열기") { _, _ -> Updater.forgetCheck(act); Updater.askInstallPermission(act) }
                .setNegativeButton("나중에") { _, _ -> declined = r.code }
                .show()
            return
        }
        busy = true
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val status = TextView(act).apply { text = "내려받는 중… 0%"; textSize = 15f }
        val note = TextView(act).apply {
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            text = "Play 프로텍트 창이 뜨면 [앱 검사] → [설치]를 눌러 주세요.\n설치가 끝나면 앱이 닫힙니다. 다시 열어 주세요."
        }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(status)
            addView(bar)
            addView(note)
        }
        val dlg = AlertDialog.Builder(act).setTitle("Hoffice ${r.version} 업데이트").setView(box).setCancelable(false).show()
        act.lifecycleScope.launch {
            val err = Updater.install(act, r) { pct ->
                bar.progress = pct
                status.text = if (pct >= 100) "설치 준비 중…" else "내려받는 중… $pct%"
            }
            busy = false
            if (err == null) {
                status.text = "설치 화면으로 넘깁니다…"
                dlg.setCancelable(true) // Play Protect "설치 안함" leaves the app running: let the box go
            } else {
                dlg.dismiss()
                AlertDialog.Builder(act).setTitle("업데이트하지 못했습니다").setMessage(err)
                    .setPositiveButton("다시 시도") { _, _ -> start(r) }
                    .setNeutralButton("브라우저로 받기") { _, _ -> browser(r, false) }
                    .setNegativeButton("닫기", null).show()
            }
        }
    }

    private fun browser(r: Updater.Release, forced: Boolean) {
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
        if (forced) act.finish()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
