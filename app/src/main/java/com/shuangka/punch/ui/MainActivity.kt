package com.shuangka.punch.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.shuangka.punch.R
import com.shuangka.punch.core.KeepAlive
import com.shuangka.punch.core.PunchScheduler
import com.shuangka.punch.core.PunchService
import com.shuangka.punch.core.engine.EnginePicker
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchNow
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.HolidayCalendar
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvDate: TextView
    private lateinit var tvExpectIn: TextView
    private lateinit var tvDoneIn: TextView
    private lateinit var tvChipIn: TextView
    private lateinit var tvExpectOut: TextView
    private lateinit var tvDoneOut: TextView
    private lateinit var tvChipOut: TextView
    private lateinit var tvElasticNote: TextView
    private lateinit var tvClock: TextView
    private lateinit var btnPunch: TextView
    private lateinit var tvWindowHint: TextView
    private lateinit var tvEngine: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvSuccess: TextView
    private lateinit var tvWeekDays: TextView
    private lateinit var tvWeekHint: TextView
    private lateinit var tvMissing: TextView
    private lateinit var calendarView: MonthCalendarView

    private var month: YearMonth = YearMonth.now()
    private val handler = Handler(Looper.getMainLooper())

    /** 统计口径的「今天是否已结算」，变了就重算概览数字 */
    private var statsKey: String = ""

    private val ticker = object : Runnable {
        override fun run() {
            updateNowState()
            val today = LocalDate.now()
            val settled = PunchStore.isOutWindowClosed(this@MainActivity, today)
            val key = "$today|$settled"
            if (key != statsKey) refreshNumbers(today)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDate = findViewById(R.id.tvDate)
        tvExpectIn = findViewById(R.id.tvExpectIn)
        tvDoneIn = findViewById(R.id.tvDoneIn)
        tvChipIn = findViewById(R.id.tvChipIn)
        tvExpectOut = findViewById(R.id.tvExpectOut)
        tvDoneOut = findViewById(R.id.tvDoneOut)
        tvChipOut = findViewById(R.id.tvChipOut)
        tvElasticNote = findViewById(R.id.tvElasticNote)
        tvClock = findViewById(R.id.tvClock)
        btnPunch = findViewById(R.id.btnPunch)
        tvWindowHint = findViewById(R.id.tvWindowHint)
        tvEngine = findViewById(R.id.tvEngine)
        tvMonth = findViewById(R.id.tvMonth)
        tvSuccess = findViewById(R.id.tvSuccess)
        tvWeekDays = findViewById(R.id.tvWeekDays)
        tvWeekHint = findViewById(R.id.tvWeekHint)
        tvMissing = findViewById(R.id.tvMissing)
        calendarView = findViewById(R.id.calendarView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tvPrev = findViewById(R.id.tvPrev)
        tvNext = findViewById(R.id.tvNext)
        tvPrev.setOnClickListener {
            month = month.minusMonths(1)
            refresh()
        }
        tvNext.setOnClickListener {
            month = month.plusMonths(1)
            refresh()
        }

        btnPunch.setOnClickListener {
            val now = PunchRules.now(LocalDateTime.now(), PunchStore.get(this, LocalDate.now()))
            val type = now.type
            if (type == null) {
                Toast.makeText(this, now.hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PunchService.start(this, PunchService.ACTION_MANUAL, type)
            Toast.makeText(this, "正在打开飞书打${type.label}卡…", Toast.LENGTH_SHORT).show()
        }

        calendarView.onDateClick = { date -> showDayDetail(date) }
    }

    private lateinit var tvPrev: TextView
    private lateinit var tvNext: TextView

    override fun onResume() {
        super.onResume()
        refresh()
        applyTaskVisibility()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /** 隐藏后台任务：不在最近任务里出现，避免被划掉 */
    private fun applyTaskVisibility() {
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val hide = PunchConfig.hideTask()
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
        } catch (e: Exception) {
        }
    }

    private fun refresh() {
        val today = LocalDate.now()
        tvDate.text = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) +
            (HolidayCalendar.label(today)?.let { " · $it" } ?: "")

        // 通道 / 保活状态已经搬到「设置 → 权限与后台」，主页不再占位
        tvEngine.visibility = android.view.View.GONE
        updateNowState()

        refreshNumbers(today)

        tvMonth.text = "${month.year}年${month.monthValue}月"
    }

    /** 概览数字 + 日历：下班窗口关门、跨天时会被自动重算 */
    private fun refreshNumbers(today: LocalDate) {
        val settled = PunchStore.isOutWindowClosed(this, today)
        statsKey = "$today|$settled"

        val week = PunchStore.weekStats(this, today)
        tvWeekDays.text = week.complete.toString()
        tvWeekHint.text = "本周应出勤 ${week.workdays} 天"

        val stats = PunchStore.stats(this, month, today)
        // 打卡成功 = 同一天上下班两张卡都打上；缺卡 = 工作日没有任何记录（今天要等下班窗口关门才算）
        tvSuccess.text = stats.complete.toString()
        tvMissing.text = stats.missing.toString()

        calendarView.month = month
        calendarView.records = PunchStore.all(this)
    }

    /** 每秒刷新：大时钟 + 该打哪张卡 + 按钮是否可点 */
    private fun updateNowState() {
        val today = LocalDate.now()
        val record = PunchStore.get(this, today)
        val now = PunchRules.now(LocalDateTime.now(), record)

        tvClock.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val inW = PunchRules.inWindow()
        val outW = PunchRules.outWindow(record)

        tvExpectIn.text = "上班 ${inW.text()}"
        tvExpectOut.text = "下班 ${outW.text()}"
        tvChipIn.text = "弹性"
        tvChipOut.text = "弹性"
        tvElasticNote.visibility = android.view.View.GONE

        val inTime = record?.inTime
        val outTime = record?.outTime
        tvDoneIn.text = inTime ?: "未打卡"
        tvDoneIn.setTextColor(
            ContextCompat.getColor(this, if (inTime == null) R.color.text_faint else R.color.success)
        )
        tvDoneOut.text = outTime ?: "未打卡"
        tvDoneOut.setTextColor(
            ContextCompat.getColor(this, if (outTime == null) R.color.text_faint else R.color.success)
        )

        applyPunchButton(now)
    }

    /** 点「通道 / 保活」那行：看清有没有被杀，并一键拉回来 */
    private fun showKeepAlive() {
        KeepAlive.beat(this, "自检")
        AlertDialog.Builder(this)
            .setTitle("保活状态：${KeepAlive.statusText(this)}")
            .setMessage(KeepAlive.detailText(this))
            .setPositiveButton("立即唤醒") { _, _ ->
                startService(
                    Intent(this, PunchService::class.java)
                        .setAction(PunchService.ACTION_KEEPALIVE)
                )
                KeepAlive.ensureService(this)
                PunchScheduler.scheduleAll(this)
                KeepAlive.scheduleChecks(this)
                Toast.makeText(this, "已拉起常驻服务、重排闹钟和自检", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("加入电池白名单") { _, _ -> requestIgnoreBattery() }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun requestIgnoreBattery() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                Toast.makeText(this, "已经在白名单里了", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "系统不让直接跳，去「耗电管理 / 自启动」里手动允许", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyPunchButton(now: PunchNow) {
        // 不在打卡时间时，按钮内容直接换成倒计时（按钮依然是关闭状态）
        btnPunch.text = now.countdown ?: now.buttonText
        btnPunch.isEnabled = now.canPunch
        if (now.canPunch) {
            btnPunch.setBackgroundResource(R.drawable.bg_btn_primary)
            btnPunch.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            btnPunch.setBackgroundResource(R.drawable.bg_btn_disabled)
            btnPunch.setTextColor(ContextCompat.getColor(this, R.color.text_faint))
        }
        tvWindowHint.text = now.hint
    }

    private fun showDayDetail(date: LocalDate) {
        val record = PunchStore.get(this, date)
        val inTime = record?.inTime
        val outTime = record?.outTime

        val builder = StringBuilder()
        builder.append(
            if (inTime == null) "上班：未打卡"
            else "上班：已打卡 $inTime" + if (record?.inManual == true) "（手动）" else ""
        )
        builder.append("\n")
        builder.append(
            if (outTime == null) "下班：未打卡"
            else "下班：已打卡 $outTime" + if (record?.outManual == true) "（手动）" else ""
        )
        builder.append("\n\n")
        builder.append(
            when {
                record?.complete == true -> "当天双卡完成 ✔"
                record == null -> "当天没有记录"
                else -> "当天只打了一张卡"
            }
        )

        AlertDialog.Builder(this)
            .setTitle(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)))
            .setMessage(builder.toString())
            .setPositiveButton("知道了", null)
            .show()
    }
}

