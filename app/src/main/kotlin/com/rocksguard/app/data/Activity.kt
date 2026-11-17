package com.rocksguard.app.data

import android.content.Context
import androidx.core.content.edit
import com.rocksguard.core.engine.QueryEvent
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/** One line in The Lookout. Kept in memory only; never written to disk. */
class ActivityEntry(
    val id: Long,
    val time: Long,
    val name: String,
    val type: Int,
    val uid: Int,
    val verdict: Verdict,
    val transport: String?,
    val encrypted: Boolean,
    val latencyMs: Long,
    val cached: Boolean,
    val failed: Boolean,
)

/** Bounded in-memory ring buffer of recent queries. */
class ActivityLog(private val capacity: Int = 1500) {
    private val buffer = ArrayDeque<ActivityEntry>(capacity)
    private var nextId = 0L
    private val _version = MutableStateFlow(0L)

    /** Bumped on every change; UI samples it rather than recomposing per query. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun add(e: QueryEvent) {
        if (buffer.size == capacity) buffer.removeFirst()
        buffer.addLast(
            ActivityEntry(nextId++, e.timeMillis, e.name, e.type, e.uid, e.verdict, e.transport, e.encrypted, e.latencyMs, e.cached, e.failed),
        )
        _version.value = nextId
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<ActivityEntry> = buffer.reversed()

    @Synchronized
    fun clear() {
        buffer.clear(); _version.value = ++nextId
    }
}

data class Counters(
    val total: Long = 0,
    val adverts: Long = 0,
    val trackers: Long = 0,
    val threats: Long = 0,
    val bypass: Long = 0,
    val firewall: Long = 0,
    val user: Long = 0,
    val cname: Long = 0,
    val cached: Long = 0,
    val encrypted: Long = 0,
    val failed: Long = 0,
    val refusedConnections: Long = 0,
) {
    val blocked get() = adverts + trackers + threats + bypass + firewall + user
    val forwarded get() = total - blocked - cached
}

data class StatsSnapshot(
    val today: Counters = Counters(),
    val lifetime: Counters = Counters(),
    /** Queries per minute for the last 60 minutes, oldest first. */
    val perMinute: IntArray = IntArray(60),
    val perMinuteBlocked: IntArray = IntArray(60),
    val topBlocked: List<Pair<String, Int>> = emptyList(),
    val topApps: List<Triple<Int, Int, Int>> = emptyList(), // uid, blocked, total
    val sinceMillis: Long = System.currentTimeMillis(),
)

/**
 * Real measurements only: every counter is incremented by the engine for an actual
 * query. Day counters reset at local midnight; lifetime counters persist.
 */
