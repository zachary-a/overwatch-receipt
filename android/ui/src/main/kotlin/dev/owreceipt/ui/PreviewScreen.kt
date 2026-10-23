package dev.owreceipt.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.owreceipt.ui.design.*
import dev.owreceipt.core.*

@Composable
fun PreviewScreen(state: AppUiState, onAction: (UiAction) -> Unit) {
    val preview = state.preview ?: return
    val artifact = preview.artifact
    val raster = artifact.raster
    val image = remember(raster) { BitmapFactory.decodeByteArray(raster.png, 0, raster.png.size).asImageBitmap() }
    var zoom by rememberSaveable(artifact.document.id) { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize().testTag("preview.screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (artifact.document.snapshot.isDemo) DemoBadge()
            Text(if (preview.saved) "已保存" else artifact.document.snapshot.range.period.label,
                Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
            IconButton({ zoom = (zoom - .25f).coerceAtLeast(.5f) }) { Icon(Icons.Outlined.ZoomOut, "缩小预览") }
            IconButton({ zoom = (zoom + .25f).coerceAtMost(3f) }) { Icon(Icons.Outlined.ZoomIn, "放大预览") }
        }
        if (state.previewBatch.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = Space.page),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ onAction(UiAction.PreviewIndex(state.previewIndex - 1)) }, enabled = state.previewIndex > 0 && !state.rendering) { Text("上一张") }
            Text("第 ${state.previewIndex + 1} / ${state.previewBatch.size} 张", style = MaterialTheme.typography.labelLarge)
            TextButton({ onAction(UiAction.PreviewIndex(state.previewIndex + 1)) },
                enabled = state.previewIndex < state.previewBatch.lastIndex && !state.rendering) { Text("下一张") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(artifact.document.id) {
                detectTransformGestures { _, _, factor, _ -> zoom = (zoom * factor).coerceIn(.5f, 3f) }
            }) {
            val width = (maxWidth - 48.dp) * zoom
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(image, if (artifact.document.snapshot.isDemo) "黑白热敏小票，包含示例数据标记" else "黑白热敏战绩小票",
                    Modifier.width(width).aspectRatio(raster.width.toFloat() / raster.height).testTag("preview.raster"),
                    contentScale = ContentScale.FillBounds)
            }
        }
        Column(Modifier.padding(horizontal = Space.page, vertical = 10.dp)) {
            if (!preview.saved) FontPicker(artifact.document.font,
                { onAction(UiAction.ChooseFont(it)) }, enabled = !state.rendering && !state.saving)
            if (state.rendering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.previewBatch.size > 1) OutlinedButton({ onAction(UiAction.SaveBatch) }, Modifier.fillMaxWidth(),
                enabled = !state.rendering && !state.saving && state.previewBatch.any { !it.saved }) {
                Text(if (state.previewBatch.all { it.saved }) "全部已存入纪念册" else "全部存入纪念册（${state.previewBatch.size} 张）")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ onAction(UiAction.Save) }, Modifier.weight(1f).testTag("preview.save"),
                    enabled = !preview.saved && !state.saving && !state.rendering) {
                    Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (preview.saved) "已存入纪念册" else if (state.saving) "保存中…" else "存入纪念册")
                }
                OutlinedButton({ onAction(UiAction.Export) }, Modifier.weight(1f).testTag("preview.export"),
                    enabled = !state.rendering) {
                    Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("导出 PNG")
                }
            }
            Button({ onAction(UiAction.Print) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview.print"),
                enabled = !state.rendering && !state.saving &&
                    !state.printer.status.active && !state.deviceWorking) {
                Icon(Icons.Outlined.Print, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.isDemo) "模拟打印" else if (state.previewBatch.size > 1) "排队打印全部 ${state.previewBatch.size} 张" else "打印这张小票")
            }
        }
    }
}
