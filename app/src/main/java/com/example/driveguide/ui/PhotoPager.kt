package com.example.driveguide.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * 사진 가로 넘김. 이전·현재·다음 사진을 빈틈 없이 나란히 두고 손가락을 1:1로 따라가게 한 뒤,
 * 놓으면 남은 거리를 감속 애니메이션으로 마저 넘긴다. 사진이 3장 이상이면 끝에서 처음으로 이어진다.
 */
class PhotoPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onPageChanged: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private lateinit var scope: CoroutineScope
    private var images: List<String> = emptyList()
    var index = 0
        private set

    /** 0: 이전, 1: 현재, 2: 다음. 넘긴 뒤에는 뷰를 돌려 써서 이미 그린 사진이 다시 깜빡이지 않게 한다. */
    private val slots = MutableList(3) { createSlot() }
    private var offset = 0f
    private var animator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var moved = false
    private var velocity: VelocityTracker? = null

    private val wraps get() = images.size >= 3

    fun setImages(scope: CoroutineScope, images: List<String>, start: Int) {
        this.scope = scope
        this.images = images
        index = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        offset = 0f
        bindAll()
    }

    private fun createSlot() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 현재에서 [step]만큼 떨어진 사진 번호. 이어지지 않는 끝이면 null. */
    private fun neighbor(step: Int): Int? {
        if (images.isEmpty()) return null
        val i = index + step
        return when {
            i in images.indices -> i
            wraps -> (i + images.size) % images.size
            else -> null
        }
    }

    private fun bindAll() {
        for (s in 0..2) bindSlot(s)
        applyOffset()
        onPageChanged?.invoke(index)
    }

    private fun bindSlot(s: Int) {
        val view = slots[s]
        val i = neighbor(s - 1)
        view.visibility = if (i == null) INVISIBLE else VISIBLE
        if (i == null) return
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        ImageLoader.load(scope, view, images[i], resources.displayMetrics.widthPixels) {
            view.scaleType = ImageView.ScaleType.CENTER
            view.setImageResource(com.example.driveguide.R.drawable.ic_place_large)
        }
    }

    private fun applyOffset() {
        val w = width.toFloat()
        slots.forEachIndexed { s, v -> v.translationX = (s - 1) * w + offset }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = ev.x
                downY = ev.y
                startOffset = offset
                dragging = false
                moved = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                if (abs(dx) > touchSlop || abs(ev.y - downY) > touchSlop) moved = true
                if (!dragging && abs(dx) > touchSlop && images.size > 1) dragging = true
                if (dragging) {
                    var next = startOffset + dx
                    // 이어지지 않는 끝에서는 저항을 준다.
                    if ((next > 0 && neighbor(-1) == null) || (next < 0 && neighbor(1) == null)) next = startOffset + dx * 0.25f
                    offset = next.coerceIn(-width.toFloat(), width.toFloat())
                    applyOffset()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !dragging) {
                    onTap?.invoke()
                    return true
                }
                val w = width.toFloat()
                val step = when {
                    (offset < -w * 0.2f || vx < -minFling) && neighbor(1) != null -> 1
                    (offset > w * 0.2f || vx > minFling) && neighbor(-1) != null -> -1
                    else -> 0
                }
                settle(step)
                dragging = false
            }
        }
        return true
    }

    /** 남은 거리를 감속하며 마저 이동한다. step=±1이면 이웃 사진이 가운데로 온다. */
    private fun settle(step: Int) {
        val target = -step * width.toFloat()
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = (180 + 160 * abs(target - offset) / width.coerceAtLeast(1)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                offset = it.animatedValue as Float
                applyOffset()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (step != 0) shift(step)
                }
            })
            start()
        }
    }

    private fun shift(step: Int) {
        index = neighbor(step) ?: return
        // 이미 그려진 이웃 뷰를 가운데로 돌려 쓰고, 새로 비는 칸만 불러온다.
        if (step == 1) slots.add(slots.removeAt(0)) else slots.add(0, slots.removeAt(2))
        offset = 0f
        bindSlot(if (step == 1) 2 else 0)
        // 사진이 3장일 때는 반대쪽 칸도 바뀐 이웃을 가리키므로 다시 묶는다.
        bindSlot(if (step == 1) 0 else 2)
        applyOffset()
        onPageChanged?.invoke(index)
    }
}
