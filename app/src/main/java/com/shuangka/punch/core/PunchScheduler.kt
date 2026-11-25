package com.shuangka.punch.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchRules
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object PunchScheduler {

    private fun requestCode(type: PunchType): Int = if (type == PunchType.IN) 7001 else 7002

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /**
     * 下一次触发时间：
     * 上班卡 = 工作日的 09:00；下班卡 = 当天弹性后的「应下班」，过了就排到下一个工作日 18:00。
     */
    fun nextTrigger(ctx: Context, type: PunchType, from: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = from.toLocalDate()
        var date = today
        // 春节这类长假可能连着休息十来天，往后找 20 天足够跨过去
        repeat(20) {
            if (PunchRules.isWorkday(date)) {
                val minutes = if (type == PunchType.IN) {
                    PunchConfig.startMinutes(PunchType.IN)
                } else if (date == today) {
                    val rec = PunchStore.get(ctx, date)
                    val w = PunchRules.outWindow(rec)
                    w.start.hour * 60 + w.start.minute
                } else {
                    PunchConfig.startMinutes(PunchType.OUT)
                }
                val dt = LocalDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60))
                if (dt.isAfter(from)) return dt
            }
            date = date.plusDays(1)
        }
        return LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0))
    }

    fun scheduleAll(ctx: Context) {
        PunchType.values().forEach {
            scheduleNext(ctx, it)
            scheduleRemind(ctx, it)
            scheduleMissedCheck(ctx, it)
        }
    }

    /**
     * 漏卡提醒：窗口截止时间后 5 分钟响一次，
     * 到点时再查记录——已经打上就什么都不做，没打上才推「漏打了」。
     * 截止时间用设置里的值，改了就跟着变。
     */
    fun scheduleMissedCheck(ctx: Context, type: PunchType) {
        if (!canScheduleExact(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var date = now.toLocalDate()
        var trigger: LocalDateTime? = null
        var guard = 0
        while (guard < 20) {
            if (PunchRules.isWorkday(date)) {
                val w = if (type == PunchType.IN) {
                    PunchRules.inWindow()
                } else {
                    PunchRules.outWindow(PunchStore.get(ctx, date))
                }
                val at = LocalDateTime.of(date, w.end).plusMinutes(5)
                if (at.isAfter(now)) {
                    trigger = at
                    break
                }
            }
            date = date.plusDays(1)
            guard++
        }
        val at = trigger ?: return
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                missedPending(ctx, type)
            )
        } catch (e: SecurityException) {
        }
    }

    fun cancelMissedCheck(ctx: Context, type: PunchType) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(missedPending(ctx, type)) }
    }

    private fun missedPending(ctx: Context, type: PunchType): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_MISSED)
            .putExtra(AlarmReceiver.EXTRA_TYPE, type.key)
        return PendingIntent.getBroadcast(
            ctx,
            if (type == PunchType.IN) 7021 else 7022,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 提醒闹钟：
     * 上班 = 窗口开始前 N 分钟（设置里的「上班提前提醒」）
     * 下班 = 窗口开启后 M 分钟（设置里的「下班提醒」，0 = 窗口一开就推）
     * 下班窗口的开始时间按今天上班实际打卡时间顺延，所以提醒也跟着顺延。
     */
    fun scheduleRemind(ctx: Context, type: PunchType) {
        if (!canScheduleExact(ctx)) return
        val now = LocalDateTime.now()
        // 今天这条提醒已经推过了：直接排到下一个工作日的窗口，别在窗口里反复补发
        val remindKey = if (type == PunchType.IN) NotifyLedger.REMIND_IN else NotifyLedger.REMIND_OUT
        val start = if (NotifyLedger.sent(ctx, remindKey)) {
            nextTrigger(
                ctx, type,
                LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT)
            )
        } else {
            nextTrigger(ctx, type)
        }
        val remindAt = if (type == PunchType.IN) {
            start.minusMinutes(PunchConfig.remindBeforeIn().toLong())
        } else {
            val after = PunchConfig.remindAfterOut()
            val end = PunchRules.outWindow(PunchStore.get(ctx, start.toLocalDate())).end
            val capped = start.plusMinutes(after.toLong())
            if (after > 0 && capped.toLocalTime().isAfter(end)) start.plusMinutes(1) else capped
        }
        val fireAt = when {
            remindAt.isAfter(now) -> remindAt
            // 已经进到提醒范围里、窗口还没关门：马上补一条，而不是等到明天
            start.toLocalDate() == now.toLocalDate() && !now.toLocalTime().isAfter(
                if (type == PunchType.IN) PunchRules.inWindow().end
                else PunchRules.outWindow(PunchStore.get(ctx, now.toLocalDate())).end
            ) -> now.plusSeconds(5)
            else -> return
        }
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                remindPending(ctx, type)
            )
        } catch (e: SecurityException) {
            // 没有精确闹钟权限时跳过，下次进 App 补排
        }
    }

    fun cancelRemind(ctx: Context, type: PunchType) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(remindPending(ctx, type)) }
    }

    fun cancelAllRemind(ctx: Context) {
        PunchType.values().forEach { cancelRemind(ctx, it) }
    }

    private fun remindPending(ctx: Context, type: PunchType): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_REMIND)
            .putExtra(AlarmReceiver.EXTRA_TYPE, type.key)
        return PendingIntent.getBroadcast(
            ctx,
            if (type == PunchType.IN) 7011 else 7012,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleNext(ctx: Context, type: PunchType) {
        if (!canScheduleExact(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTrigger(ctx, type).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending(ctx, type))
        } catch (e: SecurityException) {
            // 没有精确闹钟权限，下一次进 App 时会补排
        }
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        PunchType.values().forEach {
            runCatching { am.cancel(pending(ctx, it)) }
        }
        cancelAllRemind(ctx)
        PunchType.values().forEach { cancelMissedCheck(ctx, it) }
    }

    private fun pending(ctx: Context, type: PunchType): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_ALARM)
            .putExtra(AlarmReceiver.EXTRA_TYPE, type.key)
        return PendingIntent.getBroadcast(
            ctx,
            requestCode(type),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
