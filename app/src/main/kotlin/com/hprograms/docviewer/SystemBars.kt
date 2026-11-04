package com.hprograms.docviewer

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The Play build targets Android 16, where every screen is drawn under the status and navigation bars.
 * Pad the action bar and content back out of them. The APK build (older target) never needs this.
 */
fun AppCompatActivity.keepClearOfSystemBars() {
    if (!BuildConfig.STORE) return
    // The decor's only child holds both the action bar and the content.
    val root: View = (window.decorView as android.view.ViewGroup).getChildAt(0) ?: return
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        // Pass zeros on rather than consuming: the action bar layout still needs the call to place the content below it.
        WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.NONE)
            .build()
    }
    ViewCompat.requestApplyInsets(root)
    // Under Android 16 the action bar no longer pushes the content down; do it here (measured 2026-11-04).
    if (supportActionBar != null) {
        val tv = android.util.TypedValue()
        if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) {
            val h = android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
            findViewById<View>(android.R.id.content)?.setPadding(0, h, 0, 0)
        }
    }
}
