package dev.owreceipt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.owreceipt.core.*
import dev.owreceipt.ui.design.*

@Composable
fun DeviceScreen(state: AppUiState, onAction: (UiAction) -> Unit) {
    if (!state.isDemo) {
        BluetoothDeviceScreen(state, onAction)
        return
    }
    val printer = state.printer
    val busy = printer.status in setOf(PrintStatus.SENDING, PrintStatus.PAPER_OUT)
    LazyColumn(contentPadding = PaddingValues(Space.page), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("让纪念，有了形状。", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Print, null, Modifier.size(42.dp))
                    Column {
                        Text("WP9516", style = MaterialTheme.typography.titleLarge)
                        Text("黑白热敏打印机", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DemoBadge("演示设备")
                if (printer.status == PrintStatus.DISCONNECTED) {
                    Button({ onAction(UiAction.Connect) }, Modifier.fillMaxWidth().testTag("device.connect")) { Text("连接演示设备") }
                } else {
                    OutlinedButton({ onAction(UiAction.Disconnect) }, Modifier.fillMaxWidth().testTag("device.disconnect")) {
                        Text("断开演示设备")
                    }
                }
            }
        }
        item {
            Panel(Modifier.testTag("device.status")) {
                Text(when (printer.status) {
                    PrintStatus.DISCONNECTED -> "尚未连接"
                    PrintStatus.READY -> "演示设备已就绪"
                    PrintStatus.SENDING -> "正在模拟打印"
                    PrintStatus.PAPER_OUT -> "模拟缺纸 · 已暂停"
                    PrintStatus.STOPPED -> "模拟打印已停止"
                    PrintStatus.COMPLETED -> "模拟流程完成"
                    else -> printer.detail ?: "正在准备设备"
                }, style = MaterialTheme.typography.titleMedium)
                if (printer.receiptId != null) {
                    LinearProgressIndicator(progress = { printer.progress }, Modifier.fillMaxWidth())
                    Text("${(printer.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ onAction(if (printer.status == PrintStatus.PAPER_OUT) UiAction.Resume else UiAction.PaperOut) },
                        Modifier.weight(1f).testTag("device.paper")) {
                        Text(if (printer.status == PrintStatus.PAPER_OUT) "补纸并继续" else "模拟缺纸")
                    }
                    OutlinedButton({ onAction(UiAction.Stop) }, Modifier.weight(1f).testTag("device.stop")) { Text("停止") }
                }
            }
        }
    }
}

@Composable
private fun BluetoothDeviceScreen(state: AppUiState, onAction: (UiAction) -> Unit) {
    val printer = state.printer
    val ready = printer.status in setOf(PrintStatus.READY, PrintStatus.STOPPED, PrintStatus.COMPLETED)
    val working = state.deviceWorking || printer.status.active ||
        printer.status in setOf(PrintStatus.CONNECTING, PrintStatus.READING_INFO)
    val showSelection = printer.status == PrintStatus.DISCONNECTED ||
        (printer.status == PrintStatus.ERROR && printer.info == null)
    val devices = state.devices.filter { it.isWp9516 }
    LazyColumn(Modifier.testTag("device.list"), contentPadding = PaddingValues(Space.page), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("让纪念，有了形状。", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Panel(Modifier.testTag("device.status")) {
                Text(when (printer.status) {
                    PrintStatus.DISCONNECTED -> "尚未连接"
                    PrintStatus.CONNECTING -> "正在连接"
                    PrintStatus.READING_INFO -> "正在读取设备"
                    PrintStatus.READY -> "打印机已就绪"
                    PrintStatus.PREPARING -> "正在准备任务"
                    PrintStatus.SENDING -> "正在发送小票"
                    PrintStatus.WAITING_BUFFER -> "正在打印"
                    PrintStatus.WAITING_IDLE -> "正在打印"
                    PrintStatus.PAPER_OUT -> "打印机缺纸 · 已暂停"
                    PrintStatus.STOPPING -> "正在停止"
                    PrintStatus.STOPPED -> "打印已停止"
                    PrintStatus.COMPLETED -> "打印完成"
                    PrintStatus.ERROR -> "打印机需要处理"
                }, style = MaterialTheme.typography.titleMedium)
                printer.device?.let { Text(it.name, style = MaterialTheme.typography.bodyLarge) }
                if (printer.queueSize > 1) Text("当前第 ${printer.queueIndex} / ${printer.queueSize} 张", style = MaterialTheme.typography.titleSmall)
                printer.info?.let { info ->
                    val faults = listOfNotNull(if (info.paperOut) "缺纸" else null, if (info.lidOpen) "开盖" else null,
                        if (info.overheated) "过热" else null, if (info.lowPower) "低电" else null)
                    if (faults.isNotEmpty()) Text(faults.joinToString(" · "), color = MaterialTheme.colorScheme.error)
                }
                if (printer.status == PrintStatus.ERROR) printer.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (printer.receiptId != null) {
                    LinearProgressIndicator(progress = { printer.progress }, Modifier.fillMaxWidth())
                    Text("已发送 ${(printer.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (printer.status.active || printer.status == PrintStatus.ERROR) {
                    Button({ onAction(UiAction.Stop) }, Modifier.fillMaxWidth().testTag("device.stop"),
                        enabled = !state.deviceWorking) { Text("停止当前任务") }
                }
                if (printer.status != PrintStatus.DISCONNECTED) OutlinedButton(
                    { onAction(UiAction.Disconnect) }, Modifier.fillMaxWidth().testTag("device.disconnect"),
                    enabled = !state.deviceWorking) { Text("断开连接") }
                if (ready) OutlinedButton({ onAction(UiAction.TestPrint) },
                    Modifier.fillMaxWidth().testTag("device.test"),
                    enabled = !working && printer.info?.widthDots == 576) { Text("打印测试页") }
                if (state.preview != null) TextButton({ onAction(UiAction.ReturnPreview) }) { Text("返回小票预览") }
            }
        }
        if (showSelection) {
            item {
                SectionHeading("01", "选择打印机")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onAction(UiAction.RefreshDevices) }, enabled = !working) { Text("读取已配对设备") }
                    OutlinedButton({ onAction(UiAction.ScanDevices) }, enabled = !working) { Text("扫描附近设备") }
                }
                if (devices.isEmpty()) Text("未找到 9516 打印机",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(devices, key = { it.id }) { device ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(device.id == state.selectedDevice,
                            { onAction(UiAction.ChooseDevice(device.id)) }, enabled = !working)
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text("${device.id} · ${if (device.bonded) "已配对" else "未配对"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Button({ onAction(UiAction.Connect) }, Modifier.fillMaxWidth().testTag("device.connect"),
                    enabled = devices.any { it.id == state.selectedDevice } && !working) { Text(if (working) "正在处理…" else "连接打印机") }
            }
        }
    }
}
