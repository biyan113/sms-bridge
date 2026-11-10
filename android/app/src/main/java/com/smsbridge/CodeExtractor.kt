package com.smsbridge

/**
 * 从短信正文里提取 4~8 位数字验证码。纯 Kotlin，无 Android 依赖，可直接单元测试。
 *
 * 规则：
 * 1. 正文里必须出现验证码类关键词（中 / 英 / 马来文），否则一律视为“无验证码”，避免把金额、年份误判。
 * 2. 候选 = 独立的 4~8 位数字（前后不能紧贴数字或字母，也不能是小数的一部分）。
 * 3. 排除“尾号 1234”这类卡号尾数。
 * 4. 多个候选时取离关键词最近的；距离相同优先取关键词后面的。
 */
object CodeExtractor {

    private val KEYWORDS = Regex(
        "验证码|校验码|检验码|确认码|动态码|动态密码|激活码|授权码|验证代码|随机码|" +
            "驗證碼|校驗碼|確認碼|動態碼|動態密碼|" +
            "(?<![A-Za-z])(?:verification|verify|code|otp|tac|passcode|pin|password|kod|one[- ]time)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    private val CANDIDATE = Regex("(?<![0-9A-Za-z])(?<![0-9][.,])[0-9]{4,8}(?![0-9A-Za-z])(?![.,][0-9])")

    private val TAIL_PREFIX = Regex(
        "(?:尾号|尾號|末四位|末4位|后四位|後四位|ending(?: in| with)?|berakhir)\\s*[:：为是]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun extract(body: String): String? {
        val keywords = KEYWORDS.findAll(body).toList()
        if (keywords.isEmpty()) return null

        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(body)) {
            val before = body.substring(maxOf(0, m.range.first - 16), m.range.first)
            if (TAIL_PREFIX.containsMatchIn(before)) continue
            for (k in keywords) {
                val score = when {
                    m.range.first > k.range.last -> (m.range.first - k.range.last) * 2
                    else -> (k.range.first - m.range.last) * 2 + 1
                }
                if (score < bestScore) {
                    bestScore = score
                    best = m.value
                }
            }
        }
        return best
    }

    /** 日志里用的打码形式：只保留前两位。 */
    fun mask(code: String): String = code.take(2) + "•".repeat(code.length - 2)
}
