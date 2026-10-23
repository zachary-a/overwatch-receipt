package dev.owreceipt.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.infra.demo.DemoFixtures
import dev.owreceipt.infra.storage.LocalReceiptRepository
import dev.owreceipt.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAndArchiveTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val info = PrinterInfo(576, false, false, false, false, false, 1, 50)

    @Test fun connectionHidesSelectionThroughPrintStatesAndDisconnectRestoresIt() {
        val first = PrinterDevice("00:11:22:33:44:55", "9516-ABCD")
        val second = PrinterDevice("00:11:22:33:44:66", "9516-EFGH")
        val state = mutableStateOf(AppUiState(isDemo = false, tab = HomeTab.DEVICE,
            devices = listOf(first, second, PrinterDevice("wrong", "Headphones"), PrinterDevice("old", "WP9516")),
            selectedDevice = "wrong"))
        val actions = mutableListOf<UiAction>()
        rule.runOnUiThread { rule.activity.setContent { ReceiptApp(state.value) { actions += it } } }
        rule.onNodeWithText(first.name).assertIsDisplayed()
        rule.onNodeWithText(second.name).assertIsDisplayed()
        rule.onNodeWithText("Headphones").assertDoesNotExist()
        rule.onNodeWithText("WP9516").assertDoesNotExist()
        rule.onNodeWithTag("device.list").performScrollToNode(hasTestTag("device.connect"))
        rule.onNodeWithTag("device.connect").assertIsNotEnabled()
        for (status in PrintStatus.entries.filter { it != PrintStatus.DISCONNECTED }) {
            rule.runOnUiThread { state.value = state.value.copy(printer = PrintState(status,
                device = first, info = if (status in setOf(PrintStatus.CONNECTING, PrintStatus.READING_INFO)) null else info)) }
            rule.onNodeWithText("选择打印机").assertDoesNotExist()
            rule.onNodeWithText("扫描附近设备").assertDoesNotExist()
            rule.onNodeWithTag("device.connect").assertDoesNotExist()
            rule.onNodeWithText("打印设置").assertDoesNotExist()
            rule.onNodeWithText("份数").assertDoesNotExist()
            rule.onNodeWithText("尾部送纸").assertDoesNotExist()
        }
        rule.runOnUiThread { state.value = state.value.copy(printer = PrintState(PrintStatus.READY, device = first, info = info)) }
        rule.onNodeWithTag("device.list").performScrollToIndex(0)
        capture("25-device-connected.png")
        rule.onNodeWithTag("device.disconnect").performClick()
        assertEquals(UiAction.Disconnect, actions.last())
        rule.runOnUiThread { state.value = state.value.copy(printer = PrintState()) }
        rule.onNodeWithTag("device.list").performScrollToNode(hasText(first.name))
        rule.onNodeWithText(first.name).assertIsDisplayed()
        rule.onNodeWithTag("device.list").performScrollToIndex(0)
        capture("25-device-selection.png")
        rule.runOnUiThread { state.value = state.value.copy(printer = PrintState(PrintStatus.ERROR, device = first)) }
        rule.onNodeWithText("选择打印机").assertExists()
    }

    @Test fun longPressCancelAndConfirmDeleteTheCorrectFrozenReceipt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val repository = LocalReceiptRepository(context, "delete-$token.db", "delete-$token")
        val snapshot = DemoFixtures.snapshot(DemoFixtures.players.first(), QueryRange.at(Period.CAREER))
        val document = ReceiptDocument(UUID.randomUUID().toString(), 1, snapshot, listOf("overview"), ReceiptFont.MEDIUM)
        val bitmap = Bitmap.createBitmap(576, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val artifact = ReceiptArtifact(document, RenderedReceipt(576, 32, png, ByteArray(72 * 32)))
        runBlocking { repository.save(artifact) }
        val actions = mutableListOf<UiAction>()
        val state = mutableStateOf(AppUiState(tab = HomeTab.ARCHIVE, archiveLoading = false,
            receipts = runBlocking { repository.receipts.first() }))
        try {
            rule.runOnUiThread { rule.activity.setContent { ReceiptApp(state.value) { actions += it } } }
            rule.onNodeWithTag("archive.receipt").performTouchInput { longClick() }
            rule.onNodeWithText("删除这张小票？").assertIsDisplayed()
            assertTrue(actions.isEmpty())
            capture("25-archive-long-press.png")
            rule.onNodeWithText("保留").performClick()
            assertArrayEquals(png, runBlocking { repository.open(document.id) }.raster.png)
            rule.onNodeWithTag("archive.receipt").performClick()
            assertEquals(UiAction.OpenSaved(document.id), actions.last())
            actions.clear()
            rule.onNodeWithTag("archive.receipt").performTouchInput { longClick() }
            rule.onNodeWithText("删除", useUnmergedTree = true).performClick()
            assertEquals(listOf(UiAction.DeleteSaved(document.id)), actions)
            runBlocking { repository.delete((actions.single() as UiAction.DeleteSaved).id) }
            assertTrue(runBlocking { repository.receipts.first() }.isEmpty())
            assertFalse(File(context.filesDir, "delete-$token/${document.id}.png").exists())
            rule.runOnUiThread { state.value = state.value.copy(receipts = emptyList()) }
            rule.onNodeWithTag("archive.receipt").assertDoesNotExist()
        } finally { repository.close() }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val icon = rule.activity.applicationInfo.loadIcon(rule.activity.packageManager)
        val iconImage = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(iconImage))
        File(folder, "25-app-icon.png").outputStream().use { iconImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        iconImage.recycle()
    }
}
