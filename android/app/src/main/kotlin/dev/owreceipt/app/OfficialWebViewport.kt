package dev.owreceipt.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native two-axis fallback. Reparents the same WebView; never reloads its document or form. */
class OfficialWebViewport(context: Context, private val page: WebView) : FrameLayout(context) {
    private var wide = false
    init { addView(page, LayoutParams(-1, -1)) }

    fun setWidePage(enabled: Boolean) {
        if (wide == enabled) return
        wide = enabled
        (page.parent as? ViewGroup)?.removeView(page)
        removeAllViews()
        if (!enabled) {
            addView(page, LayoutParams(-1, -1))
            return
        }
        val density = resources.displayMetrics.density
        // Scroll views measure children without a width/height limit. The canvas minimums
        // keep WebView from collapsing to its current phone-sized content during that measure.
        val canvas = FrameLayout(context).apply {
            minimumWidth = (1440 * density).roundToInt()
            minimumHeight = (1200 * density).roundToInt()
            addView(page, LayoutParams((1440 * density).roundToInt(), (1200 * density).roundToInt()))
        }
        val horizontal = HorizontalPageScroll(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            addView(canvas, LayoutParams(-2, -2))
        }
        val vertical = VerticalPageScroll(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(horizontal, LayoutParams(-1, -2))
        }
        addView(vertical, LayoutParams(-1, -1))
    }

    private class HorizontalPageScroll(context: Context) : HorizontalScrollView(context) {
        private var startX = 0f; private var startY = 0f; private var pinching = false
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            pinching = event.pointerCount > 1
            if (pinching) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { startX = event.x; startY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.y - startY) > abs(event.x - startX)) return false
            return super.onInterceptTouchEvent(event)
        }
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept && pinching)
        }
    }
    private class VerticalPageScroll(context: Context) : ScrollView(context) {
        private var startX = 0f; private var startY = 0f; private var pinching = false
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            pinching = event.pointerCount > 1
            if (pinching) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { startX = event.x; startY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.x - startX) > abs(event.y - startY)) return false
            return super.onInterceptTouchEvent(event)
        }
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept && pinching)
        }
    }
}
