package com.miku.ray.handler

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The home screen and notification share one sample for the current exit. */
object ExitIpSnapshot {
    private val mutex = Mutex()
    private var epoch = 0L
    private var value: String? = null
    private var sampledAt = 0L

    @Synchronized fun current(): String? = value

    @Synchronized fun invalidate() { epoch++; value = null; sampledAt = 0L }

    suspend fun get(fetch: suspend () -> String?): String? = mutex.withLock {
        val request: Long
        synchronized(this) {
            value?.takeIf { SystemClock.elapsedRealtime() - sampledAt < 60_000L }?.let { return@withLock it }
            request = epoch
        }
        val fetched = fetch()
        synchronized(this) {
            if (epoch != request) null
            else fetched?.also { value = it; sampledAt = SystemClock.elapsedRealtime() }
        }
    }
}
