package com.shuangka.punch.core.engine

import android.content.Context
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Shizuku 通道：用 shell 权限跑 uiautomator dump 读界面、input tap 点按钮。
 * 不需要无障碍，但要装 Shizuku 并授权（用无线调试或 root 启动）。
 */
class ShizukuUiController(private val ctx: Context) : UiController {

    override fun name(): String = "Shizuku"

    override fun isReady(): Boolean = ShizukuHelper.granted()

    override suspend fun launchFeishu(): Boolean = withContext(Dispatchers.IO) {
        try {
            sh(FeishuLauncher.amStartCommand(), 15_000L)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun snapshot(): UiNode? = withContext(Dispatchers.IO) {
        try {
            val xml = sh(
                "uiautomator dump --compressed /data/local/tmp/shuangka_ui.xml >/dev/null 2>&1; " +
                    "cat /data/local/tmp/shuangka_ui.xml",
                25_000L
            )
            SnapshotParser.parse(xml)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun click(node: UiNode): Boolean = withContext(Dispatchers.IO) {
        val x = node.bounds.centerX()
        val y = node.bounds.centerY()
        if (x <= 0 || y <= 0) return@withContext false
        try {
            sh("input tap $x $y", 8_000L)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sh(command: String, timeoutMs: Long): String {
        val process = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", command), null, null)
        val out = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            } catch (e: Exception) {
                // 进程被杀时读流会抛，忽略
            }
        }
        reader.start()

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
        if (!finished) {
            try {
                process.destroy()
            } catch (e: Exception) {
            }
        }
        reader.join(if (finished) 3_000L else 1_000L)
        return out.toString()
    }
}
