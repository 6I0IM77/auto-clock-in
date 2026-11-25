package com.shuangka.punch.core.feedback

/**
 * 把 09:22 这种时间转成「早上九点二十二分」再交给 TTS。
 * 直接念 09:22 很多引擎会读成「零九 冒号 二二」，这是最容易被听成错误的地方。
 */
object SpokenTime {

    private val digits = "零一二三四五六七八九"

    fun of(raw: String): String {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(raw) ?: return raw
        val h = m.groupValues[1].toIntOrNull() ?: return raw
        val mi = m.groupValues[2].toIntOrNull() ?: return raw
        if (h !in 0..23 || mi !in 0..59) return raw

        val period = when {
            h < 6 -> "凌晨"
            h < 9 -> "早上"
            h < 12 -> "上午"
            h < 13 -> "中午"
            h < 18 -> "下午"
            else -> "晚上"
        }
        val h12 = when {
            h == 0 || h == 12 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val minute = when {
            mi == 0 -> "整"
            mi == 30 -> "半"
            else -> cn(mi) + "分"
        }
        return "$period${cn(h12)}点$minute"
    }

    private fun cn(n: Int): String = when {
        n < 10 -> digits[n].toString()
        n < 20 -> "十" + if (n % 10 == 0) "" else digits[n % 10].toString()
        else -> digits[n / 10].toString() + "十" +
            if (n % 10 == 0) "" else digits[n % 10].toString()
    }
}
