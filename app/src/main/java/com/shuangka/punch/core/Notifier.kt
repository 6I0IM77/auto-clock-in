package com.shuangka.punch.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shuangka.punch.R
import com.shuangka.punch.core.engine.FeishuLauncher
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import com.shuangka.punch.ui.MainActivity

object Notifier {

    const val CH_RUN = "punch_run"
    const val CH_RESULT = "punch_result"
    const val CH_REMIND = "punch_reminder"
    const val CH_KEEP = "punch_keep"

    const val ID_RUN = 9001
    const val ID_RESULT_IN = 9002      // 上班卡结果（成功/失败都占这一条）
    const val ID_RESULT_OUT = 9004     // 下班卡结果
    const val ID_ALL_DONE = 9005       // 上下班两卡都打上
    const val ID_REMIND = 9003
    const val ID_KEEP = 9006           // 常驻守护

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val run = NotificationChannel(CH_RUN, "打卡进行中", NotificationManager.IMPORTANCE_LOW).apply {
            description = "自动打卡时显示的后台进度"
            setShowBadge(false)
        }
        // 结果通知不放声音：声音/语音由 App 自己播，避免重复
        val result = NotificationChannel(CH_RESULT, "打卡结果", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "上班/下班打卡成功或失败"
            setSound(null, null)
            enableVibration(false)
        }
        val remind = NotificationChannel(CH_REMIND, "打卡提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "走到打卡窗口时提醒"
        }
        // 常驻守护用最低重要级：不出声、不弹、不占锁屏，只保证进程活着
        val keep = NotificationChannel(CH_KEEP, "后台守护", NotificationManager.IMPORTANCE_MIN).apply {
            description = "让打卡服务常驻，避免被杀后漏卡"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannels(listOf(run, result, remind, keep))
    }

    /** 常驻守护通知：低调地待在通知栏，被杀时它消失就是最直观的信号 */
    fun keepNotification(ctx: Context): Notification {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags())
        return NotificationCompat.Builder(ctx, CH_KEEP)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("自动打卡 · 后台待命")
            .setContentText("最近自检 ${KeepAlive.lastBeatText(ctx)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    fun runNotification(ctx: Context, text: String): Notification {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags())
        return NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("自动打卡")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 上班/下班打卡成功的通知：和「没打上」那条同一套样式，只是文案换成成功 */
    fun successNotice(ctx: Context, type: PunchType, time: String?, detail: String?) {
        val text = buildList {
            add(if (time.isNullOrBlank()) "打卡成功" else "打卡成功 $time")
            detail?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("，")

        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("${type.label}打卡成功")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )
        notify(ctx, if (type == PunchType.IN) ID_RESULT_IN else ID_RESULT_OUT, n.build())
    }

    /** 上下班两张卡都打上了，再补一条「今天都打上了」的通知 */
    fun allDoneNotice(ctx: Context, inTime: String?, outTime: String?) {
        val text = "上班 ${inTime ?: "-"}、下班 ${outTime ?: "-"} 都打上了，今天收工"

        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("今日打卡完成")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )
        notify(ctx, ID_ALL_DONE, n.build())
    }

    /** 状态类通知（不在打卡时间内 / 漏卡 / 休息日 / 今天已完成），和结果通知同一套样式 */
    fun statusNotice(ctx: Context, type: PunchType, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )
        notify(ctx, if (type == PunchType.IN) ID_RESULT_IN else ID_RESULT_OUT, n.build())
    }

    /** 漏卡提醒：截止时间过后这张卡还没打上 */
    fun missedNotice(ctx: Context, type: PunchType, windowText: String) {
        val text = "${type.label}窗口 $windowText 已结束，还没打上；点「重试」或打开飞书补一下"
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("${type.label}卡漏打了")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )
        val retry = PendingIntent.getForegroundService(
            ctx, 2,
            PunchService.intentFor(ctx, PunchService.ACTION_MANUAL, type),
            flags()
        )
        n.addAction(0, "重试", retry)
        feishuPending(ctx, 3)?.let { n.addAction(0, "打开飞书", it) }
        notify(ctx, if (type == PunchType.IN) ID_RESULT_IN else ID_RESULT_OUT, n.build())
    }

    fun result(ctx: Context, type: PunchType, success: Boolean, time: String?, detail: String?) {
        if (success) {
            successNotice(ctx, type, time, detail)
            return
        }
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("${type.label}打卡没打上")
            .setContentText(detail ?: "点通知里的重试再试一次")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), flags())
            )
        val retry = PendingIntent.getForegroundService(
            ctx, 2,
            PunchService.intentFor(ctx, PunchService.ACTION_AUTO, type),
            flags()
        )
        n.addAction(0, "重试", retry)
        feishuPending(ctx, 3)?.let { n.addAction(0, "打开飞书", it) }
        notify(ctx, if (type == PunchType.IN) ID_RESULT_IN else ID_RESULT_OUT, n.build())
    }

    fun remind(ctx: Context, type: PunchType) {
        val b = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_punch)
            .setContentTitle("该${type.label}打卡了")
            .setContentText("${PunchConfig.windowLabel(type)} 内打完才算按时")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        feishuPending(ctx, 4)?.let {
            b.setContentIntent(it)
            b.addAction(0, "打开飞书", it)
        }
        val markDone = PendingIntent.getForegroundService(
            ctx, 5,
            PunchService.intentFor(ctx, PunchService.ACTION_MARK_DONE, type),
            flags()
        )
        b.addAction(0, "我已完成", markDone)
        notify(ctx, ID_REMIND, b.build())
    }

    private fun feishuPending(ctx: Context, requestCode: Int): PendingIntent? {
        val intent = FeishuLauncher.intent(ctx) ?: return null
        return PendingIntent.getActivity(ctx, requestCode, intent, flags())
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: Exception) {
            // 没有通知权限时安静失败
        }
    }
}
