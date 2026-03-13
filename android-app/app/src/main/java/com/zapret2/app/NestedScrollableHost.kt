package com.zapret2.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.absoluteValue

/**
 * Layout wrapper that intercepts vertical scroll gestures and prevents
 * ViewPager2 from stealing them. Place this around any vertically-scrollable
 * child (ScrollView, RecyclerView, EditText with scrollbars, etc.) that lives
 * inside a ViewPager2 page.
 *
 * Based on the official Google sample for nested scrolling inside ViewPager2.
 */
class NestedScrollableHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var touchSlop = 0
    private var initialX = 0f
    private var initialY = 0f

    private val parentViewPager: ViewPager2?
        get() {
            var v: View? = parent as? View
            while (v != null && v !is ViewPager2) {
                v = v.parent as? View
            }
            return v as? ViewPager2
        }

    private val child: View? get() = if (childCount > 0) getChildAt(0) else null

    init {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleInterceptTouchEvent(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleInterceptTouchEvent(e: MotionEvent) {
        val orientation = parentViewPager?.orientation ?: return

        // Early return if the child can't scroll in the relevant direction
        if (!canChildScroll(orientation, -1f) && !canChildScroll(orientation, 1f)) {
            return
        }

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = e.x
                initialY = e.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - initialX
                val dy = e.y - initialY
                val isVpHorizontal = orientation == ViewPager2.ORIENTATION_HORIZONTAL

                // Determine the primary scroll axis
                val scaledDx = dx.absoluteValue * if (isVpHorizontal) 0.5f else 1f
                val scaledDy = dy.absoluteValue * if (isVpHorizontal) 1f else 0.5f

                if (scaledDx > touchSlop || scaledDy > touchSlop) {
                    if (isVpHorizontal == (scaledDy > scaledDx)) {
                        // Gesture is perpendicular to ViewPager2 orientation -- claim it
                        parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // Gesture is parallel to ViewPager2 -- let parent decide unless child can scroll
                        if (canChildScroll(orientation, if (isVpHorizontal) dx else dy)) {
                            parent.requestDisallowInterceptTouchEvent(true)
                        } else {
                            parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
            }
        }
    }

    private fun canChildScroll(orientation: Int, delta: Float): Boolean {
        val direction = -delta.toInt()
        return when (orientation) {
            ViewPager2.ORIENTATION_HORIZONTAL -> child?.canScrollHorizontally(direction) ?: false
            ViewPager2.ORIENTATION_VERTICAL -> child?.canScrollVertically(direction) ?: false
            else -> false
        }
    }
}
