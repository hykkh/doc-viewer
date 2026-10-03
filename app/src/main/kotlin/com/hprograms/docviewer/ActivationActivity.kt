package com.hprograms.docviewer

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hprograms.docviewer.R

/**
 * Shown until this phone has a licence: ask once, wait for the owner's
 * approval, then return RESULT_OK to the screen that sent us here.
 *
 * Copied from h-license/android-template by apply.py; edit it there, not here.
 */
class ActivationActivity : AppCompatActivity() {

    companion object {
        private val PRIMARY = androidx.appcompat.R.attr.colorPrimary
        private val ERROR = androidx.appcompat.R.attr.colorError
        private val MUTED = com.google.android.material.R.attr.colorOnSurfaceVariant

        /**
         * For screens that need a licence: register this as a field, and launch it
         * when [License.valid] is false. [onReady] runs once licensed; otherwise
         * [onDenied] runs (default: the screen closes).
         * The caller stays open underneath, so a one-time read grant on a file
         * handed in from another app is not lost while the owner approves.
         */
        fun gate(
            from: ComponentActivity,
            onDenied: () -> Unit = { from.finish() },
            onReady: () -> Unit,
        ) = from.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (License.valid(from)) onReady() else onDenied()
        }
    }

    private lateinit var nameLayout: TextInputLayout
    private lateinit var name: TextInputEditText
    private lateinit var ask: MaterialButton
    private lateinit var statusCard: View
    private lateinit var statusIcon: ImageView
    private lateinit var statusLine1: TextView
    private lateinit var statusLine2: TextView
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)
        findViewById<TextView>(R.id.activation_title).text = "${getString(R.string.app_name)} 사용 신청"
        nameLayout = findViewById(R.id.activation_name_layout)
        name = findViewById(R.id.activation_name)
        ask = findViewById(R.id.activation_ask)
        statusCard = findViewById(R.id.activation_status)
        statusIcon = findViewById(R.id.activation_status_icon)
        statusLine1 = findViewById(R.id.activation_status_line1)
        statusLine2 = findViewById(R.id.activation_status_line2)

        name.setText(License.lastName(this))
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { nameLayout.error = null }
        })
        ask.setOnClickListener { sendRequest() }
        findViewById<TextView>(R.id.activation_device).text = License.deviceLabel(this)
        findViewById<View>(R.id.activation_share).setOnClickListener { shareDevice() }
        findViewById<View>(R.id.activation_key).setOnClickListener { enterKey() }
    }

    override fun onResume() {
        super.onResume()
        // Asked before (or the app was closed while waiting): keep waiting.
        if (License.wasRequested(this)) startPolling()
    }

    override fun onPause() {
        super.onPause()
        polling?.cancel()
    }

    private fun sendRequest() {
        val n = name.text?.toString()?.trim().orEmpty()
        if (n.isEmpty()) {
            nameLayout.error = "이름을 넣어 주세요"
            return
        }
        ask.isEnabled = false
        render(R.drawable.ic_hourglass_24, PRIMARY, "신청하는 중…")
        lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { License.request(this@ActivationActivity, n) } }
            ask.isEnabled = true
            a.onSuccess { show(it); startPolling() }
                .onFailure {
                    android.util.Log.w("License", "request failed", it)
                    showOffline(it)
                }
        }
    }

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = lifecycleScope.launch {
            while (isActive) {
                val a = withContext(Dispatchers.IO) { runCatching { License.status(this@ActivationActivity) }.getOrNull() }
                if (a != null && show(a)) return@launch
                delay(4000)
            }
        }
    }

    /** Updates the screen; true when there is nothing more to wait for. */
    private fun show(a: License.Answer): Boolean {
        when (a.state) {
            "approved" -> {
                if (a.license != null && License.save(this, a.license)) {
                    render(R.drawable.ic_check_circle_24, PRIMARY, "승인 완료")
                    done()
                    return true
                }
                render(R.drawable.ic_block_24, ERROR, "받은 열쇠가 이 폰과 맞지 않습니다", "관리자에게 문의해 주세요")
                return true
            }
            "pending" -> render(R.drawable.ic_hourglass_24, PRIMARY, "승인 대기 중", "승인되면 자동으로 열립니다")
            // Keep asking while this screen is open: the owner may still change his mind.
            "rejected" -> render(R.drawable.ic_block_24, ERROR, "승인되지 않았습니다", "관리자가 다시 허용하면 자동으로 열립니다")
            "revoked" -> render(R.drawable.ic_block_24, ERROR, "사용이 중지된 기기", "관리자에게 문의해 주세요")
            "closed" -> { render(R.drawable.ic_block_24, MUTED, "신청을 받지 않는 중", "관리자에게 문의해 주세요"); return true }
            else -> { statusCard.visibility = View.GONE; return true }
        }
        return false
    }

    private fun showOffline(cause: Throwable) = render(
        R.drawable.ic_cloud_off_24, ERROR, "서버에 연결하지 못했습니다",
        "인터넷을 확인하거나, 아래 기기 번호를 관리자에게 보내 주세요. (${cause.javaClass.simpleName})",
    )

    private fun render(icon: Int, tintAttr: Int, line1: String, line2: String = "") {
        statusCard.visibility = View.VISIBLE
        statusIcon.setImageResource(icon)
        statusIcon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(statusIcon, tintAttr))
        statusLine1.text = line1
        statusLine2.text = line2
        statusLine2.visibility = if (line2.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun done() {
        Toast.makeText(this, "승인되었습니다", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun shareDevice() {
        val text = "${getString(R.string.app_name)} 사용 신청\n이름: ${name.text?.toString()?.trim().orEmpty()}\n기기 번호: ${License.deviceLabel(this)}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "기기 번호 보내기"))
    }

    private fun enterKey() {
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "받은 열쇠를 붙여 넣으세요"
        }
        val input = TextInputEditText(field.context).apply { minLines = 3; gravity = Gravity.TOP }
        field.addView(input)
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("열쇠 직접 넣기")
            .setView(box)
            .setPositiveButton("확인") { _, _ ->
                if (License.save(this, input.text?.toString().orEmpty())) done()
                else Toast.makeText(this, "이 폰에 맞는 열쇠가 아닙니다", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
