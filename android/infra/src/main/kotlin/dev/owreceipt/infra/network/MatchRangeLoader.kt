package dev.owreceipt.infra.network

import dev.owreceipt.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class MatchRangeResult(val matches: List<MatchRecord>, val complete: Boolean, val notes: List<String>)

/** A bounded walk of each mode's list. Never substitutes season totals for a date interval. */
class MatchRangeLoader(
    private val page: suspend (MatchMode, Int) -> JsonElement,
    private val detail: suspend (String) -> JsonObject,
    private val maxPages: Int = 30,
    private val maxMatches: Int = 300,
    private val names: MatchNames = MatchNames(),
) {
    suspend fun load(range: QueryRange, modes: Set<MatchMode>): MatchRangeResult {
        val start = requireNotNull(range.startInclusive)
        val matches = linkedMapOf<String, MatchRecord>()
        val notes = mutableListOf<String>()
        var allCovered = true
        var anyPageSucceeded = false
        var firstFailure: StatsException? = null
        for (mode in modes.sortedBy { it.ordinal }) {
            var covered = false
            var priorOldest = Long.MAX_VALUE
            val seen = hashSetOf<String>()
            for (index in 1..maxPages) {
                val data = try { page(mode, index) } catch (e: CancellationException) { throw e
                } catch (e: StatsException) {
                    if (e.problem in setOf(StatsProblem.AUTH_REQUIRED, StatsProblem.RATE_LIMIT)) throw e
                    if (firstFailure == null) firstFailure = e
                    notes += "${mode.label}第 $index 页未取得"; break
                }
                anyPageSucceeded = true
                val rows = entries(data)
                if (rows.isEmpty()) {
                    notes += "${mode.label}列表已结束，历史覆盖仍需核验"
                    break
                }
                var added = 0
                var invalid = false
                val times = mutableListOf<Long>()
                for (raw in rows) {
                    val item = parse(raw, mode, names)
                    if (item == null) { invalid = true; continue }
                    times += item.startedAt
                    if (!seen.add(item.id)) continue
                    added++
                    if (item.startedAt >= start && item.startedAt < range.endExclusive)
                        matches.putIfAbsent(item.id, item)
                }
                if (invalid) notes += "${mode.label}列表存在无法识别的时间或对局标识"
                val sorted = times.zipWithNext().all { (a, b) -> a >= b } &&
                    (times.firstOrNull()?.let { it <= priorOldest } ?: false)
                if (!sorted) notes += "${mode.label}列表排序或分页边界异常"
                priorOldest = times.minOrNull() ?: priorOldest
                if (!invalid && sorted && times.any { it < start }) { covered = true; break }
                if (added == 0) { notes += "${mode.label}分页重复，已停止翻页"; break }
                if (matches.size >= maxMatches) { notes += "本次达到 $maxMatches 场加载上限"; break }
                if (index == maxPages) notes += "${mode.label}达到 $maxPages 页加载上限"
            }
            if (!covered) allCovered = false
        }
        if (!anyPageSucceeded) firstFailure?.let { throw it }
        // Detail is needed for duration; summary combat fields come from the target player's list row.
        val enriched = matches.values.sortedByDescending { it.startedAt }.map { item ->
            if (item.durationSeconds != null) item else try {
                val data = detail(item.id)
                val duration = DashenPayloads.value(data, "gameTimeSec")?.toLongOrNull()?.takeIf { it in 0..86_400 }
                item.copy(durationSeconds = duration)
            } catch (e: CancellationException) { throw e
            } catch (e: StatsException) {
                if (e.problem in setOf(StatsProblem.AUTH_REQUIRED, StatsProblem.RATE_LIMIT)) throw e
                item
            }
        }
        if (enriched.any { it.durationSeconds == null }) notes += "部分对局时长未取得"
        return MatchRangeResult(enriched, allCovered && notes.none { "异常" in it || "无法识别" in it },
            notes.distinct())
    }
    companion object {
        fun entries(value: JsonElement): List<JsonObject> {
            val array = when (value) {
                is JsonArray -> value
                is JsonObject -> (value["matchList"] ?: value["recentMatchList"]) as? JsonArray
                    ?: throw StatsException(StatsProblem.UPSTREAM, "对局列表结构发生变化")
                else -> throw StatsException(StatsProblem.UPSTREAM, "对局列表格式无法识别")
            }
            if (array.any { it !is JsonObject }) throw StatsException(StatsProblem.UPSTREAM, "对局列表包含无效记录")
            return array.map { it.jsonObject }
        }
        fun parse(item: JsonObject, mode: MatchMode, names: MatchNames = MatchNames()): MatchRecord? {
            fun value(key: String) = DashenPayloads.value(item, key)
            fun stat(key: String) = value(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val id = value("matchId")?.takeIf { it.length in 1..128 } ?: return null
            val stamp = value("beginTs")?.toLongOrNull() ?: return null
            val millis = if (stamp in 1_000_000_000..9_999_999_999) stamp * 1000 else stamp
            if (millis !in 1_000_000_000_000..9_999_999_999_999) return null
            val result = when (value("matchRet")) {
                "1" -> MatchResult.WIN; "-1" -> MatchResult.LOSS; "0" -> MatchResult.DRAW; else -> MatchResult.UNKNOWN
            }
            return MatchRecord(id, millis, mode, result,
                value("gameTimeSec")?.toLongOrNull()?.takeIf { it in 0..86_400 },
                mapOf("tank" to "重装", "dps" to "输出", "healer" to "支援", "open" to "开放职责")[value("roleType")],
                value("heroName") ?: names.heroes[value("heroGuid")], value("mapName") ?: names.maps[value("mapGuid")],
                stat("kill"), stat("assist"), stat("death"),
                stat("heroDamage"), stat("cure"), stat("resistDamage"))
        }
    }
}
