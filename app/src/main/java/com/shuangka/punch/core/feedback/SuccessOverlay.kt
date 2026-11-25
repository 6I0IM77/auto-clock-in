package com.shuangka.punch.core.feedback

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType

/**
 * 打卡成功的中央提示 —— 完全自绘，颜色固定，不读主题、不读资源里的背景色。
 *
 * 反复“看起来透明”的原因不在颜色值，而在窗口层：
 *   1. 新加的窗口默认会跑一段系统入场动画（淡入），动画期间整窗 alpha 小于 1，
 *      这时截图/肉眼看到的就是“半透明”，所以这里显式 windowAnimations = 0 关掉；
 *   2. 窗口格式用 PixelFormat.TRANSLUCENT —— 只有四角那一点点做圆角的地方是透明的，
 *      卡片主体由整块实色铺满，不存在“透出背后文字”的情况；
 *   3. 内容全部由一个 View 在 onDraw 里用实色画满，没有 drawable、没有布局文件、
 *      没有任何 alpha 设置。
 */
object SuccessOverlay {

    private const val TAG = "SuccessOverlay"

    // 固定的浅色 / 深色配色（不跟随系统动态取色，避免主题里带 alpha 的容器色影响观感）
    private const val LIGHT_BG = 0xFFFFFFFF.toInt()
    private const val LIGHT_TITLE = 0xFF14181F.toInt()
    private const val LIGHT_WARM = 0xFF4B5563.toInt()
    private const val LIGHT_FAINT = 0xFF8A919C.toInt()
    private const val LIGHT_ACCENT = 0xFF1E8E63.toInt()
    private const val LIGHT_ACCENT_SOFT = 0xFFE6F4EE.toInt()
    private const val LIGHT_EDGE = 0xFFD6DBE2.toInt()

    private const val DARK_BG = 0xFF20242B.toInt()
    private const val DARK_TITLE = 0xFFF4F6F8.toInt()
    private const val DARK_WARM = 0xFFBFC6D1.toInt()
    private const val DARK_FAINT = 0xFF98A0AC.toInt()
    private const val DARK_ACCENT = 0xFF34C08B.toInt()
    private const val DARK_ACCENT_SOFT = 0xFF20302B.toInt()
    private const val DARK_EDGE = 0xFF3A4048.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var manager: WindowManager? = null

    fun show(ctx: Context, type: PunchType, time: String, extra: String? = null) {
        showRaw(
            ctx,
            badge = "${type.label}打卡成功",
            title = "已打卡 $time",
            warm = PunchPhrases.warm(type),
            extra = extra
        )
    }

    fun showRaw(ctx: Context, badge: String, title: String, warm: String, extra: String? = null) {
        handler.post { safeShow(ctx.applicationContext, badge, title, warm, extra) }
    }

    private fun safeShow(ctx: Context, badge: String, title: String, warm: String, extra: String?) {
        try {
            showInternal(ctx, badge, title, warm, extra)
        } catch (t: Throwable) {
            Log.w(TAG, "show failed: " + Log.getStackTraceString(t))
        }
    }

    private fun showInternal(ctx: Context, badge: String, title: String, warm: String, extra: String?) {
        dismiss()
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return

        val night = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val view = TipView(
            ctx,
            badge = badge,
            title = title,
            warm = warm,
            extra = extra,
            bgColor = if (night) DARK_BG else LIGHT_BG,
            colorTitle = if (night) DARK_TITLE else LIGHT_TITLE,
            colorWarm = if (night) DARK_WARM else LIGHT_WARM,
            colorFaint = if (night) DARK_FAINT else LIGHT_FAINT,
            colorAccent = if (night) DARK_ACCENT else LIGHT_ACCENT,
            colorAccentSoft = if (night) DARK_ACCENT_SOFT else LIGHT_ACCENT_SOFT,
            colorEdge = if (night) DARK_EDGE else LIGHT_EDGE
        )

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val params = WindowManager.LayoutParams(
            view.measuredWidth,
            view.measuredHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            alpha = 1f
            dimAmount = 0f
            // 关键：关掉系统给新窗口的入场动画（淡入），否则刚弹出那一瞬整窗是半透明的
            windowAnimations = 0
        }
        params.title = "punch-success-tip"

        wm.addView(view, params)
        view.alpha = 1f
        current = view
        manager = wm

        // 双保险：200ms 后再把 alpha 强制写回 1 并刷新一次窗口，
        // 防止个别 ROM 的入场动画把 alpha 卡在半透明上。
        handler.postDelayed({
            if (current === view) {
                try {
                    params.alpha = 1f
                    view.alpha = 1f
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                }
            }
        }, 220L)
        Log.i(
            TAG,
            "shown ${view.measuredWidth}x${view.measuredHeight} night=$night " +
                "format=TRANSLUCENT rounded corner, alpha=${params.alpha} anim=0"
        )

        val seconds = PunchConfig.tipSeconds().coerceIn(1, 20)
        handler.postDelayed({ dismiss() }, seconds * 1000L)
    }

