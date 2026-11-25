package com.shuangka.punch.data

import android.content.Context
import android.content.SharedPreferences
import com.shuangka.punch.App
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** 一天的打卡记录 */
data class DayRecord(
    val date: LocalDate,
    val inTime: String? = null,
    val outTime: String? = null,
    val inManual: Boolean = false,
    val outManual: Boolean = false
) {
    /** 上下班两张卡都打了，才算这一整天完整出勤 */
    val complete: Boolean get() = inTime != null && outTime != null

    /** 至少打了一张 */
    val touched: Boolean get() = inTime != null || outTime != null
}

data class MonthStats(
    val complete: Int,
    val half: Int,
    val missing: Int,
    val rate: Int
)

/** 本周（周一 → 今天）的统计 */
data class WeekStats(
    /** 双卡齐全的天数 */
    val complete: Int,
    /** 周一 → 今天之间的工作日数量（应出勤） */
    val workdays: Int
)

object PunchStore {

    private const val PREF = "punch_store"

    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    private val p: SharedPreferences
        get() = sp ?: App.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE).also { sp = it }

    private fun key(date: LocalDate): String = "d_$date"

    fun get(ctx: Context, date: LocalDate): DayRecord? {
        val raw = p.getString(key(date), null) ?: return null
        return try {
            decode(date, JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun all(ctx: Context): Map<LocalDate, DayRecord> {
        val out = LinkedHashMap<LocalDate, DayRecord>()
        for ((k, v) in p.all) {
            if (!k.startsWith("d_") || v !is String) continue
            try {
                val date = LocalDate.parse(k.substring(2))
                out[date] = decode(date, JSONObject(v))
            } catch (e: Exception) {
                // 坏数据跳过
            }
        }
        return out
    }

    @Synchronized
    fun record(ctx: Context, date: LocalDate, type: PunchType, time: String, manual: Boolean): DayRecord {
        val old = get(ctx, date) ?: DayRecord(date)
        val updated = when (type) {
            PunchType.IN -> old.copy(inTime = time, inManual = manual)
            PunchType.OUT -> old.copy(outTime = time, outManual = manual)
        }
        p.edit().putString(key(date), encode(updated)).apply()
        return updated
    }

    @Synchronized
    fun remove(ctx: Context, date: LocalDate) {
        p.edit().remove(key(date)).apply()
    }

    /**
     * 月度统计：双卡完成 / 只打一卡 / 缺卡 / 双卡率
     *
     * 今天这张卡要等「下班打卡窗口关门」之后才有结论：
     * 窗口没关之前，今天既不算缺卡、也不算只打一卡（刚打完上班卡就报缺卡会吓人）。
     */
    fun stats(ctx: Context, month: YearMonth, today: LocalDate = LocalDate.now()): MonthStats {
        var complete = 0
        var half = 0
        var missing = 0
        val todayOutClosed = isOutWindowClosed(ctx, today)
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            if (date.isAfter(today)) continue
            val rec = get(ctx, date)
            val settled = date != today || todayOutClosed
            when {
                rec != null && rec.complete -> complete++
                rec == null && HolidayCalendar.isWorkday(date) && settled -> missing++
                rec != null && rec.touched && settled -> half++
            }
        }
        val total = complete + half + missing
        val rate = if (total == 0) 0 else (complete * 100.0 / total).toInt()
        return MonthStats(complete, half, missing, rate)
    }

    /** 今天（或指定日）的下班打卡窗口是否已经关门 */
    fun isOutWindowClosed(ctx: Context, date: LocalDate): Boolean {
        if (!date.isEqual(LocalDate.now())) return true
        val window = PunchRules.outWindow(get(ctx, date))
        return !LocalTime.now().isBefore(window.end)
    }

    /** 本周统计：周一 → 今天，双卡齐全的天数 / 应出勤的工作日数 */
    fun weekStats(ctx: Context, today: LocalDate = LocalDate.now()): WeekStats {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        var complete = 0
        var workdays = 0
        var day = monday
        while (!day.isAfter(today)) {
            if (HolidayCalendar.isWorkday(day)) {
                workdays++
                if (get(ctx, day)?.complete == true) complete++
            }
            day = day.plusDays(1)
        }
        return WeekStats(complete, workdays)
    }

    private fun encode(r: DayRecord): String {
        val o = JSONObject()
        r.inTime?.let {
            o.put("in", it)
            o.put("inM", r.inManual)
        }
        r.outTime?.let {
            o.put("out", it)
            o.put("outM", r.outManual)
        }
        return o.toString()
    }

    private fun decode(date: LocalDate, o: JSONObject): DayRecord = DayRecord(
        date = date,
        inTime = if (o.has("in")) o.getString("in") else null,
        outTime = if (o.has("out")) o.getString("out") else null,
        inManual = o.optBoolean("inM", false),
        outManual = o.optBoolean("outM", false)
    )
}
