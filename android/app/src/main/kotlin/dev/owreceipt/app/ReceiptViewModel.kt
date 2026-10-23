package dev.owreceipt.app

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import dev.owreceipt.core.*
import dev.owreceipt.infra.storage.ReceiptDocumentCodec
import dev.owreceipt.infra.auth.OfficialWebSession
import dev.owreceipt.ui.*
import java.io.OutputStream
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ReceiptViewModel(
    private val accounts: AccountSession,
    private val stats: StatsRepository,
    private val receipts: ReceiptRepository,
    private val preferences: PreferencesRepository,
    private val renderer: ReceiptRenderer,
    private val printer: PrinterGateway,
    private val saved: SavedStateHandle,
    private val clock: Clock = Clock.systemUTC(),
    private val web: OfficialWebSession? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(AppUiState(
        isDemo = accounts.isDemo,
        period = if (accounts.isDemo) Period.CAREER else Period.TODAY,
        receiptKind = enumOr(saved["receiptKind"], ReceiptKind.SUMMARY),
        modes = saved.get<ArrayList<String>>("modes")?.mapNotNull { name -> MatchMode.entries.find { it.name == name } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: MatchMode.entries.toSet(),
        tab = enumOr(saved["tab"], HomeTab.MAKE),
        session = accounts.state.value,
        authWidePage = saved.get<Boolean>("authWidePage") ?: false,
    ))
    val state: StateFlow<AppUiState> = mutable.asStateFlow()
    private var queryJob: Job? = null
    private var renderJob: Job? = null
    private var printJob: Job? = null
    private var pendingExport: ReceiptArtifact? = null
    private var preferencesLoaded = false
    private var automaticAuthenticationShown = false

    init {
        viewModelScope.launch {
            accounts.state.collect { session ->
                val before = mutable.value.session
                mutable.update { it.copy(session = session) }
                if (!accounts.isDemo && preferencesLoaded && session.status == SessionStatus.READY &&
                    before.status != SessionStatus.REFRESHING &&
                    (before.status != SessionStatus.READY || before.activePlayer != session.activePlayer)) {
                    if (state.value.screen == Screen.ACCOUNT) go(Screen.HOME)
                    reload()
                }
            }
        }
        viewModelScope.launch { printer.state.collect { print ->
            mutable.update { it.copy(printer = print, selectedDevice = it.selectedDevice ?: print.device?.id) }
        } }
        viewModelScope.launch { printer.devices.collect { devices ->
            val supported = devices.filter { it.isWp9516 }
            mutable.update { current -> current.copy(devices = supported,
                selectedDevice = current.selectedDevice?.takeIf { id -> supported.any { it.id == id } }) }
        } }
        if (web != null) viewModelScope.launch { web.page.collect { page ->
            mutable.update { it.copy(authOrigin = page.origin, authLoading = page.loading, authError = page.error, authStatus = page.status) }
        } }
        viewModelScope.launch {
            receipts.receipts.catch { e -> mutable.update { it.copy(archiveLoading = false, archiveError = e.message ?: "读取失败") } }
                .collect { list -> mutable.update { it.copy(receipts = list, archiveLoading = false, archiveError = null) } }
        }
        viewModelScope.launch {
            val prefs = preferences.preferences.first()
            val scenario = enumOr(saved["scenario"], prefs.scenario)
            val period = enumOr(saved["period"], if (accounts.isDemo) prefs.period else Period.TODAY)
            val capability = stats.capabilities(scenario)
            mutable.update { it.copy(
                scenario = scenario, period = if (period in capability.periods) period else Period.CAREER,
                font = enumOr(saved["font"], prefs.font).forNewReceipt(), theme = prefs.theme, capabilities = capability,
            ) }
            if (accounts.isDemo) saved.get<String>("role")?.let { accounts.selectRole(it) }
            when (if (accounts.isDemo) saved.get<String>("session") else null) {
                SessionStatus.SIGNED_OUT.name -> accounts.signOut()
                SessionStatus.EXPIRED.name -> accounts.expire()
            }
            preferencesLoaded = true
            reload()
            val priorScreen: Screen = enumOr(saved["screen"], Screen.HOME)
            if (priorScreen == Screen.ACCOUNT) mutable.update { it.copy(screen = Screen.ACCOUNT) }
            if (priorScreen == Screen.PREVIEW) {
                val batch = saved.get<ArrayList<String>>("preview.batch")
                if (!batch.isNullOrEmpty()) {
                    try {
                        val savedIds = saved.get<ArrayList<String>>("preview.savedIds").orEmpty().toSet()
                        val previews = batch.map { encoded ->
                            val original = ReceiptDocumentCodec.decode(encoded)
                            val wasSaved = original.id in savedIds
                            val doc = if (wasSaved) original else original.copy(font = original.font.forNewReceipt())
                            PreviewState(if (wasSaved) receipts.open(doc.id) else ReceiptArtifact(doc, renderer.render(doc)), wasSaved)
                        }
                        installBatch(previews, (saved.get<Int>("preview.index") ?: 0).coerceIn(previews.indices))
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { go(Screen.HOME); message("上次的预览未能恢复，请重新生成") }
                    return@launch
                }
                val document = saved.get<String>("preview.document")
                if (document != null) {
                    val wasSaved = saved.get<Boolean>("preview.saved") ?: false
                    try {
                        val original = ReceiptDocumentCodec.decode(document)
                        val doc = if (wasSaved) original else original.copy(font = original.font.forNewReceipt())
                        val artifact = if (wasSaved) receipts.open(doc.id) else ReceiptArtifact(doc, renderer.render(doc))
                        mutable.update { it.copy(screen = Screen.PREVIEW, preview = PreviewState(artifact, wasSaved)) }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        go(Screen.HOME)
                        message("上次的预览未能恢复，请重新生成")
                    }
                }
            }
        }
    }

    fun dispatch(action: UiAction) {
        when (action) {
            is UiAction.Tab -> {
                renderJob?.cancel(); mutable.update { it.copy(tab = action.tab, screen = Screen.HOME, rendering = false) }
                saved["tab"] = action.tab.name; saved["screen"] = Screen.HOME.name
            }
            is UiAction.ChoosePeriod -> if (action.period in state.value.capabilities.periods) {
                saved["period"] = action.period.name
                mutable.update { it.copy(period = action.period) }; persistPreferences(); reload()
            }
            is UiAction.ChooseReceiptKind -> {
                mutable.update { it.copy(receiptKind = action.kind) }; saved["receiptKind"] = action.kind.name
            }
            is UiAction.ChooseModes -> if (action.modes.isNotEmpty()) {
                mutable.update { it.copy(modes = action.modes) }
                saved["modes"] = ArrayList(action.modes.map { it.name }); reload()
            }
            is UiAction.ToggleMatch -> {
                if (state.value.snapshot?.matches?.none { it.id == action.id } != false) return
                if (action.id !in state.value.selectedMatches && state.value.selectedMatches.size >= 50) {
                    message("每批最多选择 50 场"); return
                }
                mutable.update { it.copy(selectedMatches = if (action.id in it.selectedMatches)
                    it.selectedMatches - action.id else it.selectedMatches + action.id) }
                saved["selectedMatches"] = ArrayList(state.value.selectedMatches)
            }
            is UiAction.PreviewIndex -> if (action.index in state.value.previewBatch.indices) {
                mutable.update { it.copy(previewIndex = action.index, preview = it.previewBatch[action.index]) }
                saved["preview.index"] = action.index
            }
            is UiAction.ToggleBlock -> {
                if (state.value.snapshot?.blocks?.any { it.id == action.id && it.available } != true) return
                mutable.update {
                    it.copy(selectedBlocks = if (action.id in it.selectedBlocks) it.selectedBlocks - action.id else it.selectedBlocks + action.id)
                }
                saveSelection()
            }
            is UiAction.ChooseFont -> {
                val font = action.font.forNewReceipt()
                saved["font"] = font.name
                mutable.update { it.copy(font = font) }; persistPreferences()
                val preview = state.value.preview
                if (state.value.screen == Screen.PREVIEW && preview != null && !preview.saved && !state.value.saving) {
                    renderBatch(state.value.previewBatch.ifEmpty { listOf(preview) }.map {
                        it.artifact.document.copy(id = UUID.randomUUID().toString(), font = font, createdAt = clock.millis())
                    })
                }
            }
            is UiAction.ChooseTheme -> { mutable.update { it.copy(theme = action.theme) }; persistPreferences() }
            is UiAction.ChooseScenario -> chooseScenario(action.scenario)
            is UiAction.SelectRole -> viewModelScope.launch {
                accounts.selectRole(action.id); saved["role"] = action.id; saved["session"] = SessionStatus.READY.name
                if (state.value.scenario == DemoScenario.EXPIRED) {
                    mutable.update { it.copy(scenario = DemoScenario.NORMAL, capabilities = stats.capabilities(DemoScenario.NORMAL)) }
                    saved["scenario"] = DemoScenario.NORMAL.name; persistPreferences()
                }
                if (accounts.isDemo) { go(Screen.HOME); reload() }
            }
            is UiAction.OpenSaved -> viewModelScope.launch {
                mutable.update { it.copy(rendering = true) }
                try {
                    val artifact = receipts.open(action.id)
                    setPreview(PreviewState(artifact, true))
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { message(e.message ?: "无法打开小票")
                } finally { mutable.update { it.copy(rendering = false) } }
            }
            is UiAction.DeleteSaved -> viewModelScope.launch {
                try { receipts.delete(action.id); message("小票已从本机纪念册删除")
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { message("删除未完成，请重试") }
            }
            UiAction.OpenAccount -> go(Screen.ACCOUNT)
            UiAction.Back -> {
                renderJob?.cancel()
                mutable.update { it.copy(rendering = false) }; go(Screen.HOME)
            }
            UiAction.SignIn -> viewModelScope.launch {
                automaticAuthenticationShown = false
                queryJob?.cancel()
                mutable.update { it.copy(snapshot = null) }
                accounts.signIn()
            }
            UiAction.SignOut -> viewModelScope.launch {
                queryJob?.cancel(); accounts.signOut(); saved["session"] = SessionStatus.SIGNED_OUT.name
                mutable.update { it.copy(snapshot = null, selectedBlocks = emptySet(), selectedMatches = emptySet(),
                    preview = null, previewBatch = emptyList()) }
                saved.remove<String>("preview.document"); saved.remove<Boolean>("preview.saved"); saved.remove<String>("role")
                saved.remove<ArrayList<String>>("preview.batch"); saved.remove<ArrayList<String>>("selectedMatches")
                reload()
            }
            UiAction.RetryAuthPage -> viewModelScope.launch { web?.retry() }
            UiAction.ReadBoundRoles -> viewModelScope.launch { accounts.readRoles() }
            UiAction.ToggleAuthWidePage -> {
                mutable.update { it.copy(authWidePage = !it.authWidePage) }
                saved["authWidePage"] = state.value.authWidePage
            }
            UiAction.OpenOfficialAccount -> viewModelScope.launch { web?.showOfficialAccount() }
            UiAction.Retry -> reload()
            UiAction.Preview -> {
                val current = state.value
                val snapshot = current.snapshot ?: return
                if (!accounts.isDemo && current.period != Period.CAREER && current.receiptKind == ReceiptKind.MATCH) {
                    val selected = snapshot.matches.filter { it.id in current.selectedMatches }
                    if (selected.isEmpty() || current.rendering) return
                    renderBatch(selected.map { match ->
                        val single = MatchReceiptComposer.single(snapshot, match)
                        ReceiptDocument(UUID.randomUUID().toString(), clock.millis(), single,
                            single.blocks.map { it.id }, current.font,
                            profile = current.printer.info?.profile ?: PrinterProfile(), kind = ReceiptKind.MATCH)
                    })
                    return
                }
                if (current.queryStatus != QueryStatus.CONTENT || current.selectedBlocks.isEmpty() || current.rendering) return
                val document = ReceiptDocument(
                    UUID.randomUUID().toString(), clock.millis(), snapshot,
                    snapshot.blocks.filter { it.id in current.selectedBlocks && it.available }.map { it.id }, current.font,
                    profile = current.printer.info?.profile ?: PrinterProfile(),
                    kind = if (accounts.isDemo || current.period == Period.CAREER) ReceiptKind.OVERVIEW else ReceiptKind.SUMMARY,
                )
                render(document)
            }
            UiAction.Save -> saveReceipt()
            UiAction.SaveBatch -> saveBatch()
            UiAction.Print -> printReceipt()
            UiAction.Connect -> printerAction { printer.connect(state.value.selectedDevice, state.value.connectionStrategy) }
            UiAction.RefreshDevices -> printerAction { printer.refreshDevices() }
            UiAction.ScanDevices -> printerAction { printer.refreshDevices(true) }
            is UiAction.ChooseDevice -> if (state.value.devices.any { it.id == action.id && it.isWp9516 })
                mutable.update { it.copy(selectedDevice = action.id) }
            is UiAction.ChooseConnection -> mutable.update { it.copy(connectionStrategy = action.strategy) }
            UiAction.TestPrint -> printerAction { printer.printTest() }
            UiAction.ReturnPreview -> if (state.value.preview != null) go(Screen.PREVIEW)
            UiAction.Disconnect -> printerAction { printer.disconnect() }
            UiAction.Stop -> printerAction { printer.stop() }
            UiAction.PaperOut -> printerAction { printer.simulatePaperOut() }
            UiAction.Resume -> printerAction { printer.resume() }
            UiAction.ShowScenarios -> mutable.update { it.copy(showScenarios = true) }
            UiAction.DismissScenarios -> mutable.update { it.copy(showScenarios = false) }
            UiAction.DismissMessage -> mutable.update { it.copy(message = null) }
            UiAction.Export -> Unit // Android document picker is owned by the Activity.
        }
    }

    private fun chooseScenario(scenario: DemoScenario) {
        val capability = stats.capabilities(scenario)
        val period = if (state.value.period in capability.periods) state.value.period else Period.CAREER
        if (period != state.value.period) message("当前场景仅提供生涯统计，已切换到生涯")
        saved["scenario"] = scenario.name; saved["period"] = period.name
        mutable.update { it.copy(scenario = scenario, period = period, capabilities = capability, showScenarios = false) }
        persistPreferences(); reload()
    }
    private fun reload() {
        queryJob?.cancel()
        val account = accounts.state.value
        val player = account.activePlayer
        val current = state.value
        if (account.status != SessionStatus.READY || player == null) {
            mutable.update { it.copy(queryStatus = QueryStatus.PROBLEM, problem = StatsProblem.AUTH_REQUIRED,
                queryMessage = if (accounts.isDemo) "选择或重新认证示例账号后继续" else "在应用内完成网易官方认证后继续", snapshot = null) }
            return
        }
        mutable.update { it.copy(queryStatus = QueryStatus.LOADING, snapshot = null, problem = null) }
        queryJob = viewModelScope.launch {
            try {
                val result = stats.query(player, QueryRange.at(current.period, clock), current.scenario, current.modes)
                val allowed = result.blocks.filter { it.available }.map { it.id }.toSet()
                val knownBlocks = saved.get<ArrayList<String>>("knownBlocks")?.toSet() ?: setOf("summary", "combat")
                val selection = saved.get<ArrayList<String>>("selected")?.toSet()?.intersect(allowed)?.let { selected ->
                    if (!accounts.isDemo && current.period != Period.CAREER) selected + (allowed - knownBlocks) else selected
                } ?: allowed
                saved["knownBlocks"] = ArrayList(allowed)
                val matchSelection = saved.get<ArrayList<String>>("selectedMatches")?.toSet().orEmpty()
                    .intersect(result.matches.map { it.id }.toSet())
                mutable.update { it.copy(queryStatus = QueryStatus.CONTENT, snapshot = result, selectedBlocks = selection,
                    selectedMatches = matchSelection, problem = null) }
                automaticAuthenticationShown = false
                saveSelection()
            } catch (e: CancellationException) { throw e
            } catch (e: StatsException) {
                if (e.problem == StatsProblem.AUTH_REQUIRED) {
                    accounts.expire(); saved["session"] = SessionStatus.EXPIRED.name
                }
                mutable.update { it.copy(queryStatus = QueryStatus.PROBLEM, problem = e.problem, queryMessage = e.message.orEmpty(), snapshot = null) }
                if (e.problem == StatsProblem.AUTH_REQUIRED && !accounts.isDemo && !automaticAuthenticationShown) {
                    automaticAuthenticationShown = true
                    go(Screen.ACCOUNT)
                    accounts.signIn()
                }
            } catch (_: Exception) {
                mutable.update { it.copy(queryStatus = QueryStatus.PROBLEM, problem = StatsProblem.NETWORK,
                    queryMessage = "暂时无法取得数据，请重试", snapshot = null) }
            }
        }
    }
    private fun render(document: ReceiptDocument) {
        renderJob?.cancel()
        mutable.update { it.copy(rendering = true) }
        renderJob = viewModelScope.launch {
            try { setPreview(PreviewState(ReceiptArtifact(document, renderer.render(document))))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message(e.message ?: "小票生成失败，请重试")
            } finally { mutable.update { it.copy(rendering = false) } }
        }
    }
    private fun setPreview(preview: PreviewState) {
        installBatch(listOf(preview))
    }
    private fun installBatch(batch: List<PreviewState>, index: Int = 0) {
        val preview = batch[index]
        saved["preview.document"] = ReceiptDocumentCodec.encode(preview.artifact.document)
        saved["preview.saved"] = preview.saved
        saved["preview.batch"] = ArrayList(batch.map { ReceiptDocumentCodec.encode(it.artifact.document) })
        saved["preview.savedIds"] = ArrayList(batch.filter { it.saved }.map { it.artifact.document.id })
        saved["preview.index"] = index
        mutable.update { it.copy(preview = preview, previewBatch = batch, previewIndex = index, screen = Screen.PREVIEW) }
        saved["screen"] = Screen.PREVIEW.name
    }
    private fun renderBatch(documents: List<ReceiptDocument>) {
        renderJob?.cancel()
        mutable.update { it.copy(rendering = true) }
        renderJob = viewModelScope.launch {
            try { installBatch(documents.map { PreviewState(ReceiptArtifact(it, renderer.render(it))) }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: "生成小票未完成") }
            finally { mutable.update { it.copy(rendering = false) } }
        }
    }
    private fun saveReceipt() {
        val preview = state.value.preview ?: return
        if (preview.saved || state.value.saving || state.value.rendering) return
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                receipts.save(preview.artifact)
                markSaved(preview.artifact.document.id)
                if (state.value.preview?.artifact?.document?.id == preview.artifact.document.id) saved["preview.saved"] = true
                message("已存入本机纪念册")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message("保存未完成，请检查设备存储后重试")
            } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    private fun saveBatch() {
        if (state.value.saving || state.value.rendering) return
        val batch = state.value.previewBatch
        if (batch.isEmpty()) return
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            var completed = 0
            try {
                for (preview in batch) {
                    if (!preview.saved) receipts.save(preview.artifact)
                    completed++
                    markSaved(preview.artifact.document.id)
                }
                message("已保存 $completed 张小票")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("已保存 $completed 张，其余小票请重试") }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    private fun markSaved(id: String) {
        mutable.update { current ->
            current.copy(preview = current.preview?.let { if (it.artifact.document.id == id) it.copy(saved = true) else it },
                previewBatch = current.previewBatch.map { if (it.artifact.document.id == id) it.copy(saved = true) else it })
        }
        saved["preview.savedIds"] = ArrayList(state.value.previewBatch.filter { it.saved }.map { it.artifact.document.id })
    }
    private fun printReceipt() {
        val artifact = state.value.preview?.artifact ?: return
        if (printJob?.isActive == true || state.value.rendering) return
        printJob = viewModelScope.launch {
            try {
                if (!accounts.isDemo && printer.state.value.status !in setOf(PrintStatus.READY, PrintStatus.STOPPED, PrintStatus.COMPLETED)) {
                    dispatch(UiAction.Tab(HomeTab.DEVICE)); message("请先选择并连接打印机，然后返回小票预览"); return@launch
                }
                if (accounts.isDemo && printer.state.value.status == PrintStatus.DISCONNECTED) printer.connect()
                val batch = state.value.previewBatch.map { it.artifact }.ifEmpty { listOf(artifact) }
                printer.printBatch(batch)
                dispatch(UiAction.Tab(HomeTab.DEVICE))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message(e.message ?: "打印任务未能开始") }
        }
    }
    private fun printerAction(block: suspend () -> Unit) {
        if (state.value.deviceWorking) return
        viewModelScope.launch {
            mutable.update { it.copy(deviceWorking = true) }
            try { block() } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message(e.message ?: "设备操作未完成") }
            finally { mutable.update { it.copy(deviceWorking = false) } }
        }
    }
    private fun persistPreferences() {
        val current = state.value
        viewModelScope.launch {
            try { preferences.update(UserPreferences(current.font, current.theme, current.scenario, current.period))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message("偏好暂未保存，本次操作仍可继续") }
        }
    }
    private fun saveSelection() { saved["selected"] = ArrayList(state.value.selectedBlocks) }
    private fun go(screen: Screen) {
        mutable.update { it.copy(screen = screen) }; saved["screen"] = screen.name
    }
    fun message(value: String) { mutable.update { it.copy(message = value) } }

    fun prepareExport(): String? {
        if (state.value.rendering) return null
        pendingExport = state.value.preview?.artifact ?: return null
        return "战绩小票_${pendingExport!!.document.snapshot.range.period.label}_${pendingExport!!.document.id.take(8)}.png"
    }
    fun cancelExport() { pendingExport = null }
    fun exportTo(openStream: () -> OutputStream?) {
        val artifact = pendingExport ?: run { message("请重新点击导出"); return }
        pendingExport = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = openStream() ?: error("无法打开目标位置")
                    output.use { it.write(artifact.raster.png); it.flush() }
                }
                message(if (artifact.document.snapshot.isDemo) "PNG 已导出，包含示例数据标记" else "PNG 已导出")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message("导出未完成，请重试或选择其他保存位置") }
        }
    }
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(ReceiptViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ReceiptViewModel(graph.accounts, graph.stats, graph.receipts, graph.preferences,
                graph.renderer, graph.printer, extras.createSavedStateHandle(), web = graph.web) as T
        }
    }
}
private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback
