package dev.owreceipt.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import personal.wp9516.protocol.Wp9516Protocol as Codec

/** One framed receive stream and one serialized writer per connection. */
interface PrinterLink {
    val frames: Flow<ByteArray>
    suspend fun write(frame: ByteArray)
    fun close()
}

data class PrinterTiming(
    val connectSettleMs: Long = 200,
    val stopSettleMs: Long = 300,
    val rowMs: Long = 15,
    val bufferPollMs: Long = 300,
    val bufferTimeoutMs: Long = 20_000,
    val responseTimeoutMs: Long = 5_000,
    val drainSettleMs: Long = 2_000,
    val idleTimeoutMs: Long = 60_000,
)

class PrinterProtocolException(message: String) : Exception(message)

/** Protocol policy is independent of Bluetooth and Compose, so captured packets can exercise it. */
class Wp9516Session(
    private val link: PrinterLink,
    scope: CoroutineScope,
    private val timing: PrinterTiming = PrinterTiming(),
) {
    private val mutable = MutableStateFlow(PrintState(status = PrintStatus.READING_INFO))
    val state = mutable.asStateFlow()
    private val commands = Mutex()
    private data class Reply(val command: Int, val result: CompletableDeferred<ByteArray>)
    @Volatile private var reply: Reply? = null
    @Volatile private var protocolFault: String? = null
    private val receiver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            link.frames.collect { payload ->
                if (payload.isEmpty()) return@collect
                val command = payload[0].toInt() and 255
                if (command == 1) {
                    val info = parseInfo(payload, mutable.value.info)
                    mutable.update { current ->
                        current.copy(info = info, status = if (info.paperOut && current.status.active)
                            PrintStatus.PAPER_OUT else current.status)
                    }
                }
                if (command in setOf(0xa4, 0xa7, 0xa8)) {
                    protocolFault = "打印机返回未验证的流控状态 0x${command.toString(16).uppercase()}，请停止并重新连接"
                    reply?.result?.completeExceptionally(PrinterProtocolException(protocolFault!!))
                    mutable.update { it.copy(status = PrintStatus.ERROR, detail = protocolFault) }
                } else if (reply?.command == command) reply?.result?.complete(payload)
            }
            throw PrinterProtocolException("蓝牙连接已关闭")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            protocolFault = if (e is PrinterProtocolException) e.message else "蓝牙接收中断，请重新连接"
            reply?.result?.completeExceptionally(PrinterProtocolException(protocolFault!!))
            mutable.update { it.copy(status = PrintStatus.ERROR, detail = protocolFault) }
        }
    }

    suspend fun initialize(): PrinterInfo {
        delay(timing.connectSettleMs)
        val info = queryInfo()
        mutable.update { it.copy(status = PrintStatus.READY, info = info, detail = null) }
        return info
    }

    suspend fun queryInfo(): PrinterInfo {
        val raw = request(Codec.queryInfo(), 1)
        return parseInfo(raw, mutable.value.info).also { info -> mutable.update { it.copy(info = info) } }
    }

    suspend fun print(raster: RenderedReceipt, receiptId: String, settings: PrintSettings) {
        val info = mutable.value.info ?: throw PrinterProtocolException("请先读取设备信息")
        require(raster.width == info.widthDots) { "小票点宽与设备不一致，请按设备点宽重新生成预览" }
        require(info.transportType == 1 && info.firmware == 0x32) { "当前仅验证 WP9516 type 1 / firmware 50 的发送时序" }
        val rowsPerCopy = raster.height + settings.trailingFeedRows
        val total = rowsPerCopy.toLong() * settings.copies
        require(total in 1..65535) { "小票与份数超出设备单任务行数，请减少内容或份数" }
        mutable.update { it.copy(status = PrintStatus.PREPARING, progress = 0f, receiptId = receiptId, detail = null) }
        try {
            request(Codec.queryReady(), 0xac)
            stopCommand()
            delay(timing.stopSettleMs)
            ensurePrintable(queryInfo())
            var token = 0
            var row = 0
            suspend fun setup() {
                withTimeout(timing.bufferTimeoutMs) {
                    while (true) {
                        checkFault()
                        val ack = request(Codec.setup(total.toInt(), row, token, 1, settings.density.wireValue), 0xa5)
                        if (ack.size < 6) throw PrinterProtocolException("A5 回复长度不足")
                        val acceptedRow = Codec.u16(ack, 3)
                        if (acceptedRow != row) throw PrinterProtocolException("反馈行号 $acceptedRow 与发送位置 $row 不一致；续传策略尚未验证，请停止")
                        token = ack[5].toInt() and 255
                        if (Codec.u16(ack, 1) > 0) return@withTimeout
                        mutable.update { it.copy(status = PrintStatus.WAITING_BUFFER, detail = "等待打印机缓冲") }
                        delay(timing.bufferPollMs)
                    }
                }
            }
            setup()
            setup() // Reproduce the observed initial repeated setup before the first raster.
            val bytesPerRow = raster.width / 8
            val white = ByteArray(bytesPerRow)
            while (row < total) {
                currentCoroutineContext().ensureActive()
                checkFault()
                val currentInfo = mutable.value.info ?: info
                if (currentInfo.paperOut) {
                    mutable.update { it.copy(status = PrintStatus.PAPER_OUT,
                        detail = "已暂停发送。补纸后请停止任务，再从预览重新打印；中途续传尚未验证") }
                    while (true) delay(100) // Only explicit stop/disconnect releases a paper fault.
                }
                ensurePrintable(currentInfo.copy(busy = false))
                mutable.update { it.copy(status = PrintStatus.SENDING, detail = null) }
                val sourceRow = row % rowsPerCopy
                val packed = if (sourceRow < raster.height)
                    raster.packedRows.copyOfRange(sourceRow * bytesPerRow, (sourceRow + 1) * bytesPerRow) else white
                link.write(Codec.raster(row, raster.width, 1, packed))
                row++
                mutable.update { it.copy(progress = row.toFloat() / total.toInt()) }
                delay(timing.rowMs)
                if (row % 30 == 0 && row < total) setup()
            }
            mutable.update { it.copy(status = PrintStatus.WAITING_IDLE, detail = "数据已发送，等待打印机确认空闲") }
            delay(timing.drainSettleMs)
            withTimeout(timing.idleTimeoutMs) {
                while (true) {
                    checkFault()
                    val final = queryInfo()
                    if (final.paperOut) throw PrinterProtocolException("结束确认时缺纸，纸面结果需要检查")
                    if (final.lowPower || final.overheated || final.lidOpen) ensurePrintable(final)
                    if (!final.busy) break
                    delay(timing.bufferPollMs)
                }
            }
            mutable.update { it.copy(status = PrintStatus.COMPLETED, progress = 1f, detail = "打印机已确认空闲，请检查纸面结果") }
        } catch (e: TimeoutCancellationException) {
            mutable.update { it.copy(status = PrintStatus.ERROR, detail = "打印机回复超时，请停止并重新连接") }
            throw PrinterProtocolException("打印机回复超时，请停止并重新连接")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            mutable.update { it.copy(status = PrintStatus.ERROR,
                detail = e.message ?: "打印中断") }
            throw e
        }
    }

    /** Caller must cancel and join the raster producer before entering here. */
    suspend fun stop() {
        mutable.update { it.copy(status = PrintStatus.STOPPING) }
        stopCommand()
        protocolFault = null
        mutable.update { it.copy(status = PrintStatus.STOPPED, detail = "已收到停止回执") }
    }

    fun close() {
        receiver.cancel()
        reply?.result?.cancel()
        link.close()
    }

    private suspend fun stopCommand() {
        val ack = request(Codec.stop(), 4)
        if (ack.size < 2 || ack[1].toInt() != 0) throw PrinterProtocolException("停止回执异常")
    }
    private suspend fun request(frame: ByteArray, command: Int): ByteArray = commands.withLock {
        val pending = Reply(command, CompletableDeferred())
        reply = pending
        try {
            withTimeout(timing.responseTimeoutMs) { link.write(frame); pending.result.await() }
        } finally { if (reply === pending) reply = null }
    }
    private fun checkFault() { protocolFault?.let { throw PrinterProtocolException(it) } }
    private fun ensurePrintable(info: PrinterInfo) {
        val reason = when {
            info.paperOut -> "打印机缺纸，请补纸后重新开始"
            info.lidOpen -> "打印机纸仓未关闭"
            info.overheated -> "打印机过热，请冷却后重试"
            info.lowPower -> "打印机电量不足"
            info.busy -> "打印机正在忙碌，请稍后重试"
            else -> null
        }
        if (reason != null) throw PrinterProtocolException(reason)
    }
    companion object {
        fun parseInfo(payload: ByteArray, prior: PrinterInfo? = null): PrinterInfo {
            if (payload.size < 10 || payload[0].toInt() != 1) throw PrinterProtocolException("设备信息回复不完整")
            val width = Codec.u16(payload, 3)
            if (width !in 128..2008 || width % 8 != 0) throw PrinterProtocolException("设备点宽异常或超出单行协议容量")
            return PrinterInfo(width, payload[5].toInt() != 0, payload[6].toInt() == 1,
                payload[7].toInt() == 1, payload[8].toInt() == 1, payload[9].toInt() == 1,
                payload.getOrNull(10)?.let { it.toInt() and 255 } ?: prior?.transportType,
                payload.getOrNull(11)?.let { it.toInt() and 255 } ?: prior?.firmware)
        }
    }
}
