package com.smsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeExtractorTest {

    private fun check(expected: String?, body: String) = assertEquals(body, expected, CodeExtractor.extract(body))

    @Test
    fun chinese() {
        check("384756", "【某平台】您的验证码是384756，5分钟内有效，请勿泄露。")
        check("8848", "【银行】校验码：8848。如非本人操作请忽略。")
        check("12345678", "动态密码 12345678，任何人索取均为诈骗")
        check("482913", "482913（登录验证码），请在10分钟内完成验证")
        check("771204", "驗證碼為 771204，請勿告知他人")
    }

    @Test
    fun englishAndMalay() {
        check("493021", "Your verification code is 493021. Do not share it.")
        check("551234", "G-551234 is your Google verification code.")
        check("9021", "Use OTP 9021 to log in. Valid for 3 minutes.")
        check("660312", "RM0.00 Your TAC is 660312 for transfer. Never share TAC.")
        check("204817", "Kod pengesahan anda ialah 204817.")
        check("73190", "<#> Your code: 73190\nFA+9qCX9VSu")
    }

    @Test
    fun picksCodeNotDistractors() {
        check("384756", "2026年11月10日 您的验证码 384756，客服电话 4008123456")
        check("519273", "您尾号1234的卡正在支付1000.00元，验证码519273")
        check("519273", "Card ending in 4321: your code is 519273")
        check("2468", "订单 20261110 已创建，取件验证码 2468")
    }

    @Test
    fun noCode() {
        assertNull(CodeExtractor.extract("今晚 7 点吃饭，订了 2026 号房"))
        assertNull(CodeExtractor.extract("您的账户余额为 123456.78 元"))
        assertNull(CodeExtractor.extract("验证码已发送，请查收"))
        assertNull(CodeExtractor.extract("Your code is ABC123XY"))
        assertNull(CodeExtractor.extract("验证码相关咨询请致电 13800138000"))
    }

    @Test
    fun mask() {
        assertEquals("38••••", CodeExtractor.mask("384756"))
    }
}
