package com.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlin.concurrent.thread

/** 只处理系统实时推送的新短信广播，不读取短信数据库里的历史短信。 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val now = System.currentTimeMillis()

        // 第 1 步：短信收取。失败与“验证码识别失败”分开记录。
        val sender: String
        val body: String
        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (parts.isNullOrEmpty()) throw IllegalStateException("广播里没有短信内容")
            sender = parts[0].displayOriginatingAddress ?: "未知"
            body = parts.joinToString("") { it.displayMessageBody ?: "" } // 长短信会被拆成多段
        } catch (e: Exception) {
            Store.addEvent(
                context,
                Store.Event(now, "未知", smsOk = false, maskedCode = null, bodyLength = 0,
                    sendResult = "短信解析失败：${e.javaClass.simpleName}: ${e.message}", sendOk = false),
            )
            return
        }

        // 第 2 步：验证码识别（识别不到也照样转发，方便用普通短信测试链路）
        val code = CodeExtractor.extract(body)

        // 第 3 步：发送。网络不能放在主线程，goAsync 给后台线程约 10 秒时间。
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "sms-bridge-send") {
            try {
                val result = Sender.send(Store.config(appContext), sender, body, code, now)
                Store.addEvent(
                    appContext,
                    Store.Event(now, sender, smsOk = true, maskedCode = code?.let(CodeExtractor::mask),
                        bodyLength = body.length, sendResult = result.message, sendOk = result.ok),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
