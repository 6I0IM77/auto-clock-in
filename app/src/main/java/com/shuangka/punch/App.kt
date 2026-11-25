package com.shuangka.punch

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.shuangka.punch.core.KeepAlive
import com.shuangka.punch.core.Notifier
import com.shuangka.punch.core.PunchScheduler
import com.shuangka.punch.data.HolidayRemote
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 跟随系统主题色（Android 12+ 取壁纸配色，低版本自动回落到应用自带配色）
        DynamicColors.applyToActivitiesIfAvailable(this)
        PunchConfig.init(this)
        PunchStore.init(this)
        Notifier.createChannels(this)
        // 每次冷启动都校准一次闹钟，防止系统清理后漏打卡
        PunchScheduler.scheduleAll(this)
        KeepAlive.beat(this, "冷启动")
        // 冷启动顺手把常驻守护和自检挂上（保活链条从这里续上）
        KeepAlive.ensureService(this)
        KeepAlive.scheduleChecks(this)
        syncCalendarInBackground()
    }

    /**
     * 冷启动顺带同步一次联网日历（七天一次），这样节假日调休更新后不用重新装包。
     * 失败就继续用缓存 / 内置表，不打扰用户。
     */
    private fun syncCalendarInBackground() {
        val stale = System.currentTimeMillis() - HolidayRemote.lastSyncAt() > 7L * 24 * 3600 * 1000
        val missing = HolidayRemote.defaultYears().any { !HolidayRemote.hasYear(it) }
        if (!stale && !missing) return
        Thread {
            if (HolidayRemote.sync().isSuccess) {
                PunchScheduler.scheduleAll(this)
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        @Volatile
        lateinit var instance: App
            private set
    }
}

