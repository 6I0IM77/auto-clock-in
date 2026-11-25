package com.shuangka.punch.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 控制中心磁贴「长按」的入口。
 *
 * 长按磁贴时系统会找声明了 ACTION_QS_TILE_PREFERENCES 的 Activity，
 * 找不到就退回「应用信息 / 通知设置」页。这里挂一个透明中转页，
 * 一进来就直接把主界面拉起来再自己关掉，等于「长按 = 打开应用」。
 */
class TilePrefsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            // 拉不起来就安静关掉，别留白屏
        }
        finish()
    }
}
