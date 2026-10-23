package dev.owreceipt.infra.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.view.ViewGroup
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.owreceipt.core.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

data class OfficialPageState(val origin: String = "网易官方页面", val loading: Boolean = false,
    val error: String? = null, val status: String? = null)

/** WebView owns its official cookies. Native code only receives bound roles and business credentials. */
class OfficialWebSession(private val context: Context, private val scope: CoroutineScope) {
    private val wrapper = MutableContextWrapper(context.applicationContext)
    private val exchanges = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val pageWaiters = mutableListOf<Pair<String, CompletableDeferred<Unit>>>()
    private val mutable = MutableStateFlow(OfficialPageState())
    val page = mutable.asStateFlow()
    private val roleStatus = MutableStateFlow<String?>(null)
    val roleEndpointStatus = roleStatus.asStateFlow()
    private val siteStatus = MutableStateFlow<Int?>(null)
    val websiteSessionStatus = siteStatus.asStateFlow()
    var onRoles: (List<Player>) -> Unit = {}
    private var view: WebView? = null
    private var currentUrl: String? = null
    private val origins = setOf("https://ds.163.com", "https://pay.ds.163.com")
    private var acceptedRoles = false
    private var officialSignedIn = false
    private var openedRolePage = false
    // Current public official directory: ld5, not the report API's appKey=bn.
    private val rolePageUrl = "https://pay.ds.163.com/activity/ld5?setId=67b466a5b8cf7e3576aa3521"

