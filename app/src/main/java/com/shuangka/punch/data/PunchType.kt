package com.shuangka.punch.data

/** 打卡类型：上班卡 / 下班卡 */
enum class PunchType(val key: String, val label: String) {
    IN("in", "上班"),
    OUT("out", "下班");

    companion object {
        fun fromKey(key: String?): PunchType = if (key == OUT.key) OUT else IN
    }
}
