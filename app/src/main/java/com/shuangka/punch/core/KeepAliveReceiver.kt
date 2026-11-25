package com.shuangka.punch.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 定时自检闹钟的接收者：到点看常驻服务还在不在，不在就拉起来，再把下一次排上 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        KeepAlive.checkAndReschedule(context.applicationContext)
    }
}
