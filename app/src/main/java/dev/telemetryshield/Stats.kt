package dev.telemetryshield

import java.util.concurrent.atomic.AtomicLong

/** Process-wide counters shared between the VPN service and the UI. */
object Stats {
    @Volatile var running = false
    @Volatile var lastError: String? = null

    private val queries = AtomicLong()
    private val blocked = AtomicLong()
    private val recent = ArrayDeque<String>()
    private const val RECENT_MAX = 100

    val queryCount get() = queries.get()
    val blockedCount get() = blocked.get()

    fun recordQuery() { queries.incrementAndGet() }

    fun recordBlocked(name: String) {
        blocked.incrementAndGet()
        synchronized(recent) {
            recent.remove(name)
            recent.addFirst(name)
            while (recent.size > RECENT_MAX) recent.removeLast()
        }
    }

    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }

    fun forgetRecent(name: String) { synchronized(recent) { recent.remove(name) } }

    fun reset() {
        queries.set(0); blocked.set(0)
        synchronized(recent) { recent.clear() }
    }
}
