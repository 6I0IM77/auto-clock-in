package com.shuangka.punch.core.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 统一的界面节点模型：无障碍和 Shizuku(UiAutomator dump) 两条通道都转换成它，
 * 后面的「找按钮 / 点按钮」逻辑只写一份。
 */
class UiNode(
    val text: String,
    val bounds: Rect,
    val clickable: Boolean,
    val className: String? = null,
    val children: List<UiNode> = emptyList(),
    val source: AccessibilityNodeInfo? = null
) {

    fun visit(out: MutableList<UiNode> = ArrayList()): List<UiNode> {
        out.add(this)
        children.forEach { it.visit(out) }
        return out
    }

    /** 整棵树可见文本拼一起，用于判断「是否已打卡」「是否报错」 */
    fun texts(): String = visit().joinToString(" ") { it.text }
        .replace(Regex("\\s+"), " ")
        .trim()

    fun findByKeyword(keyword: String): UiNode? {
        val k = keyword.trim()
        if (k.isEmpty()) return null
        return visit().firstOrNull {
            it.text.contains(k, ignoreCase = true) && it.bounds.width() > 0 && it.bounds.height() > 0
        }
    }

    fun findByKeywords(keywords: List<String>): UiNode? {
        for (k in keywords) {
            findByKeyword(k)?.let { return it }
        }
        return null
    }

    fun hasAnyKeyword(keywords: List<String>): Boolean =
        visit().any { node -> keywords.any { node.text.contains(it, ignoreCase = true) } }
}
