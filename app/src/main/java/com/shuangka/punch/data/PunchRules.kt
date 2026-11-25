package com.shuangka.punch.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一个可打卡的时间段 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {

    fun contains(t: LocalTime): Boolean = !t.isBefore(start) && !t.isAfter(end)

    fun text(): String = "${fmt(start)}-${fmt(end)}"

    companion object {
        fun fmt(t: LocalTime): String = String.format(Locale.CHINA, "%02d:%02d", t.hour, t.minute)
    }
}

/** 此刻打卡页该显示什么 */
data class PunchNow(
    /** 现在可以打哪张卡；null = 不能打（不在时间窗口/今天已完成/休息日） */
    val type: PunchType?,
    /** 按钮文字：上班打卡 / 下班打卡 / 不在打卡时间内 */
    val buttonText: String,
    /** 大按钮下面那行说明 */
    val hint: String,
    /** 能不能点 */
    val canPunch: Boolean,
    /** 不在打卡时间内时的倒计时，例如「距上班打卡还有 12 分钟」 */
    val countdown: String? = null
)

/**
 * 打卡规则：上班窗口固定 09:00-10:00；下班窗口按「今天上班晚了多少分钟」整体顺延
 * （09:22 打上班卡 → 今天下班 18:22-19:00 才开窗口，截止仍 19:00）。
 */
object PunchRules {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 是否上班日：按国家法定节假日 + 调休安排判定
     * （节假日落在工作日算休息，调休落在周末算上班），见 [HolidayCalendar]
     */
    fun isWorkday(date: LocalDate): Boolean = HolidayCalendar.isWorkday(date)

    /** 上班窗口（固定 09:00-10:00，可在设置里调） */
    fun inWindow(): TimeWindow = TimeWindow(
        minutesToTime(PunchConfig.startMinutes(PunchType.IN)),
        minutesToTime(PunchConfig.endMinutes(PunchType.IN))
    )

    /**
     * 下班窗口：开始时间按「今天上班晚了多少」顺延，截止时间用设置里自己配的结束时间（不顺延）。
     * 例如上班 09:00-10:00、实际 09:22 打上、下班设 18:00-19:00 → 今天下班窗口 18:22-19:00。
     * 顺延量最多等于上班窗口本身的长度，且开始时间不会被推到截止之后。
     */
    fun outWindow(record: DayRecord? = null): TimeWindow {
        val delay = elasticDelayMinutes(record)
        val start = PunchConfig.startMinutes(PunchType.OUT)
        val end = PunchConfig.endMinutes(PunchType.OUT)
        val shiftedStart = (start + delay).coerceAtMost(end)
        return TimeWindow(minutesToTime(shiftedStart), minutesToTime(end))
    }

    /** 「应下班」时间 = 今天实际的下班窗口开始时间（顺延后） */
    fun expectOutText(record: DayRecord? = null): String = TimeWindow.fmt(outWindow(record).start)

    /** 今天的顺延分钟数：上班实际打卡时间 - 上班窗口开始时间，夹在 0..上班窗口长度之间 */
    fun elasticDelayMinutes(record: DayRecord? = null): Int {
        val inMinutes = parseMinutes(record?.inTime ?: return 0) ?: return 0
        val start = PunchConfig.startMinutes(PunchType.IN)
        val span = (PunchConfig.endMinutes(PunchType.IN) - start).coerceAtLeast(0)
        return (inMinutes - start).coerceIn(0, span)
    }

    /** 这一刻该打哪张卡 */
    fun now(now: LocalDateTime = LocalDateTime.now(), record: DayRecord? = null): PunchNow {
        val date = now.toLocalDate()
        val t = now.toLocalTime()

        if (!isWorkday(date)) {
            return PunchNow(
                null, "今天休息",
                "${HolidayCalendar.describe(date)}，不用打卡", false
            )
        }
        if (record?.inTime != null && record.outTime != null) {
            return PunchNow(
                null, "今天已完成",
                "今天两卡都打上了 ✔", false
            )
        }

        val inW = inWindow()
        val outW = outWindow(record)

        if (record?.inTime == null && inW.contains(t)) {
            return PunchNow(
                PunchType.IN, "上班打卡",
                "现在在上班打卡时间内（${inW.text()}），点一下就走飞书", true
            )
        }
        if (record?.outTime == null && outW.contains(t)) {
            val delay = elasticDelayMinutes(record)
            val hint = if (delay > 0) {
                "现在在下班打卡时间内（${outW.text()}），弹性顺延 $delay 分钟"
            } else {
                "现在在下班打卡时间内（${outW.text()}）"
            }
            return PunchNow(PunchType.OUT, "下班打卡", hint, true)
        }

        // 不在窗口：给出下一段说明
        val hint = when {
            record?.inTime == null && t.isBefore(inW.start) ->
                "不在打卡时间内，上班卡 ${inW.text()} 开始"
            record?.inTime == null ->
                "上班卡已过 ${inW.end.format(HM)}，下班卡 ${outW.text()} 开始"
            record?.outTime == null && t.isBefore(outW.start) ->
                "不在打卡时间内，下班卡 ${outW.text()} 开始"
            record?.outTime == null ->
                "下班卡已过 ${outW.end.format(HM)}，今天差一张下班卡"
            else -> "不在打卡时间内"
        }
        return PunchNow(null, "不在打卡时间内", hint, false, countdownText(date, t, record, inW, outW))
    }

    /**
     * 倒计时文案：上班卡还没打就数到今天的上班窗口开始，已经打上就数到下班窗口开始；
     * 今天的窗口已经过去就顺到下一个工作日。
     */
    private fun countdownText(
        date: LocalDate,
        now: LocalTime,
        record: DayRecord?,
        inW: TimeWindow,
        outW: TimeWindow
    ): String {
        val needIn = record?.inTime == null
        val target = if (needIn) inW.start else outW.start
        val todayReached = if (needIn) !now.isBefore(inW.start) else !now.isBefore(outW.start)
        var day = date
        if (todayReached) {
            day = day.plusDays(1)
            var guard = 0
            while (!isWorkday(day) && guard < 30) {
                day = day.plusDays(1)
                guard++
            }
        }
        val targetAt = LocalDateTime.of(day, target)
        val minutes = java.time.Duration.between(LocalDateTime.of(date, now), targetAt).toMinutes()
        val label = if (needIn) "上班" else "下班"
        return "距${label}打卡还有 ${humanMinutes(minutes)}"
    }

    private fun humanMinutes(minutes: Long): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m 分钟"
            m < 24 * 60 -> {
                val h = m / 60
                val rest = m % 60
                if (rest == 0L) "$h 小时" else "$h 小时 $rest 分钟"
            }
            else -> {
                val d = m / (24 * 60)
                val h = (m % (24 * 60)) / 60
                if (h == 0L) "$d 天" else "$d 天 $h 小时"
            }
        }
    }

    fun parseMinutes(text: String): Int? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(text) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    private fun minutesToTime(minutes: Int): LocalTime {
        val m = ((minutes % 1440) + 1440) % 1440
        return LocalTime.of(m / 60, m % 60)
    }
}
