package dev.owreceipt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Caller session is separate from queried player identity; real credentials stay in the adapter. */
interface AccountSession {
    val isDemo: Boolean get() = false
    val state: StateFlow<SessionState>
    suspend fun signIn()
    suspend fun readRoles() = signIn()
    suspend fun selectRole(id: String)
    suspend fun expire()
    suspend fun signOut()
}
interface StatsRepository {
    fun capabilities(scenario: DemoScenario): QueryCapabilities
    suspend fun query(player: Player, range: QueryRange, scenario: DemoScenario,
        modes: Set<MatchMode> = MatchMode.entries.toSet()): StatsSnapshot
}
interface ReceiptRepository {
    val receipts: Flow<List<SavedReceipt>>
    suspend fun save(artifact: ReceiptArtifact)
    suspend fun open(id: String): ReceiptArtifact
    suspend fun delete(id: String)
}
interface ReceiptRenderer { suspend fun render(document: ReceiptDocument): RenderedReceipt }
interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun update(preferences: UserPreferences)
}
interface PrinterGateway {
    val state: StateFlow<PrintState>
    val devices: StateFlow<List<PrinterDevice>>
    suspend fun refreshDevices(scan: Boolean = false)
    suspend fun connect(deviceId: String? = null, strategy: ConnectionStrategy = ConnectionStrategy.AUTO)
    suspend fun disconnect()
    suspend fun print(artifact: ReceiptArtifact, settings: PrintSettings = PrintSettings())
    suspend fun printTest(settings: PrintSettings = PrintSettings()) { error("当前设备不支持测试图") }
    suspend fun printBatch(artifacts: List<ReceiptArtifact>, settings: PrintSettings = PrintSettings()) {
        require(artifacts.size == 1) { "当前设备只支持单张打印" }
        print(artifacts.single(), settings)
    }
    suspend fun stop()
    suspend fun simulatePaperOut()
    suspend fun resume()
}
