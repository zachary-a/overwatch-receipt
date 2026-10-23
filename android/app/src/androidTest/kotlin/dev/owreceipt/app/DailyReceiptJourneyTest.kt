package dev.owreceipt.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.infra.demo.*
import dev.owreceipt.infra.receipt.MonochromeReceiptRenderer
import dev.owreceipt.infra.storage.LocalReceiptRepository
import dev.owreceipt.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the live presentation with explicit synthetic rows; never authenticates or opens Bluetooth. */
@RunWith(AndroidJUnit4::class)
class DailyReceiptJourneyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun summaryAndTwoSelectedMatchesRemainIndependentReceipts() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString().take(8)
        val repository = LocalReceiptRepository(testContext, "daily-$id.db", "daily-$id")
        val savedHandle = SavedStateHandle(mapOf("selected" to arrayListOf("summary", "combat")))
        val store = ViewModelStore()
        val printed = CompletableDeferred<List<ReceiptArtifact>>()
        val demoPrinter = DemoPrinterGateway(scope, 1)
        runBlocking { demoPrinter.connect() }
        val printer = object : PrinterGateway by demoPrinter {
            override suspend fun printBatch(artifacts: List<ReceiptArtifact>, settings: PrintSettings) {
                assertEquals(1, settings.copies)
                assertEquals(140, settings.trailingFeedRows)
                printed.complete(artifacts)
            }
        }
        val accounts = object : AccountSession by DemoAccountSession() { override val isDemo = false }
        val preferences = object : PreferencesRepository {
            override val preferences = MutableStateFlow(UserPreferences())
            override suspend fun update(preferences: UserPreferences) { this.preferences.value = preferences }
        }
        val stats = object : StatsRepository {
            override fun capabilities(scenario: DemoScenario) = QueryCapabilities(Period.entries.toSet())
            override suspend fun query(player: Player, range: QueryRange, scenario: DemoScenario, modes: Set<MatchMode>): StatsSnapshot {
                val start = range.startInclusive!!
                val matches = listOf(
                    MatchRecord("a", start + 3_600_000, MatchMode.RANKED, MatchResult.WIN, 900, "支援", "安娜", "国王大道",
                        12.0, 21.0, 3.0, 4200.0, 13500.0, 0.0),
                    MatchRecord("b", start + 7_200_000, MatchMode.QUICK, MatchResult.LOSS, 600, "输出", "猎空", "漓江塔",
                        26.0, 4.0, 7.0, 8900.0, 0.0, 0.0),
                    MatchRecord("c", start + 9_000_000, MatchMode.RANKED, MatchResult.WIN, 720, "重装", "莱因哈特", "好莱坞",
                        20.0, 7.0, 3.0, 10000.0, 0.0, 14000.0),
                ).filter { it.mode in modes }
                return StatsSnapshot(player, range, "自动化示例数据", System.currentTimeMillis(),
                    "仅供界面测试，非真实战绩", "竞技 + 快速", true, MatchReceiptComposer.summary(matches, range), true, matches)
            }
        }
        lateinit var model: ReceiptViewModel
        try {
            rule.runOnUiThread {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ReceiptViewModel(accounts, stats, repository, preferences, MonochromeReceiptRenderer(testContext),
                            printer, savedHandle) as T
                })[ReceiptViewModel::class.java]
                rule.activity.setContent {
                    val state by model.state.collectAsStateWithLifecycle()
                    ReceiptApp(state) { model.dispatch(it) }
                }
            }
            rule.waitUntil(10_000) { model.state.value.queryStatus == QueryStatus.CONTENT }
            rule.onNodeWithTag("period.TODAY").assertIsSelected()
            assertEquals(setOf("summary", "details", "combat", "highlights", "pace"), model.state.value.selectedBlocks)
            capture("10_daily_summary.png")
            rule.onNodeWithTag("make.preview").performClick()
            rule.waitUntil(10_000) { model.state.value.preview != null }
            assertEquals(ReceiptKind.SUMMARY, model.state.value.preview!!.artifact.document.kind)
            capture("11_daily_summary_receipt.png")
            writeImage("daily-summary.png", model.state.value.preview!!.artifact.raster.png)
            rule.onNodeWithTag("preview.details").assertDoesNotExist()
            rule.onNodeWithText("仅供界面测试，非真实战绩").assertDoesNotExist()
            rule.runOnIdle { model.dispatch(UiAction.Back); model.dispatch(UiAction.ChooseReceiptKind(ReceiptKind.MATCH)) }
            rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("match.a"))
            rule.onNodeWithTag("match.a").performClick()
            rule.onNodeWithTag("make.list").performScrollToNode(hasTestTag("match.b"))
            rule.onNodeWithTag("match.b").performClick()
            assertEquals(setOf("a", "b"), model.state.value.selectedMatches)
            capture("12_selected_matches.png")
            rule.onNodeWithTag("make.preview").performClick()
            rule.waitUntil(10_000) { model.state.value.screen == Screen.PREVIEW && model.state.value.previewBatch.size == 2 }
            val originals = model.state.value.previewBatch.map { it.artifact }
            assertEquals(listOf("a", "b"), originals.map { it.document.snapshot.matches.single().id })
            assertTrue(originals.all { it.document.kind == ReceiptKind.MATCH })
            capture("13_match_receipt.png")
            originals.forEachIndexed { index, artifact -> writeImage("selected-match-${index + 1}.png", artifact.raster.png) }
            rule.onNodeWithText("下一张").performClick()
            assertEquals(1, model.state.value.previewIndex)
            rule.onNodeWithText("全部存入纪念册（2 张）").performClick()
            rule.waitUntil(10_000) { model.state.value.previewBatch.all { it.saved } && !model.state.value.saving }
            val saved = runBlocking { repository.receipts.first { it.size == 2 } }
            for (receipt in saved) {
                val frozen = runBlocking { repository.open(receipt.document.id) }
                assertArrayEquals(originals.first { it.document.id == receipt.document.id }.raster.png, frozen.raster.png)
            }
            rule.runOnIdle { model.dispatch(UiAction.DismissMessage) }
            rule.waitForIdle()
            rule.onNodeWithTag("preview.print").performClick()
            try { rule.waitUntil(20_000) { printed.isCompleted && model.state.value.tab == HomeTab.DEVICE } }
            catch (e: Throwable) {
                throw AssertionError("batch=${printed.isCompleted}, printer=${printer.state.value}, ui=${model.state.value.message}", e)
            }
            assertEquals(originals.map { it.document.id }, runBlocking { printed.await() }.map { it.document.id })
            rule.runOnIdle { model.dispatch(UiAction.Tab(HomeTab.ARCHIVE)) }
            rule.onAllNodesWithTag("archive.receipt").assertCountEquals(2)
            capture("14_daily_archive.png")
        } finally {
            rule.runOnUiThread { store.clear() }
            scope.cancel(); repository.close()
        }
    }
    private fun writeImage(name: String, bytes: ByteArray) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(folder, name).writeBytes(bytes)
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(350)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = java.io.ByteArrayOutputStream()
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output); screenshot.recycle()
        writeImage(name, output.toByteArray())
    }
}
