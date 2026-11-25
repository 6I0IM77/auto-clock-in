package com.shuangka.punch.core

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保活自检：不承诺「永不被杀」，而是做到「被杀之后能立刻看出来、并且一键拉回来」。
 *
 * 判断依据（从强到弱）：
 * 1. 常驻通知还在 → 前台服务活着，最可靠
 * 2. 无障碍服务还在系统已启用列表里 → 打卡通道还能用（无障碍被系统重启时会自动回来）
 * 3. 上面两条都没有 → 只剩余心跳时间，超过阈值就判为「可能被清理」
 *
 * 心跳写入点：进程冷启动、闹钟触发、磁贴点击、前台服务启动。
 */
object KeepAlive {

    private const val PREF = "keep_alive"
    private const val KEY_LAST = "last_beat"
    private const val KEY_TAG = "last_tag"

    /** 心跳间隔超过这个时长，又没有常驻通知/无障碍，就认为被杀过 */
    private const val STALE_MS = 30L * 60 * 1000

    fun beat(ctx: Context, tag: String) {
        ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putString(KEY_TAG, tag)
            .apply()
    }

    fun lastBeatMillis(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_LAST, 0L)

    fun lastBeatText(ctx: Context): String {
        val at = lastBeatMillis(ctx)
        if (at <= 0L) return "还没有记录"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
    }

    /** 常驻通知（ID_RUN）还在 = 前台服务在跑 */
    fun serviceAlive(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.activeNotifications.any { it.id == Notifier.ID_RUN }
    } catch (e: Exception) {
        false
    }

    /** 无障碍服务是否仍然启用 */
    fun accessibilityAlive(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains("${ctx.packageName}/")
    } catch (e: Exception) {
        false
    }

    private fun stale(ctx: Context): Boolean =
        System.currentTimeMillis() - lastBeatMillis(ctx) > STALE_MS

    /** 一行状态，主页/磁贴共用 */
    fun statusText(ctx: Context): String = when {
        serviceAlive(ctx) -> "在跑"
        accessibilityAlive(ctx) -> "无障碍在跑"
        stale(ctx) -> "可能被清理"
        else -> "待唤醒"
    }

    /** 点开状态时的详细说明 */
    fun detailText(ctx: Context): String = buildString {
        append("常驻服务：")
        append(if (serviceAlive(ctx)) "在跑 ✔" else "没有常驻通知 ✘")
        append("\n无障碍：")
        append(if (accessibilityAlive(ctx)) "已启用 ✔" else "未启用 ✘")
        append("\n最近心跳：")
        append(lastBeatText(ctx))
        append("\n\n判定规则：常驻通知在就说明前台服务没被杀；"
            + "通知没了但无障碍还在，打卡通道依旧可用；"
            + "两个都不在、且心跳超过 30 分钟没更新，才判为「可能被清理」。")
    }

    // ---------- 保活手段 ----------

    const val ACTION_CHECK = "com.shuangka.punch.action.KEEP_CHECK"
    private const val REQ_ALARM = 8001
    private const val JOB_ID = 8021
    private const val CHECK_INTERVAL_MS = 15L * 60 * 1000

    /** 第 1 招：拉起常驻前台服务 */
    fun ensureService(ctx: Context) {
        KeepAliveService.start(ctx.applicationContext)
    }

    /** 第 2 招：AlarmManager 每 15 分钟自检一次（allowWhileIdle，打盹也能叫醒） */
    fun scheduleChecks(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val am = app.getSystemService(android.app.AlarmManager::class.java)
            am?.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CHECK_INTERVAL_MS,
                checkPending(app)
            )
        } catch (e: Exception) {
            // 没精确闹钟权限就靠 JobScheduler 兜
        }
        scheduleJob(app)
    }

    fun cancelChecks(ctx: Context) {
        try {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            am?.cancel(checkPending(ctx.applicationContext))
        } catch (e: Exception) {
        }
    }

    /** 自检入口：闹钟/Job 到点、开机、磁贴点击都走这里 */
    fun checkAndReschedule(ctx: Context) {
        val app = ctx.applicationContext
        beat(app, "定时自检")
        // 常驻服务没了（被杀 / 被省电策略掐了）就立刻拉回来
        if (!serviceAlive(app)) ensureService(app)
        // 打卡闹钟也顺手校准一遍，防止内存被清后闹钟丢了
        try {
            PunchScheduler.scheduleAll(app)
        } catch (e: Exception) {
        }
        scheduleChecks(app)
    }

    /** 第 3 招：JobScheduler 周期任务（setPersisted 重启后依然有效） */
    private fun scheduleJob(ctx: Context) {
        try {
            val js = ctx.getSystemService(android.app.job.JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = android.app.job.JobInfo.Builder(
                JOB_ID,
                android.content.ComponentName(ctx, KeepAliveJob::class.java)
            )
                .setPeriodic(CHECK_INTERVAL_MS)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(job)
        } catch (e: Exception) {
        }
    }

    private fun checkPending(ctx: Context): android.app.PendingIntent {
        val intent = android.content.Intent(ctx, KeepAliveReceiver::class.java)
            .setAction(ACTION_CHECK)
        return android.app.PendingIntent.getBroadcast(
            ctx,
            REQ_ALARM,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
}
