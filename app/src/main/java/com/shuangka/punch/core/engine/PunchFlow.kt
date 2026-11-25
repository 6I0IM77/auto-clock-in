package com.shuangka.punch.core.engine

import android.os.SystemClock
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

sealed class PunchResult {
    /** time = 飞书页面上「已打卡 HH:mm」里的时间 */
    data class Success(val time: String) : PunchResult()
    data class Failed(val reason: String) : PunchResult()
}

/**
 * 打卡动作本身：打开飞书 → 按配置的路径点进打卡页 → 点打卡按钮 → 校验结果。
 * 不区分「上班/下班按钮」的差异，只按当前该打的那张卡去找对应文字。
 */
class PunchFlow(private val controller: UiController) {

    private val timeRegex = Regex("(\\d{1,2}:\\d{2})")
    private val errorKeywords = listOf(
        "打卡失败", "不在打卡范围", "不在考勤范围", "超出打卡范围", "未进入打卡范围", "定位失败", "网络异常"
    )

    suspend fun run(type: PunchType, progress: (String) -> Unit = {}): PunchResult {
        progress("打开飞书")
        if (!controller.launchFeishu()) {
            return PunchResult.Failed("打不开飞书，请检查是否安装、是否被系统限制后台启动")
        }
        delay(1600L)

        val nav = PunchConfig.navPath()
        var step = 0
        var clicked = false
        var missAfterClick = 0
        val deadline = SystemClock.elapsedRealtime() + 55_000L

        while (SystemClock.elapsedRealtime() < deadline) {
            val snap = controller.snapshot()
            if (snap == null) {
                delay(900L)
                continue
            }

            val page = parsePage(snap)
            page.error?.let { return PunchResult.Failed("飞书返回：$it") }
            page.done[type]?.let {
                progress("已打卡 $it")
                return PunchResult.Success(it)
            }

            // 1. 先沿配置路径走：工作台 → 打卡
            if (step < nav.size) {
                val target = snap.findByKeyword(nav[step])
                if (target != null) {
                    progress("点击「${nav[step]}」")
                    if (controller.click(target)) {
                        step++
                        delay(1500L)
                        continue
                    }
                }
            }

            // 2. 找到这张卡的按钮就点（绝不点另一张卡）
            val button = findPunchButton(snap, type)
            if (button == null) {
                if (clicked) {
                    missAfterClick++
                    // 点完按钮消失、页面也没报错，基本就是打上了
                    if (missAfterClick >= 2) return PunchResult.Success(nowTime())
                }
                delay(900L)
                continue
            }

            missAfterClick = 0
            progress("点击「${button.text.ifBlank { type.label + "打卡" }}」")
            controller.click(button)
            clicked = true
            delay(1800L)
        }

        return PunchResult.Failed("等了 55 秒还没打上，请手动在飞书里打一次")
    }

    // ---------- 读页面 ----------

    private data class PageInfo(
        /** 每张卡在页面上已显示的打卡时间，例如 上班 -> 09:22 */
        val done: Map<PunchType, String>,
        val error: String?,
        /** 没有明显区块归属时的兜底成功时间 */
        val anyDone: String?
    )

    /** 解析飞书打卡页：归属判定交给 PunchPageParser，这里只补报错和兜底 */
    private fun parsePage(root: UiNode): PageInfo {
        val marks = PunchPageParser.parse(PunchPageParser.lines(root))
        val done = LinkedHashMap<PunchType, String>()
        marks.inTime?.let { done[PunchType.IN] = it }
        marks.outTime?.let { done[PunchType.OUT] = it }

        var error: String? = null
        var anyDone: String? = marks.inTime ?: marks.outTime
        val successKeywords = PunchConfig.successKeywords()

        for (node in root.visit()) {
            val raw = node.text.trim()
            if (raw.isEmpty()) continue
            if (raw.length <= 24) {
                successKeywords.firstOrNull { raw.contains(it) }?.let {
                    if (anyDone == null) anyDone = timeRegex.find(raw)?.value
                }
            }
            if (error == null && raw.length <= 30) {
                errorKeywords.firstOrNull { raw.contains(it) }?.let { error = it }
            }
        }
        return PageInfo(done, error, anyDone)
    }

    /**
     * 找「这一张卡」的按钮。上班卡绝不点带「下班」的节点，反之亦然；
     * 先找完全相等的文字，再找包含关系，跳过「已打卡 / 应上班」这类文字。
     */
    private fun findPunchButton(root: UiNode, type: PunchType): UiNode? {
        val other = if (type == PunchType.IN) "下班" else "上班"
        val keywords = PunchConfig.buttonTexts(type)
        val nodes = root.visit().filter { n ->
            val t = n.text.trim()
            t.isNotEmpty() &&
                n.bounds.width() > 0 && n.bounds.height() > 0 &&
                !t.contains("已打卡") && !t.contains("应上") && !t.contains("应下") &&
                !t.contains(other) && !t.contains("成功") && !t.contains("范围")
        }

        // 1. 完全相等
        nodes.firstOrNull { n -> keywords.any { it.equals(n.text.trim(), ignoreCase = true) } }
            ?.let { return it }
        // 2. 包含
        nodes.firstOrNull { n -> keywords.any { n.text.contains(it, ignoreCase = true) } }
            ?.let { return it }
        // 3. 只有一个「打卡」按钮时直接点
        return nodes.firstOrNull { it.text.contains("打卡") }
    }

    private fun nowTime(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
}
