package com.cherry.wakeupschedule.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToastQueuePolicy] 的排队 / 合并语义测试（纯 JVM，无需模拟器）。
 *
 * 覆盖用户关心的三条核心行为：同类型消息短时间多次触发要合并、多条提示要排队、
 * 高优先级提示可以合理插队但不会造成闪烁。
 */
class ToastQueuePolicyTest {

    private var idSeq = 1L

    private fun request(
        type: AppToast.Type,
        message: String,
        groupKey: String? = null,
        baseDurationMs: Long = 2200L,
        now: Long = 0L,
    ) = ToastRequest(
        id = idSeq++,
        type = type,
        message = message,
        groupKey = groupKey,
        durationMs = ToastQueuePolicy.effectiveDuration(baseDurationMs, message),
        createdAtMs = now,
        updatedAtMs = now,
    )

    /** 把前三条提示直接铺满屏幕（策略上限 MAX_VISIBLE = 3） */
    private fun fillVisible(policy: ToastQueuePolicy): List<ToastRequest> {
        val list = (1..ToastQueuePolicy.MAX_VISIBLE).map { i ->
            request(AppToast.Type.INFO, "消息$i", now = 0L)
        }
        list.forEach { policy.submit(it, 0L) }
        return list
    }

    @Test
    fun sameKeyWithinWindowMergesIntoOneCard() {
        val policy = ToastQueuePolicy()
        val first = request(AppToast.Type.SUCCESS, "保存成功", now = 0L)

        val firstDecisions = policy.submit(first, 0L)
        assertEquals(1, firstDecisions.size)
        assertTrue(firstDecisions[0] is ToastQueuePolicy.Decision.Show)

        repeat(4) { i ->
            val again = request(AppToast.Type.SUCCESS, "保存成功", now = 300L * (i + 1))
            val decisions = policy.submit(again, 300L * (i + 1))
            assertEquals(1, decisions.size)
            assertTrue(decisions[0] is ToastQueuePolicy.Decision.Refresh)
            assertSame(first, (decisions[0] as ToastQueuePolicy.Decision.Refresh).request)
        }

        assertEquals(1, policy.visibleCount)
        assertEquals(5, first.count)
        assertEquals(1200L, first.updatedAtMs)
    }

    @Test
    fun mergeRefreshesDurationButNeverShortensIt() {
        val policy = ToastQueuePolicy()
        val long = request(AppToast.Type.ERROR, "下载失败", baseDurationMs = 3600L, now = 0L)
        policy.submit(long, 0L)
        val short = request(AppToast.Type.ERROR, "下载失败", baseDurationMs = 2200L, now = 100L)
        policy.submit(short, 100L)

        assertEquals(ToastQueuePolicy.effectiveDuration(3600L, "下载失败"), long.durationMs)
    }

