package com.cherry.wakeupschedule.ui.feedback

/**
 * 应用内提示的排队 / 合并策略 —— **纯 Kotlin，零 Android 依赖**。
 *
 * 时间由调用方以 [SystemClock.uptimeMillis] 的毫秒值注入，因此整套排队语义
 * 可以在 JVM 单元测试里完整覆盖（见 `ToastQueuePolicyTest`）。
 *
 * 策略摘要：
 * 1. 同键（[ToastRequest.mergeKey]）且在 [mergeWindowMs] 内的重复提示**合并**成一张卡，
 *    只累加计数、刷新时长，不新增卡片。
 * 2. 同屏最多 [maxVisible] 张，多出的进等待队列（容量 [maxQueued]）。
 * 3. 高优先级提示可以**抢占**低优先级的已显示卡片；但被抢占卡片展示不足
 *    [minDisplayMs] 时改为插队，等它自然到期后立刻顶上，避免闪烁。
 * 4. 队列溢出时丢弃"优先级最低、其次最旧"的一条。
 */
internal class ToastQueuePolicy(
    private val maxVisible: Int = MAX_VISIBLE,
    private val maxQueued: Int = MAX_QUEUED,
    private val mergeWindowMs: Long = MERGE_WINDOW_MS,
    private val minDisplayMs: Long = MIN_DISPLAY_MS,
) {

    /** 一次 [submit] 需要宿主执行的动作 */
    sealed interface Decision {
        /** 新增一张卡片 */
        data class Show(val request: ToastRequest) : Decision

        /** 已显示的卡片被合并：刷新计数、重启计时、播放脉冲 */
        data class Refresh(val request: ToastRequest) : Decision

        /** 需要立刻让位并移除的已显示卡片（随后必定跟一条 [Show]） */
        data class Preempt(val request: ToastRequest) : Decision

        /** 因队列溢出被丢弃的提示 */
        data class Drop(val request: ToastRequest) : Decision
    }

    private val _visible = mutableListOf<ToastRequest>()
    private val _queued = ArrayDeque<ToastRequest>()

    /** 当前正在显示的提示（只读快照，按显示顺序：旧 → 新） */
    val visible: List<ToastRequest> get() = _visible

    val visibleCount: Int get() = _visible.size

    val queuedCount: Int get() = _queued.size

    /**
     * 提交一条提示，返回需要顺序执行的动作。
     * 合并进**队列**中的提示时不产生动作（计数已就地更新，等它显示时自然带上）。
     */
    fun submit(request: ToastRequest, now: Long): List<Decision> {
        // 1. 合并进已显示的卡片
        _visible.firstOrNull { it.mergeKey == request.mergeKey && withinWindow(it, now) }
            ?.let { target ->
                merge(target, request, now)
                return listOf(Decision.Refresh(target))
            }

        // 2. 合并进等待队列
        _queued.firstOrNull { it.mergeKey == request.mergeKey && withinWindow(it, now) }
            ?.let { target ->
                merge(target, request, now)
                return emptyList()
            }

        // 3. 屏幕还有空位
        if (_visible.size < maxVisible) {
            admit(request, now)
            return listOf(Decision.Show(request))
        }

        // 4. 满屏：高优先级可抢占
        val victim = _visible.minWithOrNull(
            compareBy({ it.type.priority }, { it.shownAtMs })
        )
        if (victim != null && request.type.priority > victim.type.priority) {
            if (now - victim.shownAtMs >= minDisplayMs) {
                _visible.remove(victim)
                admit(request, now)
                return listOf(Decision.Preempt(victim), Decision.Show(request))
            }
            // 让位卡刚出现，先插队，等它自然到期后立刻顶上
            _queued.addFirst(request)
            return emptyList()
        }

        // 5. 排队，并按优先级淘汰
        _queued.addLast(request)
        val decisions = mutableListOf<Decision>()
        while (_queued.size > maxQueued) {
            val dropped = _queued.minWithOrNull(
                compareBy({ it.type.priority }, { it.createdAtMs })
            ) ?: break
            _queued.remove(dropped)
            decisions += Decision.Drop(dropped)
            if (dropped === request) return decisions
        }
        return decisions
    }

    /**
     * 一条已显示提示被关闭（自然到期或用户手动关闭）。
     * 返回需要顶上显示的队列首条；队列为空时返回 null。
     */
    fun onDismissed(id: Long, now: Long): ToastRequest? {
        val index = _visible.indexOfFirst { it.id == id }
        if (index < 0) return null
        _visible.removeAt(index)
        if (_queued.isEmpty()) return null
        val next = _queued.removeFirst()
        admit(next, now)
        return next
    }

    /**
     * 一条已显示提示被关闭、但当前**没有可用宿主页面**（例如 Activity 已销毁）。
     * 只从显示列表移除，不顶上下一条 —— 队列原样保留，等宿主出现时由 [drain] 顶上。
     */
    fun forget(id: Long) {
        _visible.removeAll { it.id == id }
    }

    /** 宿主页面可用时，用等待队列填满空位，返回需要新显示的提示 */
    fun drain(now: Long): List<ToastRequest> {
        val out = mutableListOf<ToastRequest>()
        while (_visible.size < maxVisible && _queued.isNotEmpty()) {
            val next = _queued.removeFirst()
            admit(next, now)
            out += next
        }
        return out
    }

    /** 清空全部状态（应用退出 / 主动取消），返回所有被清掉的提示 */
    fun clear(): List<ToastRequest> {
        val all = _visible.toList() + _queued.toList()
        _visible.clear()
        _queued.clear()
        return all
    }

    private fun withinWindow(target: ToastRequest, now: Long): Boolean =
        now - target.updatedAtMs <= mergeWindowMs

    private fun merge(target: ToastRequest, incoming: ToastRequest, now: Long) {
        target.count += 1
        target.updatedAtMs = now
        target.durationMs = maxOf(target.durationMs, incoming.durationMs)
    }

    private fun admit(request: ToastRequest, now: Long) {
        request.shownAtMs = now
        _visible += request
    }

    companion object {
        /** 同屏最多卡片数 */
        const val MAX_VISIBLE = 3

        /** 等待队列容量 */
        const val MAX_QUEUED = 8

        /** 同键合并时间窗（毫秒） */
        const val MERGE_WINDOW_MS = 2500L

        /** 卡片最短展示时间，避免被抢占时"闪一下就没" */
        const val MIN_DISPLAY_MS = 400L

        /** 无宿主页面时，待发提示的最长滞留时间 */
        const val PENDING_TTL_MS = 8000L

        /**
         * 按文案长度自适应展示时长：基准 + 每 10 字多 250ms，
         * 最终收敛到 [1600, 6000] 区间，保证长文案有足够阅读时间、短文案不拖沓。
         */
        fun effectiveDuration(baseMs: Long, message: String): Long =
            (baseMs + (message.length / 10) * 250L).coerceIn(1600L, 6000L)
    }
}

/**
 * 一条提示请求。除 [shownAtMs] 由策略在进入显示列表时写入外，
 * 其余可变字段都用于"合并"语义。
 */
internal class ToastRequest(
    val id: Long,
    val type: AppToast.Type,
    val message: String,
    /** 调用方指定的归并键；为 null 时退化为"类型 + 文案" */
    val groupKey: String?,
    var durationMs: Long,
    val createdAtMs: Long,
    var updatedAtMs: Long,
    var count: Int = 1,
) {
    /** 进入显示列表的时刻，用于最短展示时间判定 */
    var shownAtMs: Long = 0L

    /** 合并键：同键且落在合并窗口内 → 视为同一条提示 */
    val mergeKey: String get() = groupKey ?: "${type.name}|${message.trim()}"
}
