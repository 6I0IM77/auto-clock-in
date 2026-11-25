package com.shuangka.punch.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shuangka.punch.core.debug.TestScenario
import com.shuangka.punch.core.debug.TestScenarios
import com.shuangka.punch.core.engine.EnginePicker
import com.shuangka.punch.core.engine.PunchFlow
import com.shuangka.punch.core.engine.PunchResult
import com.shuangka.punch.core.feedback.Feedback
import com.shuangka.punch.core.feedback.PunchPhrases
import com.shuangka.punch.core.feedback.SuccessOverlay
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchNow
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 打卡的执行者：唤醒后先按规则判断「这一刻该打哪张卡」，
 * 到点自动点 / 只提醒两种模式共用同一套判断。
 */
class PunchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_AUTO
        val extraType = PunchType.fromKey(intent?.getStringExtra(EXTRA_TYPE))
        startForegroundSafe("正在处理打卡")
        KeepAlive.beat(this, if (action == ACTION_KEEPALIVE) "磁贴唤醒" else "服务启动")

        val now = PunchRules.now(LocalDateTime.now(), PunchStore.get(this, LocalDate.now()))

        when (action) {
            ACTION_KEEPALIVE -> {
                // 磁贴 / 自检用的「唤醒」：不打卡、不打扰，只把通道和闹钟顶回来
                updateRun("通道已唤醒 · 等待打卡时间")
                PunchScheduler.scheduleAll(this)
                scope.launch {
                    delay(1_500L)
                    stopSoon()
                }
            }
            ACTION_MARK_DONE -> {
                markDoneManually(extraType)
                stopSoon()
            }
            ACTION_TEST_FEEDBACK -> {
                testFeedback(extraType)
                stopSoon()
            }
            ACTION_TEST_OUTSIDE -> {
                testOutside(now)
                stopSoon()
            }
            ACTION_TEST_SCENARIO -> {
                testScenario(intent?.getStringExtra(EXTRA_SCENARIO))
                stopSoon()
            }
            ACTION_AUTO, ACTION_MANUAL -> {
                if (now.type == null) {
                    outsideNotice(now, action == ACTION_MANUAL)
                    stopSoon()
                } else {
                    autoPunch(now.type, retryUntilWindowEnd = action == ACTION_AUTO)
                }
            }
            else -> stopSoon()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 任务被划掉/清理时兜底：把闹钟重新排一遍，明天照常唤醒 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            PunchScheduler.scheduleAll(this)
            // 用户划掉最近任务时，把常驻守护重新挂起来
            KeepAlive.ensureService(this)
            KeepAlive.scheduleChecks(this)
        } catch (e: Exception) {
        }
    }

    // ---------- 执行流程 ----------

    private fun autoPunch(type: PunchType, retryUntilWindowEnd: Boolean) {
        if (busy) {
            stopSoon()
            return
        }
        busy = true
        scope.launch {
            try {
                runFlow(type, retryUntilWindowEnd)
            } catch (t: Throwable) {
                Notifier.result(
                    this@PunchService, type, false, null,
                    "出错：" + (t.message ?: t.javaClass.simpleName)
                )
            } finally {
                busy = false
                stopSoon()
            }
        }
    }

    private suspend fun runFlow(type: PunchType, retryUntilWindowEnd: Boolean) {
        val controller = EnginePicker.pick()
        if (controller == null || !controller.isReady()) {
            Notifier.result(
                this, type, false, null,
                "没有可用的自动化通道：去设置里开启无障碍服务，或安装并授权 Shizuku"
            )
            return
        }

        var round = 0
        while (true) {
            round++
            updateRun("${type.label}打卡 · ${controller.name()} · 第 $round 次")
            val result = PunchFlow(controller).run(type) { msg ->
                updateRun("${type.label}打卡 · $msg")
            }
            when (result) {
                is PunchResult.Success -> {
                    onSuccess(type, result.time)
                    return
                }
                is PunchResult.Failed -> {
                    val stillThisOne = PunchRules.now(
                        LocalDateTime.now(), PunchStore.get(this, LocalDate.now())
                    ).type == type
                    if (!retryUntilWindowEnd || !stillThisOne || round >= 6) {
                        Notifier.result(this, type, false, null, result.reason)
                        return
                    }
                    updateRun("${type.label}打卡 · 没点中，20 秒后再试")
                    delay(20_000L)
                }
            }
        }
    }

    private fun onSuccess(type: PunchType, time: String) {
        val today = LocalDate.now()
        val before = PunchStore.get(this, today)

        // 两道关：现在的钟点要在窗口内，页面上的打卡时间也要在窗口内，否则不记账
        val window = if (type == PunchType.IN) PunchRules.inWindow() else PunchRules.outWindow(before)
        val nowTime = LocalTime.now()
        if (!window.contains(nowTime)) {
            Notifier.result(
                this, type, false, null,
                "现在 ${com.shuangka.punch.data.TimeWindow.fmt(nowTime)} 不在${type.label}打卡窗口 ${window.text()} 内，这条没有记录"
            )
            return
        }
        if (!timeInWindow(type, time, before)) {
            Notifier.result(
                this, type, false, null,
                "打卡时间 $time 不在${type.label}打卡窗口内，这条没有记录"
            )
            return
        }

        val updated = PunchStore.record(this, today, type, time, false)
        // 同一天同一张卡的成功提示只推一次（后台自动、看门狗、手动补点可能都会走到这里）
        val okKey = if (type == PunchType.IN) NotifyLedger.OK_IN else NotifyLedger.OK_OUT
        val firstTime = NotifyLedger.claim(this, okKey)
        if (firstTime) Feedback.success(this, type, time)
        val extra = if (type == PunchType.IN) {
            "应下班 ${PunchRules.expectOutText(updated)}${
                if (PunchRules.elasticDelayMinutes(updated) > 0)
                    " · 弹性顺延 ${PunchRules.elasticDelayMinutes(updated)} 分钟" else ""
            }"
        } else null

        // 中央提示和通知栏各走一路：浮层关了、没有悬浮窗权限时，通知栏里也一定能看到「打卡成功」
        if (firstTime) {
            showSuccessTip(type, time, extra)
            Notifier.result(this, type, true, time, extra)
        }

        if (type == PunchType.IN) {
            // 上班时间推后了，把下班闹钟顺手重排到弹性后的时间（提醒也跟着顺延）
            PunchScheduler.scheduleNext(this, PunchType.OUT)
            PunchScheduler.scheduleRemind(this, PunchType.OUT)
        }
    }

    private fun timeInWindow(type: PunchType, time: String, record: com.shuangka.punch.data.DayRecord?): Boolean {
        val minutes = PunchRules.parseMinutes(time) ?: return true
        val at = LocalTime.of(minutes / 60, minutes % 60)
        val window = if (type == PunchType.IN) PunchRules.inWindow() else PunchRules.outWindow(record)
        return window.contains(at)
    }

    private fun markDoneManually(type: PunchType) {
        val time = nowTime()
        PunchStore.record(this, LocalDate.now(), type, time, true)
        val okKey = if (type == PunchType.IN) NotifyLedger.OK_IN else NotifyLedger.OK_OUT
        if (NotifyLedger.claim(this, okKey)) {
            showSuccessTip(type, time, "手动标记")
            Notifier.result(this, type, true, time, "手动标记")
        }
        Feedback.speak(this, "已记录${type.label}打卡")
    }

    // ---------- 测试 ----------

    private fun testFeedback(type: PunchType) {
        val time = nowTime()
        Feedback.success(this, type, time)
        val extra = if (type == PunchType.IN) {
            "应下班 ${PunchRules.expectOutText(PunchStore.get(this, LocalDate.now()))}"
        } else null
        showSuccessTip(type, time, extra)
        Notifier.result(this, type, true, time, extra)
    }

    /** 测试页点某个场景：只走判定 + 提示 / 通知，不写记录、不点飞书 */
    private fun testScenario(key: String?) {
        val scenario = TestScenario.fromKey(key)
        if (scenario == null) {
            Notifier.result(this, PunchType.IN, false, null, "未知的测试场景：$key")
            return
        }
        TestScenarios.render(this, scenario)
    }

    private fun testOutside(now: PunchNow) {
        val record = PunchStore.get(this, LocalDate.now())
        val status = PunchStatusText.of(now, LocalDateTime.now(), record)
        SuccessOverlay.showRaw(
            this,
            badge = status.title,
            title = status.title,
            warm = now.hint,
            extra = null
        )
    }

    private fun outsideNotice(now: PunchNow, asToast: Boolean) {
        val today = LocalDate.now()
        val record = PunchStore.get(this, today)
        // 主提示按实际情形写（还没到点 / 上班卡漏了 / 下班卡漏了 / 休息日 / 今天已完成），
        // 不再笼统地说「打卡没打上」
        val status = PunchStatusText.of(now, LocalDateTime.now(), record)
        if (asToast) testOutside(now)
        Notifier.statusNotice(this, status.type, status.title, status.text)
    }

    // ---------- 小工具 ----------

    private fun showSuccessTip(type: PunchType, time: String, extra: String?): Boolean {
        if (!PunchConfig.overlayOn()) return false
        if (!Settings.canDrawOverlays(this)) return false
        SuccessOverlay.show(this, type, time, extra)
        return true
    }

    private fun startForegroundSafe(text: String) {
        try {
            startForeground(Notifier.ID_RUN, Notifier.runNotification(this, text))
        } catch (e: Exception) {
            // 通知权限被关时也要能继续跑
        }
    }

    private fun updateRun(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                Notifier.ID_RUN,
                Notifier.runNotification(this, text)
            )
        } catch (e: Exception) {
        }
    }

    private fun stopSoon() {
        scope.launch {
            delay(300)
            try {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
            }
            stopSelf()
        }
    }

    private fun nowTime(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    companion object {
        const val ACTION_AUTO = "com.shuangka.punch.action.AUTO"
        const val ACTION_KEEPALIVE = "com.shuangka.punch.action.KEEPALIVE"
        const val ACTION_MANUAL = "com.shuangka.punch.action.MANUAL"
        const val ACTION_MARK_DONE = "com.shuangka.punch.action.MARK_DONE"
        const val ACTION_TEST_FEEDBACK = "com.shuangka.punch.action.TEST_FEEDBACK"
        const val ACTION_TEST_BOTH = "com.shuangka.punch.action.TEST_BOTH"
        const val ACTION_TEST_OUTSIDE = "com.shuangka.punch.action.TEST_OUTSIDE"
        const val ACTION_TEST_SCENARIO = "com.shuangka.punch.action.TEST_SCENARIO"
        const val EXTRA_TYPE = "extra_punch_type"
        const val EXTRA_SCENARIO = "extra_scenario"

        fun intentFor(ctx: Context, action: String, type: PunchType): Intent =
            Intent(ctx, PunchService::class.java)
                .setAction(action)
                .putExtra(EXTRA_TYPE, type.key)

        fun start(ctx: Context, action: String, type: PunchType) {
            try {
                ContextCompat.startForegroundService(ctx, intentFor(ctx, action, type))
            } catch (e: Exception) {
                // 后台启动被系统拒绝时安静失败，用户下次打开 App 能看到状态
            }
        }

        /** 测试页：按场景跑一次假的判定链路 */
        fun startScenario(ctx: Context, key: String) {
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, PunchService::class.java)
                        .setAction(ACTION_TEST_SCENARIO)
                        .putExtra(EXTRA_SCENARIO, key)
                )
            } catch (e: Exception) {
            }
        }
    }
}
