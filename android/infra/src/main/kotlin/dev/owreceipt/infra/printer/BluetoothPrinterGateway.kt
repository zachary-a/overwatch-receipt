package dev.owreceipt.infra.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.owreceipt.core.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import personal.wp9516.protocol.Wp9516Protocol as Codec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.printerPreferences by preferencesDataStore("printer_preferences")

/** Classic RFCOMM only. A successful connect is followed by a real 01 query before READY. */
@SuppressLint("MissingPermission")
class BluetoothPrinterGateway(private val context: Context, private val scope: CoroutineScope) : PrinterGateway {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(PrintState())
    override val state = mutable.asStateFlow()
    private val found = MutableStateFlow<List<PrinterDevice>>(emptyList())
    override val devices = found.asStateFlow()
    private val operations = Mutex()
    private val lastDevice = stringPreferencesKey("last_device")
    private var session: Wp9516Session? = null
    private var printJob: Job? = null
    private var observation: Job? = null
    @Volatile private var queueIndex = 0
    @Volatile private var queueSize = 0
    private var registered = false
    private val trace = PrinterTrace(File(context.filesDir, "printer-trace"))
    private val discovery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!permitted()) return
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return
            val item = PrinterDevice(device.address, device.name ?: "未命名蓝牙设备",
                device.bondState == BluetoothDevice.BOND_BONDED)
            if (!item.isWp9516) return
            found.update { items -> (items.filterNot { it.id == item.id } + item).sortedBy { it.name } }
        }
    }
    private fun permitted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
    private fun checkAdapter(): BluetoothAdapter {
        check(permitted()) { "请先允许蓝牙附近设备权限" }
        val bt = adapter ?: error("此设备不支持经典蓝牙")
        check(bt.isEnabled) { "请先在系统设置中开启蓝牙" }
        return bt
    }
    override suspend fun refreshDevices(scan: Boolean) {
        val bt = checkAdapter()
        found.value = bt.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .map { PrinterDevice(it.address, it.name.orEmpty()) }.filter { it.isWp9516 }.sortedBy { it.name }
        if (mutable.value.status == PrintStatus.DISCONNECTED) {
            val prior = context.printerPreferences.data.first()[lastDevice]
            found.value.firstOrNull { it.id == prior }?.let { device -> mutable.update { it.copy(device = device) } }
        }
        if (scan) {
            if (Build.VERSION.SDK_INT < 31)
                check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "旧版 Android 扫描需要位置权限" }
            if (!registered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(discovery, filter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(discovery, filter)
                registered = true
            }
            bt.cancelDiscovery()
            check(bt.startDiscovery()) { "未能开始扫描，请检查系统蓝牙设置" }
        }
    }
    override suspend fun connect(deviceId: String?, strategy: ConnectionStrategy) = operations.withLock {
        check(session == null) { "请先断开当前打印机" }
        val bt = checkAdapter()
        bt.cancelDiscovery()
        val id = deviceId ?: context.printerPreferences.data.first()[lastDevice]
            ?: error("请先从设备列表选择打印机")
        require(BluetoothAdapter.checkBluetoothAddress(id)) { "设备地址无效，请重新选择" }
        val device = bt.getRemoteDevice(id)
        val model = PrinterDevice(id, device.name.orEmpty(), device.bondState == BluetoothDevice.BOND_BONDED)
        require(model.isWp9516) { "仅支持名称以 9516 开头的打印机" }
        printJob?.cancelAndJoin(); printJob = null
        closeConnection()
        queueIndex = 0; queueSize = 0
        val strategies = if (strategy == ConnectionStrategy.AUTO) listOf(ConnectionStrategy.SPP, ConnectionStrategy.CHANNEL_ONE) else listOf(strategy)
        var lastError: Exception? = null
        for (method in strategies) {
            mutable.value = PrintState(PrintStatus.CONNECTING, device = model, strategy = method,
                detail = "正在使用 ${method.label} 连接")
            trace.record("CONNECT", "strategy=$method socket:create")
            var socket: BluetoothSocket? = null
            try {
                socket = when (method) {
                    ConnectionStrategy.SPP -> device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(Codec.SPP_UUID))
                    ConnectionStrategy.CHANNEL_ONE -> device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                    else -> error("连接策略无效")
                }
                trace.record("CONNECT", "strategy=$method socket:created")
                withTimeout(15_000) { connectSocket(socket) }
                trace.record("CONNECT", "strategy=$method socket:connected")
                val link = SocketPrinterLink(socket, scope, trace)
                val connected = Wp9516Session(link, scope)
                session = connected
                observation = scope.launch {
                    connected.state.collect { update ->
                        trace.record("STATE", "${update.status} job=${update.receiptId.orEmpty()} ${update.detail.orEmpty()}")
                        mutable.value = update.copy(device = model, strategy = method, queueIndex = queueIndex, queueSize = queueSize,
                            status = if (update.status == PrintStatus.COMPLETED && queueIndex < queueSize) PrintStatus.PREPARING else update.status)
                    }
                }
                connected.initialize()
                context.printerPreferences.edit { it[lastDevice] = id }
                trace.record("CONNECT", "strategy=$method info:received width=${connected.state.value.info?.widthDots}")
                return@withLock
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) { socket?.close(); throw e }
                lastError = e
                trace.record("CONNECT_ERROR", "strategy=$method ${e.javaClass.simpleName} cause=${e.cause?.javaClass?.simpleName.orEmpty()}")
                observation?.cancel(); session?.close(); session = null
                runCatching { socket?.close() }
            }
        }
        mutable.value = PrintState(PrintStatus.ERROR, device = model, strategy = strategy,
            detail = "连接或设备查询失败。请断开乐写连接后重试，也可单独选择连接方式")
        throw IOException("未能连接并读到设备回复（${lastError?.javaClass?.simpleName}）")
    }
    override suspend fun print(artifact: ReceiptArtifact, settings: PrintSettings) = printBatch(listOf(artifact), settings)
    override suspend fun printBatch(artifacts: List<ReceiptArtifact>, settings: PrintSettings) = operations.withLock {
        checkAdapter().cancelDiscovery()
        require(artifacts.isNotEmpty() && artifacts.size <= 50) { "每批请选择 1 至 50 场" }
        check(printJob?.isActive != true) { "已有打印任务，请先停止或等待完成" }
        val connected = session ?: error("请先连接打印机")
        check(state.value.status in setOf(PrintStatus.READY, PrintStatus.STOPPED, PrintStatus.COMPLETED)) { "当前设备未就绪，请检查状态" }
        require(artifacts.all { it.raster.width == connected.state.value.info?.widthDots }) { "设备点宽发生变化，请重新生成小票" }
        require(artifacts.all { (it.raster.height.toLong() + settings.trailingFeedRows) * settings.copies <= 65535 }) { "单张任务过长，请减少份数或内容" }
        queueIndex = 0; queueSize = artifacts.size
        printJob = scope.launch {
            try {
                for ((index, artifact) in artifacts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    queueIndex = index + 1
                    connected.print(artifact.raster, artifact.document.id, settings)
                }
            }
            catch (e: CancellationException) {
                if (e is TimeoutCancellationException) mutable.update { it.copy(status = PrintStatus.ERROR, detail = "等待设备超时，请停止并重新连接") }
                else throw e
            } catch (e: Exception) {
                mutable.update { it.copy(status = PrintStatus.ERROR, detail = e.message ?: "打印中断，请重新连接") }
            }
        }
    }
    override suspend fun printTest(settings: PrintSettings) {
        val bytes = context.assets.open("WP9516_protocol_576.png").use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("测试图不可用")
        val packed = ByteArray(bitmap.width / 8 * bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if ((bitmap.getPixel(x, y) and 0xffffff) == 0) {
                val index = y * (bitmap.width / 8) + x / 8
                packed[index] = (packed[index].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
        val raster = RenderedReceipt(bitmap.width, bitmap.height, bytes, packed)
        bitmap.recycle()
        val now = System.currentTimeMillis()
        val snapshot = StatsSnapshot(Player("test", "设备测试", "打印机"), QueryRange.at(Period.CAREER),
            "协议测试图", now, "576 × 96 原始测试图", "测试", true,
            listOf(StatsBlock("test", "设备测试", listOf(Metric("测试图", "576 × 96")))), false)
        val doc = ReceiptDocument(UUID.randomUUID().toString(), now, snapshot, listOf("test"), ReceiptFont.MEDIUM)
        print(ReceiptArtifact(doc, raster), settings)
    }
    override suspend fun stop() = operations.withLock {
        printJob?.cancelAndJoin(); printJob = null
        val connected = session ?: return@withLock
        try { connected.stop() } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            mutable.update { it.copy(status = PrintStatus.ERROR, detail = "停止回执未收到，连接已关闭，请检查纸面") }
            closeConnection()
        }
    }
    override suspend fun disconnect() = operations.withLock {
        val wasActive = mutable.value.status.active || mutable.value.status == PrintStatus.ERROR
        printJob?.cancelAndJoin(); printJob = null
        try {
            if (wasActive) withTimeout(2_000) { session?.stop() }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (_: Exception) { /* Closing is still required after a failed stop reply. */ }
        finally {
            closeConnection()
            queueIndex = 0; queueSize = 0
            mutable.value = PrintState()
        }
    }
    private fun closeConnection() { observation?.cancel(); observation = null; session?.close(); session = null }
    override suspend fun simulatePaperOut() = Unit
    override suspend fun resume() { error("中途续传尚未验证，请停止后从预览重新打印") }

    private suspend fun connectSocket(socket: BluetoothSocket): Unit = suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { runCatching { socket.close() } }
        scope.launch(Dispatchers.IO) {
            try {
                socket.connect()
                result.resume(Unit, onCancellation = { _, _, _ -> socket.close() })
            } catch (_: IOException) {
                if (result.isActive) result.resumeWithException(IOException("RFCOMM 连接失败"))
            }
        }
    }
}

private class SocketPrinterLink(
    private val socket: BluetoothSocket, private val scope: CoroutineScope, private val trace: PrinterTrace,
) : PrinterLink {
    private val incoming = Channel<ByteArray>(128)
    override val frames = incoming.receiveAsFlow()
    private val writing = Mutex()
    private val reader = scope.launch(Dispatchers.IO) {
        try {
            val decoder = Codec.Decoder()
            val buffer = ByteArray(4096)
            while (isActive) {
                val count = socket.inputStream.read(buffer)
                if (count < 0) break
                val bytes = buffer.copyOf(count)
                trace.bytes("RX", bytes)
                decoder.accept(bytes).forEach { incoming.send(it) }
            }
            incoming.close(IOException("蓝牙连接已关闭"))
        } catch (e: Exception) { incoming.close(e) }
    }
    override suspend fun write(frame: ByteArray) = writing.withLock {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            scope.launch(Dispatchers.IO) {
                try {
                    trace.bytes("TX", frame)
                    socket.outputStream.write(frame)
                    socket.outputStream.flush()
                    continuation.resume(Unit)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("蓝牙发送中断"))
                }
            }
        }
    }
    override fun close() { runCatching { socket.close() }; reader.cancel(); incoming.cancel() }
}

/** Private, bounded transport evidence. It never receives any authentication data or URLs. */
private class PrinterTrace(private val directory: File) {
    private val lock = Any()
    private var previous = ""
    fun bytes(direction: String, bytes: ByteArray) = record(direction, bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) })
    fun record(kind: String, message: String) = synchronized(lock) {
        if (kind == "STATE" && previous == message) return@synchronized
        if (kind == "STATE") previous = message
        runCatching {
            directory.mkdirs()
            val file = File(directory, "transport.log")
            if (file.length() > 8 * 1024 * 1024) {
                File(directory, "transport.previous.log").delete()
                file.renameTo(File(directory, "transport.previous.log"))
            }
            file.appendText("${Instant.now()} $kind $message\n")
        }
    }
}
