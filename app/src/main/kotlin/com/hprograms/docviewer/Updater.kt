package com.hprograms.docviewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app updates itself (pattern from htrain-app's Updater).
 *
 * The approval server (lic.hyt.kr/api/version) says which version is current,
 * where it is, and the oldest one still allowed (min_code). The app downloads
 * the new APK into a PackageInstaller session and commits it; from Android 12
 * a self-update skips the system "install?" screen, but Play Protect still
 * asks once per new APK ([앱 검사] → [설치]). Installing closes the app.
 */
object Updater {
    private const val SERVER = "https://lic.hyt.kr"
    private const val PREFS = "update"
    private const val CHECK_EVERY_MS = 3L * 3600 * 1000

    data class Release(val version: String, val code: Int, val minCode: Int, val url: String, val sizeMb: Int)

    fun current() = BuildConfig.VERSION_CODE

    /** Network; off the main thread. Null when the server cannot be reached. */
    fun fetch(ctx: Context): Release? = runCatching {
        // Saying who we are lets the owner's page show when this phone was last seen and on which version.
        val who = if (License.valid(ctx)) "&device=${License.deviceId(ctx)}&ver=${BuildConfig.VERSION_NAME}" else ""
        val c = URL("$SERVER/api/version?app=${License.APP}$who").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            Release(j.getString("version"), j.getInt("code"), j.optInt("min_code", 0), j.getString("url"), j.optInt("size_mb", 0))
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Asks at most every few hours; the last answer is kept so an offline start still knows about it. */
    fun checkIfDue(ctx: Context): Release? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("checked", 0) >= CHECK_EVERY_MS) {
            fetch(ctx)?.let { r ->
                p.edit().putLong("checked", System.currentTimeMillis()).putString("version", r.version)
                    .putInt("code", r.code).putInt("min", r.minCode).putString("url", r.url).putInt("size", r.sizeMb).apply()
            }
        }
        return known(ctx)
    }

    fun known(ctx: Context): Release? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString("url", null) ?: return null
        return Release(p.getString("version", "") ?: "", p.getInt("code", 0), p.getInt("min", 0), url, p.getInt("size", 0))
    }

    fun forgetCheck(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("checked").apply()

    fun isNewer(r: Release?) = r != null && r.code > current()

    /** This version is below the oldest one the owner still allows. */
    fun mustUpdate(r: Release?) = r != null && current() < r.minCode

    fun onWifi(ctx: Context): Boolean = runCatching {
        !ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
    }

    /** Leftover sessions from a download the app was killed in the middle of hold the partial APK. */
    fun cleanStaleSessions(ctx: Context) {
        runCatching {
            val installer = ctx.packageManager.packageInstaller
            for (info in installer.mySessions) if (!info.isActive) runCatching { installer.abandonSession(info.sessionId) }
        }
    }

    /** Downloads and hands the APK to the installer. Returns an error message, or null when handed over. */
    suspend fun install(ctx: Context, r: Release, onProgress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        if (!canInstall(ctx)) return@withContext "설정에서 ‘이 출처의 앱 허용’을 먼저 켜 주세요"
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var id = -1
        try {
            id = installer.createSession(params)
            installer.openSession(id).use { session ->
                val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                }
                val total = conn.contentLengthLong.coerceAtLeast(1)
                var read = 0L
                var last = -1
                conn.inputStream.use { input ->
                    session.openWrite("hoffice", 0, -1).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != last) { last = pct; withContext(Dispatchers.Main) { onProgress(pct) } }
                        }
                        session.fsync(out)
                    }
                }
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
            null
        } catch (e: Exception) {
            if (id >= 0) runCatching { installer.abandonSession(id) }
            "내려받지 못했습니다: ${e.message?.take(80)}"
        }
    }
}

/** Brings the system / Play Protect screen forward, and says so when the install did not happen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(next) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(ctx, "업데이트를 취소했습니다 (Play 프로텍트 창에서는 [앱 검사] → [설치])", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(ctx, "업데이트를 설치하지 못했습니다: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
