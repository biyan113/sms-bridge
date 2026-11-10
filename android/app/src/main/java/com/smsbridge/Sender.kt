package com.smsbridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 把一条事件 POST 到 Mac。
 *
 * 认证：Token 不随请求发送，而是用它对 “时间戳.正文” 做 HMAC-SHA256 签名，
 * Mac 端用同一个 Token 验签，并拒绝过期或重复的请求。
 *
 * 加密：地址是 https:// 且填了证书指纹时，只信任指纹匹配的那张（自签名）证书。
 */
object Sender {

    data class Result(val ok: Boolean, val message: String)

    fun send(config: Store.Config, sender: String, body: String, code: String?, receivedAt: Long, test: Boolean = false): Result {
        if (config.url.isBlank()) return Result(false, "未设置 Mac 地址")
        if (config.token.isBlank()) return Result(false, "未设置 Token")

        val payload = JSONObject()
            .put("sender", sender)
            .put("body", body)
            .put("code", code ?: JSONObject.NULL)
            .put("receivedAt", receivedAt)
            .put("test", test)
            .toString()
            .toByteArray(Charsets.UTF_8)

        return try {
            val url = URL(normalize(config.url))
            val conn = url.openConnection() as HttpURLConnection
            try {
                if (conn is HttpsURLConnection && config.fingerprint.isNotBlank()) pin(conn, config.fingerprint)
                val ts = System.currentTimeMillis().toString()
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(payload.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Timestamp", ts)
                conn.setRequestProperty("X-Signature", sign(config.token, ts, payload))
                conn.outputStream.use { it.write(payload) }
                when (val status = conn.responseCode) {
                    200 -> Result(true, "已送达 Mac")
                    401 -> Result(false, "Mac 拒绝：Token 不一致，或手机与 Mac 时间相差超过 5 分钟 (401)")
                    else -> Result(false, "Mac 返回 HTTP $status")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result(false, "发送失败：${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        }
    }

    /** 允许只填 “192.168.1.5:8765”，自动补全协议和路径。 */
    fun normalize(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (URL(u).path.let { it.isEmpty() || it == "/" }) u = u.trimEnd('/') + "/sms"
        return u
    }

    private fun sign(token: String, ts: String, payload: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$ts.".toByteArray(Charsets.UTF_8))
        return mac.doFinal(payload).toHex()
    }

    private fun pin(conn: HttpsURLConnection, fingerprint: String) {
        val expected = fingerprint.lowercase().filter { it in "0123456789abcdef" }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("unsupported")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).toHex()
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                    throw CertificateException("证书指纹不匹配")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        conn.sslSocketFactory = ctx.socketFactory
        // 身份已由指纹锁定，自签名证书里的主机名不必与局域网 IP 一致
        conn.setHostnameVerifier { _, _ -> true }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
