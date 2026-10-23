package dev.owreceipt.app

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.infra.demo.*
import dev.owreceipt.infra.receipt.MonochromeReceiptRenderer
import dev.owreceipt.infra.network.DashenPayloads
import dev.owreceipt.infra.network.MatchNames
import dev.owreceipt.infra.storage.LocalReceiptRepository
import java.io.File
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtifactIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun document(font: ReceiptFont = ReceiptFont.MEDIUM, long: Boolean = false): ReceiptDocument {
        var snapshot = DemoFixtures.snapshot(DemoFixtures.players[if (long) 1 else 0], QueryRange.at(Period.CAREER), missing = true)
        if (long) snapshot = snapshot.copy(blocks = snapshot.blocks + StatsBlock(
            "long", "长文本与单位换行验证", (1..20).map { Metric(
                "第${it}项具有很长名称的示例统计指标", "123,456.78", "每十分钟平均值"
            ) }
        ))
        return ReceiptDocument(UUID.randomUUID().toString(), snapshot.fetchedAt, snapshot,
            snapshot.blocks.filter { it.available }.map { it.id }, font)
    }
    @Test fun careerReceiptsRenderLoadedLifetimeAndSeasonStatsInBothSizes() = runBlocking {
        fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
        val range = QueryRange.at(Period.CAREER, Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC))
        val snapshot = DashenPayloads.snapshot(DemoFixtures.players.first(), range,
            obj("""{"name":"小队长#1024","totalMatchNum":1284}"""),
            obj("""{"gameTime":"1063.29","level":48,"title":"守望者"}"""),
            obj("""{"presetsSummaryData":{"matchSum":104,"matchWinSum":62,"matchLossSum":40,"winRate":"59.62",
                "aveKill":14,"aveAssist":12,"aveDeath":5,"aveHeroDamage":7023,"aveCure":6965,"aveResistDamage":4590},
                "presetsHeroUseSummaryList":[{"heroGuid":"ana","gameTime":45360,"matchSum":67,"winRate":"64.18"}],
                "guideCountData":[{"roleType":"healer","lastRankInfo":{"rankName":"白金","rankSubTier":5},
                    "maxRankInfo":{"rankName":"钻石","rankSubTier":3}}]}"""),
            range.endExclusive,
            obj("""{"presetsSummaryData":{"matchSum":200,"matchWinSum":110,"matchLossSum":90,"winRate":55,
                "aveKill":18,"aveAssist":5,"aveDeath":7,"aveHeroDamage":8023,"aveCure":0},
                "presetsHeroUseSummaryList":[{"heroName":"猎空","gameTime":7200,"matchSum":30,"winRate":60}]}"""),
            MatchNames(heroes = mapOf("ana" to "安娜")),
        ).copy(isDemo = true)
        val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val renderer = MonochromeReceiptRenderer(context)
        for (font in ReceiptFont.choices) {
            val doc = ReceiptDocument(UUID.randomUUID().toString(), range.endExclusive, snapshot,
                snapshot.blocks.map { it.id }, font)
            val raster = renderer.render(doc)
            assertTrue(raster.height > 1000)
            val withoutTime = doc.copy(snapshot = snapshot.copy(blocks = snapshot.blocks.map { block ->
                block.copy(metrics = block.metrics.filter { it.label != "游玩时长" })
            }))
            assertFalse(raster.png.contentEquals(renderer.render(withoutTime).png))
            File(output, "career-${font.name.lowercase()}.png").writeBytes(raster.png)
        }
    }
    @Test fun rasterIsBinaryAndMatchesPackedPrinterRowsForEveryFont() = runBlocking {
        val renderer = MonochromeReceiptRenderer(context)
        val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        for (font in ReceiptFont.entries) {
            val raster = renderer.render(document(font, long = font == ReceiptFont.LARGE))
            assertEquals(576, raster.width)
            assertEquals(72 * raster.height, raster.packedRows.size)
            assertEquals("pHYs", String(raster.png, 37, 4, Charsets.US_ASCII))
            assertEquals(11811, ByteBuffer.wrap(raster.png, 41, 4).int)
            val bitmap = BitmapFactory.decodeByteArray(raster.png, 0, raster.png.size)
            val row = IntArray(raster.width)
            var blackCount = 0
            for (y in 0 until raster.height) {
                bitmap.getPixels(row, 0, raster.width, 0, y, raster.width, 1)
                for (x in row.indices) {
                    assertTrue("non-binary pixel at $x,$y", row[x] == Color.BLACK || row[x] == Color.WHITE)
                    val packedBlack = (raster.packedRows[y * 72 + x / 8].toInt() and (0x80 ushr (x % 8))) != 0
                    assertEquals(row[x] == Color.BLACK, packedBlack)
                    if (packedBlack) blackCount++
                }
            }
            assertTrue(blackCount > 5_000)
            bitmap.recycle()
            File(output, "receipt_${font.name.lowercase()}.png").writeBytes(raster.png)
            if (font == ReceiptFont.LARGE) assertTrue(raster.height > renderer.render(document(font)).height * 2)
        }
    }
    @Test fun savedSouvenirSurvivesDatabaseReopenAndIsImmutable() = runBlocking {
        val id = UUID.randomUUID().toString()
        val database = "test-$id.db"
        val folder = "test-$id"
        val renderer = MonochromeReceiptRenderer(context)
        val doc = document()
        val original = ReceiptArtifact(doc, renderer.render(doc))
        val first = LocalReceiptRepository(context, database, folder)
        first.save(original)
        // Same id with a changed layout must not overwrite a saved souvenir.
        val changed = doc.copy(font = ReceiptFont.LARGE)
        first.save(ReceiptArtifact(changed, renderer.render(changed)))
        first.close()
        val reopened = LocalReceiptRepository(context, database, folder)
        try {
            val result = reopened.open(doc.id)
            assertEquals(doc, result.document)
            assertArrayEquals(original.raster.png, result.raster.png)
            assertArrayEquals(original.raster.packedRows, result.raster.packedRows)
            assertEquals(1, reopened.receipts.first().size)
            reopened.delete(doc.id)
            assertTrue(reopened.receipts.first().isEmpty())
            try { reopened.open(doc.id); fail("deleted receipt reopened") } catch (_: IllegalStateException) {}
        } finally { reopened.close() }
    }
    @Test fun diagnosticsAndMissingRowsStayOutOfPrintedArtifacts() = runBlocking {
        val original = document().let { doc ->
            doc.copy(snapshot = doc.snapshot.copy(blocks = listOf(
                StatsBlock("stats", "个人数据", listOf(Metric("治疗", "0")), "内部统计说明")
            )), selectedBlockIds = listOf("stats"))
        }
        val changed = original.copy(createdAt = original.createdAt + 60_000,
            snapshot = original.snapshot.copy(
                coverage = "分页失败、未完整覆盖、官方延迟等状态说明", source = "另一个数据源说明",
                fetchedAt = original.snapshot.fetchedAt + 60_000,
                blocks = listOf(StatsBlock("stats", "个人数据", listOf(
                    Metric("治疗", "0"), Metric("不应打印的缺失字段", null)
                ), "另一段不应打印的统计口径")),
            ))
        val renderer = MonochromeReceiptRenderer(context)
        val expected = renderer.render(original)
        val actual = renderer.render(changed)
        assertArrayEquals(expected.png, actual.png)
        assertArrayEquals(expected.packedRows, actual.packedRows)
        assertTrue(changed.snapshot.coverage.contains("分页失败"))
    }
    @Test fun summaryArtifactsRenderAtBothPrinterWidthsAndEveryFont() = runBlocking {
        val range = QueryRange.at(Period.TODAY, Clock.fixed(Instant.parse("2026-10-23T08:54:00Z"), ZoneOffset.UTC))
        val start = range.startInclusive!!
        val matches = listOf(
            MatchRecord("damage", start + 3_600_000, MatchMode.RANKED, MatchResult.WIN, 900,
                "输出", "猎空", "国王大道", 31.0, 8.0, 4.0, 18420.0, 0.0, 0.0),
            MatchRecord("healing", start + 7_200_000, MatchMode.RANKED, MatchResult.WIN, 1200,
                "支援", "安娜", "漓江塔", 15.0, 27.0, 3.0, 6200.0, 21860.0, 0.0),
            MatchRecord("tank", start + 10_800_000, MatchMode.QUICK, MatchResult.LOSS, 600,
                "重装", "莱因哈特", "好莱坞", 22.0, 6.0, 5.0, 10400.0, 0.0, 16300.0),
            MatchRecord("d", start + 14_400_000, MatchMode.RANKED, MatchResult.WIN, 720,
                "输出", "源氏", "国王大道", 27.0, 8.0, 4.0, 14800.0, 0.0, 0.0),
            MatchRecord("e", start + 18_000_000, MatchMode.QUICK, MatchResult.LOSS, 720,
                "支援", "雾子", "漓江塔", 12.0, 18.0, 6.0, 4500.0, 12400.0, 0.0),
            MatchRecord("f", start + 21_600_000, MatchMode.RANKED, MatchResult.WIN, 600,
                "输出", "猎空", "好莱坞", 32.0, 5.0, 3.0, 16200.0, 0.0, 0.0),
            MatchRecord("g", start + 25_200_000, MatchMode.RANKED, MatchResult.LOSS, 900,
                "输出", "卡西迪", "国王大道", 24.0, 8.0, 5.0, 13200.0, 0.0, 0.0),
            MatchRecord("h", start + 28_800_000, MatchMode.RANKED, MatchResult.WIN, 900,
                "输出", "源氏", "漓江塔", 38.0, 7.0, 3.0, 17600.0, 0.0, 0.0),
        )
        val snapshot = StatsSnapshot(DemoFixtures.players.first(), range, "测试数据源", range.endExclusive,
            "数据覆盖和计算说明保留在应用里", "竞技 + 快速", true,
            MatchReceiptComposer.summary(matches, range), true, matches)
        val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val renderer = MonochromeReceiptRenderer(context)
        for (width in listOf(384, 576)) for (font in ReceiptFont.entries) {
            val doc = ReceiptDocument(UUID.randomUUID().toString(), range.endExclusive, snapshot,
                snapshot.blocks.filter { it.available }.map { it.id }, font,
                profile = PrinterProfile(width), kind = ReceiptKind.SUMMARY)
            val raster = renderer.render(doc)
            assertEquals(width, raster.width)
            assertEquals(width / 8 * raster.height, raster.packedRows.size)
            File(output, "summary-${width}-${font.name.lowercase()}.png").writeBytes(raster.png)
        }
        val weekRange = QueryRange.at(Period.WEEK, Clock.fixed(Instant.parse("2026-10-23T08:54:00Z"), ZoneOffset.UTC))
        val weeklyMatches = matches.mapIndexed { index, match -> match.copy(
            startedAt = weekRange.startInclusive!! + index % 5 * 86_400_000L + (index + 1) * 3_600_000L
        ) }
        val weekly = snapshot.copy(range = weekRange, matches = weeklyMatches,
            blocks = MatchReceiptComposer.summary(weeklyMatches, weekRange))
        val weeklyDoc = ReceiptDocument(UUID.randomUUID().toString(), range.endExclusive, weekly,
            weekly.blocks.filter { it.available }.map { it.id }, ReceiptFont.MEDIUM, kind = ReceiptKind.SUMMARY)
        File(output, "summary-week.png").writeBytes(renderer.render(weeklyDoc).png)
        val longPlayer = snapshot.copy(player = DemoFixtures.players.last(), blocks = snapshot.blocks + StatsBlock(
            "longLabel", "特殊文本与长字段", listOf(Metric("第一个名称特别长的战绩统计项目", "12345678.9", "每十分钟平均值"))
        ))
        val longDoc = ReceiptDocument(UUID.randomUUID().toString(), range.endExclusive, longPlayer,
            longPlayer.blocks.filter { it.available }.map { it.id }, ReceiptFont.LARGE,
            profile = PrinterProfile(384), kind = ReceiptKind.SUMMARY)
        File(output, "summary-long-player.png").writeBytes(renderer.render(longDoc).png)
        val empty = snapshot.copy(matches = emptyList(), blocks = MatchReceiptComposer.summary(emptyList(), range))
        val emptyDoc = ReceiptDocument(UUID.randomUUID().toString(), range.endExclusive, empty,
            listOf("summary"), ReceiptFont.MEDIUM, kind = ReceiptKind.SUMMARY)
        val emptyRaster = renderer.render(emptyDoc)
        File(output, "summary-empty.png").writeBytes(emptyRaster.png)
        assertTrue(emptyRaster.height < 800)
    }
    @Test fun printerPausesResumesStopsAndDisconnectsWithoutHardware() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val printer = DemoPrinterGateway(scope, stepMillis = 3)
        val doc = document()
        val artifact = ReceiptArtifact(doc, MonochromeReceiptRenderer(context).render(doc))
        try {
            printer.connect(); printer.print(artifact)
            withTimeout(3000) { printer.state.first { it.progress > .05f } }
            printer.simulatePaperOut()
            val paused = printer.state.value.progress
            delay(40)
            assertEquals(PrintStatus.PAPER_OUT, printer.state.value.status)
            assertEquals(paused, printer.state.value.progress)
            printer.resume()
            withTimeout(3000) { printer.state.first { it.status == PrintStatus.COMPLETED } }
            printer.print(artifact); delay(20); printer.stop()
            val stopped = printer.state.value
            delay(40); assertEquals(stopped, printer.state.value)
            printer.print(artifact); delay(20); printer.disconnect()
            delay(40); assertEquals(PrintStatus.DISCONNECTED, printer.state.value.status)
        } finally { scope.cancel() }
    }
    @Test fun demoCapabilitiesAndErrorCategoriesAreDistinct() = runBlocking {
        val source = DemoStatsRepository(0)
        assertEquals(setOf(Period.CAREER), source.capabilities(DemoScenario.CAREER_ONLY).periods)
        val expected = mapOf(DemoScenario.EMPTY to StatsProblem.EMPTY, DemoScenario.PRIVATE to StatsProblem.PRIVATE,
            DemoScenario.FAILURE to StatsProblem.NETWORK, DemoScenario.EXPIRED to StatsProblem.AUTH_REQUIRED)
        for ((scenario, problem) in expected) {
            try {
                source.query(DemoFixtures.players.first(), QueryRange.at(Period.TODAY), scenario)
                fail("expected $problem")
            } catch (e: StatsException) { assertEquals(problem, e.problem) }
        }
    }
}
