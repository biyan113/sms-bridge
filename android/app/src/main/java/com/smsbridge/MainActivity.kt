package com.smsbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var fingerprintInput: EditText
    private lateinit var testResult: TextView
    private lateinit var ticketTime: TextView
    private lateinit var ticketCode: TextView
    private lateinit var ticketMeta: TextView
    private lateinit var chipSms: TextView
    private lateinit var chipCode: TextView
    private lateinit var chipSend: TextView
    private lateinit var clearButton: Button
    private lateinit var earlierTitle: TextView
    private lateinit var earlierList: LinearLayout

    /** 用户至少点过一次“拒绝”之后才可能出现“不再询问”，此时只能去系统设置里开 */
    private var askedOnce = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_EVENTS) runOnUiThread { renderLog() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionText = findViewById(R.id.permissionText)
        permissionButton = findViewById(R.id.permissionButton)
        urlInput = findViewById(R.id.urlInput)
        tokenInput = findViewById(R.id.tokenInput)
        fingerprintInput = findViewById(R.id.fingerprintInput)
        testResult = findViewById(R.id.testResult)
        ticketTime = findViewById(R.id.ticketTime)
        ticketCode = findViewById(R.id.ticketCode)
        ticketMeta = findViewById(R.id.ticketMeta)
        chipSms = findViewById(R.id.chipSms)
        chipCode = findViewById(R.id.chipCode)
        chipSend = findViewById(R.id.chipSend)
        clearButton = findViewById(R.id.clearButton)
        earlierTitle = findViewById(R.id.earlierTitle)
        earlierList = findViewById(R.id.earlierList)

        val ticket = findViewById<View>(R.id.ticket)
        ticket.clipToOutline = true
        ticket.outlineProvider = ViewOutlineProvider.BACKGROUND

        listOf(permissionButton, findViewById<Button>(R.id.saveButton), findViewById<Button>(R.id.testButton)).forEach {
            it.backgroundTintList = null
        }

        permissionButton.setOnClickListener { requestSmsPermission() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener { sendTest() }
        clearButton.setOnClickListener {
            Store.clearEvents(this)
            renderLog()
        }

        val toggle = findViewById<Button>(R.id.settingsToggle)
        val panel = findViewById<View>(R.id.settingsPanel)
        toggle.setOnClickListener {
            val expanded = panel.visibility != View.VISIBLE
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = if (expanded) "连接设置　－" else "连接设置　＋"
        }
        val c = Store.config(this)
        if (c.url.isBlank()) {
            panel.visibility = View.VISIBLE
            toggle.text = "连接设置　－"
        }
        updateConnectionSummary()
        urlInput.setText(c.url)
        tokenInput.setText(c.token)
        fingerprintInput.setText(c.fingerprint)
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        renderPermission()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun renderPermission() {
        if (hasSmsPermission()) {
            permissionText.text = "●  短信接收已就绪"
            permissionText.setTextColor(getColor(R.color.ok_fg))
            permissionText.background = null
            permissionText.setPadding(0, 0, 0, 0)
            permissionButton.visibility = View.GONE
        } else {
            permissionText.text = "还不能收短信。若系统不弹窗：设置 → 应用 → SMS Bridge → 允许受限制的设置。"
            permissionText.setTextColor(getColor(R.color.bad_fg))
            permissionText.setBackgroundResource(R.drawable.bg_banner)
            val p = dp(12)
            permissionText.setPadding(p, p, p, p)
            permissionButton.visibility = View.VISIBLE
        }
    }

    private fun requestSmsPermission() {
        val blocked = askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)
        if (blocked) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        askedOnce = true
        renderPermission()
    }

    private fun currentConfig() = Store.Config(
        url = urlInput.text.toString(),
        token = tokenInput.text.toString(),
        fingerprint = fingerprintInput.text.toString(),
    )

    private fun updateConnectionSummary() {
        val url = Store.config(this).url
        findViewById<TextView>(R.id.connectionSummary).text =
            if (url.isBlank()) "添加你的 Mac，开启短信同步" else "目标 Mac · ${Uri.parse(url).host ?: url}"
    }

    private fun save() {
        Store.saveConfig(this, currentConfig())
        updateConnectionSummary()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun sendTest() {
        save()
        testResult.visibility = View.VISIBLE
        testResult.text = "正在连接 Mac…"
        testResult.setTextColor(getColor(R.color.muted))
        val config = Store.config(this)
        thread {
            val r = Sender.send(
                config, sender = "SMS Bridge 测试", body = "【SMS Bridge】连接测试，验证码 123456",
                code = "123456", receivedAt = System.currentTimeMillis(), test = true,
            )
            runOnUiThread {
                testResult.text = if (r.ok) "已送到 Mac。通知和剪贴板应是 123456。" else r.message
                testResult.setTextColor(getColor(if (r.ok) R.color.ok_fg else R.color.bad_fg))
            }
        }
    }

    private fun renderLog() {
        val events = Store.events(this)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        clearButton.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE

        val latest = events.firstOrNull()
        if (latest == null) {
            ticketTime.text = "你的下一条验证码，会在这里"
            ticketCode.text = "等待收码"
            ticketMeta.text = "收到短信后，将自动识别并同步到 Mac。"
            paintChip(chipSms, "未收到", Tone.IDLE)
            paintChip(chipCode, "未识别", Tone.IDLE)
            paintChip(chipSend, "未发送", Tone.IDLE)
        } else {
            ticketTime.text = fmt.format(Date(latest.time))
            ticketCode.text = when {
                !latest.smsOk -> "没收下"
                latest.maskedCode != null -> latest.maskedCode
                else -> "未识别"
            }
            val from = displaySender(latest.sender)
            ticketMeta.text = if (latest.smsOk) "来自 $from · ${latest.bodyLength} 字" else "来自 $from"
            paintChip(chipSms, if (latest.smsOk) "已收到" else "没收下", if (latest.smsOk) Tone.OK else Tone.BAD)
            paintChip(
                chipCode,
                when {
                    !latest.smsOk -> "未识别"
                    latest.maskedCode != null -> "已识别"
                    else -> "不像验证码"
                },
                when {
                    !latest.smsOk -> Tone.IDLE
                    latest.maskedCode != null -> Tone.OK
                    else -> Tone.WARN
                },
            )
            paintChip(
                chipSend,
                if (latest.sendOk) "已送达" else "没送到",
                if (latest.sendOk) Tone.OK else Tone.BAD,
            )
        }

        val older = events.drop(1)
        earlierTitle.visibility = if (older.isEmpty()) View.GONE else View.VISIBLE
        earlierList.removeAllViews()
        older.forEach { e ->
            val row = layoutInflater.inflate(R.layout.item_event, earlierList, false)
            row.findViewById<TextView>(R.id.rowTime).text = fmt.format(Date(e.time))
            val code = e.maskedCode ?: if (e.smsOk) "未识别" else "—"
            val send = if (e.sendOk) "已送达" else e.sendResult
            row.findViewById<TextView>(R.id.rowLine).text = "${displaySender(e.sender)} · $code · $send"
            earlierList.addView(row)
        }
    }

    private fun displaySender(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "未知号码"
        if (s.length <= 8) return s
        return s.take(3) + "…" + s.takeLast(4)
    }

    private enum class Tone { OK, BAD, WARN, IDLE }

    private fun paintChip(view: TextView, text: String, tone: Tone) {
        view.text = text
        val (bg, fg) = when (tone) {
            Tone.OK -> R.color.ok_bg to R.color.ok_fg
            Tone.BAD -> R.color.bad_bg to R.color.bad_fg
            Tone.WARN -> R.color.warn_bg to R.color.warn_fg
            Tone.IDLE -> R.color.idle_bg to R.color.idle_fg
        }
        view.setTextColor(getColor(fg))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            setColor(getColor(bg))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