    private fun trustedBridge(uri: Uri): Boolean = uri.scheme == "https" &&
        uri.host in setOf("ds.163.com", "pay.ds.163.com") && uri.port in setOf(-1, 443)
    private fun allowedNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https" || uri.port !in setOf(-1, 443)) return false
        val host = uri.host ?: return false
        return host in setOf("ds.163.com", "pay.ds.163.com", "reg.163.com", "passport.163.com",
            "dl.reg.163.com", "webzj.reg.163.com", "m.reg.163.com", "id.163.com", "account.battlenet.com.cn",
            "oauth.battlenet.com.cn", "god.gameyw.netease.com") || host.endsWith(".reg.163.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        view?.let { return it }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "请先更新 Android 系统 WebView，再使用官方登录"
        }
        WebView.setWebContentsDebuggingEnabled(false)
        val web = WebView(wrapper)
        web.isHorizontalScrollBarEnabled = true
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            // ds.163.com's signed web flow is the desktop website; mobile UA redirects elsewhere.
            userAgentString = userAgentString.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                .replace("; wv", "").replace(" Mobile ", " ")
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        WebViewCompat.addWebMessageListener(web, "ReceiptAuthBridge", origins) { source, message, origin, mainFrame, _ ->
            if (!mainFrame || !trustedBridge(origin) || message.data.orEmpty().length > 600_000) return@addWebMessageListener
            val envelope = runCatching { Json.parseToJsonElement(message.data.orEmpty()).jsonObject }.getOrNull()
                ?: return@addWebMessageListener
            when ((envelope["kind"] as? JsonPrimitive)?.content) {
                "roles" -> {
                    val location = runCatching { Uri.parse(source.url) }.getOrNull()
                    if (acceptedRoles || location?.host != origin.host ||
                        (envelope["endpoint"] as? JsonPrimitive)?.content != "blizzardAccounts") return@addWebMessageListener
                    val raw = (envelope["body"] as? JsonPrimitive)?.content ?: return@addWebMessageListener
                    val http = (envelope["http"] as? JsonPrimitive)?.intOrNull ?: 0
                    val result = OfficialRoleParser.read(http, raw)
                    roleStatus.value = result.code?.toString() ?: "INVALID"
                    when (result.status) {
                        RoleReadStatus.FOUND -> {
                            acceptedRoles = true
                            source.evaluateJavascript("window.__owReceiptRolesDone=true", null)
                            mutable.value = mutable.value.copy(error = null, status = "已读取绑定角色，正在准备战绩访问")
                            CookieManager.getInstance().flush(); onRoles(result.roles)
                        }
                        RoleReadStatus.SIGN_IN_REQUIRED -> {
                            if (origin.host == "ds.163.com" && officialSignedIn && !openedRolePage) {
                                openedRolePage = true
                                source.loadUrl(rolePageUrl)
                            } else mutable.value = mutable.value.copy(error = null,
                                status = "大神与角色服务的会话尚未连通，请在官方页面完成登录后重新读取")
                        }
                        RoleReadStatus.EMPTY -> mutable.value = mutable.value.copy(
                            status = "已登录，当前账号尚未发现绑定的战网角色",
                            error = "请在官方守望先锋页面绑定战网账号，然后重新读取")
                        RoleReadStatus.INVALID -> mutable.value = mutable.value.copy(
                            error = "绑定角色响应格式发生变化，请重新读取；若仍失败，请反馈此提示")
                        RoleReadStatus.FAILED -> mutable.value = mutable.value.copy(
                            error = "绑定角色读取失败（HTTP $http，业务码 ${result.code ?: "未知"}），请稍后重新读取")
                    }
                }
                "siteStatus" -> if (origin.host == "ds.163.com" && Uri.parse(source.url).host == origin.host) {
                    siteStatus.value = (envelope["code"] as? JsonPrimitive)?.intOrNull
                    officialSignedIn = (envelope["ready"] as? JsonPrimitive)?.booleanOrNull == true
                    if (roleStatus.value == null) mutable.value = mutable.value.copy(error = null, status = if (officialSignedIn)
                        "大神官网已确认登录，正在读取绑定战网角色" else "请在网易大神官网完成登录")
                }
                "discoveryError" -> {
                    roleStatus.value = "REQUEST_FAILED"
                    mutable.value = mutable.value.copy(error = "官方会话或绑定角色服务暂时无法访问，请检查网络后重新读取")
                }
                "result" -> {
                    if (origin.host != "ds.163.com" || Uri.parse(source.url).host != "ds.163.com") return@addWebMessageListener
                    val id = (envelope["id"] as? JsonPrimitive)?.content ?: return@addWebMessageListener
                    pending.remove(id)?.complete(envelope)
                }
            }
        }
        val capture = context.assets.open("official_role_capture.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(web, capture, origins)
        val layout = context.assets.open("official_login_layout.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(web, layout, setOf("https://ds.163.com"))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (allowedNavigation(request.url)) return false
                mutable.value = mutable.value.copy(error = "已拦截非认证页面，请使用网易官方登录入口")
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentUrl = url
                mutable.value = OfficialPageState(Uri.parse(url).host ?: "网易官方页面", true,
                    status = if (Uri.parse(url).host == "pay.ds.163.com")
                        "正在官方守望先锋页面读取绑定账号，完成后会自动返回。无需充值。" else null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                mutable.value = mutable.value.copy(origin = Uri.parse(url).host ?: "网易官方页面", loading = false)
                val host = Uri.parse(url).host
                val completed = pageWaiters.filter { it.first == host }
                pageWaiters.removeAll(completed.toSet())
                completed.forEach {
                    if (mutable.value.error == null) it.second.complete(Unit)
                    else it.second.completeExceptionally(IOException("官方页面加载失败"))
                }
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) mutable.value = mutable.value.copy(loading = false,
                    error = "官方页面暂时无法加载，请检查网络后重试（${error.errorCode}）")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) mutable.value = mutable.value.copy(loading = false,
                    error = "官方页面返回 ${errorResponse.statusCode}，请稍后重试")
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                mutable.value = mutable.value.copy(loading = false, error = "官方页面证书验证失败，已停止连接")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
        view = web
        return web
    }
    fun attach(hostContext: Context): WebView {
        wrapper.baseContext = hostContext
        val web = ensureView()
        (web.parent as? ViewGroup)?.removeView(web)
        web.onResume()
        return web
    }
    fun detach(hostContext: Context) {
        if (wrapper.baseContext !== hostContext) return
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper.baseContext = context.applicationContext
    }
    suspend fun begin() = withContext(Dispatchers.Main.immediate) {
        acceptedRoles = false
        officialSignedIn = false
        openedRolePage = false
        roleStatus.value = null
        siteStatus.value = null
        mutable.value = OfficialPageState("ds.163.com", loading = true)
        ensureView().loadUrl("https://ds.163.com/")
    }
    suspend fun readRoles() = withContext(Dispatchers.Main.immediate) {
        acceptedRoles = false
        roleStatus.value = null
        mutable.value = mutable.value.copy(error = null, status = "正在重新检查官网会话与绑定角色…")
        val web = ensureView()
        if (Uri.parse(currentUrl.orEmpty()).host in setOf("ds.163.com", "pay.ds.163.com")) {
            web.evaluateJavascript("if(typeof window.__owReceiptReadRoles==='function')window.__owReceiptReadRoles()", null)
        } else begin()
    }
    suspend fun retry() = withContext(Dispatchers.Main.immediate) { ensureView().reload() }
    suspend fun showOfficialAccount() = withContext(Dispatchers.Main.immediate) {
        if (Uri.parse(currentUrl.orEmpty()).host != "ds.163.com") {
            mutable.value = OfficialPageState("ds.163.com", loading = true)
            ensureView().loadUrl("https://ds.163.com/")
        }
    }
    private suspend fun openHome() = withContext(Dispatchers.Main.immediate) {
        val web = ensureView()
        if (Uri.parse(currentUrl.orEmpty()).host == "ds.163.com" && !mutable.value.loading) return@withContext
        val completed = CompletableDeferred<Unit>()
        val waiter = "ds.163.com" to completed
        pageWaiters += waiter
        web.loadUrl("https://ds.163.com/")
        try { withTimeout(35_000) { completed.await() } }
        catch (_: TimeoutCancellationException) { throw IOException("官方页面连接超时") }
        finally { pageWaiters.remove(waiter) }
    }
    suspend fun exchange(player: Player): BusinessCredential = exchanges.withLock {
        openHome()
        val body = buildJsonObject {
            put("appKey", "bn"); put("roleId", player.id); put("server", "1")
            put("source", 1); put("type", "yearly")
        }.toString()
        val id = UUID.randomUUID().toString()
        val quotedId = JsonPrimitive(id).toString()
        val quotedBody = JsonPrimitive(body).toString()
        val script = """
            (async function(){
              const id=$quotedId;
              function cookie(n){return document.cookie.split("; ").find(r=>r.startsWith(n+"="))?.split("=").slice(1).join("=")||"";}
              try {
                if(!window.sig){await new Promise((resolve,reject)=>{const s=document.createElement("script");s.src="https://g.166.net/opd/latest/sig/bootstrap.js";s.onload=resolve;s.onerror=reject;document.head.appendChild(s);});}
                const body=$quotedBody;
                const mod=await window.sig.default();
                const sign=JSON.parse(mod.gen_sign(body));
                const response=await fetch("https://inf.ds.163.com/v1/web/game/report/getReportToken",{
                  method:"POST",credentials:"include",
                  headers:{"Content-Type":"application/json;charset=UTF-8","GL-ClientType":"61",
                    "GL-DeviceId":localStorage.getItem("ns-client-id")||localStorage.getItem("ds-website-uuid")||"",
                    "GL-Uid":cookie("GOD_UUID"),"GL-X-XSRF-TOKEN":cookie("GL-XSRF-TOKEN"),
                    "GL-CheckSum":sign.sign,"GL-Nonce":String(sign.timestamp)},
                  body:body
                });
                const data=await response.json();
                window.ReceiptAuthBridge.postMessage(JSON.stringify({kind:"result",id:id,http:response.status,payload:data}));
              }catch(e){window.ReceiptAuthBridge.postMessage(JSON.stringify({kind:"result",id:id,failed:true}));}
            })();
        """.trimIndent()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.Main.immediate) { ensureView().evaluateJavascript(script, null) }
            val envelope = try { withTimeout(40_000) { deferred.await() } }
                catch (_: TimeoutCancellationException) { throw IOException("官方凭据请求超时") }
            if (envelope["failed"]?.jsonPrimitive?.booleanOrNull == true) throw IOException("官方认证请求未完成")
            val payload = envelope["payload"] as? JsonObject ?: throw IOException("官方凭据响应格式发生变化")
            val status = (envelope["http"] as? JsonPrimitive)?.intOrNull ?: 0
            val code = (payload["code"] as? JsonPrimitive)?.content
            val message = listOf("message", "msg", "errmsg").mapNotNull { (payload[it] as? JsonPrimitive)?.content }.joinToString(" ")
            if (status == 401 || code in setOf("401", "901", "825", "873", "836") ||
                Regex("未登录|登录失效|登录过期|验证码|二次验证|not logged", RegexOption.IGNORE_CASE).containsMatchIn(message))
                throw AuthenticationRequired()
            if (status !in 200..299 || code != "200") throw IOException("官方凭据换取暂未完成")
            val result = payload["result"] as? JsonObject ?: throw IOException("官方凭据响应缺少必要字段")
            fun field(name: String) = (result[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val token = field("token") ?: throw AuthenticationRequired()
            val role = field("roleId") ?: player.id
            val dts = field("day") ?: field("dts") ?: throw IOException("官方凭据响应缺少数据周期")
            val server = field("server") ?: "1"
            require(token.length <= 8192 && role.matches(Regex("[0-9]{1,30}")) && dts.length <= 32)
            BusinessCredential(role, token, dts, server, player.copy(id = role))
        } finally { pending.remove(id) }
    }
    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        pending.values.forEach { it.completeExceptionally(AuthenticationRequired()) }; pending.clear()
        pageWaiters.forEach { it.second.cancel() }; pageWaiters.clear()
        acceptedRoles = false
        officialSignedIn = false
        openedRolePage = false
        view?.loadUrl("about:blank")
        suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
