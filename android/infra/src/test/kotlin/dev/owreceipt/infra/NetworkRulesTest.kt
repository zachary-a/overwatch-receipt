package dev.owreceipt.infra

import dev.owreceipt.core.*
import dev.owreceipt.infra.auth.*
import dev.owreceipt.infra.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRulesTest {
    private class Store(var value: BusinessCredential?) : CredentialStore {
        override suspend fun read() = value
        override suspend fun write(credential: BusinessCredential) { value = credential }
        override suspend fun clear() { value = null }
    }
    private fun credential(token: String = "old") = BusinessCredential("999999999999999999", token, "2026", "1", Player("999999999999999999", "Example#1234", "角色"))
    @Test fun concurrentFailuresExchangeOnceAndRetryOnlyOnce() = runTest {
        var exchanges = 0
        val coordinator = CredentialCoordinator(Store(credential())) { old -> exchanges++; delay(50); old.copy(token = "new") }
        val results = (1..8).map { async {
            coordinator.authorized { c -> if (c.token == "old") { delay(10); throw CredentialRejected() }; c.token }
        } }.awaitAll()
        assertEquals(1, exchanges); assertTrue(results.all { it == "new" })
    }
    @Test fun failedExchangeIsSharedAndDoesNotDeleteCredential() = runTest {
        val store = Store(credential())
        var exchanges = 0
        val coordinator = CredentialCoordinator(store) { exchanges++; delay(20); throw IOException("offline") }
        (1..6).map { async { runCatching { coordinator.authorized<String> { delay(1); throw CredentialRejected() } } } }.awaitAll()
        assertEquals(1, exchanges); assertEquals("old", store.value?.token)
    }
    @Test fun privacyAndNetworkDoNotCauseRefresh() = runTest {
        var exchanges = 0
        val coordinator = CredentialCoordinator(Store(credential())) { exchanges++; it }
        try { coordinator.authorized<String> { throw StatsException(StatsProblem.PRIVATE, "private") } } catch (_: StatsException) {}
        try { coordinator.authorized<String> { throw IOException() } } catch (_: IOException) {}
        assertEquals(0, exchanges)
    }
    @Test fun logoutDuringExchangeCannotReinstallASecret() = runTest {
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val store = Store(credential())
        val coordinator = CredentialCoordinator(store) { began.complete(Unit); finish.await(); it.copy(token = "new") }
        val query = async { runCatching { coordinator.authorized<String> { throw CredentialRejected() } } }
        began.await()
        val logout = launch { coordinator.clear() }
        runCurrent()
        finish.complete(Unit)
        assertTrue(query.await().exceptionOrNull() is AuthenticationRequired)
        logout.join(); assertNull(store.value)
    }
    @Test fun businessStatusIsIndependentOfHttpStatus() {
        assertTrue(runCatching { DashenPayloads.result(200, """{"code":401,"msg":"expired"}""") }.exceptionOrNull() is CredentialRejected)
        val privacy = runCatching { DashenPayloads.result(200, """{"code":123,"msg":"隐私不可见"}""") }.exceptionOrNull() as StatsException
        assertEquals(StatsProblem.PRIVATE, privacy.problem)
        val rate = runCatching { DashenPayloads.result(429, "{}") }.exceptionOrNull() as StatsException
        assertEquals(StatsProblem.RATE_LIMIT, rate.problem)
        assertEquals("0", DashenPayloads.value(DashenPayloads.result(200, """{"code":0,"data":{"kill":0}}"""), "kill"))
    }
    @Test fun boundRolesKeepLongIdsExactlyAndRejectErrorBodies() {
        val text = """{"code":0,"result":{"accounts":[{"account":999999999999999999,"nickname":"用户#123"}]}}"""
        assertEquals("999999999999999999", OfficialRoleParser.parse(text).single().id)
        assertTrue(OfficialRoleParser.parse("""{"code":901,"result":[{"role_id":1,"name":"bad"}]}""").isEmpty())
        assertFalse(credential().toString().contains("old"))
    }
    private val clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), ZoneOffset.UTC)
    private val range = QueryRange.at(Period.TODAY, clock)
    private fun item(id: String, ts: Long, kills: Int = 0): JsonObject = buildJsonObject {
        put("matchId", id); put("beginTs", ts); put("matchRet", 1); put("kill", kills)
        put("assist", 0); put("death", 0); put("heroDamage", 0); put("cure", 0)
    }
    @Test fun midnightFilteringDeduplicatesAndFetchesDurations() = runTest {
        val midnight = range.startInclusive!!
        var pages = 0
        val loader = MatchRangeLoader(page = { _, index ->
            pages++
            JsonArray(if (index == 1) listOf(item("end", range.endExclusive), item("day", midnight + 1000), item("boundary", midnight))
                else listOf(item("boundary", midnight), item("old", midnight - 1)))
        }, detail = { buildJsonObject { put("gameTimeSec", 600) } })
        val result = loader.load(range, setOf(MatchMode.RANKED))
        assertEquals(listOf("day", "boundary"), result.matches.map { it.id })
        assertEquals(2, pages); assertTrue(result.complete)
        assertTrue(result.matches.all { it.durationSeconds == 600L })
        assertEquals(0.0, result.matches.first().kills!!, 0.0)
    }
    @Test fun repeatedPageAndMissingDetailsRemainExplicitlyPartial() = runTest {
        val loader = MatchRangeLoader(page = { _, _ -> JsonArray(listOf(item("one", range.startInclusive!! + 100))) },
            detail = { throw StatsException(StatsProblem.NETWORK, "offline") })
        val result = loader.load(range, setOf(MatchMode.QUICK))
        assertEquals(1, result.matches.size); assertFalse(result.complete)
        assertNull(result.matches.single().durationSeconds)
        assertTrue(result.notes.any { "重复" in it }); assertTrue(result.notes.any { "时长" in it })
    }
    @Test fun unknownTimestampCannotBecomeEpochZeroOrCompleteRange() = runTest {
        val loader = MatchRangeLoader(page = { _, index ->
            if (index == 1) JsonArray(listOf(item("bad", 0), item("old", range.startInclusive!! - 1))) else JsonArray(emptyList())
        }, detail = { JsonObject(emptyMap()) })
        val result = loader.load(range, setOf(MatchMode.RANKED))
        assertFalse(result.complete); assertTrue(result.matches.isEmpty())
        assertTrue(result.notes.any { "无法识别" in it })
    }
    @Test fun listIdsResolveThroughOfficialNames() {
        val row = buildJsonObject {
            put("matchId", "one"); put("beginTs", range.startInclusive!! + 1); put("heroGuid", "hero"); put("mapGuid", "map")
        }
        val parsed = MatchRangeLoader.parse(row, MatchMode.RANKED,
            MatchNames(mapOf("hero" to "安娜"), mapOf("map" to "国王大道")))!!
        assertEquals("安娜", parsed.hero); assertEquals("国王大道", parsed.map)
    }
    @Test fun inaccessibleListsCannotMasqueradeAsZeroGames() = runTest {
        val loader = MatchRangeLoader(page = { _, _ -> throw StatsException(StatsProblem.PRIVATE, "private") },
            detail = { JsonObject(emptyMap()) })
        val error = runCatching { loader.load(range, MatchMode.entries.toSet()) }.exceptionOrNull()
        assertEquals(StatsProblem.PRIVATE, (error as StatsException).problem)
    }
}
