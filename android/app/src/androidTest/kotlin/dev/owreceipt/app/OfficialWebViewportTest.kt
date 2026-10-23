package dev.owreceipt.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OfficialWebViewportTest {
    @Test fun nativeDragsReachBothAxesEvenWhenPageLocksOverflowAndKeepForm() {
        val loaded = CountDownLatch(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var web: WebView
        lateinit var viewport: OfficialWebViewport
        try {
            scenario.onActivity { activity ->
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                }
                viewport = OfficialWebViewport(activity, web)
                activity.setContentView(viewport)
                web.loadDataWithBaseURL("https://ds.163.com/", """<html><head><meta name="viewport" content="width=device-width"></head>
                  <body style="overflow:hidden;touch-action:none"><input value="synthetic-form-value">
                  <script>window.fixtureLoads=1;</script></body></html>""", "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(8, TimeUnit.SECONDS))
            scenario.onActivity { viewport.setWidePage(true) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var pageWidth = 0; var hostWidth = 0; var scrollWidth = 0
            val deadline = SystemClock.uptimeMillis() + 3000
            do {
                scenario.onActivity { pageWidth = web.width; hostWidth = viewport.width; scrollWidth = find<HorizontalScrollView>(viewport)!!.width }
                if (pageWidth > hostWidth) break
                SystemClock.sleep(30)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("Wide page must exceed viewport: page=$pageWidth host=$hostWidth scroll=$scrollWidth", pageWidth > hostWidth)
            var bounds = android.graphics.Rect()
            scenario.onActivity { viewport.getGlobalVisibleRect(bounds) }
            swipe(bounds.right - 80f, bounds.centerY().toFloat(), bounds.left + 80f, bounds.centerY().toFloat())
            var horizontal = 0; var vertical = 0
            scenario.onActivity { horizontal = find<HorizontalScrollView>(viewport)!!.scrollX }
            assertTrue("Horizontal drag must move the native viewport: page=$pageWidth host=$hostWidth scroll=$scrollWidth", horizontal > 0)
            swipe(bounds.centerX().toFloat(), bounds.bottom - 150f, bounds.centerX().toFloat(), bounds.top + 150f)
            scenario.onActivity { vertical = find<ScrollView>(viewport)!!.scrollY }
            assertTrue("Vertical drag must move the native viewport", vertical > 0)
            scenario.onActivity { viewport.setWidePage(false) }
            val evidence = CountDownLatch(1); var value: String? = null
            scenario.onActivity { web.evaluateJavascript("JSON.stringify({value:document.querySelector('input').value,loads:window.fixtureLoads})") {
                value = it; evidence.countDown()
            } }
            assertTrue(evidence.await(3, TimeUnit.SECONDS))
            assertTrue(value!!.contains("synthetic-form-value"))
            assertTrue(value!!.contains("\\\"loads\\\":1"))
        } finally {
            scenario.onActivity { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
            scenario.close()
        }
    }
    private inline fun <reified T : View> find(root: View): T? {
        val queue = java.util.ArrayDeque<View>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is T) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return null
    }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val began = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), when(i) {
                0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
            }, x1 + (x2-x1)*i/20, y1 + (y2-y1)*i/20, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(12)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
