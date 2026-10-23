package dev.owreceipt.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.*
import dev.owreceipt.ui.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Only the unsigned official page is tested automatically; real login is performed by the user. */
class LiveBootTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun liveBuildStartsSignedOutAndKeepsOfficialPageAcrossRotation() {
        Assume.assumeFalse(BuildConfig.DEMO_MODE)
        val graph = (rule.activity.application as ReceiptApplication).graph
        val model = ViewModelProvider(rule.activity)[ReceiptViewModel::class.java]
        rule.waitUntil(10_000) { model.state.value.queryStatus == QueryStatus.PROBLEM }
        assertFalse(graph.accounts.isDemo)
        assertEquals(SessionStatus.SIGNED_OUT, model.state.value.session.status)
        rule.onNodeWithTag("period.TODAY").assertIsSelected()
        capture("20_live_make.png")
        rule.onNodeWithTag("account.card").performClick()
        rule.onNodeWithTag("account.signin").performClick()
        rule.waitUntil(10_000) { model.state.value.session.status == SessionStatus.AUTHENTICATING }
        rule.onNodeWithTag("account.official.page").assertExists()
        rule.waitUntil(45_000) { !model.state.value.authLoading }
        assertNull(model.state.value.authError)
        inspectPage()
        capture("21a_official_initial.png")
        rule.waitUntil(20_000) { graph.web!!.websiteSessionStatus.value != null }
        assertNotEquals("Unsigned page must not claim a signed-in website session", 200, graph.web!!.websiteSessionStatus.value)
        assertNull("Blizzard bindings are not queried before official website login", graph.web!!.roleEndpointStatus.value)
        assertLoginFormFitsPhone()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("account.official.page").assertExists()
        rule.waitUntil(45_000) { !model.state.value.authLoading }
        assertNull("Unsigned official page should load without a network or certificate error", model.state.value.authError)
        inspectPage()
        assertLoginFormFitsPhone()
        capture("21_official_login.png")
        checkKeyboardEntry()
        rule.runOnIdle { model.dispatch(UiAction.OpenOfficialAccount) }
        rule.waitUntil(30_000) { model.state.value.authOrigin == "ds.163.com" && !model.state.value.authLoading }
        assertNull(model.state.value.authError)
        inspectPage("ds.163.com")
        var signer: String? = null
        rule.runOnIdle {
            find(rule.activity.window.decorView)!!.evaluateJavascript("typeof window.sig?.default") { signer = it }
        }
        rule.waitUntil(10_000) { signer != null }
        assertEquals("\"function\"", signer)
        rule.runOnIdle { model.dispatch(UiAction.Back); model.dispatch(UiAction.Tab(HomeTab.DEVICE)) }
        rule.onNodeWithTag("device.status").assertExists()
        capture("22_live_device.png")
        assertEquals(PrintStatus.DISCONNECTED, graph.printer.state.value.status)
        assertFalse(File(rule.activity.filesDir, "official-session.enc").exists())
    }
    private fun find(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun assertLoginFormFitsPhone() {
        var geometry: String? = null
        rule.waitUntil(20_000) {
            rule.runOnIdle { find(rule.activity.window.decorView)!!.evaluateJavascript(
                "(function(){const f=document.querySelector('.c-login-urs-popup-form');if(!f||!f.offsetParent||getComputedStyle(f).visibility!=='visible')return null;const r=f.getBoundingClientRect(),p=document.querySelector('.c-login-urs-popup').getBoundingClientRect();const v=window.visualViewport;return JSON.stringify({left:r.left-(v?v.offsetLeft:0),right:r.right-(v?v.offsetLeft:0),width:v?v.width:document.documentElement.clientWidth,top:p.top,bottom:p.bottom,popupHeight:p.height,height:v?v.height:innerHeight,layoutWidth:innerWidth,tabs:!!f.querySelector('#js-urs-mobile')&&!!f.querySelector('#js-urs-mail')});})()"
            ) { geometry = it } }
            geometry != null && geometry != "null"
        }
        val json = org.json.JSONObject(org.json.JSONArray("[$geometry]").getString(0))
        assertTrue("Official SMS/email form must fit the phone viewport: $json", json.getDouble("left") >= -1 &&
            json.getDouble("right") <= json.getDouble("width") + 1)
        assertTrue(json.getBoolean("tabs"))
        assertTrue("Official form must be fully visible vertically: $json", json.getDouble("popupHeight") >= 300 &&
            json.getDouble("top") >= -1 && json.getDouble("bottom") <= json.getDouble("height") + 1)
    }
    private fun checkKeyboardEntry() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun editable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        var input: android.view.accessibility.AccessibilityNodeInfo? = null
        rule.waitUntil(10_000) { input = editable(automation.rootInActiveWindow); input != null }
        var height = 0
        rule.runOnIdle { height = find(rule.activity.window.decorView)!!.height }
        val bounds = android.graphics.Rect(); input!!.getBoundsInScreen(bounds)
        val began = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val touch = android.view.MotionEvent.obtain(began, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            touch.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(touch, true)); touch.recycle()
        }
        rule.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        rule.waitUntil(10_000) { find(rule.activity.window.decorView)!!.height < height }
        capture("21c_official_keyboard.png")
        rule.runOnIdle { androidx.core.view.WindowInsetsControllerCompat(rule.activity.window, rule.activity.window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        rule.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
    }
    private fun inspectPage(expectedHost: String = "ds.163.com") {
        var inspected: String? = null
        rule.runOnIdle {
            val web = find(rule.activity.window.decorView)
            assertNotNull("Official WebView must remain attached", web)
            web!!.evaluateJavascript("JSON.stringify({host:location.hostname,length:document.body?document.body.innerText.length:0,ready:document.readyState,visible:document.visibilityState,capture:!!window.__owReceiptCaptureInstalled,probe:window.__owReceiptRoleProbeState,bridge:!!window.ReceiptAuthBridge,missing:document.body.innerText.includes('你访问的页面不存在')})") { inspected = it }
        }
        rule.waitUntil(10_000) { inspected != null }
        assertTrue("Official DOM should be loaded: $inspected", inspected!!.contains(expectedHost))
        assertFalse("Official page should contain text", inspected!!.contains("\\\"length\\\":0"))
        assertTrue("Document-start capture and bridge should be installed: $inspected", inspected!!.contains("\\\"capture\\\":true") && inspected!!.contains("\\\"bridge\\\":true"))
        assertFalse("Official route should exist: $inspected", inspected!!.contains("\\\"missing\\\":true"))
        println("OFFICIAL_PAGE_STATE $inspected")
    }
    private fun capture(name: String) {
        rule.waitForIdle(); Thread.sleep(350)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
