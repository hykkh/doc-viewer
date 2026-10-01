package com.hprograms.docviewer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * One-time approval (server: C:\H-Programs\h-license, lic.hyt.kr).
 *
 * The owner approves a device once; the server signs "app|device|name|date"
 * with its private key. The app checks that signature against [PUBLIC_KEY]
 * itself, so after approval it works offline. Every [CHECK_EVERY_MS] it asks
 * the server when it can, and a device the owner revoked loses its licence.
 */
object License {
    const val APP = "hoffice"
    private const val SERVER = "https://lic.hyt.kr"
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEahQLjd3xELDZwC3HbDCS3iuwW2kVGAfe6V5GwBIK3P1yAqFj0ei1dtquNaENbuFf/Y3R37cMzksaC+E0CPgGoA=="
    private const val PREFS = "license"
    private const val CHECK_EVERY_MS = 7L * 24 * 3600 * 1000

    @Volatile private var cached: Pair<String, Boolean>? = null

    /** 16 hex digits, stable for this app on this phone (until a factory reset). */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "none"
        val h = MessageDigest.getInstance("SHA-256").digest("$APP:$androidId".toByteArray())
        return h.take(8).joinToString("") { "%02X".format(it) }
    }

    fun deviceLabel(ctx: Context) = deviceId(ctx).chunked(4).joinToString("-")

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun valid(ctx: Context): Boolean {
        val lic = prefs(ctx).getString("key", null) ?: return false
        cached?.let { if (it.first == lic) return it.second }
        val ok = verify(ctx, lic) != null
        cached = lic to ok
        return ok
    }

    /** The approved name, or null. */
    fun holder(ctx: Context): String? = prefs(ctx).getString("key", null)?.let { verify(ctx, it) }

    /** Checks [lic]; returns the holder's name when it is genuine and made for this phone. */
    private fun verify(ctx: Context, lic: String): String? = runCatching {
        val (p, s) = lic.trim().split('.').also { require(it.size == 2) }
        val payload = Base64.decode(p, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val sig = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload) }
        require(v.verify(sig))
        val parts = String(payload, Charsets.UTF_8).split('|')
        require(parts.size >= 3 && parts[0] == APP && parts[1] == deviceId(ctx))
        parts[2]
    }.getOrNull()

    /** Stores [lic] if it is genuine and for this phone. */
    fun save(ctx: Context, lic: String): Boolean {
        if (verify(ctx, lic) == null) return false
        prefs(ctx).edit().putString("key", lic.trim()).putLong("checked", System.currentTimeMillis()).apply()
        cached = null
        return true
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("key").apply()
        cached = null
    }

    fun wasRequested(ctx: Context) = prefs(ctx).getBoolean("requested", false)
    fun lastName(ctx: Context) = prefs(ctx).getString("name", "") ?: ""

    /** Server answer: state (pending / approved / rejected / revoked / unknown) and, when approved, the licence. */
    data class Answer(val state: String, val license: String?)

    /** Network; call off the main thread. */
    fun request(ctx: Context, name: String): Answer {
        prefs(ctx).edit().putBoolean("requested", true).putString("name", name).apply()
        val body = JSONObject().put("app", APP).put("device", deviceId(ctx)).put("name", name).put("model", model()).put("ver", BuildConfig.VERSION_NAME)
        return call("$SERVER/api/request", body.toString())
    }

    fun status(ctx: Context): Answer = call("$SERVER/api/status?app=$APP&device=${deviceId(ctx)}&ver=${BuildConfig.VERSION_NAME}", null)

    private fun call(url: String, post: String?): Answer {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("서버 응답 ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            return Answer(j.optString("state"), j.optString("license").ifEmpty { null })
        } finally {
            c.disconnect()
        }
    }

    /**
     * The periodic check: when due and the server answers, a revoked device
     * loses its licence (returns true). No network = nothing changes.
     */
    fun recheckIfDue(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!valid(ctx) || System.currentTimeMillis() - p.getLong("checked", 0) < CHECK_EVERY_MS) return false
        val a = runCatching { status(ctx) }.getOrNull() ?: return false
        p.edit().putLong("checked", System.currentTimeMillis()).apply()
        if (a.state == "revoked") {
            clear(ctx)
            return true
        }
        return false
    }
}
