package com.shuangka.punch.core.engine

import com.shuangka.punch.data.PunchType

/**
 * 飞书打卡页的文字解析，自动通道和「手动打卡监听」共用一套，避免两边判定不一致。
 *
 * 页面结构（自上而下）：
 *   应上班 09:22  [弹性]
 *   已打卡 09:22
 *   应下班 18:22  [弹性]
 *   已打卡 18:22
 *
 * 关键点：靠「应上班 / 应下班」给后面的「已打卡」定位归属。
 * 只看「已打卡」+整页是否出现「下班」这种写法，会把上午的卡错记成下班。
 */
object PunchPageParser {

    private val timeRegex = Regex("(\\d{1,2}:\\d{2})")

    data class Marks(
        val inTime: String? = null,
        val outTime: String? = null
    )

    fun parse(texts: List<String>): Marks {
        var pending: PunchType? = null
        var inTime: String? = null
        var outTime: String? = null

        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue

            if (text.contains("应上班")) pending = PunchType.IN
            if (text.contains("应下班")) pending = PunchType.OUT

            if (!text.contains("已打卡")) continue
            val time = timeRegex.find(text)?.value ?: continue

            val type = when {
                text.contains("上班") -> PunchType.IN
                text.contains("下班") -> PunchType.OUT
                else -> pending
            }
            when (type) {
                PunchType.IN -> if (inTime == null) inTime = time
                PunchType.OUT -> if (outTime == null) outTime = time
                null -> Unit
            }
        }
        return Marks(inTime, outTime)
    }

    /** 节点树按顺序摊平成文字列表 */
    fun lines(root: UiNode): List<String> = root.visit().map { it.text }
}
