package com.shuangka.punch.core.engine

import com.shuangka.punch.App

/** 选通道：无障碍优先（更稳、更省电），没有就退到 Shizuku */
object EnginePicker {

    fun pick(): UiController? {
        if (AccessibilityUiController.isReady()) return AccessibilityUiController
        val shizuku = ShizukuUiController(App.instance)
        return if (shizuku.isReady()) shizuku else null
    }

    /** 通道名（不含前缀），界面自己加「通道：」 */
    fun label(): String = when {
        AccessibilityUiController.isReady() -> "无障碍服务"
        ShizukuHelper.granted() -> "Shizuku"
        ShizukuHelper.running() -> "Shizuku 待授权"
        else -> "未开启（去设置里开无障碍）"
    }
}
