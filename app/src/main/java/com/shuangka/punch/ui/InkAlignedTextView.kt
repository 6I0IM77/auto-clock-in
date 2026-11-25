package com.shuangka.punch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max

/**
 * 数字按「固定格」对齐的文本控件。
 *
 * 0~9 的天然字宽不一样（1 明显比 0 窄），普通排版会把冒号、每一位数字推到不同位置，
 * 于是「09:25 / 18:20」和「上班 09:00-10:00 / 下班 18:22-19:00」看着永远不齐。
 * 这里绕开排版引擎：每个数字固定占同样宽的一格并居中，冒号、连字符、中文字按自身宽度依次排。
 * 只要两行结构相同（都是 2 位 : 2 位），冒号、每一位数字、左右边缘就会严格同列。
 * 不含数字的文本（未打卡 / 今天休息 等）仍按普通文本画。
 */
class InkAlignedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var cellWidth = 0f
    private var metricsKey: String? = null
    private var cacheText: String? = null
    private var starts: FloatArray = FloatArray(0)
    private var widths: FloatArray = FloatArray(0)

    private fun ensureMetrics() {
        val key = "${paint.textSize}-${paint.typeface}-${paint.isFakeBoldText}-${paint.textSkewX}"
        if (metricsKey == key && cellWidth > 0f) return
        metricsKey = key
        var w = 0f
        for (c in '0'..'9') w = max(w, paint.measureText(c.toString()))
        cellWidth = w
        cacheText = null
    }

    /** 逐字排布：数字固定格宽，其它字符按自身宽度 */
    private fun buildLayout(t: String) {
        val n = t.length
        starts = FloatArray(n)
        widths = FloatArray(n)
        var x = 0f
        for (i in 0 until n) {
            val ch = t[i].toString()
            val adv = paint.measureText(ch)
            val slot = if (t[i].isDigit()) cellWidth else adv
            starts[i] = x
            widths[i] = slot
            x += slot
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = text?.toString().orEmpty()
        if (t.isEmpty() || t.any { it == '\n' } || t.none { it.isDigit() }) {
            super.onDraw(canvas)
            return
        }
        ensureMetrics()
        if (cellWidth <= 0f) {
            super.onDraw(canvas)
            return
        }
        if (cacheText != t || starts.size != t.length) {
            buildLayout(t)
            cacheText = t
        }

        val baseY = (baseline.takeIf { it > 0 } ?: (paddingTop + -paint.fontMetrics.ascent.toInt()))
            .toFloat()
        val left = paddingLeft.toFloat()
        val p = paint
        val oldAlign = p.textAlign
        val oldColor = p.color
        p.textAlign = Paint.Align.LEFT
        // 自己画就得自己上色：TextView 的颜色是在它自己的 onDraw 里塞进画笔的
        p.color = currentTextColor
        try {
            for (i in t.indices) {
                val ch = t[i].toString()
                val adv = p.measureText(ch)
                val x = left + starts[i] + (widths[i] - adv) / 2f
                canvas.drawText(ch, x, baseY, p)
            }
        } finally {
            p.textAlign = oldAlign
            p.color = oldColor
        }
    }
}
