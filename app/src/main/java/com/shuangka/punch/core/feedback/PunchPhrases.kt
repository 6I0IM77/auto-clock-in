package com.shuangka.punch.core.feedback

import com.shuangka.punch.data.PunchType

/** 打卡成功时那句暖心话 + 双卡齐全时的收工语 */
object PunchPhrases {

    private val IN = listOf(
        "早起的你，今天一定顺顺利利",
        "路上慢一点，安全第一",
        "先喝杯热水，元气满满开工",
        "新的一天开始啦，不用太紧绷",
        "早餐别忘了吃，慢慢来也很好"
    )

    private val OUT = listOf(
        "今天的辛苦到此为止，下班快乐",
        "回家的路上，风刚刚好",
        "今天也扛住了，你真的很棒",
        "把工作留在工位，把轻松带回家",
        "辛苦一天了，晚上吃点好的"
    )

    private val DONE = listOf(
        "今天上下班都打上了，安心下班",
        "两卡齐全，今天完美收工",
        "全勤又 +1，辛苦啦",
        "今天两卡都搞定了，回家休息吧"
    )

    fun warm(type: PunchType): String =
        if (type == PunchType.IN) IN.random() else OUT.random()

    fun done(): String = DONE.random()
}
