package com.shuangka.punch.data

import android.content.Context
import android.content.SharedPreferences
import com.shuangka.punch.App
import java.time.LocalTime
import java.util.Locale

/**
 * 全局配置。所有能力都走 SharedPreferences，改动即时生效，不需要重启。
 */
object PunchConfig {

    private const val PREF = "punch_cfg"

    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    private val p: SharedPreferences
        get() = sp ?: App.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE).also { sp = it }

    private const val DEFAULT_IN_START = 9 * 60      // 09:00
    private const val DEFAULT_IN_END = 10 * 60       // 10:00
    private const val DEFAULT_OUT_START = 18 * 60    // 18:00
    private const val DEFAULT_OUT_END = 19 * 60      // 19:00

    // ---------- 时间窗口 ----------

    fun startMinutes(type: PunchType): Int = p.getInt(
        "start_${type.key}",
        if (type == PunchType.IN) DEFAULT_IN_START else DEFAULT_OUT_START
    )

    fun endMinutes(type: PunchType): Int = p.getInt(
        "end_${type.key}",
        if (type == PunchType.IN) DEFAULT_IN_END else DEFAULT_OUT_END
    )

    fun setWindow(type: PunchType, startMinutes: Int, endMinutes: Int) {
        p.edit()
            .putInt("start_${type.key}", startMinutes)
            .putInt("end_${type.key}", endMinutes)
            .apply()
    }

    fun minutesToText(minutes: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)

    fun windowLabel(type: PunchType): String =
        "${minutesToText(startMinutes(type))} - ${minutesToText(endMinutes(type))}"

    // ---------- 提醒推送时间 ----------

    /** 上班：窗口开始前多少分钟推提醒（0~60，默认 5） */
    fun remindBeforeIn(): Int = p.getInt("lead_in", 5).coerceIn(0, 60)

    fun setRemindBeforeIn(minutes: Int) = p.edit().putInt("lead_in", minutes.coerceIn(0, 60)).apply()

    /** 下班：窗口开启后多少分钟推提醒（0~60，默认 0 = 窗口一开就推） */
    fun remindAfterOut(): Int = p.getInt("lead_out", 0).coerceIn(0, 60)

    fun setRemindAfterOut(minutes: Int) = p.edit().putInt("lead_out", minutes.coerceIn(0, 60)).apply()

    fun remindInText(): String {
        val v = remindBeforeIn()
        return if (v == 0) "窗口开启时" else "提前 $v 分钟"
    }

    fun remindOutText(): String {
        val v = remindAfterOut()
        return if (v == 0) "窗口开启时" else "开启后 $v 分钟"
    }

    /** 当前时间是否落在该卡的打卡窗口内 */
    fun isInWindow(type: PunchType, now: LocalTime = LocalTime.now()): Boolean {
        val m = now.hour * 60 + now.minute
        return m >= startMinutes(type) && m <= endMinutes(type)
    }

    // ---------- 弹性工作制 ----------

    /** 弹性：上班晚到多少分钟，下班就顺延多少分钟（下班=上班时间+工时） */
    fun elasticOn(): Boolean = p.getBoolean("elastic", true)

    fun setElasticOn(v: Boolean) = p.edit().putBoolean("elastic", v).apply()

    /** 每日工时（分钟），默认 9 小时 */
    fun workMinutes(): Int = p.getInt("work_minutes", 540)

    fun setWorkMinutes(v: Int) = p.edit().putInt("work_minutes", v).apply()

    fun workHoursText(): String {
        val m = workMinutes()
        return if (m % 60 == 0) "${m / 60} 小时" else String.format(Locale.CHINA, "%.1f 小时", m / 60f)
    }

    // ---------- 打卡方式 ----------

    /** true = 到点自动打卡；false = 只弹提醒 */
    fun autoMode(): Boolean = p.getBoolean("auto_mode", true)

    fun setAutoMode(value: Boolean) {
        p.edit().putBoolean("auto_mode", value).apply()
    }

    // ---------- 反馈 ----------

    fun soundOn(): Boolean = p.getBoolean("fb_sound", true)

    /** 提示音音效序号（对应 Feedback.SOUND_STYLES，默认 0 = 经典叮咚） */
    fun soundStyle(): Int = p.getInt("sound_style", 0).coerceIn(0, 4)

    fun setSoundStyle(v: Int) = p.edit().putInt("sound_style", v.coerceIn(0, 4)).apply()
    fun voiceOn(): Boolean = p.getBoolean("fb_voice", true)
    fun vibrateOn(): Boolean = p.getBoolean("fb_vibrate", true)
    fun overlayOn(): Boolean = p.getBoolean("fb_overlay", true)

    /** 是否识别「我在飞书里手动打卡」的结果 */
    fun watchOn(): Boolean = p.getBoolean("watch_result", true)

    /** 隐藏后台任务：不在最近任务列表中出现，避免被一键划掉 */
    fun hideTask(): Boolean = p.getBoolean("hide_task", true)

    fun setHideTask(v: Boolean) = p.edit().putBoolean("hide_task", v).apply()

    fun setSoundOn(v: Boolean) = p.edit().putBoolean("fb_sound", v).apply()
    fun setVoiceOn(v: Boolean) = p.edit().putBoolean("fb_voice", v).apply()
    fun setVibrateOn(v: Boolean) = p.edit().putBoolean("fb_vibrate", v).apply()
    fun setOverlayOn(v: Boolean) = p.edit().putBoolean("fb_overlay", v).apply()

    /** 语音播报音量（0~1000%，默认 300）。只调 App 自己那段音频的幅度，系统音量不动 */
    fun voiceVolume(): Int = p.getInt("voice_vol", 300).coerceIn(0, 1000)

    fun setVoiceVolume(v: Int) = p.edit().putInt("voice_vol", v.coerceIn(0, 1000)).apply()

    /** 屏幕中央提示停留几秒（1~20，默认 5） */
    fun tipSeconds(): Int = p.getInt("tip_seconds", 5).coerceIn(1, 20)

    fun setTipSeconds(v: Int) = p.edit().putInt("tip_seconds", v.coerceIn(1, 20)).apply()
    fun setWatchOn(v: Boolean) = p.edit().putBoolean("watch_result", v).apply()

    // ---------- 飞书页面识别 ----------

    fun feishuPackage(): String = p.getString("feishu_pkg", "com.ss.android.lark") ?: "com.ss.android.lark"

    fun setFeishuPackage(v: String) = p.edit().putString("feishu_pkg", v).apply()

    /** 从飞书首页进到打卡页的点击路径，例如 工作台 > 打卡 */
    fun navPath(): List<String> = split(p.getString("nav_path", null) ?: "工作台>打卡", ">")

    fun setNavPath(path: String) = p.edit().putString("nav_path", path).apply()

    fun buttonTexts(type: PunchType): List<String> {
        val def = if (type == PunchType.IN) "上班打卡|打卡" else "下班打卡|打卡"
        return split(p.getString("btn_${type.key}", null) ?: def, "|")
    }

    fun setButtonTexts(type: PunchType, value: String) =
        p.edit().putString("btn_${type.key}", value).apply()

    /** 判定「打卡成功」的文案 */
    fun successKeywords(): List<String> =
        split(p.getString("success_kw", null) ?: "打卡成功|打卡完成", "|")

    private fun split(raw: String, sep: String): List<String> =
        raw.split(sep).map { it.trim() }.filter { it.isNotEmpty() }

    // ---------- 日历手动覆盖 ----------

    /** 手动标成休息日的日期（ISO，逗号分隔） */
    fun extraRestDates(): Set<java.time.LocalDate> = readDates("rest_dates")

    /** 手动标成上班日的日期（ISO，逗号分隔） */
    fun extraWorkDates(): Set<java.time.LocalDate> = readDates("work_dates")

    fun addRestDate(date: java.time.LocalDate) = writeDate("rest_dates", date, add = true)

    fun removeRestDate(date: java.time.LocalDate) = writeDate("rest_dates", date, add = false)

    fun addWorkDate(date: java.time.LocalDate) = writeDate("work_dates", date, add = true)

    fun removeWorkDate(date: java.time.LocalDate) = writeDate("work_dates", date, add = false)

    fun clearManualDates() = p.edit().remove("rest_dates").remove("work_dates").apply()

    private fun readDates(key: String): Set<java.time.LocalDate> {
        val raw = p.getString(key, null) ?: return emptySet()
        return raw.split(",")
            .mapNotNull { runCatching { java.time.LocalDate.parse(it.trim()) }.getOrNull() }
            .toSet()
    }

    private fun writeDate(key: String, date: java.time.LocalDate, add: Boolean) {
        val set = readDates(key).toMutableSet()
        if (add) set.add(date) else set.remove(date)
        val text = set.sorted().joinToString(",") { it.toString() }
        p.edit().putString(key, text).apply()
    }

    // ---------- 联网日历缓存 ----------

    fun remoteCalendar(year: Int): String? = p.getString("cal_y_$year", null)

    fun saveRemoteCalendar(year: Int, json: String) =
        p.edit().putString("cal_y_$year", json).apply()

    fun calendarSyncTime(): Long = p.getLong("cal_sync_at", 0L)

    fun setCalendarSyncTime(v: Long) = p.edit().putLong("cal_sync_at", v).apply()

    fun calendarSyncYears(): List<Int> {
        val raw = p.getString("cal_years", null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun setCalendarSyncYears(years: Collection<Int>) =
        p.edit().putString("cal_years", years.sorted().joinToString(",")).apply()

    /** 某个年份是否缓存过联网日历 */
    fun hasRemoteCalendar(year: Int): Boolean = remoteCalendar(year) != null
}
