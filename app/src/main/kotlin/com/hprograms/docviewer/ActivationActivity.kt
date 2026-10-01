package com.hprograms.docviewer

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown until this phone has a licence: ask once, wait for the owner's
 * approval, then return RESULT_OK to the screen that sent us here.
 */
class ActivationActivity : AppCompatActivity() {

    companion object {
        /**
         * For screens that need a licence: register this as a field, and launch it
         * when [License.valid] is false. [onReady] runs once licensed; without
         * approval the screen closes.
         * The caller stays open underneath, so a one-time read grant on a file
         * handed in from another app is not lost while the owner approves.
         */
        fun gate(from: AppCompatActivity, onReady: () -> Unit) =
            from.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
                if (License.valid(from)) onReady() else from.finish()
            }
    }

    private lateinit var name: EditText
    private lateinit var ask: Button
    private lateinit var state: TextView
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Hoffice 사용 신청"
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = "처음 한 번만 사용 승인을 받으면, 그다음부터는 인터넷 없이 쓸 수 있습니다."
            textSize = 16f
            setTextColor(0xFF333333.toInt())
        })
        name = EditText(this).apply {
            hint = "이름"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setSingleLine()
            setText(License.lastName(this@ActivationActivity))
        }
        box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        ask = Button(this).apply {
            text = "사용 신청"
            textSize = 16f
            setOnClickListener { sendRequest() }
        }
        box.addView(ask, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        state = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        box.addView(state)

        box.addView(TextView(this).apply {
            text = "기기 번호  ${License.deviceLabel(this@ActivationActivity)}"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(24), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "인터넷이 안 되면 기기 번호를 관리자에게 보내고, 받은 열쇠를 넣으세요."
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "기기 번호 보내기"
            setOnClickListener { shareDevice() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "열쇠 직접 넣기"
            setOnClickListener { enterKey() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        setContentView(ScrollView(this).apply { addView(box) })
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
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = "이름을 넣어 주세요"
            return
        }
        ask.isEnabled = false
        state.text = "신청하는 중…"
        lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { License.request(this@ActivationActivity, n) } }
            ask.isEnabled = true
            a.onSuccess { show(it); startPolling() }
                .onFailure {
                    android.util.Log.w("License", "request failed", it)
                    state.text = "서버에 연결하지 못했습니다.\n인터넷을 확인하거나, 아래 기기 번호를 관리자에게 보내 주세요.\n(${it.javaClass.simpleName})"
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
                    done()
                    return true
                }
                state.text = "받은 열쇠가 이 폰과 맞지 않습니다. 관리자에게 문의해 주세요."
                return true
            }
            "pending" -> state.text = "승인을 기다리는 중입니다…\n승인되면 저절로 열립니다."
            // Keep asking while this screen is open: the owner may still change his mind.
            "rejected" -> state.text = "승인되지 않았습니다."
            "revoked" -> state.text = "사용이 중지된 기기입니다. 관리자에게 문의해 주세요."
            else -> { state.text = ""; return true }
        }
        return false
    }

    private fun done() {
        Toast.makeText(this, "승인되었습니다", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun shareDevice() {
        val text = "Hoffice 사용 신청\n이름: ${name.text.toString().trim()}\n기기 번호: ${License.deviceLabel(this)}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "기기 번호 보내기"))
    }

    private fun enterKey() {
        val input = EditText(this).apply { hint = "받은 열쇠를 붙여 넣으세요"; minLines = 3 }
        AlertDialog.Builder(this)
            .setTitle("열쇠 직접 넣기")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                if (License.save(this, input.text.toString())) done()
                else Toast.makeText(this, "이 폰에 맞는 열쇠가 아닙니다", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
