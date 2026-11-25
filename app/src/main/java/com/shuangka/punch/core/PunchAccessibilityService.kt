package com.shuangka.punch.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.shuangka.punch.core.engine.AccessibilityUiController

/**
 * 唯一的无障碍服务：既给「自动点」提供读屏/点击能力，
 * 也负责在你自己手动打卡后识别飞书的成功提示。
 */
class PunchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityUiController.service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        PunchWatcher.onEvent(event)
    }

    override fun onInterrupt() {
        // 系统回收时无需处理
    }

    override fun onDestroy() {
        AccessibilityUiController.service = null
        super.onDestroy()
    }

    /** 按坐标点一下（节点不可点时的兜底） */
    fun tap(x: Int, y: Int): Boolean {
        if (x <= 0 || y <= 0) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
