package dev.owreceipt.app

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owreceipt.core.*
import dev.owreceipt.ui.*

class MainActivity : ComponentActivity() {
    private val graph get() = (application as ReceiptApplication).graph
    private val model: ReceiptViewModel by viewModels { ReceiptViewModel.Factory((application as ReceiptApplication).graph) }
    private var pendingBluetooth: UiAction? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (window.decorView.display?.displayId == displayId) requestHighRefreshRate()
        }
    }
    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingBluetooth
        pendingBluetooth = null
        if (action != null && requiredPermissions(action).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            model.dispatch(action)
        else model.message("蓝牙权限未获允许，可继续制作与导出小票")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) model.cancelExport()
        else model.exportTo { contentResolver.openOutputStream(uri, "wt") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = state.theme == AppTheme.DARK || (state.theme == AppTheme.SYSTEM && systemDark)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (state.printer.status.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            BackHandler(state.screen != Screen.HOME || state.tab != HomeTab.MAKE) {
                model.dispatch(if (state.screen != Screen.HOME) UiAction.Back else UiAction.Tab(HomeTab.MAKE))
            }
            ReceiptApp(state, authContent = { modifier ->
                val web = graph.web
                if (web != null) {
                    val host = LocalContext.current
                    AndroidView(factory = { OfficialWebViewport(host, web.attach(host)) },
                        update = { it.setWidePage(state.authWidePage) }, modifier = modifier.clipToBounds())
                    DisposableEffect(web, host) { onDispose { web.detach(host) } }
                }
            }) { action ->
                if (action == UiAction.Export) model.prepareExport()?.let { export.launch(it) }
                else if (!state.isDemo && action in setOf(UiAction.Connect, UiAction.RefreshDevices, UiAction.ScanDevices)) {
                    val needed = requiredPermissions(action).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (needed.isEmpty()) model.dispatch(action)
                    else { pendingBluetooth = action; bluetoothPermissions.launch(needed.toTypedArray()) }
                } else {
                    if (!state.isDemo && action in setOf(UiAction.Print, UiAction.TestPrint) &&
                        state.printer.status in setOf(PrintStatus.READY, PrintStatus.STOPPED, PrintStatus.COMPLETED)) {
                        startForegroundService(Intent().setClassName(this, "dev.owreceipt.app.LivePrintService"))
                    }
                    model.dispatch(action)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }
    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
    }
    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestHighRefreshRate()
    }
    private fun requestHighRefreshRate() {
        val display = window.decorView.display ?: return
        val current = display.mode
        val mode = display.supportedModes.asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        // A frame-rate vote lets Android respect power, thermal and user display policies.
        val attributes = window.attributes
        if (attributes.preferredRefreshRate != mode.refreshRate || attributes.preferredDisplayModeId != mode.modeId) {
            attributes.preferredRefreshRate = mode.refreshRate
            attributes.preferredDisplayModeId = mode.modeId
            window.attributes = attributes
        }
    }
    private fun requiredPermissions(action: UiAction): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else if (action == UiAction.ScanDevices) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
}
