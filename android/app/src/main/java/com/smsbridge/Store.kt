package com.smsbridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 配置 + 最近事件日志，全部放在 SharedPreferences 里。日志不保存短信正文和完整验证码。 */
object Store {
    private const val PREFS = "sms_bridge"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_FINGERPRINT = "fingerprint"
    const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 50

    data class Config(val url: String, val token: String, val fingerprint: String)

    data class Event(
        val time: Long,
        val sender: String,
        /** 短信是否收到并解析成功（false 表示广播到了但读不出内容） */
        val smsOk: Boolean,
        /** 打码后的验证码；null = 未识别出验证码 */
        val maskedCode: String?,
        val bodyLength: Int,
        /** 发送结果描述，例如 “已送达 (200)” 或错误信息 */
        val sendResult: String,
        val sendOk: Boolean,
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(
            url = p.getString(KEY_URL, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: "",
            fingerprint = p.getString(KEY_FINGERPRINT, "") ?: "",
        )
    }

    fun saveConfig(ctx: Context, c: Config) {
        prefs(ctx).edit()
            .putString(KEY_URL, c.url.trim())
            .putString(KEY_TOKEN, c.token.trim())
            .putString(KEY_FINGERPRINT, c.fingerprint.trim())
            .apply()
    }

    @Synchronized
    fun addEvent(ctx: Context, e: Event) {
        val old = JSONArray(prefs(ctx).getString(KEY_EVENTS, "[]"))
        val out = JSONArray()
        out.put(
            JSONObject()
                .put("time", e.time)
                .put("sender", e.sender)
                .put("smsOk", e.smsOk)
                .put("code", e.maskedCode ?: JSONObject.NULL)
                .put("len", e.bodyLength)
                .put("result", e.sendResult)
                .put("sendOk", e.sendOk),
        )
        for (i in 0 until minOf(old.length(), MAX_EVENTS - 1)) out.put(old.get(i))
        // commit：广播接收器随时可能被回收，必须同步落盘
        prefs(ctx).edit().putString(KEY_EVENTS, out.toString()).commit()
    }

    fun events(ctx: Context): List<Event> {
        val arr = JSONArray(prefs(ctx).getString(KEY_EVENTS, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Event(
                time = o.getLong("time"),
                sender = o.getString("sender"),
                smsOk = o.getBoolean("smsOk"),
                maskedCode = if (o.isNull("code")) null else o.getString("code"),
                bodyLength = o.getInt("len"),
                sendResult = o.getString("result"),
                sendOk = o.getBoolean("sendOk"),
            )
        }
    }

    fun clearEvents(ctx: Context) {
        prefs(ctx).edit().remove(KEY_EVENTS).apply()
    }
}
