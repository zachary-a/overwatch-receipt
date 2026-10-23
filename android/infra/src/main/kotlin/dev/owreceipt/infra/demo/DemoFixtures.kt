package dev.owreceipt.infra.demo

import dev.owreceipt.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DemoFixtures {
    val players = listOf(
        Player("demo-role-1", "小队长#1024", "示例角色一"),
        Player("demo-role-2", "正在等待队友的守望先锋玩家#2026", "示例角色二"),
    )
    fun snapshot(player: Player, range: QueryRange, missing: Boolean = false): StatsSnapshot {
        val totals = when (range.period) {
            Period.TODAY -> listOf("8", "5", "3", "62.5", "1.6")
            Period.WEEK -> listOf("32", "19", "13", "59.4", "6.4")
            Period.CAREER -> listOf("1,284", "706", "578", "55.0", "428")
        }
        val heroTimes = when (range.period) {
            Period.TODAY -> listOf("42", "32", "22")
            Period.WEEK -> listOf("126", "83", "58")
            Period.CAREER -> listOf("126", "83", "64")
        }
        val heroUnit = if (range.period == Period.CAREER) "小时" else "分钟"
        return StatsSnapshot(
            player, range, "国服演示数据集", range.endExclusive,
            if (missing) "部分统计尚未返回，缺失项标为暂不可用" else "覆盖所选区间 · 示例数据",
            "快速＋竞技 · 示例口径", !missing,
            listOf(
                StatsBlock("overview", "战绩总览", listOf(
                    Metric("完成场次", totals[0], "场"), Metric("获胜", totals[1], "场"),
                    Metric("失利", totals[2], "场"), Metric("平局", "0", "场"),
                    Metric("胜率", totals[3], "%"), Metric("游戏时长", totals[4], "小时"),
                )),
                StatsBlock("heroes", "常用英雄", listOf(
                    Metric("安娜", heroTimes[0], heroUnit),
                    Metric("雾子", heroTimes[1], heroUnit),
                    Metric("莱因哈特", if (missing) null else heroTimes[2], heroUnit),
                )),
                StatsBlock("best", "最佳表现", listOf(
                    Metric("单场最高消灭", "38"), Metric("单场最高助攻", "27"),
                    Metric("单场最高伤害", if (missing) null else "18,420"),
                    Metric("单场最高治疗", "21,860"),
                )),
                StatsBlock("rank", "竞技段位", listOf(
                    Metric("重装", if (missing) null else "白金 3"),
                    Metric("输出", if (missing) null else "黄金 1"),
                    Metric("支援", if (missing) null else "钻石 5"),
                )),
            ),
        )
    }
}

class DemoAccountSession : AccountSession {
    override val isDemo = true
    private val mutable = MutableStateFlow(
        SessionState(SessionStatus.READY, DemoFixtures.players, DemoFixtures.players.first())
    )
    override val state = mutable.asStateFlow()
    override suspend fun signIn() {
        mutable.value = SessionState(SessionStatus.AUTHENTICATING)
        delay(400)
        mutable.value = SessionState(SessionStatus.SELECTING_ROLE, DemoFixtures.players)
    }
    override suspend fun selectRole(id: String) {
        val role = DemoFixtures.players.first { it.id == id }
        mutable.value = SessionState(SessionStatus.READY, DemoFixtures.players, role)
    }
    override suspend fun expire() { mutable.value = mutable.value.copy(status = SessionStatus.EXPIRED) }
    override suspend fun signOut() { mutable.value = SessionState() }
}

class DemoStatsRepository(private val latencyMillis: Long = 450) : StatsRepository {
    override fun capabilities(scenario: DemoScenario) = if (scenario == DemoScenario.CAREER_ONLY)
        QueryCapabilities(setOf(Period.CAREER), "当前示例数据源仅提供生涯统计，无法查询日／周战绩")
    else QueryCapabilities(Period.entries.toSet())

    override suspend fun query(player: Player, range: QueryRange, scenario: DemoScenario, modes: Set<MatchMode>): StatsSnapshot {
        delay(latencyMillis)
        if (range.period !in capabilities(scenario).periods)
            throw StatsException(StatsProblem.UNSUPPORTED, "当前数据源不支持这个时间区间")
        when (scenario) {
            DemoScenario.EMPTY -> throw StatsException(StatsProblem.EMPTY, "这个区间还没有战绩，试试其他区间")
            DemoScenario.PRIVATE -> throw StatsException(StatsProblem.PRIVATE, "当前资料不可见，暂时无法制作小票")
            DemoScenario.FAILURE -> throw StatsException(StatsProblem.NETWORK, "本次示例查询失败，可以重试或切换示例场景")
            DemoScenario.EXPIRED -> throw StatsException(StatsProblem.AUTH_REQUIRED, "示例登录已过期，请重新认证后继续")
            else -> return DemoFixtures.snapshot(player, range, scenario == DemoScenario.MISSING)
        }
    }
}
