package com.shuangka.punch.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import java.time.LocalDate
import java.time.LocalDateTime

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        val wantType = PunchType.fromKey(intent?.getStringExtra(EXTRA_TYPE))
        // 闹钟能响就说明进程还在，记一次心跳（保活自检靠它判断「多久没动静」）
        KeepAlive.beat(ctx, "闹钟 ${wantType.label}")

        // 漏卡提醒：截止后 1 分钟查一次，已经打上就闭嘴，没打上才提醒
        if (intent?.action == ACTION_MISSED) {
            handleMissed(ctx, wantType)
            return
        }

        // 提前提醒：只提醒、不打卡；这张卡今天已经打过、或今天已经推过提醒，就只重排下一次
        if (intent?.action == ACTION_REMIND) {
            val rec = PunchStore.get(ctx, LocalDate.now())
            val already = if (wantType == PunchType.IN) rec?.inTime else rec?.outTime
            val key = if (wantType == PunchType.IN) NotifyLedger.REMIND_IN else NotifyLedger.REMIND_OUT
            if (already == null && NotifyLedger.claim(ctx, key)) Notifier.remind(ctx, wantType)
            PunchScheduler.scheduleRemind(ctx, wantType)
            return
        }

        // 到点时按规则再看一眼「现在到底该打哪张卡」，弹性顺延后也不会打错
        val rec = PunchStore.get(ctx, LocalDate.now())
        val nowType = PunchRules.now(LocalDateTime.now(), rec).type

        if (PunchConfig.autoMode() && nowType != null) {
            PunchService.start(ctx, PunchService.ACTION_AUTO, nowType)
        } else {
            val key = if (wantType == PunchType.IN) NotifyLedger.REMIND_IN else NotifyLedger.REMIND_OUT
            val already = if (wantType == PunchType.IN) rec?.inTime else rec?.outTime
            if (already == null && NotifyLedger.claim(ctx, key)) Notifier.remind(ctx, wantType)
        }

        // 无论成功与否，先把下一次排上，避免只响一次
        PunchScheduler.scheduleNext(ctx, wantType)
    }

    /**
     * 漏卡判定：截止时间后 5 分钟提醒一次；
     * 这张卡已经打上、或今天不是工作日，就什么都不发。
     */
    private fun handleMissed(ctx: Context, type: PunchType) {
        val today = LocalDate.now()
        PunchScheduler.scheduleMissedCheck(ctx, type)
        if (!PunchRules.isWorkday(today)) return

        val rec = PunchStore.get(ctx, today)
        val done = if (type == PunchType.IN) rec?.inTime else rec?.outTime
        if (done != null) return
        if (rec?.inTime != null && rec.outTime != null) return

        val window = if (type == PunchType.IN) {
            PunchRules.inWindow()
        } else {
            PunchRules.outWindow(rec)
        }
        val nowAt = LocalDateTime.now()
        val deadline = LocalDateTime.of(today, window.end)
        // 闹钟偶尔会晚一点响，留 5 分钟宽限；再晚就直接跳过，避免半夜补发过期提醒
        if (nowAt.isAfter(deadline.plusMinutes(10))) return

        val key = if (type == PunchType.IN) NotifyLedger.MISSED_IN else NotifyLedger.MISSED_OUT
        if (!NotifyLedger.claim(ctx, key)) return
        Notifier.missedNotice(ctx, type, window.text())
    }

    companion object {
        const val ACTION_ALARM = "com.shuangka.punch.action.ALARM"
        const val ACTION_REMIND = "com.shuangka.punch.action.REMIND"
        const val ACTION_MISSED = "com.shuangka.punch.action.MISSED"
        const val EXTRA_TYPE = "extra_punch_type"
    }
}
