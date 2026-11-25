package com.shuangka.punch.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 常驻守护服务：没有任何打卡动作，只负责「让进程活着、并把闹钟/自检一直挂着」。
 * - START_STICKY：被系统清掉后系统会尽量把它拉回来
 * - 每 60 秒写一次心跳，通知栏显示最近一次自检时间
 * - onTaskRemoved（用户划掉最近任务）时自己再排一次自检闹钟
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            KeepAlive.beat(this@KeepAliveService, "常驻心跳")
            // 每 10 分钟刷新一次通知上的时间，别太频繁换通知
            if (ticks % 10 == 0) refreshNotification()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        startForegroundSafe()
        KeepAlive.beat(this, "常驻服务启动")
        KeepAlive.scheduleChecks(this)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        KeepAlive.scheduleChecks(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            PunchScheduler.scheduleAll(this)
            KeepAlive.scheduleChecks(this)
        } catch (e: Exception) {
            // 排不上就靠自检闹钟兜底
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 被杀前再把自检排上，等闹钟把自己叫回来
        try {
            KeepAlive.scheduleChecks(this)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        try {
            startForeground(Notifier.ID_KEEP, Notifier.keepNotification(this))
        } catch (e: Exception) {
            // 通知权限被关也继续跑
        }
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.notify(Notifier.ID_KEEP, Notifier.keepNotification(this))
        } catch (e: Exception) {
        }
    }

    companion object {
        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            try {
                ctx.startForegroundService(intent)
            } catch (e: Exception) {
                try {
                    ctx.startService(intent)
                } catch (e2: Exception) {
                }
            }
        }
    }
}
