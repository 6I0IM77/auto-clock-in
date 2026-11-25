package com.shuangka.punch.core.debug

import android.content.Context
import android.widget.Toast
import com.shuangka.punch.core.Notifier
import com.shuangka.punch.core.PunchStatusText
import com.shuangka.punch.core.feedback.Feedback
import com.shuangka.punch.core.feedback.SuccessOverlay
import com.shuangka.punch.data.DayRecord
import com.shuangka.punch.data.HolidayCalendar
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** 测试里要覆盖的判定分支 */
enum class ScenarioKind {
    /** 打卡成功：提示音 + 语音 + 中央提示 + 通知栏「打卡成功」 */
    SUCCESS,
    /** 成功的收尾：两卡都齐了 */
    ALL_DONE,
    /** 到点提醒：只推通知，不点按钮 */
    REMIND,
    /** 不在打卡时间内 / 漏卡：只推通知 + Toast 说明 */
    OUTSIDE,
    /** 打卡失败（没找到按钮 / 页面时间不对 / 没有通道） */
    FAIL
}

/**
 * 测试页里的每一个场景。全部是「假时钟 + 假记录」，只走判定和提示链路，
 * 不写真实打卡记录，也不真的去点飞书。
 */
enum class TestScenario(
    val key: String,
    val label: String,
    val desc: String,
    val kind: ScenarioKind,
    /** 模拟时刻 HH:mm；null 表示用当前时刻 */
    val clock: String? = null,
    /** 模拟「今天已经打上的上班卡」 */
    val fakeIn: String? = null,
    /** 模拟「今天已经打上的下班卡」 */
    val fakeOut: String? = null,
    /** 这次成功提示属于哪张卡 */
    val type: PunchType = PunchType.IN,
    /** 模拟打卡页面上的时间（成功场景用） */
    val punchTime: String? = null,
    /** 模拟成休息日（周六） */
    val restDay: Boolean = false,
    /** 指定某一天来做判定（节假日 / 调休场景用），ISO 日期 */
    val fixedDate: String? = null,
    /** 失败场景的说明文案 */
    val failText: String? = null
) {

    IN_OK(
        "in_ok", "上班打卡成功", "09:05 打上班卡：声音 + 中央提示 + 通知栏「上班打卡成功」",
        ScenarioKind.SUCCESS, clock = "09:05", type = PunchType.IN, punchTime = "09:05"
    ),

    IN_ELASTIC(
        "in_elastic", "上班打卡成功 · 晚到", "09:37 才打上：下班窗口仍是固定的 18:00-19:00，不顺延",
        ScenarioKind.SUCCESS, clock = "09:37", type = PunchType.IN, punchTime = "09:37"
    ),

    OUT_OK(
        "out_ok", "下班打卡成功", "上班 09:22、18:05 打下班卡：通知栏「下班打卡成功」",
        ScenarioKind.SUCCESS, clock = "18:05", fakeIn = "09:22", type = PunchType.OUT, punchTime = "18:05"
    ),

    REMIND_IN(
        "remind_in", "上班到点提醒", "08:55 推「该上班打卡了」，带「打开飞书 / 我已完成」",
        ScenarioKind.REMIND, clock = "08:55", type = PunchType.IN
    ),

    REMIND_OUT(
        "remind_out", "下班到点提醒", "17:55 推「该下班打卡了」，下班窗口按弹性算",
        ScenarioKind.REMIND, clock = "17:55", fakeIn = "09:22", type = PunchType.OUT
    ),

    BEFORE_WINDOW(
        "before_window", "上班窗口还没到", "08:40 判定「不在打卡时间内」，按钮置灰",
        ScenarioKind.OUTSIDE, clock = "08:40"
    ),

    MIDDAY(
        "midday", "午间等下班", "14:30 上班已打、下班还没到：不在打卡时间内",
        ScenarioKind.OUTSIDE, clock = "14:30", fakeIn = "09:22"
    ),

    LATE_IN(
        "late_in", "上班卡漏了", "10:20 上班窗口已过、还没打上班卡",
        ScenarioKind.OUTSIDE, clock = "10:20"
    ),

    LATE_OUT(
        "late_out", "下班卡漏了", "19:30 下班窗口已过、今天只差下班卡",
        ScenarioKind.OUTSIDE, clock = "19:30", fakeIn = "09:22"
    ),

    REST_DAY(
        "rest_day", "普通周末", "周六判定「今天休息」，不用打卡",
        ScenarioKind.OUTSIDE, clock = "10:00", restDay = true
    ),

    HOLIDAY_REST(
        "holiday_rest", "法定节假日", "10月2日国庆节（周五）：按国务院日历判定放假，不出打卡",
        ScenarioKind.OUTSIDE, clock = "10:00", fixedDate = "2026-10-02"
    ),

    MAKEUP_WORK(
        "makeup_work", "调休上班日", "10月10日（周六）是调休上班：09:05 照常提醒上班打卡",
        ScenarioKind.SUCCESS, clock = "09:05", fixedDate = "2026-10-10",
        type = PunchType.IN, punchTime = "09:05"
    ),

    ALREADY_DONE(
        "already_done", "今天已完成", "两卡都打上了：按钮显示「今天已完成」",
        ScenarioKind.OUTSIDE, clock = "18:30", fakeIn = "09:22", fakeOut = "18:05"
    ),

    BAD_TIME(
        "bad_time", "页面时间不在窗口", "页面显示 08:41 的打卡时间：不记账 + 失败通知",
        ScenarioKind.FAIL, clock = "09:05", type = PunchType.IN,
        failText = "打卡时间 08:41 不在上班打卡窗口内，这条没有记录"
    ),

    NO_CHANNEL(
        "no_channel", "没有自动化通道", "无障碍和 Shizuku 都没开：失败通知 + 重试按钮",
        ScenarioKind.FAIL, clock = "09:05", type = PunchType.IN,
        failText = "没有可用的自动化通道：去设置里开启无障碍服务，或安装并授权 Shizuku"
    ),

    NOT_FOUND(
        "not_found", "没找到打卡按钮", "页面文字和设置里的按钮文案对不上：失败通知",
        ScenarioKind.FAIL, clock = "09:05", type = PunchType.IN,
        failText = "在打卡页里没找到「上班打卡」按钮，去设置里改按钮文字"
    );

    companion object {
        fun fromKey(key: String?): TestScenario? = values().firstOrNull { it.key == key }
    }
}

