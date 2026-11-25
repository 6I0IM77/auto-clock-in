package com.shuangka.punch.core.engine

/** 一条能操作界面的通道（无障碍 / Shizuku） */
interface UiController {
    fun name(): String
    fun isReady(): Boolean
    suspend fun launchFeishu(): Boolean
    suspend fun snapshot(): UiNode?
    suspend fun click(node: UiNode): Boolean
}
