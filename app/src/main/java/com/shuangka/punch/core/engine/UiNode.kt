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

    /**
     * 只在屏幕底部那一条（悬浮栏 / 标签栏）里找入口。
     * 飞书消息列表里可能有个同名的群或机器人（比如「假勤」），
     * 那种条目在内容区，按位置就能把它排掉。
     */
    fun findTabInBottomBar(keyword: String, bottomRatio: Double = 0.72): UiNode? {
        val k = keyword.trim()
        if (k.isEmpty()) return null
        val h = bounds.height()
        if (h <= 0) return null
        val minCenterY = bounds.top + h * bottomRatio
        val maxItemHeight = h * 0.13

        val candidates = visit().filter { n ->
            val t = n.text.trim()
            t.isNotEmpty() &&
                t.length <= 10 &&
                !t.contains("\n") &&
                t.contains(k, ignoreCase = true) &&
                n.bounds.width() > 0 && n.bounds.height() > 0 &&
                n.bounds.height() <= maxItemHeight &&
                n.bounds.centerY() >= minCenterY
        }
        // 越靠下、越小巧越像标签栏里的图标
        return candidates.minWithOrNull(
            compareBy<UiNode>({ -it.bounds.centerY() }, { it.bounds.width() * it.bounds.height() }, { it.text.length })
        )
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
