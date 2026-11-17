package com.rocksguard.core.dns

/**
 * Memory-bounded LRU cache of upstream DNS responses that honours record TTLs.
 *
 * Bounded both by entry count and by total bytes so it stays predictable on
 * entry-level devices. Returned responses get the caller's transaction ID and
 * TTLs aged by the time spent in the cache (so downstream caches stay correct).
 */
class DnsCache(
    private val maxEntries: Int = 2048,
    private val maxBytes: Int = 1 shl 20,
    private val maxTtlSeconds: Int = 3600,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val data: ByteArray, val storedAt: Long, val expiresAt: Long)

    private var bytes = 0
    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size > maxEntries || bytes > maxBytes) {
                bytes -= eldest.value.data.size
                return true
            }
            return false
        }
    }

    var hits = 0L
        private set
    var misses = 0L
        private set

    val size: Int @Synchronized get() = map.size

    fun key(q: DnsQuery): String =
        buildString(q.name.length + 12) {
            append(q.name).append('|').append(q.type).append('|').append(q.qclass)
            if (q.dnssecOk) append("|do")
            if (q.checkingDisabled) append("|cd")
        }

    @Synchronized
    fun get(q: DnsQuery): ByteArray? {
        val k = key(q)
        val e = map[k]
        val now = clock()
        if (e == null || now >= e.expiresAt) {
            if (e != null) {
                map.remove(k); bytes -= e.data.size
            }
            misses++
            return null
        }
        hits++
        val copy = e.data.copyOf()
        DnsWire.setId(copy, q.id)
        try {
            DnsWire.ageTtls(copy, (now - e.storedAt) / 1000)
        } catch (_: DnsFormatException) {
            return null
        }
        return copy
    }

    /** Stores [response] if it is cacheable. Returns the TTL used (0 = not cached). */
    @Synchronized
    fun put(q: DnsQuery, response: ByteArray): Int {
        val ttl = try {
            DnsWire.cacheTtl(response)
        } catch (_: DnsFormatException) {
            0
        }.coerceAtMost(maxTtlSeconds)
        if (ttl <= 0 || response.size > 4096) return 0
        val now = clock()
        val old = map.put(key(q), Entry(response.copyOf(), now, now + ttl * 1000L))
        if (old != null) bytes -= old.data.size
        bytes += response.size
        return ttl
    }

    @Synchronized
    fun clear() {
        map.clear(); bytes = 0
    }
}
