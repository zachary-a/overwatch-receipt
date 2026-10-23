package dev.owreceipt.app

import android.webkit.*
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.infra.auth.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Replays login through the real document-start bridge and native session, with synthetic replies.
 * No real credentials, remote login, form submission or production test bypass is involved.
 */
class OfficialSessionJourneyTest {
    private class Store : CredentialStore {
        var value: BusinessCredential? = null
        override suspend fun read() = value
        override suspend fun write(credential: BusinessCredential) { value = credential }
        override suspend fun clear() { value = null }
    }

    @Test fun loginRereadAndSingleRoleReachReadyWithoutReloading() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store()
        val web = OfficialWebSession(context, scope)
        val accounts = OfficialAccountSession(web, store, scope)
        var browser: WebView? = null
        var mainLoads = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val holder = FrameLayout(activity)
                activity.setContentView(holder)
                val view = web.attach(activity); browser = view
                val original = view.webViewClient
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.isForMainFrame) mainLoads++
                        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
                          <body>网易大神（本地测试夹具）<button class="c-header-user__login">登录</button><script>
                          window.__fixtureSignedIn=false;window.__fixtureInfo=0;window.__fixtureRoles=0;
                          window.__fixtureExchange=0;window.__fixtureUrls=[];
                          window.sig={default:async()=>({gen_sign:()=>JSON.stringify({sign:'synthetic',timestamp:1})})};
                          window.fetch=async function(url){
                            window.__fixtureUrls.push(String(url));let payload;
                            if(String(url).includes('/mine/userInfo')){window.__fixtureInfo++;payload={code:window.__fixtureSignedIn?200:825};}
                            else if(String(url).includes('/blizzard/accounts?game_code=ld5')){window.__fixtureRoles++;
                              payload={code:0,result:{accounts:[{account:'999999999999999999',nickname:'示例玩家#1234'}]}};}
                            else if(String(url).includes('/getReportToken')){window.__fixtureExchange++;
                              payload={code:200,result:{token:'synthetic-test-only',roleId:'999999999999999999',day:'2026',server:'1'}};}
                            else throw Error('Unexpected endpoint');
                            return {status:200,json:async()=>payload,text:async()=>JSON.stringify(payload),headers:{get:()=>null}};
                          };
                          </script></body></html>""".trimIndent()
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = original.onPageStarted(view,url,favicon)
                    override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view,url)
                }
                holder.addView(view, FrameLayout.LayoutParams(-1,-1))
            }
            accounts.signIn()
            withTimeout(12_000) { web.websiteSessionStatus.first { it == 825 } }
            assertEquals(SessionStatus.AUTHENTICATING, accounts.state.value.status)
            assertNull(store.value)
            assertNull(web.roleEndpointStatus.value)
            val loadsBefore = mainLoads
            withContext(Dispatchers.Main) { browser!!.evaluateJavascript("window.__fixtureSignedIn=true", null) }
            accounts.readRoles()
            val ready = withTimeout(12_000) { accounts.state.first { it.status == SessionStatus.READY } }
            assertEquals("999999999999999999", ready.activePlayer?.id)
            assertEquals("示例玩家#1234", ready.activePlayer?.battleTag)
            assertEquals("synthetic-test-only", store.value?.token)
            assertEquals("0", web.roleEndpointStatus.value)
            assertNull(web.page.value.error)
            assertEquals("Rereading must not navigate or reload the authenticated page", loadsBefore, mainLoads)
            var evidence: String? = null
            withContext(Dispatchers.Main) { browser!!.evaluateJavascript(
                "JSON.stringify({info:window.__fixtureInfo,roles:window.__fixtureRoles,exchange:window.__fixtureExchange,url:location.href})") { evidence=it } }
            withTimeout(2000) { while (evidence == null) delay(10) }
            assertTrue(evidence!!.contains("\\\"exchange\\\":1"))
        } finally {
            accounts.signOut()
            scenario.onActivity { web.detach(it); browser?.destroy() }
            scenario.close(); scope.cancel()
        }
    }
}
