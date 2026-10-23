package dev.owreceipt.infra

import dev.owreceipt.core.*
import dev.owreceipt.infra.auth.*
import dev.owreceipt.infra.network.DashenStatsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DashenApiTest {
    private val player = Player("111", "Player#123", "绑定角色")
    private class Store(private var value: BusinessCredential?) : CredentialStore {
        override suspend fun read() = value
        override suspend fun write(credential: BusinessCredential) { value = credential }
        override suspend fun clear() { value = null }
    }
    @Test fun careerLoadsLifetimeHoursAndBothSeasonModesUsingTargetToken() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"name":"Player#123","customerToken":"target","totalMatchNum":400}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"gameTime":"1063.29"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"presetsSummaryData":{"matchSum":100}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"presetsSummaryData":{"matchSum":200}}}"""))
            server.start()
            val coordinator = CredentialCoordinator(Store(BusinessCredential("111", "caller", "2026", "1", player))) { it }
            val repo = DashenStatsRepository(coordinator, apiRoot = server.url("/v1").toString())
            val snapshot = repo.query(player, QueryRange.at(Period.CAREER), DemoScenario.NORMAL)
            assertEquals("1063.29 小时", snapshot.blocks.first().metrics.single { it.label == "游玩时长" }.displayValue)
            assertEquals("100", snapshot.blocks.single { it.id == "competitive" }.metrics.first().value)
            assertEquals("200", snapshot.blocks.single { it.id == "quick" }.metrics.first().value)
            val calls = (1..4).map { server.takeRequest() }
            assertTrue(calls.all { it.getHeader("GL-Bigdata-Auth-Token") == "caller" })
            assertTrue(calls.drop(1).all { it.requestUrl!!.queryParameter("token") == "target" })
            assertEquals(listOf("sport", "leisure"), calls.drop(2).map { it.requestUrl!!.queryParameter("gameMode") })
            assertTrue(calls.drop(2).none { it.requestUrl!!.queryParameter("season") != null })
        } finally { server.shutdown() }
    }
    @Test fun unavailableQuickStatsKeepLifetimeAndCompetitiveData() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"name":"Player#123","customerToken":"target","gameTime":"123.5"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"name":"Player#123"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"presetsSummaryData":{"matchSum":100}}}"""))
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            server.start()
            val coordinator = CredentialCoordinator(Store(BusinessCredential("111", "caller", "2026", "1", player))) { it }
            val snapshot = DashenStatsRepository(coordinator, apiRoot = server.url("/v1").toString())
                .query(player, QueryRange.at(Period.CAREER), DemoScenario.NORMAL)
            assertEquals("123.5 小时", snapshot.blocks.first().metrics.single { it.label == "游玩时长" }.displayValue)
            assertTrue(snapshot.blocks.any { it.id == "competitive" })
            assertFalse(snapshot.blocks.any { it.id == "quick" })
            assertFalse(snapshot.complete)
        } finally { server.shutdown() }
    }
    @Test fun dailyReceiptUsesMatchRowsWithNativeAuthAndOneRefresh() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), ZoneOffset.UTC)
        val range = QueryRange.at(Period.TODAY, clock)
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"name":"Player#123","customerToken":"target"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":[
                {"matchId":"one","beginTs":${range.startInclusive!! + 1000},"matchRet":1,"kill":0,"assist":2,"death":1,"heroDamage":3000,"cure":0},
                {"matchId":"old","beginTs":${range.startInclusive!! - 1},"matchRet":-1}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"gameTimeSec":600}}"""))
            server.start()
            var refreshes = 0
            val coordinator = CredentialCoordinator(Store(BusinessCredential("111", "old", "2026", "1", player))) {
                refreshes++; it.copy(token = "new")
            }
            val repo = DashenStatsRepository(coordinator, clock, apiRoot = server.url("/v1").toString())
            val snapshot = repo.query(player, range, DemoScenario.NORMAL, setOf(MatchMode.RANKED))
            assertEquals(1, refreshes); assertEquals(1, snapshot.matches.size)
            assertEquals(600L, snapshot.matches.single().durationSeconds)
            assertEquals("0", snapshot.blocks.last().metrics.first { it.label == "消灭" }.value)
            val calls = (1..4).map { server.takeRequest() }
            assertEquals("old", calls[0].getHeader("GL-Bigdata-Auth-Token"))
            assertTrue(calls.drop(1).all { it.getHeader("GL-Bigdata-Auth-Token") == "new" })
            assertEquals("target", calls[2].requestUrl!!.queryParameter("token"))
            assertEquals("sport", calls[2].requestUrl!!.queryParameter("gameMode"))
            assertTrue(calls.none { it.path!!.contains("queryCard") || it.path!!.contains("queryCountInfo") })
        } finally { server.shutdown() }
    }
    @Test fun retryAfterStopsImmediateRequestsAndKeepsSession() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
            server.start()
            var refreshes = 0
            val coordinator = CredentialCoordinator(Store(BusinessCredential("111", "old", "2026", "1", player))) { refreshes++; it }
            val repo = DashenStatsRepository(coordinator, apiRoot = server.url("/v1").toString())
            repeat(2) {
                val failure = runCatching { repo.query(player, QueryRange.at(Period.TODAY), DemoScenario.NORMAL) }.exceptionOrNull()
                assertEquals(StatsProblem.RATE_LIMIT, (failure as StatsException).problem)
            }
            assertEquals(1, server.requestCount); assertEquals(0, refreshes)
            assertEquals("old", coordinator.restore()?.token)
        } finally { server.shutdown() }
    }
}
