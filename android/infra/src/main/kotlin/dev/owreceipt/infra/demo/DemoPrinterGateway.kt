package dev.owreceipt.infra.demo

import dev.owreceipt.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Does not open sockets or write to hardware. All progress is explicitly simulated. */
class DemoPrinterGateway(
    private val scope: CoroutineScope,
    private val stepMillis: Long = 120,
) : PrinterGateway {
    private val mutable = MutableStateFlow(PrintState())
    override val state = mutable.asStateFlow()
    override val devices = MutableStateFlow(listOf(PrinterDevice("demo", "9516（演示）"))).asStateFlow()
    override suspend fun refreshDevices(scan: Boolean) = Unit
    private val mutex = Mutex()
    private var job: Job? = null
    override suspend fun connect(deviceId: String?, strategy: ConnectionStrategy) = mutex.withLock {
        if (mutable.value.status == PrintStatus.DISCONNECTED) {
            delay(200)
            mutable.value = PrintState(PrintStatus.READY)
        }
    }
    override suspend fun disconnect() = mutex.withLock {
        job?.cancelAndJoin()
        mutable.value = PrintState()
    }
    override suspend fun print(artifact: ReceiptArtifact, settings: PrintSettings) = mutex.withLock {
        check(mutable.value.status in setOf(PrintStatus.READY, PrintStatus.STOPPED, PrintStatus.COMPLETED)) {
            "请先连接演示设备，并结束当前任务"
        }
        job?.cancelAndJoin()
        mutable.value = PrintState(PrintStatus.SENDING, 0f, artifact.document.id)
        job = scope.launch {
            var sent = 0
            while (sent < 100) {
                delay(stepMillis)
                mutex.withLock {
                    if (mutable.value.status == PrintStatus.SENDING) {
                        sent++
                        mutable.value = mutable.value.copy(
                            progress = sent / 100f,
                            status = if (sent == 100) PrintStatus.COMPLETED else PrintStatus.SENDING,
                        )
                    }
                }
            }
        }
    }
    override suspend fun stop() = mutex.withLock {
        if (mutable.value.status in setOf(PrintStatus.SENDING, PrintStatus.PAPER_OUT)) {
            job?.cancelAndJoin()
            mutable.value = mutable.value.copy(status = PrintStatus.STOPPED)
        }
    }
    override suspend fun simulatePaperOut() = mutex.withLock {
        if (mutable.value.status == PrintStatus.SENDING)
            mutable.value = mutable.value.copy(status = PrintStatus.PAPER_OUT)
    }
    override suspend fun resume() = mutex.withLock {
        if (mutable.value.status == PrintStatus.PAPER_OUT)
            mutable.value = mutable.value.copy(status = PrintStatus.SENDING)
    }
}
