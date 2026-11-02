package com.example.driveguide.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 좌우 스와이프를 감지하는 FrameLayout. 끄는 동안 화면을 손가락을 따라 조금 움직이고,
 * 충분히 밀면 [onSwipe](+1 다음, -1 이전)를 부른다. 세로 스크롤과 [excludedViews] 위의 가로 드래그는 건드리지 않는다.
 * 움직이지 않고 손을 떼면 [onTap]을 부른다. 단 [tapExcludedViews](정보 패널·버튼 등) 위의 탭은 제외한다.
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onSwipe: ((Int) -> Unit)? = null
    /** 이 뷰들 위에서 시작한 드래그는 무시한다(썸네일·칩 가로 스크롤). */
    var excludedViews: List<View> = emptyList()
    /** 넘길 곳이 있는지. false면 끌어도 거의 움직이지 않는다. */
    var canSwipe: (Int) -> Boolean = { true }
    var onTap: (() -> Unit)? = null
    var tapExcludedViews: List<View> = emptyList()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var ignore = false
    private var tapCandidate = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    /** 터치 시작점을 기록하고, 가로로 분명히 움직였으면 드래그로 판정한다. */
    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                ignore = isOnExcluded(excludedViews, ev.rawX.toInt(), ev.rawY.toInt())
                tapCandidate = !isOnExcluded(tapExcludedViews, ev.rawX.toInt(), ev.rawY.toInt())
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) tapCandidate = false
                if (!ignore && abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 2) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            // 자식(세로 스크롤 등)이 터치를 가져가도 부모 가로채기 단계에서 손 뗌을 볼 수 있다.
            MotionEvent.ACTION_UP -> if (!dragging && tapCandidate) {
                tapCandidate = false
                onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 사진처럼 터치를 받지 않는 자식 위에서 시작하면 이후 이벤트가 바로 여기로 오므로 여기서도 판정한다.
        if (!dragging) {
            track(ev)
            if (!dragging) return !ignore
        }
        velocity?.addMovement(ev)
        val dx = ev.x - downX
        // 손가락 방향의 반대가 넘길 대상이다(왼쪽으로 밀면 다음).
        val dir = if (dx < 0) 1 else -1
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val damping = if (canSwipe(dir)) 0.5f else 0.12f
                translationX = dx * damping
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val passed = abs(dx) > width * 0.18f || abs(vx) > minFlingVelocity
                dragging = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && passed && canSwipe(dir)) {
                    onSwipe?.invoke(dir)
                } else {
                    animate().translationX(0f).setDuration(180).start()
                }
            }
        }
        return true
    }

    private fun isOnExcluded(views: List<View>, rawX: Int, rawY: Int): Boolean {
        val rect = Rect()
        val loc = IntArray(2)
        return views.any { v ->
            if (!v.isShown) return@any false
            v.getLocationOnScreen(loc)
            rect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            rect.contains(rawX, rawY)
        }
    }
}
