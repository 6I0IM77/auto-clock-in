package com.shuangka.punch.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shuangka.punch.R
import com.shuangka.punch.core.PunchService
import com.shuangka.punch.core.debug.TestScenario
import com.shuangka.punch.data.PunchStore
import com.shuangka.punch.data.PunchType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 设置里的二级页面：手动补卡 + 场景模拟 + 真机调试 */
class TestActivity : AppCompatActivity() {

    private var pickDate: LocalDate = LocalDate.now()
    private var inTime: String? = null
    private var outTime: String? = null

    private lateinit var tvPickDate: TextView
    private lateinit var tvInTime: TextView
    private lateinit var tvOutTime: TextView
    private lateinit var tvWriteHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRunIn).setOnClickListener {
            PunchService.start(this, PunchService.ACTION_MANUAL, PunchType.IN)
        }

        tvPickDate = findViewById(R.id.tvPickDate)
        tvInTime = findViewById(R.id.tvInTime)
        tvOutTime = findViewById(R.id.tvOutTime)
        tvWriteHint = findViewById(R.id.tvWriteHint)

        findViewById<LinearLayout>(R.id.rowPickDate).setOnClickListener { pickDay() }
        findViewById<LinearLayout>(R.id.rowInTime).apply {
            setOnClickListener { pickClock("上班时间", inTime) { inTime = it; refreshManual() } }
            setOnLongClickListener { inTime = null; refreshManual(); true }
        }
        findViewById<LinearLayout>(R.id.rowOutTime).apply {
            setOnClickListener { pickClock("下班时间", outTime) { outTime = it; refreshManual() } }
            setOnLongClickListener { outTime = null; refreshManual(); true }
        }
        findViewById<TextView>(R.id.btnWriteRecord).setOnClickListener { writeManual() }
        findViewById<TextView>(R.id.btnClearRecord).setOnClickListener { clearManual() }

        refreshManual()

        // 场景按钮按枚举生成，覆盖所有判定分支
        val list = findViewById<LinearLayout>(R.id.layoutScenarios)
        TestScenario.values().forEachIndexed { index, scenario ->
            list.addView(row(scenario, index > 0))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshManual()
    }

    // ---------- 手动补卡 ----------

    private fun refreshManual() {
        val today = LocalDate.now()
        tvPickDate.text = when (pickDate) {
            today -> "今天 · ${pickDate.format(fmt())}"
            today.minusDays(1) -> "昨天 · ${pickDate.format(fmt())}"
            else -> pickDate.format(fmt())
        }
        tvInTime.text = inTime ?: "不补"
        tvOutTime.text = outTime ?: "不补"

        val existing = PunchStore.get(this, pickDate)
        tvWriteHint.text = if (existing == null) {
            "这天现在还没有记录"
        } else {
            "这天已有记录：上班 ${existing.inTime ?: "-"}、下班 ${existing.outTime ?: "-"}"
        }
    }

    private fun pickDay() {
        val d = pickDate
        DatePickerDialog(
            this,
            { _, y, m, day ->
                pickDate = LocalDate.of(y, m + 1, day)
                refreshManual()
            },
            d.year, d.monthValue - 1, d.dayOfMonth
        ).show()
    }

    private fun pickClock(title: String, current: String?, onPicked: (String?) -> Unit) {
        val base = current?.let { splitTime(it) } ?: (9 to 0)
        TimePickerDialog(
            this,
            { _, h, mi -> onPicked(String.format(Locale.CHINA, "%02d:%02d", h, mi)) },
            base.first, base.second, true
        ).apply { setTitle(title) }.show()
    }

    /** 把选好的时间写进记录，主页返回时自动刷新 */
    private fun writeManual() {
        if (inTime == null && outTime == null) {
            toast("上班和下班时间都还没填，先点一下时间行选个时间（长按可以取消）")
            return
        }
        inTime?.let { PunchStore.record(this, pickDate, PunchType.IN, it, manual = true) }
        outTime?.let { PunchStore.record(this, pickDate, PunchType.OUT, it, manual = true) }
        val record = PunchStore.get(this, pickDate)
        toast(
            "已写入 ${pickDate.format(fmt())}：上班 ${record?.inTime ?: "-"}、下班 ${record?.outTime ?: "-"}，回主页就能看到"
        )
        refreshManual()
    }

    private fun clearManual() {
        PunchStore.remove(this, pickDate)
        toast("已清空 ${pickDate.format(fmt())} 的记录")
        refreshManual()
    }

    private fun splitTime(text: String): Pair<Int, Int> {
        val parts = text.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 9) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun fmt(): DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd E", Locale.CHINA)

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    // ---------- 场景列表 ----------

    private fun row(scenario: TestScenario, withTopMargin: Boolean): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (withTopMargin) {
                setPadding(0, dp(8), 0, 0)
            }
        }

        val button = TextView(this).apply {
            text = scenario.label
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            setBackgroundResource(R.drawable.bg_btn_secondary)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            )
            setOnClickListener {
                PunchService.startScenario(this@TestActivity, scenario.key)
            }
        }
        wrap.addView(button)

        val desc = TextView(this).apply {
            text = scenario.desc
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.text_faint))
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        wrap.addView(desc)
        return wrap
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
