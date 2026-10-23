package dev.owreceipt.infra.auth

import dev.owreceipt.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OfficialAccountSession(
    private val web: OfficialWebSession,
    store: CredentialStore,
    private val scope: CoroutineScope,
) : AccountSession {
    private val mutable = MutableStateFlow(SessionState())
    override val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    val credentials = CredentialCoordinator(store) { old ->
        val expected = generation
        mutable.value = mutable.value.copy(status = SessionStatus.REFRESHING, detail = "正在续期战绩访问凭据")
        try {
            web.exchange(old.player).also {
                if (expected != generation) throw AuthenticationRequired()
                mutable.value = SessionState(SessionStatus.READY, mutable.value.roles, it.player)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AuthenticationRequired) {
            if (expected == generation) mutable.value = mutable.value.copy(status = SessionStatus.EXPIRED, detail = "需要完成官方登录或验证")
            throw e
        } catch (e: Exception) {
            if (expected == generation) mutable.value = mutable.value.copy(status = SessionStatus.READY, detail = "续期暂未完成，请稍后重试")
            throw e
        }
    }
    init {
        web.onRoles = { roles ->
            mutable.value = SessionState(SessionStatus.SELECTING_ROLE, roles)
            if (roles.size == 1) scope.launch { selectRole(roles.single().id) }
        }
        val expected = generation
        scope.launch {
            credentials.restore()?.let { credential ->
                if (expected == generation && mutable.value.status == SessionStatus.SIGNED_OUT)
                    mutable.value = SessionState(SessionStatus.READY, listOf(credential.player), credential.player)
            }
        }
    }
    override suspend fun signIn() {
        generation++
        mutable.value = mutable.value.copy(status = SessionStatus.AUTHENTICATING, detail = "请在网易大神官网完成登录")
        try { web.begin() } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(status = SessionStatus.FAILED, detail = "官方页面未能启动，请检查系统 WebView 与网络")
        }
    }
    override suspend fun readRoles() {
        if (mutable.value.status == SessionStatus.FETCHING_CREDENTIAL) return
        generation++
        mutable.value = mutable.value.copy(status = SessionStatus.AUTHENTICATING, detail = "正在读取当前会话的绑定角色")
        try { web.readRoles() } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(status = SessionStatus.FAILED, detail = "绑定角色未能读取，请重试")
        }
    }
    override suspend fun selectRole(id: String) {
        val player = mutable.value.roles.firstOrNull { it.id == id } ?: return
        generation++
        val expected = generation
        mutable.value = mutable.value.copy(status = SessionStatus.FETCHING_CREDENTIAL, activePlayer = player, detail = "正在连接战绩服务")
        try {
            val credential = web.exchange(player)
            if (expected != generation) return
            credentials.install(credential)
            mutable.value = SessionState(SessionStatus.READY, mutable.value.roles, credential.player)
        } catch (e: CancellationException) { throw e
        } catch (_: AuthenticationRequired) {
            if (expected == generation) mutable.value = mutable.value.copy(status = SessionStatus.EXPIRED, detail = "官方会话需要重新认证")
        } catch (_: Exception) {
            if (expected == generation) mutable.value = mutable.value.copy(status = SessionStatus.FAILED, detail = "连接战绩服务未完成，请检查网络并重试")
        }
    }
    override suspend fun expire() { mutable.value = mutable.value.copy(status = SessionStatus.EXPIRED) }
    override suspend fun signOut() {
        generation++
        web.clear(); credentials.clear()
        mutable.value = SessionState()
    }
}
