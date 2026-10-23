package dev.owreceipt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.owreceipt.core.*
import dev.owreceipt.ui.design.*

@Composable
fun MakeScreen(state: AppUiState, onAction: (UiAction) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).testTag("make.list"),
            contentPadding = PaddingValues(horizontal = Space.page, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("把战绩，留在纸上。", style = MaterialTheme.typography.headlineLarge)
            }
            if (state.isDemo) item { DemoNotice() }
            item {
                Panel(Modifier.clickable { onAction(UiAction.OpenAccount) }.testTag("account.card")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.session.activePlayer?.battleTag ?: if (state.isDemo) "选择示例账号" else "登录我的国服账号", style = MaterialTheme.typography.titleMedium)
                            if (state.session.status == SessionStatus.EXPIRED) Text("登录已过期",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Icon(Icons.Outlined.ChevronRight, "选择账号")
                    }
                }
            }
            item {
                SectionHeading("01", "选择时间", "北京时间")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Period.entries.forEach { period ->
                        FilterChip(
                            selected = state.period == period,
                            enabled = period in state.capabilities.periods,
                            onClick = { onAction(UiAction.ChoosePeriod(period)) },
                            label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(period.label) } },
                            modifier = Modifier.weight(1f).testTag("period.${period.name}"),
                        )
                    }
                }
            }
            if (!state.isDemo && state.period != Period.CAREER) item {
                SectionHeading("02", "留下什么")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ReceiptKind.SUMMARY to "区间总结", ReceiptKind.MATCH to "挑选纪念对局").forEach { (kind, title) ->
                        FilterChip(state.receiptKind == kind, { onAction(UiAction.ChooseReceiptKind(kind)) },
                            label = { Text(title) }, modifier = Modifier.testTag("kind.${kind.name}"))
                    }
                }
                Text("对局模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MatchMode.entries.toSet() to "竞技＋快速", setOf(MatchMode.RANKED) to "竞技", setOf(MatchMode.QUICK) to "快速")
                        .forEach { (modes, label) ->
                            FilterChip(state.modes == modes, { onAction(UiAction.ChooseModes(modes)) }, label = { Text(label) })
                        }
                }
            }
            when (state.queryStatus) {
                QueryStatus.LOADING -> item {
                    Panel {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.isDemo) "正在整理示例战绩…" else "正在读取国服战绩…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                QueryStatus.PROBLEM -> item {
                    StatusPanel(
                        when (state.problem) {
                            StatsProblem.EMPTY -> "还没有可制作的战绩"
                            StatsProblem.PRIVATE -> "资料暂不可见"
                            StatsProblem.AUTH_REQUIRED -> "需要重新认证"
                            else -> "这次查询没有完成"
                        }, state.queryMessage,
                        if (state.problem == StatsProblem.AUTH_REQUIRED) (if (state.isDemo) "重新认证示例账号" else "前往官方认证") else "重试",
                    ) { onAction(if (state.problem == StatsProblem.AUTH_REQUIRED) UiAction.OpenAccount else UiAction.Retry) }
                    if (state.isDemo) TextButton({ onAction(UiAction.ShowScenarios) }) { Text("切换示例场景") }
                }
                QueryStatus.CONTENT -> {
                    if (!state.isDemo && state.period != Period.CAREER && state.receiptKind == ReceiptKind.MATCH) {
                        item {
                            SectionHeading("03", "挑选值得留下的一场", "已选 ${state.selectedMatches.size} 场")
                            if (state.snapshot?.matches.isNullOrEmpty()) StatusPanel("还没有取得这个区间的对局",
                                "请切换模式或稍后重试。")
                        }
                        items(state.snapshot?.matches.orEmpty(), key = { "match.${it.id}" }) { match ->
                            Panel(Modifier.testTag("match.${match.id}").clickable { onAction(UiAction.ToggleMatch(match.id)) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(match.title, style = MaterialTheme.typography.titleSmall)
                                        listOfNotNull(match.hero, match.role, match.map).takeIf { it.isNotEmpty() }?.let {
                                            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("消灭 ${number(match.kills) ?: "—"} · 助攻 ${number(match.assists) ?: "—"} · 死亡 ${number(match.deaths) ?: "—"}",
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text("伤害 ${number(match.damage) ?: "—"} · 治疗 ${number(match.healing) ?: "—"}",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(match.durationSeconds?.let(::formatDuration) ?: "时长暂不可用", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Checkbox(match.id in state.selectedMatches, { onAction(UiAction.ToggleMatch(match.id)) })
                                }
                            }
                        }
                    } else {
                    item { SectionHeading(if (state.isDemo || state.period == Period.CAREER) "02" else "03", "装进小票", "已选 ${state.selectedBlocks.size} 项") }
                    items(state.snapshot?.blocks.orEmpty(), key = { it.id }) { block ->
                        Panel(Modifier.testTag("block.${block.id}")
                            .clickable(enabled = block.available) { onAction(UiAction.ToggleBlock(block.id)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(block.title, style = MaterialTheme.typography.titleMedium)
                                    Text(if (block.available) block.metrics.filter { it.value != null }.take(3)
                                        .joinToString("  ·  ") { "${it.label} ${it.displayValue}" }
                                        else "此模块没有可用数据",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(block.id in state.selectedBlocks,
                                    onCheckedChange = { onAction(UiAction.ToggleBlock(block.id)) }, enabled = block.available,
                                    modifier = Modifier.testTag("check.${block.id}"))
                            }
                        }
                    }
                    }
                    item {
                        SectionHeading(if (state.isDemo || state.period == Period.CAREER) "03" else "04", "小票字号")
                        FontPicker(state.font, onChoose = { onAction(UiAction.ChooseFont(it)) })
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Button(
                { onAction(UiAction.Preview) },
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp).heightIn(min = 52.dp).testTag("make.preview"),
                enabled = state.queryStatus == QueryStatus.CONTENT && !state.rendering &&
                    (if (!state.isDemo && state.period != Period.CAREER && state.receiptKind == ReceiptKind.MATCH)
                        state.selectedMatches.isNotEmpty() else state.selectedBlocks.isNotEmpty()),
            ) {
                if (state.rendering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.rendering) "正在生成小票…" else if (!state.isDemo && state.receiptKind == ReceiptKind.MATCH &&
                    state.period != Period.CAREER) "预览 ${state.selectedMatches.size} 张纪念小票" else "预览我的小票")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun FontPicker(font: ReceiptFont, onChoose: (ReceiptFont) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReceiptFont.choices.forEach {
            FilterChip(font == it, { onChoose(it) }, enabled = enabled,
                label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("${it.label}号字") } },
                modifier = Modifier.weight(1f).testTag("font.${it.name}"))
        }
    }
}
