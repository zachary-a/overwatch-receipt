package dev.owreceipt.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import dev.owreceipt.core.*
import dev.owreceipt.ui.design.ReceiptTheme
import dev.owreceipt.ui.design.TransitionMillis

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ReceiptApp(state: AppUiState, authContent: @Composable (Modifier) -> Unit = {}, onAction: (UiAction) -> Unit) {
    ReceiptTheme(state.theme) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { snackbar.showSnackbar(it); onAction(UiAction.DismissMessage) }
        }
        var themeMenu by remember { mutableStateOf(false) }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (state.screen == Screen.PREVIEW) 170.dp else 0.dp)) },
            topBar = {
                TopAppBar(
                    title = { Text(when (state.screen) {
                        Screen.ACCOUNT -> if (state.isDemo) "账号与示例" else "我的账号"
                        Screen.PREVIEW -> "小票预览"
                        Screen.HOME -> when (state.tab) {
                            HomeTab.MAKE -> "战绩小票"
                            HomeTab.ARCHIVE -> "我的纪念册"
                            HomeTab.DEVICE -> "打印设备"
                        }
                    }, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (state.screen != Screen.HOME) IconButton(
                            onClick = { onAction(UiAction.Back) }, modifier = Modifier.testTag("back")
                        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    },
                    actions = {
                        Box {
                            IconButton({ themeMenu = true }) { Icon(Icons.Outlined.Contrast, "切换主题") }
                            DropdownMenu(themeMenu, { themeMenu = false }) {
                                AppTheme.entries.forEach { theme ->
                                    DropdownMenuItem(text = { Text(theme.label) }, onClick = {
                                        themeMenu = false; onAction(UiAction.ChooseTheme(theme))
                                    })
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (state.screen == Screen.HOME) NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    HomeTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab, onClick = { onAction(UiAction.Tab(tab)) },
                            icon = { Icon(when (tab) {
                                HomeTab.MAKE -> Icons.Outlined.ReceiptLong
                                HomeTab.ARCHIVE -> Icons.Outlined.CollectionsBookmark
                                HomeTab.DEVICE -> Icons.Outlined.Print
                            }, null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("tab.${tab.name}"),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).preferredFrameRate(FrameRateCategory.High)) {
                Crossfade(
                    targetState = state.screen to state.tab.takeIf { state.screen == Screen.HOME },
                    animationSpec = tween(TransitionMillis), label = "page",
                ) { (screen, tab) -> when (screen) {
                    Screen.ACCOUNT -> AccountScreen(state, onAction, authContent)
                    Screen.PREVIEW -> PreviewScreen(state, onAction)
                    Screen.HOME -> when (tab) {
                        HomeTab.MAKE -> MakeScreen(state, onAction)
                        HomeTab.ARCHIVE -> ArchiveScreen(state, onAction)
                        HomeTab.DEVICE -> DeviceScreen(state, onAction)
                        null -> Unit
                    }
                }
                }
            }
        }
        if (state.showScenarios) AlertDialog(
            onDismissRequest = { onAction(UiAction.DismissScenarios) },
            title = { Text("切换示例场景") },
            text = {
                Column {
                    DemoScenario.entries.forEach { scenario ->
                        Row {
                            RadioButton(state.scenario == scenario,
                                onClick = { onAction(UiAction.ChooseScenario(scenario)) })
                            TextButton({ onAction(UiAction.ChooseScenario(scenario)) }) { Text(scenario.label) }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ onAction(UiAction.DismissScenarios) }) { Text("关闭") } },
        )
    }
}

@Preview(name = "制作 · 浅色", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun LightPreview() { ReceiptApp(AppUiState(theme = AppTheme.LIGHT)) {} }
@Preview(name = "制作 · 深色", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun DarkPreview() { ReceiptApp(AppUiState(theme = AppTheme.DARK)) {} }
