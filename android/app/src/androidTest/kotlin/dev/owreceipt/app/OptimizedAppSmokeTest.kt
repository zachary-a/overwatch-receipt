package dev.owreceipt.app

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Black-box check: the runner lives in the demo package, outside the optimized app's class loader. */
@RunWith(AndroidJUnit4::class)
class OptimizedAppSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun optimizedAppOpensDeviceAndOfficialAccountScreens() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("runOptimizedSmoke") == "true")
        Assume.assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        shell("am force-stop dev.owreceipt.app")
        shell("am start -W -n dev.owreceipt.app/dev.owreceipt.app.MainActivity")
        try {
            waitFor { automation.rootInActiveWindow?.packageName?.toString() == "dev.owreceipt.app" && findText("制作") != null }
            click("设备")
            waitFor { findText("打印设备") != null && findText("扫描附近设备") != null }
            assertNull(findText("份数"))
            assertNull(findText("尾部送纸"))
            capture("25-optimized-device.png")
            click("制作")
            waitFor { findText("登录我的国服账号") != null }
            click("登录我的国服账号")
            waitFor { findText("打开网易官方登录") != null }
            click("打开网易官方登录")
            waitFor { nodes(automation.rootInActiveWindow).any { it.className?.toString() == "android.webkit.WebView" } }
            waitFor { findText("宽页滑动") != null && findText("重新读取绑定角色") != null }
            capture("25-optimized-official-page.png")
        } finally { shell("am force-stop dev.owreceipt.app") }
    }

    // Compose exposes virtual descendants; traverse them instead of using the View text-search API.
    private fun findText(text: String) = nodes(automation.rootInActiveWindow)
        .firstOrNull { it.isVisibleToUser && it.text?.toString() == text }

    private fun click(text: String) {
        var node = findText(text) ?: error("Missing text: $text")
        repeat(6) {
            if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            node = node.parent ?: error("No clickable ancestor for: $text")
        }
        error("No clickable ancestor for: $text")
    }

    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (index in 0 until node.childCount) yieldAll(nodes(node.getChild(index)))
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Optimized app did not reach the expected screen", predicate())
    }

    private fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun capture(name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
