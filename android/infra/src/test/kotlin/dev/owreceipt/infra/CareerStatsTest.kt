package dev.owreceipt.infra

import dev.owreceipt.core.*
import dev.owreceipt.infra.network.*
import dev.owreceipt.infra.storage.ReceiptDocumentCodec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CareerStatsTest {
    private val player = Player("player", "Player#123", "角色")
    private val range = QueryRange(Period.CAREER, null, 1_000)
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun lifetimeHoursAndSeasonQueuesStaySeparateAndHeroTimeUsesSeconds() {
        val identity = obj("""{"name":"Player#123","totalMatchNum":1284,"gameTime":"400"}""")
        val card = obj("""{"name":"Player#123","gameTime":"1063.29","level":4,"title":"守望者"}""")
        val competitive = obj("""{
            "presetsSummaryData":{"matchSum":104,"matchWinSum":62,"matchLossSum":40,"winRate":"59.62",
                "aveKill":14,"aveAssist":12,"aveDeath":5,"aveHeroDamage":7023,"aveCure":0,
                "aveResistDamage":4590,"aveIndividualKill":1,"aveFinalHit":5,
                "serverMapCountData":{"maxDamage":12000}},
            "openSummaryData":{"matchSum":20,"matchWinSum":12},
            "presetsHeroUseSummaryList":[{"heroGuid":"ana","gameTime":5400,"matchSum":8,"winRate":"75"},
                {"heroGuid":"unknown","gameTime":9000,"matchSum":9}],
            "guideCountData":[{"roleType":"healer","lastRankInfo":{"rankName":"白金","rankSubTier":5},
                "maxRankInfo":{"rank_name":"钻石","rank_sub_tier":3}}]
        }""")
        val quick = obj("""{"presetsSummaryData":{"matchSum":200,"matchWinSum":100},
            "presetsHeroUseSummaryList":[{"heroName":"雾子","gameTime":600,"matchSum":2,"winRate":0}]}""")
        val snapshot = DashenPayloads.snapshot(player, range, identity, card, competitive, 1_000, quick,
            MatchNames(heroes = mapOf("ana" to "安娜")))
        fun block(id: String) = snapshot.blocks.single { it.id == id }
        val overview = block("profile")
        assertEquals("1063.29 小时", overview.metrics.single { it.label == "游玩时长" }.displayValue)
        assertEquals("1284", overview.metrics.single { it.label == "累计场次" }.value)
        assertEquals("104", block("competitive").metrics.first().value)
        assertEquals("20", block("competitive.open").metrics.first().value)
        assertEquals("200", block("quick").metrics.first().value)
        assertTrue(block("competitive").title.startsWith("本赛季"))
        assertEquals("0", block("competitive").metrics.single { it.label == "场均治疗" }.value)
        assertEquals("1 小时 30 分 · 8 场 · 胜率 75%", block("competitive.heroes").metrics.single().value)
        assertEquals("10 分 0 秒 · 2 场 · 胜率 0%", block("quick.heroes").metrics.single().value)
        assertEquals("白金 5", block("rank").metrics.single().value)
        assertEquals("钻石 3", block("rank.peak").metrics.single().value)
        assertFalse(snapshot.blocks.flatMap { it.metrics }.any { it.value == "12000" || it.label == "unknown" })
    }

    @Test fun missingInvalidAndZeroFieldsAreHandledWithoutInventingTotals() {
        val snapshot = DashenPayloads.snapshot(player, range,
            obj("""{"gameTime":"0","totalMatchNum":0,"level":0,"title":"称号"}"""),
            obj("""{"gameTime":"NaN","level":null}"""),
            obj("""{"presetsSummaryData":{"matchSum":0,"winRate":"120","aveKill":-1,"aveCure":"NaN"}}"""), 1_000)
        val profile = snapshot.blocks.single { it.id == "profile" }
        assertEquals("0 小时", profile.metrics.single { it.label == "游玩时长" }.displayValue)
        assertEquals("称号", profile.metrics.single { it.label == "头衔" }.value)
        assertEquals(listOf(Metric("完成场次", "0", "场")), snapshot.blocks.single { it.id == "competitive" }.metrics)
        assertFalse(snapshot.blocks.any { it.id == "quick" })
        assertFalse(snapshot.complete)
        val noStats = DashenPayloads.snapshot(player, range, obj("{}"), obj("{}"), null, 1_000)
        assertEquals(listOf(Metric("战网昵称", player.battleTag)), noStats.blocks.single().metrics)
    }

    @Test fun legacySmallReceiptsStillDecodeButNewReceiptsUseMedium() {
        val snapshot = DashenPayloads.snapshot(player, range, obj("{}"), obj("{}"), null, 1_000)
        val old = ReceiptDocument("legacy", 1_000, snapshot, listOf("profile"), ReceiptFont.SMALL)
        assertEquals(ReceiptFont.SMALL, ReceiptDocumentCodec.decode(ReceiptDocumentCodec.encode(old)).font)
        assertEquals(ReceiptFont.MEDIUM, ReceiptFont.SMALL.forNewReceipt())
        assertEquals(listOf(ReceiptFont.MEDIUM, ReceiptFont.LARGE), ReceiptFont.choices)
    }
}
