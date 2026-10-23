package dev.owreceipt.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
enum class MatchMode(val label: String, val wireName: String) { RANKED("竞技", "sport"), QUICK("快速", "leisure") }
@Serializable
enum class ReceiptKind(val label: String) { OVERVIEW("资料小票"), SUMMARY("战绩总结"), MATCH("精选对局") }
@Serializable
enum class MatchResult(val label: String) { WIN("胜利"), LOSS("失利"), DRAW("平局"), UNKNOWN("结果未知") }

/** Only the queried player's statistics are retained; no opponent or teammate credentials. */
@Serializable
data class MatchRecord(
    val id: String,
    val startedAt: Long,
    val mode: MatchMode,
    val result: MatchResult,
    val durationSeconds: Long? = null,
    val role: String? = null,
    val hero: String? = null,
    val map: String? = null,
    val kills: Double? = null,
    val assists: Double? = null,
    val deaths: Double? = null,
    val damage: Double? = null,
    val healing: Double? = null,
    val mitigation: Double? = null,
) {
    val title: String get() = "${formatTime(startedAt)} · ${mode.label} · ${result.label}"
    fun block(): StatsBlock = StatsBlock("match.$id", "对局战绩", listOf(
        Metric("开始时间", formatTime(startedAt)),
        Metric("模式 / 结果", "${mode.label} / ${result.label}"),
        Metric("英雄", hero), Metric("职责", role), Metric("地图", map),
        Metric("对局时长", durationSeconds?.let(::formatDuration)),
        Metric("消灭", number(kills)), Metric("助攻", number(assists)), Metric("死亡", number(deaths)),
        Metric("伤害", number(damage)), Metric("治疗", number(healing)), Metric("减伤", number(mitigation)),
    ), "用户选中的单场记录")
}

fun number(value: Double?): String? = value?.takeIf { it.isFinite() && it >= 0 }?.let {
    BigDecimal.valueOf(it).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
fun formatDuration(seconds: Long): String = if (seconds >= 3600)
    "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分" else "${seconds / 60} 分 ${seconds % 60} 秒"

object MatchReceiptComposer {
    fun summary(matches: List<MatchRecord>, range: QueryRange): List<StatsBlock> {
        val known = matches.count { it.result != MatchResult.UNKNOWN }
        val wins = matches.count { it.result == MatchResult.WIN }
        val losses = matches.count { it.result == MatchResult.LOSS }
        val draws = matches.count { it.result == MatchResult.DRAW }
        fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }
        fun sum(select: (MatchRecord) -> Double?): String? =
            if (matches.isEmpty()) null else matches.map { valid(select(it)) }.let { values ->
                if (values.any { it == null }) null else number(values.filterNotNull().sum())
            }
        fun best(select: (MatchRecord) -> Double?): String? =
            number(matches.mapNotNull { valid(select(it)) }.maxOrNull())
        fun timedSamples(select: (MatchRecord) -> Double?): List<Pair<Double, Long>> = matches.mapNotNull { match ->
            val value = valid(select(match)) ?: return@mapNotNull null
            val seconds = match.durationSeconds?.takeIf { it > 0 } ?: return@mapNotNull null
            value to seconds
        }
        fun perTenMinutes(select: (MatchRecord) -> Double?): String? = timedSamples(select).takeIf { it.isNotEmpty() }?.let { samples ->
            number(samples.sumOf { it.first } / samples.sumOf { it.second.toDouble() } * 600)
        }
        val timed = matches.count { it.durationSeconds?.let { seconds -> seconds >= 0 } == true }
        return listOf(
            StatsBlock("summary", "战绩概况", buildList {
                add(Metric("记录对局", matches.size.toString(), "场"))
                if (matches.isNotEmpty()) {
                    add(Metric("胜 / 负 / 平", "$wins / $losses / $draws"))
                    if (known < matches.size) add(Metric("结果待定", (matches.size - known).toString(), "场"))
                    add(Metric(if (known == matches.size) "胜率" else "已知胜率",
                        if (known > 0) number(wins * 100.0 / known) else null, "%"))
                    add(Metric("总对局时长", if (timed == matches.size)
                        formatDuration(matches.sumOf { it.durationSeconds!! }) else null))
                }
            }, "${range.period.label}按对局开始时间筛选 · 北京时间；胜率仅计算结果已知的对局"),
            StatsBlock("details", if (range.period == Period.TODAY) "对局消费明细" else "每日结算明细", listOf(
                Metric("记录对局", matches.size.takeIf { it > 0 }?.toString(), "场"),
            ), "日报逐场列出已取得记录，周报按日汇总；可取消勾选以缩短小票"),
            StatsBlock("highlights", "单局高光", listOf(
                Metric("最高伤害", best { it.damage }),
                Metric("最高治疗", best { it.healing }),
                Metric("最多消灭", best { it.kills }),
                Metric("最高减伤", best { it.mitigation }),
            ), "已取得对局中的单场最高；缺失项不参与比较"),
            StatsBlock("pace", "每 10 分钟", listOf(
                Metric("消灭 / 10分钟", perTenMinutes { it.kills }),
                Metric("伤害 / 10分钟", perTenMinutes { it.damage }),
                Metric("治疗 / 10分钟", perTenMinutes { it.healing }),
            ), "按有效对局总数值 ÷ 总时长 × 600 计算；消灭 ${timedSamples { it.kills }.size}/${matches.size} 场、" +
                "伤害 ${timedSamples { it.damage }.size}/${matches.size} 场、治疗 ${timedSamples { it.healing }.size}/${matches.size} 场"),
            StatsBlock("combat", "个人数据合计", listOf(
                Metric("消灭", sum { it.kills }), Metric("助攻", sum { it.assists }), Metric("死亡", sum { it.deaths }),
                Metric("伤害", sum { it.damage }), Metric("治疗", sum { it.healing }), Metric("减伤", sum { it.mitigation }),
            ), "仅汇总已取得的个人数据；缺失项不按零计算"),
        )
    }
    fun single(snapshot: StatsSnapshot, match: MatchRecord): StatsSnapshot = snapshot.copy(
        blocks = listOf(match.block()), matches = listOf(match),
        mode = match.mode.label,
        coverage = "本票为用户选中的单场战绩。${snapshot.coverage}",
    )
}
