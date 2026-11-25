package com.shuangka.punch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.shuangka.punch.R
import com.shuangka.punch.data.DayRecord
import com.shuangka.punch.data.HolidayCalendar
import com.shuangka.punch.data.PunchStore
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月度打卡日历：自己画的，不依赖 RecyclerView。
 * 绿点=双卡完成，橙点=只打一卡，灰点=工作日缺卡，今天带描边。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var month: YearMonth = YearMonth.now()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var records: Map<LocalDate, DayRecord> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    var onDateClick: ((LocalDate) -> Unit)? = null

    /** 跟随系统主题色 */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true)) {
            return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        }
        return context.getColor(R.color.primary)
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val weekHeight = dp(20f)
    private val cellHeight = dp(36f)
    private var rows = 6

    private val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
        color = context.getColor(R.color.text_faint)
    }

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
        color = context.getColor(R.color.text_main)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = themeColor(androidx.appcompat.R.attr.colorPrimary)
    }

    init {
        isClickable = true
    }

    /** 这个月实际需要几行（4~6 行），不再固定留 6 行 */
    private fun rowCount(): Int {
        val offset = month.atDay(1).dayOfWeek.value - 1
        return ((offset + month.lengthOfMonth() + 6) / 7).coerceIn(4, 6)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rows = rowCount()
        val desired = (weekHeight + cellHeight * rows + dp(2f)).toInt()
        setMeasuredDimension(width, View.resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellWidth = width.toFloat() / 7f
        val weeks = arrayOf("一", "二", "三", "四", "五", "六", "日")

        for (i in weeks.indices) {
            canvas.drawText(weeks[i], cellWidth * (i + 0.5f), weekHeight * 0.85f, weekPaint)
        }

        val today = LocalDate.now()
        val offset = month.atDay(1).dayOfWeek.value - 1
        val radius = dp(13f)

        for (day in 1..month.lengthOfMonth()) {
            val index = offset + day - 1
            val row = index / 7
            val col = index % 7
            val cx = cellWidth * (col + 0.5f)
            val cy = weekHeight + cellHeight * row + cellHeight * 0.5f

            val date = month.atDay(day)
            val record = records[date]
            val isFuture = date.isAfter(today)
            val isWorkday = HolidayCalendar.isWorkday(date)
            val holiday = HolidayCalendar.holidayName(date)
            val makeup = HolidayCalendar.isMakeupWorkday(date)
            val complete = record?.complete == true
            // 今天要等下班窗口关门才有结论：没关门前不显示「只打一卡 / 缺卡」
            val settled = date != today || PunchStore.isOutWindowClosed(context, today)
            val half = record != null && !complete && record.touched && settled
            val missing = record == null && !isFuture && isWorkday && settled

            if (complete || half || missing) {
                fillPaint.color = when {
                    complete -> themeColor(androidx.appcompat.R.attr.colorPrimary)
                    half -> context.getColor(R.color.warn)
                    else -> context.getColor(R.color.miss_bg)
                }
                canvas.drawCircle(cx, cy, radius, fillPaint)
            }

            dayPaint.color = when {
                complete || half -> context.getColor(R.color.white)
                missing || isFuture -> context.getColor(R.color.text_faint)
                // 法定节假日：红字；调休上班的周末：主题色，提示这天要打卡
                holiday != null -> context.getColor(R.color.holiday)
                makeup -> themeColor(androidx.appcompat.R.attr.colorPrimary)
                !isWorkday -> context.getColor(R.color.text_faint)
                else -> context.getColor(R.color.text_main)
            }
            dayPaint.typeface = if (date == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(day.toString(), cx, cy + dayPaint.textSize / 3f, dayPaint)

            if (date == today) {
                canvas.drawCircle(cx, cy, radius + dp(2.5f), ringPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cellWidth = width.toFloat() / 7f
            val offset = month.atDay(1).dayOfWeek.value - 1
            val col = (event.x / cellWidth).toInt().coerceIn(0, 6)
            val row = ((event.y - weekHeight) / cellHeight).toInt()
            if (row in 0 until rows) {
                val day = row * 7 + col - offset + 1
                if (day in 1..month.lengthOfMonth()) {
                    onDateClick?.invoke(month.atDay(day))
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}



