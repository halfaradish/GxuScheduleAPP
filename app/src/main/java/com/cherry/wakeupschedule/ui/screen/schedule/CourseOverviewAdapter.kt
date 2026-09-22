package com.cherry.wakeupschedule.ui.screen.schedule

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.service.TimeTableManager

/**
 * 总课表列表适配器：一条 = 一门课程（同名课程已由 [CourseOverviewGroup.build] 归并）。
 *
 * 取色与周课表卡片/详情弹层保持一致（`color` 按课程名分配），点击整卡弹出课程详情。
 */
class CourseOverviewAdapter : RecyclerView.Adapter<CourseOverviewAdapter.GroupViewHolder>() {

    private val items = mutableListOf<CourseOverviewGroup>()
    private var colors: IntArray = IntArray(0)

    fun submit(groups: List<CourseOverviewGroup>, courseColors: IntArray) {
        items.clear()
        items.addAll(groups)
        colors = courseColors
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_overview, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(items[position], colors)
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val avatar: TextView = itemView.findViewById(R.id.iv_course_avatar)
        private val name: TextView = itemView.findViewById(R.id.tv_course_name)
        private val teacher: TextView = itemView.findViewById(R.id.tv_course_teacher)
        private val sessionCount: TextView = itemView.findViewById(R.id.tv_session_count)
        private val weeks: TextView = itemView.findViewById(R.id.tv_course_weeks)
        private val timeList: LinearLayout = itemView.findViewById(R.id.ll_time_list)

        fun bind(group: CourseOverviewGroup, courseColors: IntArray) {
            val ctx = itemView.context

            avatar.text = group.name.trim().take(1).ifEmpty { "课" }
            avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(courseColor(group, courseColors))
            }

            name.text = group.name
            teacher.text = group.teachers.ifBlank { "未知" }
            // 实践课没有固定时段，用文案替代「共 N 次课」
            sessionCount.text = if (group.hasFixedTime) {
                "共 ${group.sessions.size} 次课"
            } else {
                "实践课 · 无固定时间"
            }
            weeks.text = formatWeekRanges(group.weekBitmap)

            bindSessions(ctx, group)

            itemView.setOnClickListener {
                SchedulePageDetailDialog.show(ctx, group.courses, courseColors)
            }
        }

        /** 逐条渲染上课时段；行布局固定，仅替换文本 */
        private fun bindSessions(ctx: Context, group: CourseOverviewGroup) {
            timeList.removeAllViews()
            // 实践课没有星期/节次，卡片只展示教师与周次
            if (!group.hasFixedTime) return
            val slots = TimeTableManager.getInstance(ctx).getTimeSlots()
            group.sessions.forEach { session ->
                val row = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_course_time, timeList, false)
                row.findViewById<TextView>(R.id.tv_day).text = dayOfWeekLabel(session.dayOfWeek)
                row.findViewById<TextView>(R.id.tv_time_range).text =
                    sessionTimeText(slots, session.startTime, session.endTime)
                row.findViewById<TextView>(R.id.tv_location).text =
                    session.classroom.ifBlank { "地点待定" }
                timeList.addView(row)
            }
        }

        private fun sessionTimeText(
            slots: List<TimeTableManager.TimeSlot>,
            startNode: Int,
            endNode: Int
        ): String {
            val startSlot = slots.find { it.node == startNode }
            val endSlot = slots.find { it.node == endNode }
            return if (startSlot != null && endSlot != null) {
                "${startSlot.startTime}-${endSlot.endTime}"
            } else {
                "第${startNode}-${endNode}节"
            }
        }

        private fun courseColor(group: CourseOverviewGroup, courseColors: IntArray): Int {
            if (courseColors.isEmpty()) return 0
            val index = if (group.colorIndex > 0) (group.colorIndex - 1) % courseColors.size else 0
            return courseColors[index]
        }
    }
}
