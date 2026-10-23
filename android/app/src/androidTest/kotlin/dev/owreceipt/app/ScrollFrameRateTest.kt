package dev.owreceipt.app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.infra.demo.*
import dev.owreceipt.ui.*
import java.io.File
import java.util.Collections
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Checks Android's real per-frame vote after lazy items and pages are replaced. */
@RunWith(AndroidJUnit4::class)
class ScrollFrameRateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun highRefreshVoteSurvivesLazyScrollingAndPageChanges() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 35)
        val player = Player("scroll", "滑动验证#9516", "国服", "守望先锋")
        val snapshot = DemoFixtures.snapshot(player, QueryRange.at(Period.CAREER))
        val receipts = (0..59).map { index ->
            SavedReceipt(ReceiptDocument("scroll-$index", index.toLong(), snapshot,
                snapshot.blocks.map { it.id }, ReceiptFont.MEDIUM), 576, 2000)
        }
        val state = mutableStateOf(AppUiState(receipts = receipts, archiveLoading = false,
            snapshot = snapshot, queryStatus = QueryStatus.CONTENT))
        rule.runOnUiThread { rule.activity.setContent { ReceiptApp(state.value) { action ->
            if (action is UiAction.Tab) state.value = state.value.copy(tab = action.tab)
        } } }
        rule.onNodeWithTag("tab.ARCHIVE").performClick()
        rule.onNodeWithTag("archive.list").performScrollToIndex(25)
        val scrollRange = rule.onNodeWithTag("archive.list").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        var previousPosition = scrollRange.value()
        val samples = Collections.synchronizedList(mutableListOf<Boolean>())
        val frameDurations = Collections.synchronizedList(mutableListOf<Long>())
        val frameMetrics = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameDurations += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        val listener = ViewTreeObserver.OnDrawListener {
            val position = scrollRange.value()
            if (position != previousPosition) {
                // Sample after this traversal. FrameMetrics callbacks can arrive for an older frame.
                rule.activity.window.decorView.post {
                    val votes = views(rule.activity.window.decorView).map { it.requestedFrameRate }.toList()
                    samples += votes.any { it == View.REQUESTED_FRAME_RATE_CATEGORY_HIGH || it > 60f }
                }
                previousPosition = position
            }
        }
        rule.runOnUiThread {
            rule.activity.window.decorView.viewTreeObserver.addOnDrawListener(listener)
            rule.activity.window.addOnFrameMetricsAvailableListener(frameMetrics, Handler(Looper.getMainLooper()))
        }
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            repeat(2) {
                instrumentation.uiAutomation.executeShellCommand("input swipe 540 1650 540 650 800").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
                }
            }
            rule.waitForIdle()
        } finally {
            rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
            rule.runOnUiThread { rule.activity.window.removeOnFrameMetricsAvailableListener(frameMetrics) }
        }
        val captured = synchronized(samples) { samples.toList() }
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "verification").apply { mkdirs() }
        val durations = synchronized(frameDurations) { frameDurations.sorted() }
        fun percentile(value: Double): Double = durations[((durations.size - 1) * value).toInt()] / 1_000_000.0
        File(folder, "scroll-refresh-${BuildConfig.BUILD_TYPE}.txt").writeText(
            "frames=${captured.size}, high=${captured.count { it }}, debuggable=${BuildConfig.DEBUG}\n" +
                "rendered=${durations.size}, p50_ms=${percentile(.5)}, p90_ms=${percentile(.9)}\n")
        assertTrue("Expected multiple real scroll frames, got ${captured.size}", captured.size >= 10)
        assertTrue("Scrolling lost its high-refresh vote: ${captured.count { it }}/${captured.size} frames",
            captured.all { it })
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
