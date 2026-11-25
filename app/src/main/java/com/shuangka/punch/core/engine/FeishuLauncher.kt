package com.shuangka.punch.core.engine

import android.content.Context
import android.content.Intent
import com.shuangka.punch.data.PunchConfig

object FeishuLauncher {

    fun intent(ctx: Context): Intent? {
        val pkg = PunchConfig.feishuPackage()
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return intent
    }

    fun launch(ctx: Context): Boolean {
        return try {
            val intent = intent(ctx) ?: return false
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Shizuku 通道用 shell 拉起飞书，绕过后台启动 Activity 的限制 */
    fun amStartCommand(): String =
        "monkey -p ${PunchConfig.feishuPackage()} -c android.intent.category.LAUNCHER 1"
}
