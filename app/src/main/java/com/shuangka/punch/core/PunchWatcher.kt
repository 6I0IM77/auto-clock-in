package com.shuangka.punch.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shuangka.punch.App
import com.shuangka.punch.core.engine.AccessibilityUiController
import com.shuangka.punch.core.engine.PunchPageParser
import com.shuangka.punch.core.feedback.Feedback
import com.shuangka.punch.core.feedback.SuccessOverlay
import com.shuangka.punch.data.DayRecord
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 「你在飞书里点完卡之后，App 也要吱一声」的实现：
 * 盯住飞书窗口，一旦出现 打卡成功 / 已打卡 09:03，就写记录 + 提示。
 */
object PunchWatcher {

    private const val MIN_INTERVAL_MS = 2500L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeRegex = Regex("(\\d{1,2}:\\d{2})")

    private var lastScanAt = 0L
    private var lastRejectAt = 0L
    private var scanning = false

    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!PunchConfig.watchOn()) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != PunchConfig.feishuPackage()) return

        // 飞书的 Toast 会以通知事件上报，这条最可靠；Toast 里没有区块结构，按当前时间窗口判类型
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text?.joinToString(" ") ?: return
            handleToast(text)
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return

        val now = SystemClock.elapsedRealtime()
        if (scanning || now - lastScanAt < MIN_INTERVAL_MS) return
        lastScanAt = now
        scanning = true
        executor.execute {
            try {
                scan()
            } catch (t: Throwable) {
                // 读屏失败不影响主流程
            } finally {
                scanning = false
            }
        }
    }

    private fun scan() {
        val service = AccessibilityUiController.service ?: return
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        val lines = ArrayList<String>()
        collect(root, lines, 0)
        handle(lines)
    }

    private fun handle(lines: List<String>) {
        if (lines.isEmpty()) return
        // 只有在真正的打卡页（含「应上班 / 应下班」）才认页面上的「已打卡」，
        // 看「打卡记录 / 考勤明细」这类历史列表时直接跳过，避免把旧记录当成今天的
        if (lines.none { it.contains("应上班") || it.contains("应下班") }) return

        val marks = PunchPageParser.parse(lines)
        if (marks.inTime == null && marks.outTime == null) return

        val today = LocalDate.now()
        val existing = PunchStore.get(App.instance, today)

        // 上班：页面上「应上班」区块下出现已打卡时间，且时间与此刻都在上班窗口里
        val inTime = marks.inTime
        if (inTime != null && existing?.inTime == null && accepted(PunchType.IN, inTime, existing)) {
            val updated = PunchStore.record(App.instance, today, PunchType.IN, inTime, false)
            notifySuccess(PunchType.IN, inTime, updated)
        }

        // 下班：同理，绝不在下班窗口之外把历史记录记成今天的下班卡
        val outTime = marks.outTime
        if (outTime != null && existing?.outTime == null && accepted(PunchType.OUT, outTime, existing)) {
            val updated = PunchStore.record(App.instance, today, PunchType.OUT, outTime, false)
            notifySuccess(PunchType.OUT, outTime, updated)
        }
    }

    /** Toast 里只有「打卡成功」，没有时间也没有区块，只能按当前窗口判类型 */
    private fun handleToast(text: String) {
        if (!PunchConfig.successKeywords().any { text.contains(it) }) return
        val today = LocalDate.now()
        val existing = PunchStore.get(App.instance, today)
        // 带上今天的记录，下班窗口才会按「今天上班晚了多少」顺延，不会把 Toast 判错类型
        val now = PunchRules.now(record = existing)
        val type = now.type ?: return
        val already = when (type) {
            PunchType.IN -> existing?.inTime
            PunchType.OUT -> existing?.outTime
        }
        if (already != null) return

        val time = timeRegex.find(text)?.value ?: nowTime()
        if (!accepted(type, time, existing)) return
        val updated = PunchStore.record(App.instance, today, type, time, false)
        notifySuccess(type, time, updated)
    }

    /**
     * 判定这次「打卡成功」能不能记账，三道关都必须过：
     * 1. 现在的钟点本身就在窗口内（避免在看打卡记录、或在窗口外时被旧记录带跑）
     * 2. 页面上那条打卡时间也在窗口内（窗口内出现的「已打卡」就是今天这张卡）
     * 3. 页面时间离现在不超过这个窗口的长度，防止翻到旧记录被带跑
     */
    private fun accepted(type: PunchType, pageTime: String?, record: DayRecord?): Boolean {
        val window = windowOf(type, record)
        val now = LocalTime.now()
        if (!window.contains(now)) {
            rejectNotice(type, pageTime, window)
            return false
        }
        if (pageTime == null) return true
        val minutes = PunchRules.parseMinutes(pageTime) ?: return false
        val time = LocalTime.of(minutes / 60, minutes % 60)
        if (!window.contains(time)) return false
        val span = java.time.Duration.between(window.start, window.end).toMinutes().coerceAtLeast(30)
        val diff = kotlin.math.abs(minutes - (now.hour * 60 + now.minute))
        if (diff > span) {
            rejectNotice(type, pageTime, window)
            return false
        }
        return true
    }

    private fun windowOf(type: PunchType, record: DayRecord?) =
        if (type == PunchType.IN) PunchRules.inWindow() else PunchRules.outWindow(record)

    /** 有「打卡成功」的痕迹但不在窗口里：说清为什么没记账，别默默吞掉 */
    private fun rejectNotice(type: PunchType, pageTime: String?, window: com.shuangka.punch.data.TimeWindow) {
        val stamp = SystemClock.elapsedRealtime()
        if (stamp - lastRejectAt < 60_000L) return
        lastRejectAt = stamp
        val detail = "${type.label}打卡时间 ${pageTime ?: "刚刚"} 不在${type.label}窗口 ${window.text()} 内，这条没有记账"
        mainHandler.post {
            Notifier.result(App.instance, type, false, pageTime, detail)
        }
    }

    private fun notifySuccess(type: PunchType, time: String, updated: DayRecord) {
        // 同一天同一张卡只推一次：Toast 那条和页面这条经常一前一后都命中
        val key = if (type == PunchType.IN) NotifyLedger.OK_IN else NotifyLedger.OK_OUT
        if (!NotifyLedger.claim(App.instance, key)) return
        Feedback.success(App.instance, type, time)
        mainHandler.post {
            if (PunchConfig.overlayOn() && Settings.canDrawOverlays(App.instance)) {
                SuccessOverlay.show(App.instance, type, time, "飞书已确认")
            }
            // 手动在飞书里点完卡，通知栏同样要推一条「打卡成功」
            Notifier.result(App.instance, type, true, time, "飞书已确认")
        }
    }

    private fun collect(node: AccessibilityNodeInfo, lines: MutableList<String>, depth: Int) {
        if (depth > 40 || lines.size > 600) return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) lines.add(text)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            collect(child, lines, depth + 1)
        }
    }

    private fun nowTime(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
}
