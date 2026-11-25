package com.shuangka.punch.core

import com.shuangka.punch.data.DayRecord
import com.shuangka.punch.data.HolidayCalendar
import com.shuangka.punch.data.PunchNow
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchType
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 通知栏「主提示」的文案：标题说清是哪种情形，正文用规则算出来的那句说明。
 * 和测试页里的场景一一对应（上班窗口还没到 / 上班卡漏了 / 下班卡漏了 / 休息日 / 今天已完成）。
 */
object PunchStatusText {

    data class Status(val type: PunchType, val title: String, val text: String)

    fun of(now: PunchNow, at: LocalDateTime, record: DayRecord?): Status {
        val date = at.toLocalDate()
        val t = at.toLocalTime()

        if (!PunchRules.isWorkday(date)) {
            val title = when {
                HolidayCalendar.holidayName(date) != null -> "今天是${HolidayCalendar.holidayName(date)}"
                HolidayCalendar.manualRest(date) -> "今天是你标的休息日"
                else -> "今天是周末"
            }
            val text = when {
                HolidayCalendar.holidayName(date) != null ->
                    "${HolidayCalendar.holidayName(date)}放假（按国务院节假日安排），今天不用打卡"
                HolidayCalendar.manualRest(date) -> "今天手动标成了休息日，不用打卡"
                else -> "周末休息，不用打卡"
            }
            return Status(PunchType.IN, title, text)
        }
        if (record?.inTime != null && record.outTime != null) {
            return Status(
                PunchType.OUT, "今天已完成",
                "上班 ${record.inTime}、下班 ${record.outTime} 都打上了"
            )
        }

        val inW = PunchRules.inWindow()
        val outW = PunchRules.outWindow(record)

        // 上班窗口已经过去、今天还没打上班卡
        if (record?.inTime == null && t.isAfter(inW.end)) {
            return Status(
                PunchType.IN, "上班卡漏打了",
                "上班卡 ${inW.text()} 已经过了，还没记录上"
            )
        }
        // 下班窗口已经过去、今天还差下班卡
        if (record?.inTime != null && record.outTime == null && t.isAfter(outW.end)) {
            return Status(
                PunchType.OUT, "下班卡漏打了",
                "下班卡 ${outW.text()} 已经过了，今天还差一张下班卡"
            )
        }
        if (record?.inTime == null && t.isBefore(inW.start)) {
            return Status(
                PunchType.IN, "还没到上班打卡时间",
                "上班卡 ${inW.text()} 开始，到点会再提醒你"
            )
        }
        if (record?.outTime == null && t.isBefore(outW.start)) {
            return Status(
                PunchType.OUT, "还没到下班打卡时间",
                "下班卡 ${outW.text()} 开始，上班卡已记录"
            )
        }
        return Status(typeFor(t), "不在打卡时间内", now.hint)
    }

    private fun typeFor(t: LocalTime): PunchType =
        if (t.hour >= 15) PunchType.OUT else PunchType.IN
}
