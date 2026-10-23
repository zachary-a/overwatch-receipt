package dev.owreceipt.infra.network

import dev.owreceipt.core.*
import dev.owreceipt.infra.auth.*
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DashenStatsRepository(
    private val credentials: CredentialCoordinator,
    private val clock: Clock = Clock.systemUTC(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).build(),
    private val apiRoot: String = "https://datamsapi.ds.163.com/v1/a19ld5tool",
    private val names: suspend () -> MatchNames = { MatchNames() },
) : StatsRepository {
    private val pacing = Mutex()
    private var nextRequestAt = 0L
    @Volatile private var retryAfterAt = 0L
    override fun capabilities(scenario: DemoScenario) = QueryCapabilities(Period.entries.toSet())
    override suspend fun query(player: Player, range: QueryRange, scenario: DemoScenario, modes: Set<MatchMode>): StatsSnapshot {
        require(modes.isNotEmpty())
        try {
            val identity = credentials.authorized { credential ->
                val body = buildJsonObject {
                    put("token", credential.token); put("roleId", credential.roleId)
                    put("dts", credential.dts); put("server", credential.server); put("name", player.battleTag)
                }
                request("searchBnetAccount", credential, body)
            }
            val target = DashenPayloads.value(identity, "customerToken")
                ?: throw StatsException(StatsProblem.EMPTY, "未能解析这个战网角色，请在账号页确认昵称")
            if (range.period != Period.CAREER) {
                val result = MatchRangeLoader(
                    page = { mode, page ->
                        credentials.authorized { requestElement("customer/queryMatchList", it,
                            parameters = mapOf("token" to target, "gameMode" to mode.wireName, "page" to page.toString())) }
                    },
                    detail = { id -> credentials.authorized { request("customer/queryMatchInfo", it,
                        parameters = mapOf("token" to target, "matchId" to id)) } },
                    names = names(),
                ).load(range, modes)
                val coverage = buildList {
                    add("按对局开始时间筛选；已取得 ${result.matches.size} 场")
                    add(if (result.complete) "列表已覆盖到所选区间起点" else "未确认完整覆盖区间")
                    addAll(result.notes)
                    add("仅包含所选竞技／快速模式，官方数据可能延迟")
                }.joinToString("。")
                return StatsSnapshot(player.copy(battleTag = DashenPayloads.value(identity, "name") ?: player.battleTag),
                    range, "网易大神 · 国服逐场战绩", clock.millis(), coverage,
                    modes.sortedBy { it.ordinal }.joinToString(" + ") { it.label }, result.complete,
                    MatchReceiptComposer.summary(result.matches, range), false, result.matches)
            }
            val card = credentials.authorized { request("customer/queryCard", it, parameters = mapOf("token" to target)) }
            suspend fun counts(mode: MatchMode): JsonObject? = try {
                credentials.authorized { request("customer/queryCountInfo", it,
                    parameters = mapOf("token" to target, "gameMode" to mode.wireName)) }
            } catch (e: StatsException) {
                if (e.problem in setOf(StatsProblem.AUTH_REQUIRED, StatsProblem.RATE_LIMIT)) throw e
                null
            }
            val competitive = counts(MatchMode.RANKED)
            val quick = counts(MatchMode.QUICK)
            return DashenPayloads.snapshot(player, range, identity, card, competitive, clock.millis(), quick, names())
        } catch (_: AuthenticationRequired) {
            throw StatsException(StatsProblem.AUTH_REQUIRED, "请在应用内完成官方登录或验证")
        } catch (_: IOException) {
            throw StatsException(StatsProblem.NETWORK, "连接战绩服务失败，请检查网络后重试")
        }
    }
    private suspend fun request(
        path: String, credential: BusinessCredential, body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): JsonObject = requestElement(path, credential, body, parameters) as? JsonObject
        ?: throw StatsException(StatsProblem.UPSTREAM, "上游对象响应结构发生变化")
    private suspend fun requestElement(
        path: String, credential: BusinessCredential, body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): JsonElement = withContext(Dispatchers.IO) {
        if (clock.millis() < retryAfterAt) throw StatsException(StatsProblem.RATE_LIMIT, "上游要求暂停查询，请稍后再试")
        pacing.withLock {
            val wait = nextRequestAt - System.nanoTime()
            if (wait > 0) delay((wait / 1_000_000).coerceAtLeast(1))
            nextRequestAt = System.nanoTime() + 250_000_000
        }
        val base = (apiRoot.trimEnd('/') + "/").toHttpUrl()
        val builder = base.newBuilder().addPathSegments(path)
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build())
            .header("Accept", "application/json, text/plain, */*")
            .header("GL-Bigdata-Auth-Token", credential.token)
            .header("GL-Bigdata-Role-Id", credential.roleId).header("GL-Bigdata-Dts", credential.dts)
            .header("GL-Bigdata-Server", credential.server).header("GL-ClientType", "60")
            .header("Origin", "https://act.ds.163.com").header("Referer", "https://act.ds.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json;charset=UTF-8".toMediaType())) }
            .build()
        val call = client.newCall(request)
        try {
            call.awaitResponse().use { response ->
                if (response.code == 429) {
                    val header = response.header("Retry-After")
                    val seconds = header?.toLongOrNull()?.coerceIn(1, 86_400)
                        ?: runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                            .getOrNull()?.let { ((it - clock.millis()) / 1000 + 1).coerceAtLeast(1) } ?: 60
                    retryAfterAt = clock.millis() + seconds * 1000
                }
                try { DashenPayloads.resultElement(response.code, response.body?.string().orEmpty()) }
                catch (e: StatsException) {
                    if (e.problem == StatsProblem.RATE_LIMIT && retryAfterAt <= clock.millis()) retryAfterAt = clock.millis() + 60_000
                    throw e
                }
            }
        } catch (_: IOException) { throw StatsException(StatsProblem.NETWORK, "连接战绩服务失败，请检查网络后重试") }
    }
}
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("战绩网络请求失败"))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
