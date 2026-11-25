package com.shuangka.punch.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * 从网络日历接口取「放假日 + 调休补班日」，这样节假日 / 调休调整不用重新发版。
 *
 * 用的是公开的 timor.tech 节假日接口：`/api/holiday/year/{年}`，
 * 返回里 holiday=true 是放假，holiday=false 是补班（调休上班）。
 * 拉下来的结果按年缓存在本地，没网或接口挂了就继续用缓存，再不行退回内置表。
 */
object HolidayRemote {

    private const val BASE = "https://timor.tech/api/holiday/year/"

    data class Day(
        val date: LocalDate,
        /** true = 放假，false = 调休上班（补班） */
        val holiday: Boolean,
        val name: String,
        /** 补班对应的节日，例如「国庆节」 */
        val target: String?
    )

    private val memory = HashMap<Int, Map<LocalDate, Day>>()

    /** 某一天的联网日历信息；没有这个年份的数据就返回 null */
    fun day(date: LocalDate): Day? = year(date.year)[date]

    fun year(year: Int): Map<LocalDate, Day> {
        memory[year]?.let { return it }
        val raw = PunchConfig.remoteCalendar(year) ?: return emptyMap()
        val parsed = parse(raw)
        memory[year] = parsed
        return parsed
    }

    fun hasYear(year: Int): Boolean = PunchConfig.remoteCalendar(year) != null

    fun syncedYears(): List<Int> = PunchConfig.calendarSyncYears()

    fun lastSyncAt(): Long = PunchConfig.calendarSyncTime()

    /** 同步哪几年：今年、明年（有数据就一起更新） */
    fun defaultYears(): List<Int> {
        val y = LocalDate.now().year
        return listOf(y, y + 1)
    }

    /**
     * 拉取并落盘。返回一句给界面看的说明。
     */
    fun sync(years: List<Int> = defaultYears()): Result<String> = runCatching {
        val ok = mutableListOf<Int>()
        years.forEach { y ->
            val json = fetch(y)
            if (json != null) {
                PunchConfig.saveRemoteCalendar(y, json)
                memory[y] = parse(json)
                ok += y
            }
        }
        if (ok.isEmpty()) error("没拿到任何年份的数据，检查一下网络")
        val known = PunchConfig.calendarSyncYears().toMutableSet().apply { addAll(ok) }
        PunchConfig.setCalendarSyncYears(known)
        PunchConfig.setCalendarSyncTime(System.currentTimeMillis())
        "已同步 ${ok.joinToString("、") { "${it}年" }}"
    }

    private fun fetch(year: Int): String? {
        val conn = (URL(BASE + year).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "shuangka-punch/1.6")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .takeIf { it.contains("\"holiday\"") }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parse(raw: String): Map<LocalDate, Day> {
        val map = LinkedHashMap<LocalDate, Day>()
        runCatching {
            val root = JSONObject(raw)
            val node = root.optJSONObject("holiday") ?: return@runCatching
            node.keys().forEach { key ->
                val o = node.optJSONObject(key) ?: return@forEach
                val dateText = o.optString("date").takeIf { it.isNotBlank() } ?: return@forEach
                val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@forEach
                map[date] = Day(
                    date = date,
                    holiday = o.optBoolean("holiday", false),
                    name = o.optString("name").ifBlank { "节假日" },
                    target = o.optString("target").takeIf { it.isNotBlank() }
                )
            }
        }
        return map
    }

    /** 给界面用的同步状态一句话 */
    fun statusText(): String {
        val years = syncedYears()
        if (years.isEmpty()) return "未同步（用内置表）"
        val at = lastSyncAt()
        if (at <= 0L) return "已缓存 ${years.joinToString("、")} 年"
        val stamp = java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
            )
        return "${years.joinToString("、")} 年 · $stamp 同步"
    }
}
