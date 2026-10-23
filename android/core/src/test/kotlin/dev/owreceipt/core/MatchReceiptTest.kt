package dev.owreceipt.core

import org.junit.Assert.*
import org.junit.Test

class MatchReceiptTest {
    @Test fun zeroIsValidButMissingNeverBecomesZeroInSummary() {
        val range = QueryRange.at(Period.TODAY)
        val matches = listOf(MatchRecord("1", 1, MatchMode.RANKED, MatchResult.WIN, 300, kills = 0.0, damage = null),
            MatchRecord("2", 2, MatchMode.QUICK, MatchResult.LOSS, 600, kills = 10.0, damage = 200.0))
        val blocks = MatchReceiptComposer.summary(matches, range)
        val values = blocks.last().metrics.associate { it.label to it.value }
        assertEquals("10", values["消灭"]); assertNull(values["伤害"])
        assertEquals("15 分 0 秒", blocks.first().metrics.first { it.label == "总对局时长" }.value)
    }
    @Test fun eachSelectedMatchContainsOnlyItsOwnData() {
        val match = MatchRecord("chosen", 1777212060658, MatchMode.QUICK, MatchResult.WIN, 955, kills = 15.0)
        val snapshot = StatsSnapshot(Player("role", "Player#123", "绑定角色"), QueryRange.at(Period.TODAY),
            "source", 1, "partial", "快速", false, emptyList(), false, listOf(match, match.copy(id = "other")))
        val single = MatchReceiptComposer.single(snapshot, match)
        assertEquals(listOf("chosen"), single.matches.map { it.id })
        assertEquals("15", single.blocks.single().metrics.first { it.label == "消灭" }.value)
        assertTrue(single.coverage.contains("partial"))
    }
    @Test fun highlightsUseMatchMaximumsAndRatesAreWeightedByDuration() {
        val matches = listOf(
            MatchRecord("short", 1, MatchMode.QUICK, MatchResult.WIN, 300,
                kills = 10.0, damage = 6000.0, healing = 0.0, mitigation = 500.0),
            MatchRecord("long", 2, MatchMode.RANKED, MatchResult.LOSS, 900,
                kills = 20.0, damage = 12000.0, healing = 18000.0, mitigation = 16000.0),
        )
        val blocks = MatchReceiptComposer.summary(matches, QueryRange.at(Period.TODAY)).associateBy { it.id }
        val highlights = blocks.getValue("highlights").metrics.associate { it.label to it.value }
        assertEquals("12000", highlights["最高伤害"])
        assertEquals("18000", highlights["最高治疗"])
        assertEquals("20", highlights["最多消灭"])
        assertEquals("16000", highlights["最高减伤"])
        val pace = blocks.getValue("pace").metrics.associate { it.label to it.value }
        assertEquals("15", pace["消灭 / 10分钟"])
        assertEquals("9000", pace["伤害 / 10分钟"])
        assertEquals("9000", pace["治疗 / 10分钟"])
    }
    @Test fun ratesUseOnlyPairedValidValuesAndPositiveDurations() {
        val matches = listOf(
            MatchRecord("valid", 1, MatchMode.RANKED, MatchResult.WIN, 300,
                kills = 0.0, damage = 500.0, healing = 1000.0),
            MatchRecord("missingDamage", 2, MatchMode.RANKED, MatchResult.UNKNOWN, 600,
                kills = 12.0, damage = null, healing = 2000.0),
            MatchRecord("missingTime", 3, MatchMode.QUICK, MatchResult.LOSS,
                kills = 99.0, damage = 10000.0, healing = 3000.0),
            MatchRecord("zeroTime", 4, MatchMode.QUICK, MatchResult.LOSS, 0,
                kills = 100.0, damage = 20000.0, healing = 4000.0),
            MatchRecord("invalid", 5, MatchMode.QUICK, MatchResult.WIN, -10,
                kills = Double.NaN, damage = Double.POSITIVE_INFINITY, healing = -1.0),
        )
        val blocks = MatchReceiptComposer.summary(matches, QueryRange.at(Period.WEEK)).associateBy { it.id }
        val pace = blocks.getValue("pace").metrics.associate { it.label to it.value }
        assertEquals("8", pace["消灭 / 10分钟"])
        assertEquals("1000", pace["伤害 / 10分钟"])
        assertEquals("2000", pace["治疗 / 10分钟"])
        val highlights = blocks.getValue("highlights").metrics.associate { it.label to it.value }
        assertEquals("20000", highlights["最高伤害"])
        assertEquals("4000", highlights["最高治疗"])
        assertEquals("100", highlights["最多消灭"])
        assertNull(highlights["最高减伤"])
        assertTrue(blocks.getValue("combat").metrics.all { it.value == null })
        assertTrue(blocks.getValue("pace").scopeLabel!!.contains("伤害 1/5 场"))
        assertEquals("50", blocks.getValue("summary").metrics.first { it.label == "已知胜率" }.value)
    }
    @Test fun emptyAndUntimedMatchesDoNotInventHighlightsOrRates() {
        val range = QueryRange.at(Period.TODAY)
        val empty = MatchReceiptComposer.summary(emptyList(), range)
        assertEquals(listOf(Metric("记录对局", "0", "场")), empty.first().metrics)
        assertTrue(empty.drop(1).none { it.available })
        val untimed = MatchReceiptComposer.summary(listOf(
            MatchRecord("zero", 1, MatchMode.QUICK, MatchResult.WIN, 0, kills = 0.0, healing = 0.0)
        ), range)
        assertFalse(untimed.first { it.id == "pace" }.available)
        assertEquals("0", untimed.first { it.id == "highlights" }.metrics.first { it.label == "最高治疗" }.value)
    }
}