class StatsStore(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private var today = load("d_")
    private var lifetime = load("l_")
    private var day = prefs.getLong("day", dayKey())
    private val minuteTotals = IntArray(60)
    private val minuteBlocked = IntArray(60)
    private var minuteBase = currentMinute()
    private val blockedDomains = HashMap<String, Int>()
    private val apps = HashMap<Int, IntArray>()
    private val since = System.currentTimeMillis()
    private var publishJob: Job? = null
    private var persistJob: Job? = null

    private val _flow = MutableStateFlow(build())
    val flow: StateFlow<StatsSnapshot> = _flow.asStateFlow()

    /**
     * Event-driven: a change schedules one publish 500 ms later and one save 30 s later.
     * When nothing happens nothing runs, so an idle device is never woken by RocksGuard.
     */
    private fun changed() {
        if (publishJob?.isActive != true) {
            publishJob = scope.launch(Dispatchers.Default) {
                delay(500)
                _flow.value = synchronized(this@StatsStore) { build() }
            }
        }
        if (persistJob?.isActive != true) {
            persistJob = scope.launch(Dispatchers.IO) { delay(30_000); persist() }
        }
    }

    private fun currentMinute() = System.currentTimeMillis() / 60_000

    private fun dayKey(): Long {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun load(p: String) = Counters(
        prefs.getLong(p + "total", 0), prefs.getLong(p + "ads", 0), prefs.getLong(p + "trk", 0), prefs.getLong(p + "thr", 0),
        prefs.getLong(p + "byp", 0), prefs.getLong(p + "fw", 0), prefs.getLong(p + "usr", 0), prefs.getLong(p + "cname", 0),
        prefs.getLong(p + "cached", 0), prefs.getLong(p + "enc", 0), prefs.getLong(p + "fail", 0), prefs.getLong(p + "ref", 0),
    )

    @Synchronized
    fun persist() = prefs.edit {
        fun put(p: String, c: Counters) {
            putLong(p + "total", c.total); putLong(p + "ads", c.adverts); putLong(p + "trk", c.trackers)
            putLong(p + "thr", c.threats); putLong(p + "byp", c.bypass); putLong(p + "fw", c.firewall)
            putLong(p + "usr", c.user); putLong(p + "cname", c.cname); putLong(p + "cached", c.cached)
            putLong(p + "enc", c.encrypted); putLong(p + "fail", c.failed); putLong(p + "ref", c.refusedConnections)
        }
        put("d_", today); put("l_", lifetime); putLong("day", day)
    }

    private fun rollDay() {
        val k = dayKey()
        if (k != day) {
            day = k; today = Counters()
        }
    }

    private fun rollMinutes() {
        val now = currentMinute()
        val shift = (now - minuteBase).coerceAtMost(60).toInt()
        if (shift <= 0) return
        System.arraycopy(minuteTotals, shift.coerceAtMost(60), minuteTotals, 0, 60 - shift.coerceAtMost(60))
        System.arraycopy(minuteBlocked, shift.coerceAtMost(60), minuteBlocked, 0, 60 - shift.coerceAtMost(60))
        for (i in 60 - shift until 60) { minuteTotals[i] = 0; minuteBlocked[i] = 0 }
        minuteBase = now
    }

    private fun Counters.plus(e: QueryEvent): Counters {
        val v = e.verdict
        var c = copy(total = total + 1)
        if (e.cached) c = c.copy(cached = c.cached + 1)
        if (e.encrypted && !e.cached) c = c.copy(encrypted = c.encrypted + 1)
        if (e.failed) c = c.copy(failed = c.failed + 1)
        if (v.blocked) {
            c = when (v) {
                is Verdict.BlockedByFirewall -> c.copy(firewall = c.firewall + 1)
                is Verdict.BlockedByUser -> c.copy(user = c.user + 1)
                else -> when (v.statCategory) {
                    Category.ADS -> c.copy(adverts = c.adverts + 1)
                    Category.TRACKERS -> c.copy(trackers = c.trackers + 1)
                    Category.THREATS -> c.copy(threats = c.threats + 1)
                    Category.BYPASS -> c.copy(bypass = c.bypass + 1)
                    Category.NONE -> c.copy(user = c.user + 1)
                }
            }
            if (v is Verdict.BlockedCname) c = c.copy(cname = c.cname + 1)
        }
        return c
    }

    @Synchronized
    fun record(e: QueryEvent) {
        rollDay(); rollMinutes()
        today = today.plus(e)
        lifetime = lifetime.plus(e)
        minuteTotals[59]++
        if (e.verdict.blocked) {
            minuteBlocked[59]++
            if (blockedDomains.size > 4000) blockedDomains.entries.removeAll { it.value <= 1 }
            blockedDomains.merge(e.name, 1, Int::plus)
        }
        if (e.uid >= 0) {
            val a = apps.getOrPut(e.uid) { IntArray(2) }
            a[1]++
            if (e.verdict.blocked) a[0]++
        }
        changed()
    }

    @Synchronized
    fun recordRefused() {
        rollDay()
        today = today.copy(refusedConnections = today.refusedConnections + 1)
        lifetime = lifetime.copy(refusedConnections = lifetime.refusedConnections + 1)
        changed()
    }

    @Synchronized
    fun reset() {
        today = Counters(); lifetime = Counters()
        minuteTotals.fill(0); minuteBlocked.fill(0)
        blockedDomains.clear(); apps.clear()
        changed()
        persist()
    }

    @Synchronized
    private fun build(): StatsSnapshot {
        rollMinutes()
        return StatsSnapshot(
            today = today,
            lifetime = lifetime,
            perMinute = minuteTotals.copyOf(),
            perMinuteBlocked = minuteBlocked.copyOf(),
            topBlocked = blockedDomains.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            topApps = apps.entries.sortedByDescending { it.value[0] }.take(10).map { Triple(it.key, it.value[0], it.value[1]) },
            sinceMillis = since,
        )
    }
}
