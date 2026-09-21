package com.cherry.wakeupschedule.ui.feedback

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.cherry.wakeupschedule.R
import com.cherry.wakeupschedule.databinding.ViewAppToastBinding
import com.cherry.wakeupschedule.ui.theme.setTextSizeRes
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一张提示卡解析后的配色（由 [ToastManager] 从当前宿主页面的主题里取） */
internal data class ToastColors(
    /** 语义强调色：图标、描边、计数徽标、进度线 */
    val accent: Int,
    /** 卡片底色 */
    val surface: Int,
    /** 主文案颜色 */
    val onSurface: Int,
)

/**
 * 单张应用内提示卡：负责渲染、进出场动画、计时进度线与关闭手势。
 *
 * 视觉语言对齐项目既有卡片（`AppSectionCard` / `DetailSheetCard`）：18dp 大圆角、
 * 1dp 语义色描边、6dp 软阴影；强调色只用在图标芯片、描边、徽标和进度线上，
 * 主文案始终用 `colorOnSurface`，保证任何主题/深浅色下都可读。
 */
internal class ToastView(context: Context) {

    private val binding = ViewAppToastBinding.inflate(LayoutInflater.from(context))

    /** 挂到堆叠容器上的根视图 */
    val view: View get() = binding.root

    /** 用户点按 / 滑动要求关闭本卡 */
    var onDismissRequested: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private var progressAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var entering = false
    private var dismissed = false

    init {
        binding.root.setOnClickListener {
            if (!dismissed) onDismissRequested?.invoke()
        }
        installGestures(context)
    }

    // ── 渲染 ─────────────────────────────────────────────

    fun bind(request: ToastRequest, colors: ToastColors, maxTextWidthPx: Int) {
        val accent = colors.accent
        val card = binding.root

        card.setCardBackgroundColor(colors.surface)
        card.strokeColor = ColorUtils.setAlphaComponent(accent, STROKE_ALPHA)
        card.strokeWidth = dp(1f)

        binding.flIconChip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, CHIP_ALPHA))
        }
        binding.ivIcon.setImageResource(iconRes(request.type))
        binding.ivIcon.imageTintList = ColorStateList.valueOf(accent)

        binding.tvMessage.text = request.message
        binding.tvMessage.setTextSizeRes(R.dimen.text_body)
        binding.tvMessage.setTextColor(colors.onSurface)
        binding.tvMessage.maxWidth = maxTextWidthPx

        binding.tvBadge.background = GradientDrawable().apply {
            cornerRadius = dp(9f).toFloat()
            setColor(ColorUtils.setAlphaComponent(accent, BADGE_ALPHA))
        }
        binding.tvBadge.setTextSizeRes(R.dimen.text_caption)
        binding.tvBadge.setTextColor(accent)
        binding.tvBadge.setTypeface(Typeface.DEFAULT_BOLD)

        binding.vProgress.setBackgroundColor(ColorUtils.setAlphaComponent(accent, PROGRESS_ALPHA))

        updateCount(request.count)

        card.contentDescription = "${request.type.label}：${request.message}"
    }

    /** 合并计数变化：`×N` 徽标淡入 + 轻微放大 */
    fun updateCount(count: Int) {
        val badge = binding.tvBadge
        if (count <= 1) {
            badge.visibility = View.GONE
            return
        }
        badge.text = if (count > 99) "×99+" else "×$count"
        if (badge.visibility == View.VISIBLE) return
        badge.visibility = View.VISIBLE
        badge.alpha = 0f
        badge.scaleX = 0.6f
        badge.scaleY = 0.6f
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
    }

    // ── 动画 ─────────────────────────────────────────────

    fun playEnter() {
        pulseAnimator?.cancel()
        val card = binding.root
        entering = true
        card.alpha = 0f
        card.translationY = -dpF(8f)
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220L)
            .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .withEndAction { entering = false }
            .start()
    }

    fun playExit(onEnd: () -> Unit) {
        dismissed = true
        stopProgress()
        pulseAnimator?.cancel()
        binding.root.animate()
            .alpha(0f).translationY(-dpF(6f)).scaleX(0.98f).scaleY(0.98f)
            .setDuration(160L)
            .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
            .withEndAction(onEnd)
            .start()
    }

    /** 同键提示被合并时的一次"呼吸"反馈；入场动画未结束时只弹徽标，避免两条动画打架 */
    fun playPulse() {
        if (entering) return
        pulseAnimator?.cancel()
        val card = binding.root
        val animator = ValueAnimator.ofFloat(1f, 1.03f, 1f).apply {
            duration = 180L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val v = it.animatedValue as Float
                card.scaleX = v
                card.scaleY = v
            }
        }
        pulseAnimator = animator
        animator.start()
    }

    // ── 计时进度线 ───────────────────────────────────────

    fun startProgress(durationMs: Long) {
        val line = binding.vProgress
        if (!SHOW_PROGRESS_LINE) {
            line.visibility = View.GONE
            return
        }
        progressAnimator?.cancel()
        line.visibility = View.VISIBLE
        line.pivotX = 0f
        line.pivotY = 0f
        line.scaleX = 1f
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { line.scaleX = it.animatedValue as Float }
        }
        progressAnimator = animator
        animator.start()
    }

    fun stopProgress() {
        progressAnimator?.cancel()
        progressAnimator = null
    }

    // ── 手势：点按关闭 + 上下滑动关闭 ─────────────────────

    private fun installGestures(context: Context) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 走 performClick 而不是直接回调，保证 TalkBack 的 ACTION_CLICK 行为一致
                binding.root.performClick()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val dy = e2.rawY - (e1?.rawY ?: e2.rawY)
                val card = binding.root
                card.translationY = dy
                val height = card.height.coerceAtLeast(1)
                card.alpha = (1f - abs(dy) / (height * 1.6f)).coerceIn(0.25f, 1f)
                return true
            }
        })

        binding.root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dismissed) {
                        val card = binding.root
                        val threshold = maxOf(dpF(24f), card.height / 4f)
                        if (abs(card.translationY) > threshold) {
                            onDismissRequested?.invoke()
                        } else {
                            card.animate().translationY(0f).alpha(1f).setDuration(140L).start()
                        }
                    }
                }
            }
            true
        }
    }

    // ── 工具 ─────────────────────────────────────────────

    private fun iconRes(type: AppToast.Type): Int = when (type) {
        AppToast.Type.SUCCESS -> R.drawable.ic_toast_success
        AppToast.Type.INFO -> R.drawable.ic_toast_info
        AppToast.Type.WARN -> R.drawable.ic_toast_warning
        AppToast.Type.ERROR -> R.drawable.ic_toast_error
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun dpF(value: Float): Float = value * density

    private companion object {
        const val STROKE_ALPHA = 0x33
        const val CHIP_ALPHA = 0x24
        const val BADGE_ALPHA = 0x1F
        const val PROGRESS_ALPHA = 0x66

        /** 卡片底部剩余时长进度线；觉得干扰可直接关掉 */
        const val SHOW_PROGRESS_LINE = true
    }
}