    fun dismiss() {
        val view = current ?: return
        current = null
        try {
            manager?.removeView(view)
        } catch (e: Exception) {
        }
    }

    /** 自绘提示卡：整块实色打底 → 徽标 → 大字时间 → 暖心话 → 附注 */
    private class TipView(
        ctx: Context,
        private val badge: String,
        private val title: String,
        private val warm: String,
        private val extra: String?,
        private val bgColor: Int,
        colorTitle: Int,
        colorWarm: Int,
        colorFaint: Int,
        colorAccent: Int,
        colorAccentSoft: Int,
        colorEdge: Int
    ) : View(ctx) {

        private val d = resources.displayMetrics.density
        private fun dp(v: Float) = v * d

        private val pad = dp(22f)
        private val badgeH = dp(24f)
        private val titleH = dp(40f)

        private val bgPaint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
            color = bgColor
        }

        private val edgePaint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = colorEdge
        }

        private val pillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = colorAccentSoft
        }

        private val badgePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = dp(12f)
            color = colorAccent
            typeface = Typeface.DEFAULT_BOLD
        }

        private val titlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = dp(30f)
            color = colorTitle
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private val warmPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = dp(14f)
            color = colorWarm
        }

        private val extraPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = dp(12f)
            color = colorFaint
        }

        private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(dp(2f), 1f)
                .setIncludePad(false)
                .build()

        private var warmLayout: StaticLayout? = null
        private var extraLayout: StaticLayout? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screen = resources.displayMetrics.widthPixels
            val w = minOf((screen * 0.86f).toInt(), dp(330f).toInt())
                .coerceAtLeast(dp(240f).toInt())
            val inner = w - pad.toInt() * 2
            warmLayout = staticLayout(warm, warmPaint, inner)
            extraLayout = extra?.takeIf { it.isNotBlank() }?.let { staticLayout(it, extraPaint, inner) }

            val warmH = (warmLayout?.height ?: 0).toFloat()
            val extraH = (extraLayout?.height ?: 0).toFloat()
            val h = pad + badgeH + dp(12f) + titleH + dp(8f) + warmH +
                (if (extraH > 0f) dp(6f) + extraH else 0f) + pad
            setMeasuredDimension(w, h.toInt())
        }

        private val corner = dp(20f)
        private val clipPath = Path()
        private val roundRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // 圆角裁剪：只有四个角是透明的，卡片主体是整块实色
            roundRect.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(roundRect, corner, corner, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            // ① 整块窗口铺满实色（先铺，后面所有内容画在它上面）
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            // ② 细边框，方便和背景区分
            canvas.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), corner, corner, edgePaint)

            val cx = w / 2f
            var y = pad

            // ③ 徽标胶囊
            val badgeTextW = badgePaint.measureText(badge)
            val pillW = badgeTextW + dp(24f)
            val pill = RectF(cx - pillW / 2f, y, cx + pillW / 2f, y + badgeH)
            canvas.drawRoundRect(pill, badgeH / 2f, badgeH / 2f, pillPaint)
            canvas.drawText(badge, cx - badgeTextW / 2f, y + badgeH / 2f - (badgePaint.descent() + badgePaint.ascent()) / 2f, badgePaint)
            y += badgeH + dp(12f)

            // ④ 大字打卡时间
            canvas.drawText(title, cx, y - titlePaint.ascent(), titlePaint)
            y += titleH + dp(8f)

            // ⑤ 暖心话
            warmLayout?.let {
                canvas.save()
                canvas.translate(pad, y)
                it.draw(canvas)
                canvas.restore()
                y += it.height + dp(6f)
            }

            // ⑥ 附注（应下班时间 / 双卡时间）
            extraLayout?.let {
                canvas.save()
                canvas.translate(pad, y)
                it.draw(canvas)
                canvas.restore()
            }

            canvas.restore()
        }
    }
}
