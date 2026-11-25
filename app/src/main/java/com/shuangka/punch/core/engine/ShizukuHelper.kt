package com.shuangka.punch.core.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku / Shevery / 兼容存根 的状态查询与授权。
 *
 * 背景：Shevery（com.hamondev.shevery）是 Shizuku 的独立分支，包名和权限命名空间都换了，
 * 官方 Shizuku-API 的 requestPermission 是写死发给 moe.shizuku.privileged.api 的，
 * 所以在只装了 Shevery 的机器上弹不出授权框。这里按几条路依次尝试：
 *   1. 官方 API（装了 Shizuku 或 Shevery 的兼容存根时可用）
 *   2. 直接给 Shevery 发它自己登记的 REQUEST_PERMISSION
 *   3. 给 Shevery 主包发旧版 moe.shizuku.manager 的 REQUEST_PERMISSION
 */
object ShizukuHelper {

    const val REQUEST_CODE = 4242

    private const val PKG_SHIZUKU = "moe.shizuku.privileged.api"
    private const val PKG_SHEVERY = "com.hamondev.shevery"

    enum class Result { OK, NOT_RUNNING, NEEDS_STUB, FAILED }

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun version(): Int = try {
        Shizuku.getVersion()
    } catch (e: Throwable) {
        0
    }

    /** 机器上装了哪个管理器：Shizuku / Shevery / 兼容存根 / 都没有 */
    fun implementation(ctx: Context, pm: PackageManager = ctx.packageManager): String {
        val installed = ArrayList<String>()
        if (isInstalled(pm, PKG_SHIZUKU)) installed.add("兼容存根")
        if (isInstalled(pm, PKG_SHEVERY)) installed.add("Shevery")
        return if (installed.isEmpty()) "" else installed.joinToString(" + ")
    }

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Throwable) {
        false
    }

    /** 拉起授权，返回结果，方便界面给出准确提示 */
    fun request(ctx: Context): Result {
        if (!running()) {
            // 只装了 Shevery 但服务没跑，也属于「需要处理」的情况
            return Result.NOT_RUNNING
        }

        // 1. 官方 API
        try {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE)
                return Result.OK
            }
        } catch (e: Throwable) {
            // 落到下面的兜底
        }

        // 2. 直接找 Shevery 自己的授权页
        if (sendRequest(ctx, PKG_SHEVERY, "com.hamondev.shevery.intent.action.REQUEST_PERMISSION")) {
            return Result.OK
        }
        if (sendRequest(ctx, PKG_SHEVERY, "moe.shizuku.manager.intent.action.REQUEST_PERMISSION")) {
            return Result.OK
        }
        if (sendRequest(ctx, PKG_SHEVERY, "moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION")) {
            return Result.OK
        }

        // 3. 装了 Shevery 却没弹窗，基本都是没开兼容存根
        return Result.NEEDS_STUB
    }

    private fun sendRequest(ctx: Context, pkg: String, action: String): Boolean {
        val pm = ctx.packageManager
        if (!isInstalled(pm, pkg)) return false
        return try {
            val intent = Intent(action).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("moe.shizuku.privileged.api.intent.extra.PACKAGE_NAME", ctx.packageName)
                putExtra("moe.shizuku.privileged.api.intent.extra.REQUEST_CODE", REQUEST_CODE)
                putExtra("com.hamondev.shevery.intent.extra.PACKAGE_NAME", ctx.packageName)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 打开管理器 App 的设置页，让用户自己开兼容存根 */
    fun openManager(ctx: Context): Boolean {
        val pm = ctx.packageManager
        for (pkg in listOf(PKG_SHIZUKU, PKG_SHEVERY)) {
            if (!isInstalled(pm, pkg)) continue
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            return try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                true
            } catch (e: Throwable) {
                false
            }
        }
        return false
    }
}
