package dev.owreceipt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.owreceipt.core.*
import dev.owreceipt.ui.design.*

@Composable
fun AccountScreen(state: AppUiState, onAction: (UiAction) -> Unit, authContent: @Composable (Modifier) -> Unit = {}) {
    if (!state.isDemo) {
        OfficialAccountScreen(state, onAction, authContent)
        return
    }
    LazyColumn(contentPadding = PaddingValues(Space.page), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { DemoNotice() }
        item {
            Text("从你的账号开始", style = MaterialTheme.typography.headlineSmall)
        }
        if (state.session.status in setOf(SessionStatus.SIGNED_OUT, SessionStatus.EXPIRED)) {
            item {
                StatusPanel(if (state.session.status == SessionStatus.EXPIRED) "示例会话已过期" else "尚未选择账号",
                    "选择一个示例角色")
                Button({ onAction(UiAction.SignIn) }, Modifier.fillMaxWidth().testTag("account.signin")) {
                    Text(if (state.session.status == SessionStatus.EXPIRED) "重新认证示例账号" else "使用示例账号")
                }
            }
        } else if (state.session.status == SessionStatus.AUTHENTICATING) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在模拟认证…")
            }
        } else {
            item { SectionHeading("01", "选择角色", "${state.session.roles.size} 个示例角色") }
            items(state.session.roles, key = { it.id }) { player ->
                Panel(Modifier.clickable { onAction(UiAction.SelectRole(player.id)) }.testTag("role.${player.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(player.battleTag, style = MaterialTheme.typography.titleMedium)
                            Text("${player.realm} · ${player.roleName}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.session.activePlayer?.id == player.id)
                            Icon(Icons.Outlined.CheckCircle, "当前角色")
                    }
                }
            }
            item { OutlinedButton({ onAction(UiAction.SignOut) }) { Text("退出示例账号") } }
        }
        item {
            SectionHeading("02", "体验不同数据状态")
            Panel {
                Text("当前场景：${state.scenario.label}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton({ onAction(UiAction.ShowScenarios) }, Modifier.testTag("scenario.open")) { Text("切换示例场景") }
            }
        }
    }
}

@Composable
private fun OfficialAccountScreen(state: AppUiState, onAction: (UiAction) -> Unit,
    authContent: @Composable (Modifier) -> Unit) {
    val session = state.session
    val showingPage = session.status in setOf(SessionStatus.AUTHENTICATING, SessionStatus.FETCHING_CREDENTIAL)
    if (showingPage) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.padding(horizontal = Space.page, vertical = 8.dp)) {
                Text(state.authOrigin, style = MaterialTheme.typography.labelLarge)
                if (state.authError != null) {
                    Text(state.authError, color = MaterialTheme.colorScheme.error)
                    TextButton({ onAction(UiAction.RetryAuthPage) }) { Text("重新加载官方页面") }
                }
                if (state.authOrigin == "pay.ds.163.com") TextButton({ onAction(UiAction.OpenOfficialAccount) }) { Text("返回大神官网登录") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ onAction(UiAction.ToggleAuthWidePage) }, Modifier.testTag("account.page.mode")) {
                        Text(if (state.authWidePage) "恢复手机布局" else "宽页滑动")
                    }
                }
            }
            if (state.authLoading || session.status == SessionStatus.FETCHING_CREDENTIAL)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            authContent(Modifier.fillMaxWidth().weight(1f).testTag("account.official.page"))
            Row(Modifier.padding(horizontal = Space.page)) {
                TextButton({ onAction(UiAction.ReadBoundRoles) },
                    enabled = !state.authLoading && session.status != SessionStatus.FETCHING_CREDENTIAL,
                    modifier = Modifier.testTag("account.read.roles")) { Text("重新读取绑定角色") }
                Spacer(Modifier.weight(1f))
                TextButton({ onAction(UiAction.SignOut) }) { Text("退出登录") }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(Space.page), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("连接你的国服战绩", style = MaterialTheme.typography.headlineSmall)
        }
        if (session.status in setOf(SessionStatus.SIGNED_OUT, SessionStatus.EXPIRED, SessionStatus.FAILED)) item {
            StatusPanel(if (session.status == SessionStatus.EXPIRED) "需要重新认证" else "官方账号",
                session.detail ?: "登录网易大神，选择绑定角色")
            Button({ onAction(UiAction.SignIn) }, Modifier.fillMaxWidth().testTag("account.signin")) { Text("打开网易官方登录") }
        }
        if (session.status == SessionStatus.REFRESHING) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在更新登录状态…")
        }
        if (session.roles.isNotEmpty()) {
            item { SectionHeading("01", "绑定角色", "${session.roles.size} 个角色") }
            items(session.roles, key = { it.id }) { player ->
                Panel(Modifier.clickable(enabled = session.status != SessionStatus.REFRESHING) {
                    onAction(UiAction.SelectRole(player.id))
                }.testTag("role.${player.id}")) {
                    Text(player.battleTag, style = MaterialTheme.typography.titleMedium)
                    Text("${player.realm} · ${player.roleName}", style = MaterialTheme.typography.bodySmall)
                    if (session.activePlayer?.id == player.id) Text("当前角色", style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                OutlinedButton({ onAction(UiAction.ReadBoundRoles) }) { Text("重新读取官方绑定角色") }
                TextButton({ onAction(UiAction.SignOut) }) { Text("退出登录") }
            }
        }
    }
}
