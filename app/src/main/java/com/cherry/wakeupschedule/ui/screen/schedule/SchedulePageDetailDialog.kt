package com.cherry.wakeupschedule.ui.screen.schedule

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cherry.wakeupschedule.ui.feedback.AppToast
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.service.TimeTableManager

/** 课程详情 Bottom Sheet Dialog，从原始 WeekPageFragment 提取 */
object SchedulePageDetailDialog {

    private var currentDialog: Dialog? = null

    /**
     * 展示课程详情。
     *
     * [courses] 为同一门课的全部课程行：周课表传入单门；总课表传入同名课程的全部时段。
     * 时间行按 (星期, 开始节次, 结束节次) 去重排序后逐行展示，周次取所有行位图的并集。
     */
    fun show(context: Context, courses: List<Course>, courseColors: IntArray) {
        if (courses.isEmpty()) return
        // 防止连点打开多个详情弹窗（旧弹窗可能已随 Activity 销毁，安全关闭）
        dismissCurrent()
        val dialog = Dialog(context, R.style.BottomSheetDialog)
        currentDialog = dialog
        dialog.setOnDismissListener { currentDialog = null }
        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.dialog_course_detail, null)
        val density = context.resources.displayMetrics.density

        val topRadius = 24 * density
        val sheetBg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        }
        val typedValue = android.util.TypedValue()
        // 弹层底色提升一级到 colorSurfaceContainer：暗色模式下与蒙版压暗的背景拉开亮度差
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true
        )
        sheetBg.setColor(typedValue.data)
        sheetView.background = sheetBg

        val slots = TimeTableManager.getInstance(context).getTimeSlots()
        val primary = courses.first()
        val courseColor = if (courseColors.isEmpty()) 0
            else courseColors[if (primary.color > 0) (primary.color - 1) % courseColors.size else 0]

        // ── 头部：课程色圆形头像（取课程名首字） ──
        sheetView.findViewById<TextView>(R.id.iv_detail_avatar)?.apply {
            text = primary.name.trim().take(1).ifEmpty { "课" }
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(courseColor)
            }
        }

        // ── 头部：课程类别徽章（组内第一个非空值，都没有则隐藏） ──
        val category = courses.firstOrNull { it.courseCategory.isNotBlank() }
            ?.courseCategory?.trim() ?: ""
        sheetView.findViewById<TextView>(R.id.tv_detail_category)?.apply {
            if (category.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = category
                setTextColor(courseColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(ColorUtils.setAlphaComponent(courseColor, 38))
                }
            }
        }

        sheetView.findViewById<TextView>(R.id.tv_detail_name)?.text = primary.name
        sheetView.findViewById<TextView>(R.id.tv_detail_teacher)?.text =
            joinDistinct(courses) { it.teacher }.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_classroom)?.text =
            joinDistinct(courses) { it.classroom }.ifBlank { "未知" }
        sheetView.findViewById<TextView>(R.id.tv_detail_time)?.text = formatSessions(slots, courses)
        sheetView.findViewById<TextView>(R.id.tv_detail_week)?.text =
            formatWeekRanges(courses.fold(0L) { acc, c -> acc or c.weekBitmap })
        sheetView.findViewById<TextView>(R.id.tv_detail_credit)?.text =
            courses.firstOrNull { it.credits.isNotBlank() }?.credits?.trim().orEmpty().ifBlank { "未设置" }
        setupQqGroupJump(sheetView, courses.firstOrNull { it.qqGroup.isNotBlank() } ?: primary)

        dialog.setContentView(sheetView)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        sheetView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        container.addView(sheetView)
        dialog.setContentView(container)

        // 内容超高时限制高度并允许内部滚动（小屏手机友好）
        try {
            val display = context.resources.displayMetrics
            val scroll = sheetView.findViewById<ScrollView>(R.id.scroll_detail)
            val inner = scroll.getChildAt(0)
            inner.measure(
                View.MeasureSpec.makeMeasureSpec(display.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val desired = inner.measuredHeight
            val maxH = (display.heightPixels * 0.72f).toInt()
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (desired > 0) desired.coerceAtMost(maxH) else maxH
            )
        } catch (_: Exception) { }

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.BottomSheetAnimation)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setDimAmount(0.5f)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * 安全关闭当前弹窗。
     *
     * 承载弹窗的 Activity 销毁/重建后，旧弹窗的 DecorView 已脱离 WindowManager，
     * 直接 dismiss() 会抛 "not attached to window manager" 崩溃（本单例持有旧引用，
     * 属于 Activity 生命周期管理不到的对象）。这里先查 isShowing，再 try-catch 兜底。
     */
    private fun dismissCurrent() {
        val d = currentDialog ?: return
        if (!d.isShowing) {
            currentDialog = null
            return
        }
        try {
            d.dismiss()
        } catch (_: Exception) {
            // 窗口已解绑（Activity 已销毁/重建），忽略并清引用
            currentDialog = null
        }
    }

    /**
     * 设置 QQ 群行：有群号时显示跳转箭头，点击拉起 QQ 加群；
     * 无群号时退化为纯展示。
     *
     * 群号取文本中第一段连续数字（≥5 位），兼容 "12345678" / "群号：12345678" 等格式。
     * 跳转使用 QQ 官方 mqqapi scheme，未安装 QQ 时捕获异常并提示。
     */
    private fun setupQqGroupJump(view: View, course: Course) {
        val row = view.findViewById<View>(R.id.row_detail_qq) ?: return
        val tv = view.findViewById<TextView>(R.id.tv_detail_qq)
        val arrow = view.findViewById<View>(R.id.iv_detail_qq_arrow)

        val raw = course.qqGroup.trim()
        val groupId = Regex("\\d{5,}").find(raw)?.value
        tv?.text = raw.ifBlank { "未设置" }

        if (groupId == null) {
            arrow?.visibility = View.GONE
            row.isClickable = false
            row.setOnClickListener(null)
            return
        }

        arrow?.visibility = View.VISIBLE
        row.isClickable = true
        row.setOnClickListener {
            val ctx = view.context
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                            "&uin=$groupId&card_type=group&source=external"
                    )
                )
                if (ctx is Activity) {
                    ctx.startActivity(intent)
                } else {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                AppToast.warn(ctx, "未检测到 QQ，无法跳转加群")
            }
        }
    }

    /**
     * 把同一门课的全部上课时段格式化为多行文本（总课表合并同名课程后会有多行）。
     * 例：周一 08:00 - 09:40\n周三 10:00 - 11:40
     */
    private fun formatSessions(
        slots: List<TimeTableManager.TimeSlot>,
        courses: List<Course>
    ): String {
        // 实践课没有星期/节次，时间行退化为「无固定时间」
        if (courses.none { it.hasFixedTime() }) return "无固定时间"
        return courses
            .filter { it.hasFixedTime() }
            .map { Triple(it.dayOfWeek, it.startTime, it.endTime) }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .joinToString("\n") { (day, startTime, endTime) ->
                val startSlot = slots.find { it.node == startTime }
                val endSlot = slots.find { it.node == endTime }
                val timeText = if (startSlot != null && endSlot != null) {
                    "${startSlot.startTime} - ${endSlot.endTime}"
                } else {
                    "第${startTime}-${endTime}节"
                }
                "${dayOfWeekLabel(day)} $timeText"
            }
    }

    /** 组内某字段的非空去重值，用「、」连接（教师 / 教室等） */
    private fun joinDistinct(courses: List<Course>, selector: (Course) -> String): String =
        courses.map { selector(it).trim() }.filter { it.isNotEmpty() }.distinct().joinToString("、")
}