/** 场景渲染：复用真实的提示 / 通知 / 语音链路，只是数据是假的 */
object TestScenarios {

    fun render(ctx: Context, s: TestScenario) {
        val today = LocalDate.now()
        val date = when {
            s.fixedDate != null -> LocalDate.parse(s.fixedDate)
            s.restDay -> nextRestSaturday(today)
            else -> today
        }
        val at = atTime(date, s.clock) ?: LocalDateTime.now()
        val record: DayRecord? = if (s.fakeIn == null && s.fakeOut == null) {
            null
        } else {
            DayRecord(
                date = date,
                inTime = s.fakeIn,
                outTime = s.fakeOut,
                inManual = s.fakeIn != null,
                outManual = s.fakeOut != null
            )
        }

        when (s.kind) {
            ScenarioKind.SUCCESS, ScenarioKind.ALL_DONE -> success(ctx, s, record)
            ScenarioKind.REMIND -> {
                Notifier.remind(ctx, s.type)
                toast(ctx, "已推送「该${s.type.label}打卡了」，去通知栏看一眼")
            }
            ScenarioKind.OUTSIDE -> {
                val now = PunchRules.now(at, record)
                val status = PunchStatusText.of(now, at, record)
                Notifier.statusNotice(ctx, status.type, status.title, status.text)
                toast(ctx, "${status.title} · ${status.text}")
            }
            ScenarioKind.FAIL -> {
                Notifier.result(ctx, s.type, false, null, s.failText)
                toast(ctx, "已推送失败通知：${s.failText}")
            }
        }
    }

    private fun success(ctx: Context, s: TestScenario, record: DayRecord?) {
        val time = s.punchTime ?: LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

        // 成功提示的颜色/文案与真实打卡完全一致
        Feedback.success(ctx, s.type, time)

        val extra = if (s.type == PunchType.IN) {
            val delay = PunchRules.elasticDelayMinutes(record)
            "应下班 ${PunchRules.expectOutText(record)}" +
                if (delay > 0) " · 弹性顺延 $delay 分钟" else ""
        } else {
            record?.inTime?.let { "上班 $it 已打" }
        }

        if (PunchConfig.overlayOn()) {
            SuccessOverlay.show(
                ctx, s.type, time, extra
            )
        } else {
            toast(ctx, "中央提示已关闭（设置里打开「屏幕中央提示」才能看到）")
        }

        Notifier.successNotice(ctx, s.type, time, extra)
    }

    /** 最近的、确实是休息日的周六（跳过调休上班的周末） */
    private fun nextRestSaturday(today: LocalDate): LocalDate {
        var d = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        while (HolidayCalendar.isWorkday(d)) d = d.plusWeeks(1)
        return d
    }

    private fun atTime(date: LocalDate, clock: String?): LocalDateTime? {
        if (clock == null) return null
        val parts = clock.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return date.atTime(h, m)
    }

    private fun toast(ctx: Context, text: String) {
        Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}
