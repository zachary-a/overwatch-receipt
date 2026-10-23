package dev.owreceipt.infra.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** One exchange for concurrent authentication failures; each request retries at most once. */
class CredentialCoordinator(
    private val store: CredentialStore,
    private val exchange: suspend (BusinessCredential) -> BusinessCredential,
) {
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    @Volatile private var current: BusinessCredential? = null
    private var revision = 0L
    private var failedRefreshToken: String? = null
    private var failedRefreshAt: Long = 0
    private var failedRefresh: Exception? = null
    suspend fun restore(): BusinessCredential? = mutex.withLock {
        current ?: store.read()?.also { current = it }
    }
    suspend fun install(credential: BusinessCredential) = mutex.withLock {
        epoch.incrementAndGet()
        store.write(credential); current = credential; revision++
        failedRefreshToken = null; failedRefresh = null
    }
    suspend fun clear() {
        epoch.incrementAndGet()
        mutex.withLock { current = null; revision++; failedRefreshToken = null; failedRefresh = null; store.clear() }
    }
    suspend fun <T> authorized(request: suspend (BusinessCredential) -> T): T {
        val (original, originalRevision, originalEpoch) = mutex.withLock {
            val credential = current ?: store.read()?.also { current = it } ?: throw AuthenticationRequired()
            Triple(credential, revision, epoch.get())
        }
        try { return request(original) } catch (_: CredentialRejected) { /* only explicit authentication rejection */ }
        val replacement = mutex.withLock {
            if (epoch.get() != originalEpoch) throw AuthenticationRequired()
            val latest = current ?: throw AuthenticationRequired()
            if (revision != originalRevision) latest else {
                if (failedRefreshToken == latest.token && System.nanoTime() - failedRefreshAt < 5_000_000_000L)
                    throw (failedRefresh ?: AuthenticationRequired())
                val refreshed = try { exchange(latest) } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    failedRefreshToken = latest.token; failedRefreshAt = System.nanoTime(); failedRefresh = e
                    throw e
                }
                if (epoch.get() != originalEpoch) throw AuthenticationRequired()
                store.write(refreshed)
                current = refreshed
                revision++
                failedRefreshToken = null; failedRefresh = null
                refreshed
            }
        }
        try { return request(replacement) } catch (_: CredentialRejected) { throw AuthenticationRequired() }
    }
}
