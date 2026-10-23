package dev.owreceipt.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.owreceipt.core.formatTime
import dev.owreceipt.ui.design.*

@Composable
fun ArchiveScreen(state: AppUiState, onAction: (UiAction) -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.testTag("archive.list"), contentPadding = PaddingValues(Space.page), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("留住的每一张", style = MaterialTheme.typography.headlineSmall)
            Text("${state.receipts.size} 张小票", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isDemo) item { DemoNotice() }
        if (state.archiveLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (state.archiveError != null) item { StatusPanel("纪念册暂时无法读取", state.archiveError) }
        else if (state.receipts.isEmpty()) item {
            StatusPanel("第一张纪念小票，等你留下", "在预览页点击“存入纪念册”，以后可以随时打开和导出。",
                "去制作小票") { onAction(UiAction.Tab(HomeTab.MAKE)) }
        }
        items(state.receipts, key = { it.document.id }) { receipt ->
            Panel(Modifier.combinedClickable(
                onClick = { onAction(UiAction.OpenSaved(receipt.document.id)) },
                onLongClickLabel = "删除小票",
                onLongClick = { deleting = receipt.document.id },
            )
                .testTag("archive.receipt")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(receipt.document.snapshot.player.battleTag, style = MaterialTheme.typography.titleMedium)
                        Text("${receipt.document.snapshot.range.period.label} · ${receipt.document.blocks.size} 个战绩模块",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(formatTime(receipt.document.createdAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ deleting = receipt.document.id }, Modifier.testTag("archive.delete")) {
                        Icon(Icons.Outlined.DeleteOutline, "删除小票")
                    }
                }
                if (receipt.document.snapshot.isDemo) DemoBadge()
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("删除这张小票？") },
            text = { Text("仅删除本机小票，导出图片保留。") },
            confirmButton = { TextButton({ deleting = null; onAction(UiAction.DeleteSaved(id)) }) { Text("删除") } },
            dismissButton = { TextButton({ deleting = null }) { Text("保留") } })
    }
}
