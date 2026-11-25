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
    /** 打卡前/后的验证环节：看到这些字样说明还在验人，绝不能当作打卡完成 */
    private val faceKeywords = listOf(
        "人脸识别", "刷脸", "活体检测", "请正对", "请正视", "识别中", "正在识别", "验证中", "请完成验证"
    )

    suspend fun run(type: PunchType, progress: (String) -> Unit = {}): PunchResult {
        progress("打开飞书")
        if (!controller.launchFeishu()) {
            return PunchResult.Failed("打不开飞书，请检查是否安装、是否被系统限制后台启动")
        }
        delay(1600L)

        val nav = PunchConfig.navPath()
        var step = 0
        var navMiss = 0
        var clicked = false
        var waitRounds = 0
        var faceWarned = false
        var deadline = SystemClock.elapsedRealtime() + 55_000L

        while (SystemClock.elapsedRealtime() < deadline) {
            val snap = controller.snapshot()
            if (snap == null) {
                delay(900L)
                continue
            }

            val page = parsePage(snap)
            page.error?.let { return PunchResult.Failed("飞书返回：$it") }

            // 唯一的成功依据：页面上真的出现了「已打卡 HH:mm」，且归属这张卡
            page.done[type]?.let {
                progress("已打卡 $it")
                return PunchResult.Success(it)
            }

            // 人脸识别 / 活体检测挡着：只等，不点、不判成功
            if (page.faceAuth) {
                if (!faceWarned) {
                    faceWarned = true
                    progress("弹出人脸识别，请配合一下")
                    // 验人可能要好一会儿，再给一轮完整时间
                    deadline = maxOf(deadline, SystemClock.elapsedRealtime() + 55_000L)
                }
                delay(1200L)
                continue
            }

            // 1. 先沿配置路径走：工作台 → 假勤
            if (step < nav.size) {
                val keyword = nav[step]
                // 先在底部悬浮栏/标签栏里找，消息列表里的同名「假勤」不会被点；
                // 底部暂时没渲染出来就先等几轮，实在找不到再放宽到屏幕下半部分，最后才全屏兜底
                val bottom = snap.findTabInBottomBar(keyword)
                    ?: (if (navMiss >= 3) snap.findTabInBottomBar(keyword, 0.55) else null)
                    ?: (if (navMiss >= 8) snap.findByKeyword(keyword) else null)
                if (bottom != null) {
                    progress("点击「$keyword」")
                    if (controller.click(bottom)) {
                        step++
                        navMiss = 0
                        delay(1500L)
                        continue
                    }
                }
                navMiss++
            }

            // 2. 找到这张卡的按钮就点（绝不点另一张卡）
            val button = findPunchButton(snap, type)
            if (button == null) {
                if (clicked) {
                    // 按钮不见了只说明「提交了」，可能卡在人脸/网络/加载中；
                    // 继续等页面出现「已打卡」，等不到就如实报失败让流程重试
                    waitRounds++
                    progress("已点击，等飞书返回打卡结果（$waitRounds）")
                }
                delay(900L)
                continue
            }

            waitRounds = 0
            progress("点击「${button.text.ifBlank { type.label + "打卡" }}」")
            if (controller.click(button)) clicked = true
            delay(1800L)
        }

        return PunchResult.Failed(
            if (clicked) "点了打卡但没等到飞书的「已打卡」结果（可能卡在人脸识别或网络），请手动确认一下"
            else "等了 55 秒还没打上，请手动在飞书里打一次"
        )
    }

    // ---------- 读页面 ----------

    private data class PageInfo(
        /** 每张卡在页面上已显示的打卡时间，例如 上班 -> 09:22 */
        val done: Map<PunchType, String>,
        val error: String?,
        /** 页面是否停在人脸识别 / 活体检测之类的验证环节 */
        val faceAuth: Boolean
    )

    /** 解析飞书打卡页：归属判定交给 PunchPageParser，这里只补报错和验证环节 */
    private fun parsePage(root: UiNode): PageInfo {
        val marks = PunchPageParser.parse(PunchPageParser.lines(root))
        val done = LinkedHashMap<PunchType, String>()
        marks.inTime?.let { done[PunchType.IN] = it }
        marks.outTime?.let { done[PunchType.OUT] = it }

        var error: String? = null
        var face = false
        val errorKeywords = this.errorKeywords
        for (node in root.visit()) {
            val raw = node.text.trim()
            if (raw.isEmpty()) continue
            if (!face && faceKeywords.any { raw.contains(it) }) face = true
            if (error == null && raw.length <= 30) {
                errorKeywords.firstOrNull { raw.contains(it) }?.let { error = it }
            }
        }
        return PageInfo(done, error, face)
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
