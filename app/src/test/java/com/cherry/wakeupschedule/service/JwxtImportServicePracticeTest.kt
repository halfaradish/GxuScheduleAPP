package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import com.gxu.jwxt.model.ScheduleResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实践课（教务 sjkList）导入转换测试。
 *
 * 实践课条目只有课程名/教师/类别/起止教学周（qsjsz），没有星期（xqj）与节次（jc/jcs），
 * 早期实现会在 convertEntry 里整条丢弃。样例字段取自 docs/superpowers/testdata.json 的真实抓包。
 */
class JwxtImportServicePracticeTest {

    private fun response(json: String): ScheduleResponse =
        Gson().fromJson(json, ScheduleResponse::class.java)

    private fun coursesOf(json: String): List<Course> =
        JwxtImportService.convertScheduleResponse(response(json)).first

    @Test
    fun `实践课不再被丢弃且周次取自 qsjsz`() {
        val courses = coursesOf(
            """
            {"sjkList":[{
              "kcmc":"数据结构综合实践","jsxm":"田阳","kclb":"集中实践必修",
              "xf":"1.0","qsjsz":"1-16周","sfsjk":"1"
            }]}
            """.trimIndent()
        )

        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals("数据结构综合实践", c.name)
        assertEquals("田阳", c.teacher)
        assertEquals("集中实践必修", c.courseCategory)
        assertEquals("1.0", c.credits)
        assertEquals(Course.bitmapFromRange(1, 16), c.weekBitmap)
        assertEquals(Course.NO_FIXED_DAY, c.dayOfWeek)
        assertEquals(Course.NO_FIXED_SLOT, c.startTime)
        assertFalse(c.hasFixedTime())
        assertTrue(c.isPractice)
        // 无固定时间 → 不排闹钟
        assertFalse(c.alarmEnabled)
    }

    @Test
    fun `理论课带回的补充信息完整映射且占位值被清空`() {
        val courses = coursesOf(
            """
            {"kbList":[{
              "kcmc":"计算机组成原理","xm":"李娜","cdmc":"计电302",
              "xqj":"1","jc":"1-2节","jcs":"1-2","zcd":"7-11周(单),12-16周",
              "kch":"1071198","kcxz":"学类","kclb":"学类核心课",
              "jxbmc":"计算机组成原理-0003A","jxbzc":"计算机科学与技术241",
              "kczxs":"72","kcxszc":"理论:56,实验:16","cdlbmc":"机房",
              "khfsmc":"考试","ksfsmc":"闭卷","zcmc":"无","xqmc":"*"
            }]}
            """.trimIndent()
        )

        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals("1071198", c.courseCode)
        assertEquals("学类", c.courseNature)
        assertEquals("计算机组成原理-0003A", c.teachingClass)
        assertEquals("计算机科学与技术241", c.classComposition)
        assertEquals("72", c.totalHours)
        assertEquals("理论:56,实验:16", c.hourComposition)
        assertEquals("机房", c.classroomType)
        assertEquals("考试", c.assessmentMethod)
        assertEquals("闭卷", c.examForm)
        assertFalse(c.isPractice)
    }

    @Test
    fun `实践课描述被带入详情展示字段`() {
        val courses = coursesOf(
            """
            {"sjkList":[{
              "kcmc":"数据结构综合实践","jsxm":"田阳","qsjsz":"1-16周","sfsjk":"1",
              "sjkcgs":"数据结构综合实践●田阳(共16周)/1-16周","xf":"1.0"
            }]}
            """.trimIndent()
        )

        assertEquals(1, courses.size)
        assertEquals("数据结构综合实践●田阳(共16周)/1-16周", courses[0].practiceDetail)
        assertTrue(courses[0].isPractice)
    }

    @Test
    fun `缺 sfsjk 时按没有星期节次但有起止教学周兜底识别实践课`() {
        val courses = coursesOf(
            """
            {"sjkList":[{"kcmc":"数字电路与逻辑设计实践","jsxm":"严毅","qsjsz":"1-16周"}]}
            """.trimIndent()
        )

        assertEquals(1, courses.size)
        assertFalse(courses[0].hasFixedTime())
    }

    @Test
    fun `理论课仍然按星期与节次导入`() {
        val courses = coursesOf(
            """
            {"kbList":[{
              "kcmc":"高等数学","xm":"张三","cdmc":"6B-603",
              "xqj":"1","jc":"1-2节","jcs":"1-2","zcd":"1-16周","kclb":"学类核心课"
            }]}
            """.trimIndent()
        )

        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startTime)
        assertEquals(2, c.endTime)
        assertTrue(c.hasFixedTime())
        assertTrue(c.alarmEnabled)
    }

    @Test
    fun `既没有星期节次也没有起止教学周的条目仍然丢弃`() {
        assertTrue(coursesOf("""{"sjkList":[{"kcmc":"神秘课程"}]}""").isEmpty())
    }

    @Test
    fun `只有实践课时不反推学期开始日期`() {
        val start = JwxtImportService.convertScheduleResponse(
            response("""{"sjkList":[{"kcmc":"数据结构综合实践","jsxm":"田阳","qsjsz":"1-16周","sfsjk":"1"}]}""")
        ).second

        assertNull(start)
    }
}