    @Test
    fun sameKeyOutsideWindowCreatesNewCard() {
        val policy = ToastQueuePolicy()
        policy.submit(request(AppToast.Type.SUCCESS, "保存成功", now = 0L), 0L)
        val late = request(AppToast.Type.SUCCESS, "保存成功", now = 5000L)

        val decisions = policy.submit(late, 5000L)

        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Show)
        assertEquals(2, policy.visibleCount)
    }

    @Test
    fun differentMessageDoesNotMerge() {
        val policy = ToastQueuePolicy()
        policy.submit(request(AppToast.Type.SUCCESS, "保存成功", now = 0L), 0L)
        val other = request(AppToast.Type.SUCCESS, "已解绑教务账号", now = 100L)

        val decisions = policy.submit(other, 100L)

        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Show)
        assertEquals(2, policy.visibleCount)
    }

    @Test
    fun differentTypeDoesNotMergeEvenWithSameText() {
        val policy = ToastQueuePolicy()
        policy.submit(request(AppToast.Type.INFO, "已完成", now = 0L), 0L)
        val error = request(AppToast.Type.ERROR, "已完成", now = 100L)

        val decisions = policy.submit(error, 100L)

        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Show)
        assertEquals(2, policy.visibleCount)
    }

    @Test
    fun sameGroupKeyMergesEvenWhenTextDiffers() {
        val policy = ToastQueuePolicy()
        policy.submit(
            request(AppToast.Type.ERROR, "下载失败: HTTP 500", groupKey = "download_failed", now = 0L),
            0L,
        )
        val other = request(
            AppToast.Type.ERROR, "下载失败: HTTP 502", groupKey = "download_failed", now = 200L,
        )

        val decisions = policy.submit(other, 200L)

        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Refresh)
        assertEquals(1, policy.visibleCount)
    }

    @Test
    fun queuedDuplicateMergesWithoutGrowingQueue() {
        val policy = ToastQueuePolicy()
        fillVisible(policy)
        val queuedFirst = request(AppToast.Type.INFO, "正在下载课程表...", groupKey = "dl", now = 10L)
        policy.submit(queuedFirst, 10L)
        assertEquals(1, policy.queuedCount)

        val duplicate = request(AppToast.Type.INFO, "正在下载课程表...", groupKey = "dl", now = 500L)
        val decisions = policy.submit(duplicate, 500L)

        assertTrue(decisions.isEmpty())
        assertEquals(1, policy.queuedCount)
        assertEquals(2, queuedFirst.count)
        assertEquals(500L, queuedFirst.updatedAtMs)
    }

    @Test
    fun fourthDistinctRequestWaitsThenPromotesInOrder() {
        val policy = ToastQueuePolicy()
        val visible = fillVisible(policy)
        assertEquals(3, policy.visibleCount)

        val fourth = request(AppToast.Type.INFO, "第四条", now = 100L)
        val decisions = policy.submit(fourth, 100L)

        assertTrue(decisions.isEmpty())
        assertEquals(3, policy.visibleCount)
        assertEquals(1, policy.queuedCount)

        val promoted = policy.onDismissed(visible[0].id, 1000L)

        assertSame(fourth, promoted)
        assertEquals(3, policy.visibleCount)
        assertEquals(0, policy.queuedCount)
    }

    @Test
    fun fifoOrderIsPreservedWhenPromoting() {
        val policy = ToastQueuePolicy()
        val visible = fillVisible(policy)
        val a = request(AppToast.Type.INFO, "A", now = 10L)
        val b = request(AppToast.Type.INFO, "B", now = 20L)
        policy.submit(a, 10L)
        policy.submit(b, 20L)

        assertSame(a, policy.onDismissed(visible[0].id, 1000L))
        assertSame(b, policy.onDismissed(visible[1].id, 1100L))
    }

    @Test
    fun higherPriorityPreemptsLowPriorityAfterMinDisplay() {
        val policy = ToastQueuePolicy()
        fillVisible(policy)
        val error = request(AppToast.Type.ERROR, "下载失败", now = 1000L)

        val decisions = policy.submit(error, 1000L)

        assertEquals(2, decisions.size)
        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Preempt)
        assertTrue(decisions[1] is ToastQueuePolicy.Decision.Show)
        assertEquals(3, policy.visibleCount)
        assertEquals(0, policy.queuedCount)
        assertTrue(policy.visible.any { it === error })
    }

    @Test
    fun higherPriorityQueuesWhenVictimIsTooFreshThenPromotes() {
        val policy = ToastQueuePolicy()
        val visible = fillVisible(policy)
        val error = request(AppToast.Type.ERROR, "下载失败", now = 100L)

        // 被抢占卡只展示了 100ms（< MIN_DISPLAY_MS），不抢占，插队等待
        val decisions = policy.submit(error, 100L)

        assertTrue(decisions.isEmpty())
        assertEquals(1, policy.queuedCount)

        // 让位卡到期后，错误提示立刻顶上
        assertSame(error, policy.onDismissed(visible[0].id, 600L))
        assertTrue(policy.visible.any { it === error })
    }

    @Test
    fun equalPriorityNeverPreempts() {
        val policy = ToastQueuePolicy()
        repeat(ToastQueuePolicy.MAX_VISIBLE) { i ->
            policy.submit(request(AppToast.Type.WARN, "警告$i", now = 0L), 0L)
        }
        val sameLevel = request(AppToast.Type.WARN, "请先绑定教务账号", now = 1000L)

        val decisions = policy.submit(sameLevel, 1000L)

        assertTrue(decisions.isEmpty())
        assertEquals(1, policy.queuedCount)
    }

    @Test
    fun lowerPriorityNeverPreemptsHigherPriority() {
        val policy = ToastQueuePolicy()
        repeat(ToastQueuePolicy.MAX_VISIBLE) { i ->
            policy.submit(request(AppToast.Type.ERROR, "错误$i", now = 0L), 0L)
        }
        val info = request(AppToast.Type.INFO, "普通提示", now = 1000L)

        val decisions = policy.submit(info, 1000L)

        assertTrue(decisions.isEmpty())
        assertEquals(1, policy.queuedCount)
        assertTrue(policy.visible.none { it === info })
    }

    @Test
    fun queueOverflowDropsOldestLowestPriority() {
        val policy = ToastQueuePolicy()
        fillVisible(policy)
        val queued = (1..ToastQueuePolicy.MAX_QUEUED).map { i ->
            request(AppToast.Type.INFO, "排队$i", now = i.toLong())
        }
        queued.forEach { policy.submit(it, it.createdAtMs) }
        assertEquals(ToastQueuePolicy.MAX_QUEUED, policy.queuedCount)

        val newcomer = request(AppToast.Type.INFO, "新来的", now = 100L)
        val decisions = policy.submit(newcomer, 100L)

        assertEquals(1, decisions.size)
        assertTrue(decisions[0] is ToastQueuePolicy.Decision.Drop)
        assertSame(queued[0], (decisions[0] as ToastQueuePolicy.Decision.Drop).request)
        assertEquals(ToastQueuePolicy.MAX_QUEUED, policy.queuedCount)
    }

    @Test
    fun queueOverflowKeepsHighPriorityAndDropsTheNewLowPriorityOne() {
        val policy = ToastQueuePolicy()
        val visible = (1..ToastQueuePolicy.MAX_VISIBLE).map { i ->
            request(AppToast.Type.ERROR, "错误$i", now = 0L).also { policy.submit(it, 0L) }
        }
        assertEquals(3, visible.size)
        (1..ToastQueuePolicy.MAX_QUEUED).forEach { i ->
            val e = request(AppToast.Type.ERROR, "排队错误$i", now = i.toLong())
            policy.submit(e, e.createdAtMs)
        }
        assertEquals(ToastQueuePolicy.MAX_QUEUED, policy.queuedCount)

        val info = request(AppToast.Type.INFO, "普通提示", now = 999L)
        val decisions = policy.submit(info, 999L)

        assertEquals(1, decisions.size)
        assertSame(info, (decisions[0] as ToastQueuePolicy.Decision.Drop).request)
        assertEquals(ToastQueuePolicy.MAX_QUEUED, policy.queuedCount)
    }

    @Test
    fun forgetRemovesVisibleButKeepsQueue() {
        val policy = ToastQueuePolicy()
        val visible = fillVisible(policy)
        val queued = request(AppToast.Type.INFO, "排队中", now = 10L)
        policy.submit(queued, 10L)

        policy.forget(visible[0].id)

        assertEquals(2, policy.visibleCount)
        assertEquals(1, policy.queuedCount)
        assertEquals(null, policy.onDismissed(visible[0].id, 1000L))
    }

    @Test
    fun drainFillsVisibleSlotsFromQueue() {
        val policy = ToastQueuePolicy()
        val visible = fillVisible(policy)
        val a = request(AppToast.Type.INFO, "A", now = 10L)
        val b = request(AppToast.Type.INFO, "B", now = 20L)
        policy.submit(a, 10L)
        policy.submit(b, 20L)
        policy.forget(visible[0].id)
        policy.forget(visible[1].id)

        val drained = policy.drain(2000L)

        assertEquals(2, drained.size)
        assertSame(a, drained[0])
        assertSame(b, drained[1])
        assertEquals(3, policy.visibleCount)
        assertEquals(0, policy.queuedCount)
    }

    @Test
    fun clearReturnsEverythingAndResetsState() {
        val policy = ToastQueuePolicy()
        fillVisible(policy)
        policy.submit(request(AppToast.Type.INFO, "排队", now = 10L), 10L)

        val all = policy.clear()

        assertEquals(4, all.size)
        assertEquals(0, policy.visibleCount)
        assertEquals(0, policy.queuedCount)
    }

    @Test
    fun effectiveDurationIsClampedAndGrowsWithTextLength() {
        assertEquals(1600L, ToastQueuePolicy.effectiveDuration(1000L, "短"))
        assertEquals(2200L, ToastQueuePolicy.effectiveDuration(2200L, "短"))
        assertEquals(6000L, ToastQueuePolicy.effectiveDuration(3600L, "长".repeat(200)))
        assertTrue(
            ToastQueuePolicy.effectiveDuration(2200L, "长".repeat(40)) >
                ToastQueuePolicy.effectiveDuration(2200L, "短")
        )
    }
}
