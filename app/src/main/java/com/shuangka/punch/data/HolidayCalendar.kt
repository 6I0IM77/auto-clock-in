package com.shuangka.punch.data

import java.time.LocalDate

/**
 * 工作日日历：严格按国务院办公厅公布的法定节假日 + 调休安排，
 * 所以「节假日在工作日」「调休在周末」都能正确判定。
 *
 * 内置：2025、2026、2027 三年的官方安排（国办发明电）。
 * 另外留了手动入口：设置页可以额外标记某天休息 / 某天上班，
 * 用来应对公司自有假期、地方性调休或官方后续调整。
 */
object HolidayCalendar {

    /** 放假日期 -> 节日名 */
    private val holidays: Map<LocalDate, String> = buildMap {
        // ---------- 2025 ----------
        putAll(range("元旦", "2025-01-01", "2025-01-01"))
        putAll(range("春节", "2025-01-28", "2025-02-04"))
        putAll(range("清明节", "2025-04-04", "2025-04-06"))
        putAll(range("劳动节", "2025-05-01", "2025-05-05"))
        putAll(range("端午节", "2025-05-31", "2025-06-02"))
        putAll(range("国庆节·中秋", "2025-10-01", "2025-10-08"))

        // ---------- 2026 ----------
        putAll(range("元旦", "2026-01-01", "2026-01-03"))
        putAll(range("春节", "2026-02-15", "2026-02-23"))
        putAll(range("清明节", "2026-04-04", "2026-04-06"))
        putAll(range("劳动节", "2026-05-01", "2026-05-05"))
        putAll(range("端午节", "2026-06-19", "2026-06-21"))
        putAll(range("中秋节", "2026-09-25", "2026-09-27"))
        putAll(range("国庆节", "2026-10-01", "2026-10-07"))

        // ---------- 2027 ----------
        putAll(range("元旦", "2027-01-01", "2027-01-03"))
        putAll(range("春节", "2027-02-05", "2027-02-13"))
        putAll(range("清明节", "2027-04-03", "2027-04-05"))
        putAll(range("劳动节", "2027-05-01", "2027-05-05"))
        putAll(range("端午节", "2027-06-09", "2027-06-09"))
        putAll(range("中秋节", "2027-09-15", "2027-09-15"))
        putAll(range("国庆节", "2027-10-01", "2027-10-07"))
    }

    /** 调休上班的周末 */
    private val makeupWorkdays: Set<LocalDate> = buildSet {
        // 2025
        addAll(dates("2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11"))
        // 2026
        addAll(dates("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"))
        // 2027
        addAll(dates("2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09"))
    }

    private val coveredYears = intArrayOf(2025, 2026, 2027)

    /** 官方日历覆盖到哪一年为止（内置表，联网数据另算） */
    fun coveredYearRange(): String = "${coveredYears.first()}–${coveredYears.last()}"

    fun coversYear(year: Int): Boolean =
        year in coveredYears || HolidayRemote.hasYear(year)

    /** 当天是不是法定节假日，返回节日名（含元旦/春节…），不是则 null */
    fun holidayName(date: LocalDate): String? {
        val remote = HolidayRemote.day(date)
        if (remote != null && remote.holiday) return normalize(remote.name)
        return holidays[date]
    }

    /** 联网日历把春节拆成「除夕 / 初一 / 初二…」，展示时统一成春节 */
    private fun normalize(name: String): String =
        if (name == "除夕" || name.startsWith("初")) "春节" else name

    /** 当天是不是调休出来的上班日（周末但上班） */
    fun isMakeupWorkday(date: LocalDate): Boolean {
        val remote = HolidayRemote.day(date)
        if (remote != null && !remote.holiday) return true
        if (remote != null && remote.holiday) return false
        return date in makeupWorkdays
    }

    /** 用户手动标记的休息日 */
    fun manualRest(date: LocalDate): Boolean = date in PunchConfig.extraRestDates()

    /** 用户手动标记的上班日 */
    fun manualWork(date: LocalDate): Boolean = date in PunchConfig.extraWorkDates()

    /**
     * 今天要不要上班：
     * 手动设置 > 联网日历 > 内置官方表 > 周一至周五
     */
    fun isWorkday(date: LocalDate): Boolean {
        if (manualWork(date)) return true
        if (manualRest(date)) return false
        // 联网日历：有这天的数据就以它为准
        HolidayRemote.day(date)?.let { return !it.holiday }
        if (holidayName(date) != null) return false
        if (date in makeupWorkdays) return true
        return date.dayOfWeek.value <= 5
    }

    /** 日历上给这一天打的标记，用于界面展示 */
    fun label(date: LocalDate): String? = when {
        manualWork(date) -> "手动上班"
        manualRest(date) -> "手动休息"
        HolidayRemote.day(date) != null -> HolidayRemote.day(date)!!.let {
            if (it.holiday) normalize(it.name) else "调休上班"
        }
        holidayName(date) != null -> holidayName(date)
        isMakeupWorkday(date) -> "调休上班"
        else -> null
    }

    /** 一句话说明今天为什么上班/休息 */
    fun describe(date: LocalDate): String {
        val remote = HolidayRemote.day(date)
        return when {
            manualWork(date) -> "手动标成了上班日"
            manualRest(date) -> "手动标成了休息日"
            remote != null && remote.holiday -> "${normalize(remote.name)}放假（按联网日历）"
            remote != null -> "${remote.target ?: remote.name}调休上班日，今天要打卡"
            holidayName(date) != null -> "${holidayName(date)}放假"
            isMakeupWorkday(date) -> "调休上班日，今天要打卡"
            !isWorkday(date) -> "周末休息"
            else -> "工作日"
        }
    }

    // ---------- 内部工具 ----------

    private fun range(name: String, from: String, to: String): Map<LocalDate, String> {
        val start = LocalDate.parse(from)
        val end = LocalDate.parse(to)
        val map = LinkedHashMap<LocalDate, String>()
        var d = start
        while (!d.isAfter(end)) {
            map[d] = name
            d = d.plusDays(1)
        }
        return map
    }

    private fun dates(vararg iso: String): List<LocalDate> = iso.map { LocalDate.parse(it) }
}
