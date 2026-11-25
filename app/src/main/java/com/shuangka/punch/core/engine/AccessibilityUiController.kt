package com.shuangka.punch.core.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.shuangka.punch.App
import com.shuangka.punch.core.PunchAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无障碍通道：能读窗口内容、能点、必要时用手势补点。
 * 优点是不需要装 Shizuku；缺点是必须先手动开启无障碍服务。
 */
object AccessibilityUiController : UiController {

    @Volatile
    var service: PunchAccessibilityService? = null

    override fun name(): String = "无障碍"

    override fun isReady(): Boolean = service != null

    override suspend fun launchFeishu(): Boolean = withContext(Dispatchers.IO) {
        FeishuLauncher.launch(App.instance)
    }

    override suspend fun snapshot(): UiNode? = withContext(Dispatchers.Default) {
        val svc = service ?: return@withContext null
        val root = try {
            svc.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return@withContext null

        val counter = intArrayOf(0)
        build(root, 0, counter)
    }

    private fun build(node: AccessibilityNodeInfo, depth: Int, counter: IntArray): UiNode {
        counter[0]++
        val text = (node.text?.toString() ?: "").trim()
            .ifBlank { (node.contentDescription?.toString() ?: "").trim() }
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val children = ArrayList<UiNode>()
        if (depth < 40 && counter[0] < 1500) {
            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                children.add(build(child, depth + 1, counter))
            }
        }
        return UiNode(text, rect, node.isClickable, node.className?.toString(), children, node)
    }

    override suspend fun click(node: UiNode): Boolean = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext false

        var current = node.source
        var guard = 0
        while (current != null && guard < 8) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
            }
            current = try {
                current.parent
            } catch (e: Exception) {
                null
            }
            guard++
        }
        // 节点点不动就直接按坐标补一下
        svc.tap(node.bounds.centerX(), node.bounds.centerY())
    }
}
