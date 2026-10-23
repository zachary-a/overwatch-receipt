package dev.owreceipt.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReceiptRulesTest {
    private fun clock(value: String) = Clock.fixed(Instant.parse(value), ZoneId.of("UTC"))
    @Test fun mondayAndBeijingMidnightAreUsedInsteadOfHostTimezone() {
        val now = clock("2026-10-25T16:30:00Z") // Monday 00:30 in Beijing; Sunday in UTC.
        val today = QueryRange.at(Period.TODAY, now)
        val week = QueryRange.at(Period.WEEK, now)
        assertEquals(Instant.parse("2026-10-25T16:00:00Z").toEpochMilli(), today.startInclusive)
        assertEquals(today.startInclusive, week.startInclusive)
        assertEquals(now.millis(), week.endExclusive)
        assertEquals("Asia/Shanghai", week.zoneId)
    }
    @Test fun sundayStillBelongsToPreviousMonday() {
        val week = QueryRange.at(Period.WEEK, clock("2026-10-25T15:59:59Z"))
        assertEquals(Instant.parse("2026-10-18T16:00:00Z").toEpochMilli(), week.startInclusive)
        assertNull(QueryRange.at(Period.CAREER, clock("2026-10-25T15:59:59Z")).startInclusive)
    }
    @Test fun missingMetricsAreDifferentFromRealZero() {
        assertEquals("0 场", Metric("平局", "0", "场").displayValue)
        assertEquals("暂不可用", Metric("最高伤害", null).displayValue)
        assertFalse(StatsBlock("rank", "段位", listOf(Metric("支援", null))).available)
        assertTrue(StatsBlock("overview", "总览", listOf(Metric("平局", "0"))).available)
    }
    @Test fun documentRejectsEmptyAndUnavailableContent() {
        val snapshot = StatsSnapshot(
            Player("id", "示例#1", "角色"), QueryRange.at(Period.CAREER),
            "示例", 1, "完整", "快速", true,
            listOf(StatsBlock("unavailable", "段位", listOf(Metric("输出", null)))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptDocument("id", 1, snapshot, emptyList(), ReceiptFont.MEDIUM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptDocument("id", 1, snapshot, listOf("unavailable"), ReceiptFont.MEDIUM)
        }
    }
}
