package dev.owreceipt.infra.network

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

data class MatchNames(val heroes: Map<String, String> = emptyMap(), val maps: Map<String, String> = emptyMap())

/** Public names only, refreshed on demand. The bundled official snapshot is an offline fallback. */
class OfficialNameCatalog(context: Context) {
    private val seed = Json.parseToJsonElement(context.assets.open("official_names.json").bufferedReader().use { it.readText() }).jsonObject
    private val mutex = Mutex()
    private var cached: MatchNames? = null
    private val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()
    private fun seedNames() = MatchNames(
        seed["heroes"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        seed["maps"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
    )
    suspend fun load(): MatchNames = mutex.withLock {
        cached?.let { return@withLock it }
        val fallback = seedNames()
        val names = coroutineScope {
            val hero = async { fetch("ow_hero_config.json", "heroGuid") }
            val map = async { fetch("ow_map_config.json", "guid") }
            MatchNames(hero.await().ifEmpty { fallback.heroes }, map.await().ifEmpty { fallback.maps })
        }
        cached = names
        names
    }
    private suspend fun fetch(file: String, key: String): Map<String, String> {
        return try {
            client.newCall(Request.Builder().url("https://s.166.net/config/ds_ow/$file").build()).awaitResponse().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val array = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonArray ?: return emptyMap()
                array.mapNotNull { item ->
                    val row = item as? JsonObject ?: return@mapNotNull null
                    val id = DashenPayloads.value(row, key) ?: return@mapNotNull null
                    val name = DashenPayloads.value(row, "name")?.takeIf { it.length <= 100 } ?: return@mapNotNull null
                    id to name
                }.toMap()
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { emptyMap() }
    }
}
