package com.shuangka.punch.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.shuangka.punch.R
import com.shuangka.punch.core.KeepAlive
import com.shuangka.punch.core.PunchScheduler
import com.shuangka.punch.core.PunchService
import com.shuangka.punch.core.engine.AccessibilityUiController
import com.shuangka.punch.core.feedback.Feedback
import com.shuangka.punch.core.engine.EnginePicker
import com.shuangka.punch.core.engine.ShizukuHelper
import com.shuangka.punch.data.HolidayCalendar
import com.shuangka.punch.data.HolidayRemote
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import java.time.LocalDate

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvInStart: TextView
    private lateinit var tvInEnd: TextView
    private lateinit var tvOutStart: TextView
    private lateinit var tvOutEnd: TextView
    private lateinit var tvLeadIn: TextView
    private lateinit var tvLeadOut: TextView

    private lateinit var tvAccStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvExactStatus: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvChannelStatus: TextView
    private lateinit var tvKeepStatus: TextView

    private lateinit var tvCalendarSource: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvTodayState: TextView
    private lateinit var tvRestDates: TextView
    private lateinit var tvWorkDates: TextView

    private lateinit var rgMode: RadioGroup
    private lateinit var swSound: CompoundButton
    private lateinit var swVoice: CompoundButton
    private lateinit var swVibrate: CompoundButton
    private lateinit var swOverlay: CompoundButton
    private lateinit var swWatch: CompoundButton
    private lateinit var swHideTask: CompoundButton

    private lateinit var sbTipSeconds: SeekBar
    private lateinit var tvTipSeconds: TextView

    private lateinit var sbVoiceVolume: SeekBar
    private lateinit var tvVoiceVolume: TextView
    private lateinit var tvSoundStyle: TextView

    private lateinit var etNavPath: EditText
    private lateinit var etInBtn: EditText
    private lateinit var etOutBtn: EditText

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvInStart = findViewById(R.id.tvInStart)
        tvInEnd = findViewById(R.id.tvInEnd)
        tvOutStart = findViewById(R.id.tvOutStart)
        tvOutEnd = findViewById(R.id.tvOutEnd)
        tvLeadIn = findViewById(R.id.tvLeadIn)
        tvLeadOut = findViewById(R.id.tvLeadOut)

        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvExactStatus = findViewById(R.id.tvExactStatus)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvChannelStatus = findViewById(R.id.tvChannelStatus)
        tvKeepStatus = findViewById(R.id.tvKeepStatus)

        tvCalendarSource = findViewById(R.id.tvCalendarSource)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvTodayState = findViewById(R.id.tvTodayState)
        tvRestDates = findViewById(R.id.tvRestDates)
        tvWorkDates = findViewById(R.id.tvWorkDates)

        rgMode = findViewById(R.id.rgMode)
        swSound = findViewById(R.id.swSound)
        swVoice = findViewById(R.id.swVoice)
        swVibrate = findViewById(R.id.swVibrate)
        swOverlay = findViewById(R.id.swOverlay)
        swWatch = findViewById(R.id.swWatch)
        swHideTask = findViewById(R.id.swHideTask)

        sbTipSeconds = findViewById(R.id.sbTipSeconds)
        tvTipSeconds = findViewById(R.id.tvTipSeconds)

        sbVoiceVolume = findViewById(R.id.sbVoiceVolume)
        tvVoiceVolume = findViewById(R.id.tvVoiceVolume)
        tvSoundStyle = findViewById(R.id.tvSoundStyle)

        etNavPath = findViewById(R.id.etNavPath)
        etInBtn = findViewById(R.id.etInBtn)
        etOutBtn = findViewById(R.id.etOutBtn)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 时间窗口
        findViewById<android.view.View>(R.id.rowInStart).setOnClickListener {
            pickTime(PunchConfig.startMinutes(PunchType.IN)) { minutes ->
                applyWindow(PunchType.IN, minutes, null)
            }
        }
        findViewById<android.view.View>(R.id.rowInEnd).setOnClickListener {
            pickTime(PunchConfig.endMinutes(PunchType.IN)) { minutes ->
                applyWindow(PunchType.IN, null, minutes)
            }
        }
        findViewById<android.view.View>(R.id.rowOutStart).setOnClickListener {
            pickTime(PunchConfig.startMinutes(PunchType.OUT)) { minutes ->
                applyWindow(PunchType.OUT, minutes, null)
            }
        }
        findViewById<android.view.View>(R.id.rowOutEnd).setOnClickListener {
            pickTime(PunchConfig.endMinutes(PunchType.OUT)) { minutes ->
                applyWindow(PunchType.OUT, null, minutes)
            }
        }
        findViewById<android.view.View>(R.id.rowLeadIn).setOnClickListener {
            pickRemind(PunchType.IN)
        }
        findViewById<android.view.View>(R.id.rowLeadOut).setOnClickListener {
            pickRemind(PunchType.OUT)
        }


        // 打卡方式
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            PunchConfig.setAutoMode(checkedId == R.id.rbAuto)
        }

        // 反馈开关
        swSound.isChecked = PunchConfig.soundOn()
        swVoice.isChecked = PunchConfig.voiceOn()
        swVibrate.isChecked = PunchConfig.vibrateOn()
        swOverlay.isChecked = PunchConfig.overlayOn()
        swWatch.isChecked = PunchConfig.watchOn()
        swHideTask.isChecked = PunchConfig.hideTask()

        // 提示停留时长（1~20 秒）
        sbTipSeconds.progress = PunchConfig.tipSeconds() - 1
        tvTipSeconds.text = "${PunchConfig.tipSeconds()} 秒"
        sbTipSeconds.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val sec = (progress + 1).coerceIn(1, 20)
                tvTipSeconds.text = "$sec 秒"
                if (fromUser) PunchConfig.setTipSeconds(sec)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // 语音播报音量（10%~100%，播报时临时抬高媒体音量）
        sbVoiceVolume.progress = PunchConfig.voiceVolume() - 10
        tvVoiceVolume.text = "${PunchConfig.voiceVolume()}%"
        sbVoiceVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val vol = (progress + 10).coerceIn(10, 100)
                tvVoiceVolume.text = "$vol%"
                if (fromUser) PunchConfig.setVoiceVolume(vol)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                tvVoiceVolume.text = "${PunchConfig.voiceVolume()}%"
                Feedback.speak(this@SettingsActivity, "语音播报音量 ${PunchConfig.voiceVolume()}%")
            }
        })
        findViewById<TextView>(R.id.btnVoiceTest).setOnClickListener {
            Feedback.speak(this, "上班打卡成功，现在是早上九点二十二分")
            toast("试听中，音量 ${PunchConfig.voiceVolume()}%")
        }

        // 提示音音效：点开单选，点一下就响，方便逐个对比
        tvSoundStyle.text = Feedback.styleLabel(PunchConfig.soundStyle())
        findViewById<LinearLayout>(R.id.rowSoundStyle).setOnClickListener { pickSoundStyle() }
        findViewById<TextView>(R.id.btnSoundTest).setOnClickListener { Feedback.playSound(this) }

        swSound.setOnCheckedChangeListener { _, v -> PunchConfig.setSoundOn(v) }
        swVoice.setOnCheckedChangeListener { _, v -> PunchConfig.setVoiceOn(v) }
        swVibrate.setOnCheckedChangeListener { _, v -> PunchConfig.setVibrateOn(v) }
        swOverlay.setOnCheckedChangeListener { _, v -> PunchConfig.setOverlayOn(v) }
        swWatch.setOnCheckedChangeListener { _, v -> PunchConfig.setWatchOn(v) }
        swHideTask.setOnCheckedChangeListener { _, v ->
            PunchConfig.setHideTask(v)
            applyTaskVisibility()
        }

        // 页面识别
        etNavPath.setText(PunchConfig.navPath().joinToString(">"))
        etInBtn.setText(PunchConfig.buttonTexts(PunchType.IN).joinToString("|"))
        etOutBtn.setText(PunchConfig.buttonTexts(PunchType.OUT).joinToString("|"))

        // 权限入口
        findViewById<TextView>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<TextView>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<TextView>(R.id.btnExact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            } else {
                toast("当前系统不需要单独授权")
            }
        }
        findViewById<TextView>(R.id.btnNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200
                )
            } else {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        findViewById<TextView>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<LinearLayout>(R.id.rowKeepAlive).setOnClickListener { showKeepAlive() }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // 测试都收进二级页面，这里只做入口
        findViewById<LinearLayout>(R.id.rowTest).setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }

        // 工作日日历（法定节假日 + 调休，可手动覆盖）
        tvCalendarSource.text = "内置 ${HolidayCalendar.coveredYearRange()} + 联网"
        findViewById<LinearLayout>(R.id.rowSyncCalendar).apply {
            setOnClickListener { syncCalendar() }
        }
        findViewById<TextView>(R.id.btnSyncCalendar).setOnClickListener { syncCalendar() }
        findViewById<LinearLayout>(R.id.rowAddRest).apply {
            setOnClickListener { pickDate("休息日") { date -> PunchConfig.addRestDate(date); refreshCalendar() } }
            setOnLongClickListener { showDates("休息日") }
        }
        findViewById<LinearLayout>(R.id.rowAddWork).apply {
            setOnClickListener { pickDate("上班日") { date -> PunchConfig.addWorkDate(date); refreshCalendar() } }
            setOnLongClickListener { showDates("上班日") }
        }
        findViewById<TextView>(R.id.btnClearCalendar).setOnClickListener {
            PunchConfig.clearManualDates()
            refreshCalendar()
            toast("已清空手动标记")
        }
        refreshCalendar()

        refreshTimeLabels()
        rgMode.check(if (PunchConfig.autoMode()) R.id.rbAuto else R.id.rbRemind)
    }

    override fun onResume() {
        super.onResume()
        refreshTimeLabels()
        refreshPermissions()
        applyTaskVisibility()
    }

    /** 让当前任务从「最近任务」里出现/消失，运行时生效 */
    private fun applyTaskVisibility() {
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val hide = PunchConfig.hideTask()
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
        } catch (e: Exception) {
            // 个别 ROM 不支持，忽略
        }
    }

    override fun onPause() {
        super.onPause()
        PunchConfig.setNavPath(etNavPath.text.toString().ifBlank { "工作台>打卡" })
        PunchConfig.setButtonTexts(
            PunchType.IN,
            etInBtn.text.toString().ifBlank { "上班打卡|打卡" }
        )
        PunchConfig.setButtonTexts(
            PunchType.OUT,
            etOutBtn.text.toString().ifBlank { "下班打卡|打卡" }
        )
    }

    // ---------- 时间窗口 ----------

    private fun pickTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _: TimePicker, hour: Int, minute: Int -> onPicked(hour * 60 + minute) },
            initialMinutes / 60,
            initialMinutes % 60,
            true
        ).show()
    }

    private fun applyWindow(type: PunchType, newStart: Int?, newEnd: Int?) {
        val start = newStart ?: PunchConfig.startMinutes(type)
        val end = newEnd ?: PunchConfig.endMinutes(type)
        if (start >= end) {
            toast("结束时间要晚于开始时间")
            return
        }
        PunchConfig.setWindow(type, start, end)
        PunchScheduler.scheduleAll(this)
        refreshTimeLabels()
    }

    private fun pickWorkHours() {
        val options = arrayOf("8 小时", "8.5 小时", "9 小时", "9.5 小时", "10 小时")
        val values = intArrayOf(480, 510, 540, 570, 600)
        val current = values.indexOfFirst { it == PunchConfig.workMinutes() }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("每日工时")
            .setSingleChoiceItems(options, current) { dialog, which ->
                PunchConfig.setWorkMinutes(values[which])
                PunchScheduler.scheduleAll(this)
                refreshTimeLabels()
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshTimeLabels() {
        tvInStart.text = PunchConfig.minutesToText(PunchConfig.startMinutes(PunchType.IN))
        tvInEnd.text = PunchConfig.minutesToText(PunchConfig.endMinutes(PunchType.IN))
        tvOutStart.text = PunchConfig.minutesToText(PunchConfig.startMinutes(PunchType.OUT))
        tvOutEnd.text = PunchConfig.minutesToText(PunchConfig.endMinutes(PunchType.OUT))
        tvLeadIn.text = PunchConfig.remindInText()
        tvLeadOut.text = PunchConfig.remindOutText()
    }

    /** 提醒时间：上班选「提前几分钟」，下班选「窗口开启后几分钟」 */
    private fun pickRemind(type: PunchType) {
        val values = intArrayOf(0, 3, 5, 10, 15, 20, 30, 45, 60)
        val labels = values.map {
            when {
                it == 0 -> "窗口开启时"
                type == PunchType.IN -> "提前 $it 分钟"
                else -> "开启后 $it 分钟"
            }
        }.toTypedArray()
        val current = if (type == PunchType.IN) PunchConfig.remindBeforeIn() else PunchConfig.remindAfterOut()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (type == PunchType.IN) "上班提醒时间" else "下班提醒时间")
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                if (type == PunchType.IN) PunchConfig.setRemindBeforeIn(values[which])
                else PunchConfig.setRemindAfterOut(values[which])
                PunchScheduler.scheduleAll(this)
                refreshTimeLabels()
                dialog.dismiss()
            }
            .show()
    }

    /** 选提示音：点哪个立刻响哪个，对话框不关，方便来回对比 */
    private fun pickSoundStyle() {
        val labels = Feedback.SOUND_STYLES.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选提示音（点一下试听）")
            .setSingleChoiceItems(labels, PunchConfig.soundStyle()) { _, which ->
                PunchConfig.setSoundStyle(which)
                tvSoundStyle.text = Feedback.styleLabel(which)
                Feedback.playSound(this)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 工作日日历 ----------

    /** 联网同步节假日 + 调休，成功后重排闹钟 */
    private fun syncCalendar() {
        tvSyncStatus.text = "同步中…"
        Thread {
            val result = HolidayRemote.sync()
            handler.post {
                result.fold(
                    onSuccess = {
                        toast(it)
                        refreshCalendar()
                    },
                    onFailure = { e ->
                        tvSyncStatus.text = HolidayRemote.statusText()
                        toast("同步失败：${e.message ?: "网络不通"}，继续用已缓存/内置日历")
                    }
                )
            }
        }.start()
    }

    private fun pickDate(kind: String, onPicked: (LocalDate) -> Unit) {
        val today = LocalDate.now()
        android.app.DatePickerDialog(
            this,
            { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            today.year, today.monthValue - 1, today.dayOfMonth
        ).show()
    }

    private fun showDates(kind: String): Boolean {
        val dates = if (kind == "休息日") PunchConfig.extraRestDates() else PunchConfig.extraWorkDates()
        if (dates.isEmpty()) {
            toast("还没有手动标记的$kind")
            return true
        }
        val sorted = dates.sorted()
        val labels = sorted.map { d ->
            val tag = HolidayCalendar.label(d)
            "$d（周${"一二三四五六日"[d.dayOfWeek.value - 1]}${if (tag != null) " · $tag" else ""}）"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("点一下删除")
            .setItems(labels) { dialog, which ->
                val d = sorted[which]
                if (kind == "休息日") PunchConfig.removeRestDate(d) else PunchConfig.removeWorkDate(d)
                refreshCalendar()
                dialog.dismiss()
                showDates(kind)
            }
            .setNegativeButton("关闭", null)
            .show()
        return true
    }

    private fun refreshCalendar() {
        tvCalendarSource.text = "内置 ${HolidayCalendar.coveredYearRange()} + 联网"
        tvSyncStatus.text = HolidayRemote.statusText()

        val today = LocalDate.now()
        tvTodayState.text = buildString {
            append(if (HolidayCalendar.isWorkday(today)) "上班" else "休息")
            append(" · ")
            append(HolidayCalendar.describe(today))
        }

        val rest = PunchConfig.extraRestDates().sorted()
        tvRestDates.text = if (rest.isEmpty()) {
            "无"
        } else {
            rest.take(2).joinToString("、") { it.toString() } + if (rest.size > 2) " 等 ${rest.size} 天" else ""
        }

        val work = PunchConfig.extraWorkDates().sorted()
        tvWorkDates.text = if (work.isEmpty()) {
            "无"
        } else {
            work.take(2).joinToString("、") { it.toString() } + if (work.size > 2) " 等 ${work.size} 天" else ""
        }

        // 改了日历要重排闹钟，否则节假日还会响
        PunchScheduler.scheduleAll(this)
    }

    // ---------- 权限状态 ----------

    private fun refreshPermissions() {
        tvAccStatus.text = if (AccessibilityUiController.isReady()) "已开启" else "未开启"

        tvOverlayStatus.text = if (Settings.canDrawOverlays(this)) "已授权" else "未授权"

        val exactAlarm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }
        tvExactStatus.text = if (exactAlarm) "已授权" else "未授权"

        tvNotifStatus.text =
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "已开启" else "未开启"

        val impl = ShizukuHelper.implementation(this)
        tvShizukuStatus.text = when {
            impl.isEmpty() -> "未安装"
            !ShizukuHelper.running() -> "$impl · 服务未运行"
            ShizukuHelper.granted() -> "$impl · 已授权"
            else -> "$impl · 待授权"
        }

        val ignoring = try {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (e: Exception) {
            false
        }
        tvBatteryStatus.text = if (ignoring) "已忽略" else "未忽略"

        tvChannelStatus.text = EnginePicker.label()
        tvKeepStatus.text = KeepAlive.statusText(this)
    }

    /** 保活详情：常驻服务 / 无障碍 / 最近心跳，并给出一键唤醒 */
    private fun showKeepAlive() {
        KeepAlive.beat(this, "自检")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("保活状态：${KeepAlive.statusText(this)}")
            .setMessage(KeepAlive.detailText(this))
            .setPositiveButton("立即唤醒") { _, _ ->
                startService(
                    android.content.Intent(this, PunchService::class.java)
                        .setAction(PunchService.ACTION_KEEPALIVE)
                )
                KeepAlive.ensureService(this)
                PunchScheduler.scheduleAll(this)
                KeepAlive.scheduleChecks(this)
                toast("已拉起常驻服务、重排闹钟和自检")
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun requestShizuku() {
        if (ShizukuHelper.granted()) {
            toast("已经授权过了")
            return
        }
        val impl = ShizukuHelper.implementation(this)
        when (ShizukuHelper.request(this)) {
            ShizukuHelper.Result.OK -> {
                toast("请在授权弹窗里点「允许」")
                pollGranted()
            }
            ShizukuHelper.Result.NOT_RUNNING -> {
                if (impl.isEmpty()) {
                    toast("没检测到 Shizuku / Shevery，请先安装并启动服务")
                } else {
                    toast("$impl 的服务没在运行，先去管理器里启动服务")
                    alert(
                        "服务未运行",
                        "$impl 已安装，但 Shizuku 服务没启动。请先在 $impl 里启动服务（无线调试或 root），再回来点授权。"
                    ) { ShizukuHelper.openManager(this) }
                }
            }
            ShizukuHelper.Result.NEEDS_STUB -> alert(
                "检测到 Shevery，但授权没弹出来",
                "Shevery 用的是自己的权限命名空间，官方 Shizuku 接口要它打开「兼容存根 / Compatibility Stubs」才通。\n\n" +
                    "做法：打开 Shevery → 设置（隐蔽版 / Stealth 相关项）→ 打开「兼容存根」，" +
                    "按提示装好存根后再回来点「授权」。\n\n" +
                    "懒得折腾也可以直接用无障碍模式——功能一样，只是需要开一次无障碍服务。"
            ) { ShizukuHelper.openManager(this) }
            ShizukuHelper.Result.FAILED -> alert(
                "没有弹出授权",
                "请打开 $impl 手动允许「自动打卡」：管理器 → 授权应用列表 → 自动打卡 → 允许。\n" +
                    "或者直接用无障碍模式。"
            ) { ShizukuHelper.openManager(this) }
        }
    }

    private fun pollGranted() {
        var count = 0
        val poll = object : Runnable {
            override fun run() {
                count++
                if (ShizukuHelper.granted() || count > 24) {
                    refreshPermissions()
                } else {
                    handler.postDelayed(this, 500L)
                }
            }
        }
        handler.postDelayed(poll, 500L)
    }

    private fun alert(title: String, message: String, onOpen: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("打开管理器") { _, _ -> onOpen() }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
