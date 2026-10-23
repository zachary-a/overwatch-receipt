package dev.owreceipt.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dev.owreceipt.core.DemoScenario
import dev.owreceipt.ui.UiAction
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReceiptJourneyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitForTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun ready() = rule.waitUntil(10_000) {
        runCatching { rule.onNodeWithTag("make.preview").assertIsEnabled(); true }.getOrDefault(false)
    }
    private fun capture(name: String) {
        rule.runOnIdle {
            ViewModelProvider(rule.activity)[ReceiptViewModel::class.java].dispatch(UiAction.DismissMessage)
        }
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let the emulator's GPU present the committed Compose frame before taking a window screenshot.
        Thread.sleep(350)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun scenario(value: DemoScenario) {
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("account.card"))
        rule.onNodeWithTag("account.card").performClick()
        rule.onNodeWithTag("scenario.open").performScrollTo().performClick()
        rule.onNodeWithText(value.label, useUnmergedTree = true).performClick()
        rule.onNodeWithTag("back").performClick()
    }

    @Test fun a_createRotateSaveExportAndReopen() {
        ready()
        rule.onNodeWithTag("open.account").assertDoesNotExist()
        rule.runOnIdle {
            val display = rule.activity.window.decorView.display
            val current = display.mode
            val maximum = display.supportedModes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }.maxOf { it.refreshRate }
            assertEquals(maximum, rule.activity.window.attributes.preferredRefreshRate, .1f)
            assertTrue(display.supportedModes.any {
                it.modeId == rule.activity.window.attributes.preferredDisplayModeId &&
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                    kotlin.math.abs(it.refreshRate - maximum) < .1f
            })
        }
        rule.onNodeWithTag("period.WEEK").performClick()
        ready()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("check.best"))
        rule.onNodeWithTag("check.best").performClick()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("font.LARGE"))
        rule.onNodeWithTag("font.LARGE").performClick()
        rule.onNodeWithTag("font.SMALL").assertDoesNotExist()
        rule.activityRule.scenario.recreate()
        ready()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("font.LARGE"))
        rule.onNodeWithTag("font.LARGE").assertIsSelected()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("check.best"))
        rule.onNodeWithTag("check.best").assertIsOff()
        rule.onNodeWithTag("make.list").performScrollToIndex(0)
        rule.onNodeWithTag("period.WEEK").assertIsSelected()
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        ready()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("font.LARGE"))
        rule.onNodeWithTag("font.LARGE").assertIsSelected()
        capture("09_landscape.png")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ready()
        rule.onNodeWithTag("make.list").performScrollToIndex(0)
        capture("01_make.png")
        rule.onNodeWithTag("make.preview").performClick()
        waitForTag("preview.screen")
        rule.onNodeWithTag("font.SMALL").assertDoesNotExist()
        capture("02_preview.png")
        rule.onNodeWithContentDescription("放大预览").performClick()
        rule.onNodeWithContentDescription("缩小预览").performClick()
        rule.onNodeWithTag("preview.save").performClick()
        waitForText("已存入纪念册")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = rule.activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "overwatch-receipt-verification.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        })!!
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/png")
        }
        val monitor = Instrumentation.ActivityMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithTag("preview.export").performClick()
            rule.waitUntil(10_000) {
                runCatching { resolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() } }.getOrDefault(false)
            }
            val graph = (rule.activity.application as ReceiptApplication).graph
            val saved = runBlocking { graph.receipts.receipts.first { it.isNotEmpty() }.first() }
            val original = runBlocking { graph.receipts.open(saved.document.id) }
            assertArrayEquals(original.raster.png, resolver.openInputStream(uri)!!.use { it.readBytes() })
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
            File(folder, "exported.png").writeBytes(original.raster.png)
        } finally { instrumentation.removeMonitor(monitor); resolver.delete(uri, null, null) }

        rule.onNodeWithTag("back").performClick()
        rule.onNodeWithTag("tab.ARCHIVE").performClick()
        waitForTag("archive.receipt")
        capture("03_archive.png")
        rule.onAllNodesWithTag("archive.receipt").onFirst().performClick()
        waitForTag("preview.screen")
        rule.onNodeWithTag("preview.save").assertIsNotEnabled()
        rule.activityRule.scenario.recreate()
        waitForTag("preview.screen")
        rule.onNodeWithTag("preview.save").assertIsNotEnabled()
        rule.onNodeWithContentDescription("切换主题").performClick()
        rule.onNodeWithText("深色").performClick()
        capture("04_dark_preview.png")
        rule.onNodeWithContentDescription("切换主题").performClick()
        rule.onNodeWithText("浅色").performClick()
    }

    @Test fun b_capabilitiesMissingFieldsAndReauthentication() {
        ready()
        scenario(DemoScenario.CAREER_ONLY)
        ready()
        rule.onNodeWithTag("period.TODAY").assertIsNotEnabled()
        rule.onNodeWithTag("period.WEEK").assertIsNotEnabled()
        rule.onNodeWithTag("period.CAREER").assertIsSelected()
        capture("05_career_only.png")
        scenario(DemoScenario.MISSING)
        ready()
        rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("check.rank"))
        rule.onNodeWithTag("check.rank").assertIsNotEnabled()
        scenario(DemoScenario.EXPIRED)
        waitForText("需要重新认证")
        rule.onNodeWithText("重新认证示例账号").performClick()
        rule.onNodeWithTag("account.signin").performClick()
        waitForTag("role.demo-role-1")
        rule.onNodeWithTag("role.demo-role-1").performClick()
        ready()
        scenario(DemoScenario.PRIVATE)
        waitForText("资料暂不可见")
        capture("06_private.png")
        scenario(DemoScenario.EMPTY)
        waitForText("还没有可制作的战绩")
        scenario(DemoScenario.FAILURE)
        waitForText("这次查询没有完成")
        rule.onNodeWithText("重试").performClick()
        waitForText("这次查询没有完成")
        scenario(DemoScenario.NORMAL)
        ready()
    }

    @Test fun c_printPauseStopDisconnectAndComplete() {
        ready()
        rule.onNodeWithTag("make.preview").performClick()
        waitForTag("preview.screen")
        rule.onNodeWithTag("preview.print").performClick()
        waitForTag("device.paper")
        rule.onNodeWithTag("device.paper").performClick()
        waitForText("模拟缺纸 · 已暂停")
        capture("07_device_paused.png")
        rule.onNodeWithTag("device.paper").performClick()
        waitForText("正在模拟打印")
        rule.onNodeWithTag("device.stop").performClick()
        waitForText("模拟打印已停止")
        rule.onNodeWithTag("device.disconnect").performClick()
        waitForText("尚未连接")
        rule.onNodeWithTag("tab.MAKE").performClick()
        ready()
        rule.onNodeWithTag("make.preview").performClick()
        waitForTag("preview.screen")
        rule.onNodeWithTag("preview.print").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("模拟流程完成").fetchSemanticsNodes().isNotEmpty()
        }
        capture("08_device_complete.png")
    }
}
