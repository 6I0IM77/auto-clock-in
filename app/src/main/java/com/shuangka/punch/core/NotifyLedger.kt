package com.shuangka.punch.core

import android.content.Context
import java.time.LocalDate

/**
 * 推送记账簿：同一天的同一种推送只允许过一次。
 * 上班提醒 / 下班提醒 / 上班成功 / 下班成功 / 上班漏卡 / 下班漏卡 各自一个键，
 * 日期一变自动作废，第二天照常推。
 */
object NotifyLedger {

    private const val PREF = "notify_ledger"

    const val OK_IN = "ok_in"
    const val OK_OUT = "ok_out"
    const val REMIND_IN = "remind_in"
    const val REMIND_OUT = "remind_out"
    const val MISSED_IN = "missed_in"
    const val MISSED_OUT = "missed_out"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun sent(ctx: Context, key: String): Boolean =
        prefs(ctx).getString(key, null) == LocalDate.now().toString()

    /** 第一次抢到返回 true（可以推），今天已经推过返回 false */
    fun claim(ctx: Context, key: String): Boolean = synchronized(this) {
        val today = LocalDate.now().toString()
        val p = prefs(ctx)
        if (p.getString(key, null) == today) {
            false
        } else {
            p.edit().putString(key, today).apply()
            true
        }
    }
}
