package dev.owreceipt.ui

import dev.owreceipt.core.*

enum class HomeTab(val label: String) { MAKE("制作"), ARCHIVE("纪念册"), DEVICE("设备") }
enum class Screen { HOME, ACCOUNT, PREVIEW }
enum class QueryStatus { LOADING, CONTENT, PROBLEM }
data class PreviewState(val artifact: ReceiptArtifact, val saved: Boolean = false)
data class AppUiState(
    val isDemo: Boolean = true,
    val tab: HomeTab = HomeTab.MAKE,
    val screen: Screen = Screen.HOME,
    val session: SessionState = SessionState(),
    val period: Period = Period.CAREER,
    val receiptKind: ReceiptKind = ReceiptKind.SUMMARY,
    val modes: Set<MatchMode> = MatchMode.entries.toSet(),
    val selectedMatches: Set<String> = emptySet(),
    val scenario: DemoScenario = DemoScenario.NORMAL,
    val font: ReceiptFont = ReceiptFont.MEDIUM,
    val theme: AppTheme = AppTheme.SYSTEM,
    val capabilities: QueryCapabilities = QueryCapabilities(Period.entries.toSet()),
    val queryStatus: QueryStatus = QueryStatus.LOADING,
    val snapshot: StatsSnapshot? = null,
    val selectedBlocks: Set<String> = emptySet(),
    val problem: StatsProblem? = null,
    val queryMessage: String = "",
    val rendering: Boolean = false,
    val saving: Boolean = false,
    val preview: PreviewState? = null,
    val previewBatch: List<PreviewState> = emptyList(),
    val previewIndex: Int = 0,
    val receipts: List<SavedReceipt> = emptyList(),
    val archiveLoading: Boolean = true,
    val archiveError: String? = null,
    val printer: PrintState = PrintState(),
    val devices: List<PrinterDevice> = emptyList(),
    val selectedDevice: String? = null,
    val connectionStrategy: ConnectionStrategy = ConnectionStrategy.AUTO,
    val deviceWorking: Boolean = false,
    val authOrigin: String = "网易官方页面",
    val authLoading: Boolean = false,
    val authError: String? = null,
    val authStatus: String? = null,
    val authWidePage: Boolean = false,
    val showScenarios: Boolean = false,
    val message: String? = null,
)
sealed interface UiAction {
    data class Tab(val tab: HomeTab) : UiAction
    data class ChoosePeriod(val period: Period) : UiAction
    data class ChooseReceiptKind(val kind: ReceiptKind) : UiAction
    data class ChooseModes(val modes: Set<MatchMode>) : UiAction
    data class ToggleMatch(val id: String) : UiAction
    data class PreviewIndex(val index: Int) : UiAction
    data class ToggleBlock(val id: String) : UiAction
    data class ChooseFont(val font: ReceiptFont) : UiAction
    data class ChooseTheme(val theme: AppTheme) : UiAction
    data class ChooseScenario(val scenario: DemoScenario) : UiAction
    data class SelectRole(val id: String) : UiAction
    data class OpenSaved(val id: String) : UiAction
    data class DeleteSaved(val id: String) : UiAction
    data class ChooseDevice(val id: String) : UiAction
    data class ChooseConnection(val strategy: ConnectionStrategy) : UiAction
    data object OpenAccount : UiAction
    data object Back : UiAction
    data object SignIn : UiAction
    data object SignOut : UiAction
    data object ReadBoundRoles : UiAction
    data object ToggleAuthWidePage : UiAction
    data object RetryAuthPage : UiAction
    data object OpenOfficialAccount : UiAction
    data object Retry : UiAction
    data object Preview : UiAction
    data object Save : UiAction
    data object SaveBatch : UiAction
    data object Export : UiAction
    data object Print : UiAction
    data object TestPrint : UiAction
    data object ReturnPreview : UiAction
    data object RefreshDevices : UiAction
    data object ScanDevices : UiAction
    data object Connect : UiAction
    data object Disconnect : UiAction
    data object Stop : UiAction
    data object PaperOut : UiAction
    data object Resume : UiAction
    data object ShowScenarios : UiAction
    data object DismissScenarios : UiAction
    data object DismissMessage : UiAction
}
