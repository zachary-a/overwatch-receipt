package dev.owreceipt.infra.network

import dev.owreceipt.core.*
import dev.owreceipt.infra.auth.CredentialRejected
import java.math.BigDecimal
import kotlinx.serialization.json.*

object DashenPayloads {
    fun value(objectValue: JsonObject?, name: String): String? =
        (objectValue?.get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotBlank() }

    fun result(status: Int, text: String): JsonObject = resultElement(status, text) as? JsonObject
        ?: throw StatsException(StatsProblem.EMPTY, "上游暂未提供可显示的数据")
    fun resultElement(status: Int, text: String): JsonElement {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = value(root, "code")
        val message = listOf("errmsg", "msg", "message").mapNotNull { value(root, it) }.joinToString(" ")
        val explicitAuth = Regex(
            "(?i)(token|登录|登陆|凭证).{0,24}(过期|失效|无效|expired|invalid)|未登录|未登陆|login required|not logged"
        ).containsMatchIn(message)
        if (status == 401 || code == "401" || (code != "0" && explicitAuth)) throw CredentialRejected()
        if (status == 429 || code == "429") throw StatsException(StatsProblem.RATE_LIMIT, "查询过于频繁，请稍后再试")
        if (status == 403) throw StatsException(StatsProblem.PRIVATE, "上游拒绝访问当前资料")
        if (status !in 200..299 || root == null) throw StatsException(StatsProblem.NETWORK, "战绩服务暂时无法访问，请稍后重试")
        if (code != "0" || value(root, "success") == "false") {
            if (Regex("隐私|不可见|未公开|private", RegexOption.IGNORE_CASE).containsMatchIn(message))
                throw StatsException(StatsProblem.PRIVATE, "当前玩家资料未公开或不可见")
            if (Regex("查无|不存在|未找到|not found", RegexOption.IGNORE_CASE).containsMatchIn(message))
                throw StatsException(StatsProblem.EMPTY, "没有找到对应的国服玩家")
            throw StatsException(StatsProblem.UPSTREAM, "上游返回了未识别的业务状态，请稍后重试")
        }
        return root["data"]?.takeUnless { it is JsonNull }
            ?: throw StatsException(StatsProblem.EMPTY, "上游暂未提供可显示的数据")
    }

