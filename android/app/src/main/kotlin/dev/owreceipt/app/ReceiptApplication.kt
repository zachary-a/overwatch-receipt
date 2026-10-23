package dev.owreceipt.app

import android.app.Application
import dev.owreceipt.core.*
import dev.owreceipt.infra.demo.*
import dev.owreceipt.infra.auth.*
import dev.owreceipt.infra.network.DashenStatsRepository
import dev.owreceipt.infra.network.OfficialNameCatalog
import dev.owreceipt.infra.printer.BluetoothPrinterGateway
import dev.owreceipt.infra.receipt.MonochromeReceiptRenderer
import dev.owreceipt.infra.storage.*
import kotlinx.coroutines.*

class ReceiptApplication : Application() {
    val graph by lazy { AppGraph(this) }
}
/** Composition root: replace adapters here; presentation has no knowledge of their implementation. */
class AppGraph(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val web: OfficialWebSession? = if (BuildConfig.DEMO_MODE) null else OfficialWebSession(application, appScope)
    val accounts: AccountSession = if (BuildConfig.DEMO_MODE) DemoAccountSession()
        else OfficialAccountSession(web!!, EncryptedCredentialStore(application), appScope)
    val stats: StatsRepository = if (BuildConfig.DEMO_MODE) DemoStatsRepository()
        else DashenStatsRepository((accounts as OfficialAccountSession).credentials, names = OfficialNameCatalog(application)::load)
    val receipts: ReceiptRepository = LocalReceiptRepository(application)
    val preferences: PreferencesRepository = LocalPreferencesRepository(application)
    val renderer: ReceiptRenderer = MonochromeReceiptRenderer(application)
    val printer: PrinterGateway = if (BuildConfig.DEMO_MODE) DemoPrinterGateway(appScope)
        else BluetoothPrinterGateway(application, appScope)
}
