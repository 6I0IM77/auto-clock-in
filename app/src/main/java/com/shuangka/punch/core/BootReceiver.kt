package com.shuangka.punch.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val ctx = context.applicationContext
            KeepAlive.beat(ctx, "开机")
            PunchScheduler.scheduleAll(ctx)
            // 开机第一件事就把常驻服务挂起来，并排上 15 分钟一次的自检
            KeepAlive.ensureService(ctx)
            KeepAlive.scheduleChecks(ctx)
        }
    }
}