    fun snapshot(
        player: Player, range: QueryRange, identity: JsonObject, card: JsonObject, competitive: JsonObject?,
        fetchedAt: Long, quick: JsonObject? = null, names: MatchNames = MatchNames(),
    ): StatsSnapshot {
        val blocks = mutableListOf<StatsBlock>()
        blocks += StatsBlock("profile", "生涯总览", listOf(
            Metric("累计场次", stat(identity, "totalMatchNum") ?: stat(card, "totalMatchNum"), "场"),
            // queryCard/searchBnetAccount gameTime is in hours; hero gameTime below is in seconds.
            Metric("游玩时长", stat(card, "gameTime") ?: stat(identity, "gameTime"), "小时"),
            Metric("战网昵称", value(card, "name") ?: value(identity, "name") ?: player.battleTag),
            Metric("头衔", value(card, "title") ?: value(identity, "title")),
            Metric("资料等级", stat(card, "level") ?: stat(identity, "level")),
        ).filter { it.value != null })
        fun addMode(data: JsonObject?, id: String, title: String) {
            if (data == null) return
            listOf("presets" to "职责", "open" to "开放").forEach { (queue, queueTitle) ->
                val blockId = if (queue == "presets") id else "$id.open"
                val summary = data["${queue}SummaryData"] as? JsonObject
                if (summary != null) blocks += StatsBlock(blockId, "本赛季$title · $queueTitle", listOf(
                    Metric("完成场次", stat(summary, "matchSum"), "场"),
                    Metric("获胜", stat(summary, "matchWinSum"), "场"),
                    Metric("失利", stat(summary, "matchLossSum"), "场"),
                    Metric("胜率", percentage(summary, "winRate"), "%"),
                    Metric("场均消灭", stat(summary, "aveKill")),
                    Metric("场均助攻", stat(summary, "aveAssist")),
                    Metric("场均死亡", stat(summary, "aveDeath")),
                    Metric("场均伤害", stat(summary, "aveHeroDamage")),
                    Metric("场均治疗", stat(summary, "aveCure")),
                    Metric("场均减伤", stat(summary, "aveResistDamage")),
                    Metric("场均单独消灭", stat(summary, "aveIndividualKill")),
                    Metric("场均最后一击", stat(summary, "aveFinalHit")),
                ).filter { it.value != null })
                val heroes = (data["${queue}HeroUseSummaryList"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .distinctBy { value(it, "heroGuid") ?: value(it, "heroName") }
                    .sortedByDescending { stat(it, "gameTime")?.toDoubleOrNull() ?: -1.0 }
                    .mapNotNull { hero ->
                        val name = names.heroes[value(hero, "heroGuid")] ?: value(hero, "heroName")
                            ?: return@mapNotNull null
                        val details = listOfNotNull(
                            stat(hero, "gameTime")?.toDoubleOrNull()?.takeIf { it < Long.MAX_VALUE.toDouble() }
                                ?.toLong()?.let(::formatDuration),
                            stat(hero, "matchSum")?.let { "$it 场" },
                            percentage(hero, "winRate")?.let { "胜率 $it%" },
                        )
                        details.takeIf { it.isNotEmpty() }?.let { Metric(name, it.joinToString(" · ")) }
                    }.take(3)
                if (heroes.isNotEmpty()) blocks += StatsBlock("$blockId.heroes", "本赛季${title}${queueTitle}英雄", heroes)
            }
        }
        addMode(competitive, "competitive", "竞技")
        addMode(quick, "quick", "快速")
        competitive?.let { data ->
            val guides = data["guideCountData"] as? JsonArray
            val roleNames = mapOf("tank" to "重装", "dps" to "输出", "healer" to "支援")
            fun rankLabel(rank: JsonObject?): String? {
                val name = value(rank, "rankName") ?: value(rank, "rank_name") ?: return null
                val tier = stat(rank, "rankSubTier") ?: stat(rank, "rank_sub_tier")
                return listOfNotNull(name, tier).joinToString(" ")
            }
            val ranks = guides.orEmpty().mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val role = roleNames[value(item, "roleType")] ?: return@mapNotNull null
                Metric(role, rankLabel(item["lastRankInfo"] as? JsonObject))
            }
            if (ranks.any { it.value != null }) blocks += StatsBlock("rank", "本赛季段位", ranks.filter { it.value != null })
            val peaks = guides.orEmpty().mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val role = roleNames[value(item, "roleType")] ?: return@mapNotNull null
                val rank = rankLabel(item["maxRankInfo"] as? JsonObject) ?: return@mapNotNull null
                Metric(role, rank)
            }
            if (peaks.isNotEmpty()) blocks += StatsBlock("rank.peak", "本赛季最高段位", peaks)
        }
        return StatsSnapshot(
            player.copy(battleTag = value(identity, "name") ?: player.battleTag), range,
            "网易大神 · 国服接口", fetchedAt,
            "生涯资料及当前赛季竞技／快速统计",
            "生涯 / 本赛季", competitive != null && quick != null, blocks.filter { it.available }, false,
        )
    }
    private fun stat(data: JsonObject?, key: String): String? = value(data, key)
        ?.takeIf { it.length <= 32 }?.toBigDecimalOrNull()
        ?.takeIf { it >= BigDecimal.ZERO && it.precision() <= 20 && it.scale() in -6..10 }
        ?.stripTrailingZeros()?.toPlainString()
    private fun percentage(data: JsonObject?, key: String): String? = stat(data, key)
        ?.takeIf { it.toBigDecimal() <= BigDecimal(100) }
}
