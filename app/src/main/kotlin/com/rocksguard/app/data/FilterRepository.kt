package com.rocksguard.app.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.CompiledLists
import com.rocksguard.core.filter.FilterCompiler
import com.rocksguard.core.filter.FilterParser
import com.rocksguard.core.filter.FilterSnapshot
import com.rocksguard.core.filter.SourceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit

/** A filter list RocksGuard knows how to fetch. */
data class FilterList(
    val id: String,
    val name: String,
    val description: String,
    val category: Category,
    /** null = bundled in the APK. */
    val url: String?,
    val homepage: String?,
    val licence: String,
    val defaultEnabled: Boolean,
    val custom: Boolean = false,
)

data class ListMeta(
    val updatedAt: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val rules: Int = 0,
    val error: String? = null,
)

data class ListState(val list: FilterList, val enabled: Boolean, val meta: ListMeta, val downloaded: Boolean)

data class CompileState(
    val compiling: Boolean = false,
    val updating: Boolean = false,
    val totalDomains: Int = 0,
    val lastCompiledAt: Long = 0,
    val lastError: String? = null,
)

/**
 * Owns The Armoury: the catalogue of filter lists, their downloads, and the compiled
 * rule set. Compilation streams list files (no full copy in memory) and the result is
 * cached on disk, so the next start loads in milliseconds instead of re-parsing.
 */
class FilterRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "RocksGuard/Lists"
        private const val MAX_LIST_BYTES = 40L * 1024 * 1024

        val CATALOGUE = listOf(
            FilterList(
                "essentials-ads", "RocksGuard Essentials · Adverts", "Built-in starter list of major ad networks. Works offline.",
                Category.ADS, null, null, "RocksGuard", defaultEnabled = true,
            ),
            FilterList(
                "essentials-trackers", "RocksGuard Essentials · Trackers", "Built-in starter list of analytics and data brokers. Works offline.",
                Category.TRACKERS, null, null, "RocksGuard", defaultEnabled = true,
            ),
            FilterList(
                "hagezi-light", "HaGeZi Multi Light", "Ads, trackers and telemetry without breaking sites. Recommended.",
                Category.ADS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = true,
            ),
            FilterList(
                "hagezi-pro", "HaGeZi Multi Pro", "Stronger ads and tracking protection. Occasionally blocks something you want.",
                Category.ADS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "stevenblack", "StevenBlack Unified Hosts", "Long-standing consolidated hosts file of ads and malware.",
                Category.ADS, "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                "https://github.com/StevenBlack/hosts", "MIT", defaultEnabled = false,
            ),
            FilterList(
                "adguard-dns", "AdGuard DNS Filter", "AdGuard's DNS-optimised merge of popular ad filters.",
                Category.ADS, "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                "https://github.com/AdguardTeam/AdGuardSDNSFilter", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "easyprivacy", "EasyPrivacy (domains)", "Tracking domains extracted from EasyPrivacy by firebog.net.",
                Category.TRACKERS, "https://v.firebog.net/hosts/Easyprivacy.txt",
                "https://firebog.net", "GPL-3.0 / CC BY-SA 3.0", defaultEnabled = true,
            ),
            FilterList(
                "hagezi-native-samsung", "Native tracking · Samsung", "Samsung device telemetry.",
                Category.TRACKERS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/native.samsung.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "hagezi-native-xiaomi", "Native tracking · Xiaomi", "Xiaomi/MIUI device telemetry.",
                Category.TRACKERS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/native.xiaomi.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "hagezi-native-huawei", "Native tracking · Huawei", "Huawei device telemetry.",
                Category.TRACKERS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/native.huawei.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "hagezi-native-tiktok", "Native tracking · TikTok", "TikTok in-app tracking (the app keeps working).",
                Category.TRACKERS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/native.tiktok.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "hagezi-tif-mini", "HaGeZi Threat Intelligence (mini)", "Malware, phishing, scam and crypto-jacking domains.",
                Category.THREATS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.mini.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = true,
            ),
            FilterList(
                "hagezi-tif-medium", "HaGeZi Threat Intelligence (medium)", "Broader threat feed. Larger download.",
                Category.THREATS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.medium.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
            FilterList(
                "phishing-army", "Phishing Army", "Community phishing domains from PhishTank, OpenPhish, CERT.PL and others.",
                Category.THREATS, "https://phishing.army/download/phishing_army_blocklist.txt",
                "https://phishing.army", "CC BY-NC 4.0", defaultEnabled = false,
            ),
            FilterList(
                "hagezi-bypass", "Encrypted-DNS & proxy bypass", "Stops apps from silently switching to their own DNS, VPN or proxy to dodge filtering.",
                Category.BYPASS, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/doh-vpn-proxy-bypass.txt",
                "https://github.com/hagezi/dns-blocklists", "GPL-3.0", defaultEnabled = false,
            ),
        )

        private val BUNDLED_ASSETS = mapOf("essentials-ads" to "essentials_ads.txt", "essentials-trackers" to "essentials_trackers.txt")
    }

    private val prefs = context.getSharedPreferences("lists", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val compiledFile = File(context.filesDir, "compiled.bin")
    private val mutex = Mutex()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false) // never downgrade https → http
            .build()
    }

    private val _lists = MutableStateFlow(readStates())
    val lists: StateFlow<List<ListState>> = _lists.asStateFlow()

    private val _compile = MutableStateFlow(CompileState())
    val compileState: StateFlow<CompileState> = _compile.asStateFlow()

    private val compiled = MutableStateFlow(CompiledLists.EMPTY)

    /** The live rule snapshot used by the engine. */
    val snapshot: StateFlow<FilterSnapshot> = combine(
        compiled,
        settings.flow.map { Triple(it.userBlocked, it.userAllowed, it.categories) }.distinctUntilChanged(),
    ) { lists, (blocked, allowed, categories) ->
        lists.withSnapshot(blocked, allowed, categories)
    }.stateIn(scope, SharingStarted.Eagerly, FilterSnapshot.EMPTY)

    fun start() {
        scope.launch(Dispatchers.IO) {
            if (!loadCached()) rebuild()
        }
    }

    // ---- Catalogue state ---------------------------------------------------------------

    private fun customLists(): List<FilterList> =
        (prefs.getStringSet("custom", emptySet()) ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split('\u0001')
            if (parts.size < 4) return@mapNotNull null
            FilterList(
                parts[0], parts[1], "Custom list", runCatching { Category.valueOf(parts[3]) }.getOrDefault(Category.ADS),
                parts[2], null, "As published", defaultEnabled = true, custom = true,
            )
        }.sortedBy { it.name }

    private fun allLists() = CATALOGUE + customLists()

    private fun isEnabled(l: FilterList) = prefs.getBoolean("on:${l.id}", l.defaultEnabled)

    private fun meta(id: String): ListMeta = ListMeta(
        updatedAt = prefs.getLong("t:$id", 0),
        etag = prefs.getString("etag:$id", null),
        lastModified = prefs.getString("lm:$id", null),
        rules = prefs.getInt("n:$id", 0),
        error = prefs.getString("err:$id", null),
    )

    private fun fileFor(id: String) = File(dir, "$id.txt")

    private fun readStates() = allLists().map {
        ListState(it, isEnabled(it), meta(it.id), it.url == null || fileFor(it.id).exists())
    }

    private fun refreshStates() {
        _lists.value = readStates()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit { putBoolean("on:$id", enabled) }
        refreshStates()
        scope.launch(Dispatchers.IO) {
            val l = allLists().firstOrNull { it.id == id } ?: return@launch
            if (enabled && l.url != null && !fileFor(id).exists()) download(l)
            rebuild()
        }
    }

    /** Adds a user list. Only https:// URLs are accepted. */
    fun addCustom(name: String, url: String, category: Category): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return "Only https:// addresses are accepted."
        if (trimmed.toHttpUrlOrNull() == null) return "That address is not valid."
        val id = "custom-" + Integer.toHexString(trimmed.hashCode())
        val set = (prefs.getStringSet("custom", emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith("$id\u0001") }
        set += listOf(id, name.ifBlank { trimmed.substringAfter("://").substringBefore('/') }, trimmed, category.name).joinToString("\u0001")
        prefs.edit { putStringSet("custom", set); putBoolean("on:$id", true) }
        refreshStates()
        scope.launch(Dispatchers.IO) {
            allLists().firstOrNull { it.id == id }?.let { download(it) }
            rebuild()
        }
        return null
    }

    fun removeCustom(id: String) {
        val set = (prefs.getStringSet("custom", emptySet()) ?: emptySet()).filterNot { it.startsWith("$id\u0001") }.toSet()
        prefs.edit { putStringSet("custom", set); remove("on:$id") }
        fileFor(id).delete()
        refreshStates()
        scope.launch(Dispatchers.IO) { rebuild() }
    }

    // ---- Downloading --------------------------------------------------------------------

    /** Downloads every enabled list that has a URL, then recompiles. Returns lists updated. */
    suspend fun updateAll(): Int = withContext(Dispatchers.IO) {
        _compile.value = _compile.value.copy(updating = true)
        var changed = 0
        try {
            for (l in allLists()) if (l.url != null && isEnabled(l)) if (download(l)) changed++
            if (changed > 0 || compiled.value === CompiledLists.EMPTY) rebuild()
        } finally {
            _compile.value = _compile.value.copy(updating = false)
        }
        changed
    }

    fun updateAllAsync() = scope.launch { updateAll() }

    /** Conditional GET (ETag / Last-Modified). Returns true when new content was stored. */
    private fun download(l: FilterList): Boolean {
        val url = l.url ?: return false
        val m = meta(l.id)
        val target = fileFor(l.id)
        val req = Request.Builder().url(url).apply {
            if (target.exists()) {
                m.etag?.let { header("If-None-Match", it) }
                m.lastModified?.let { header("If-Modified-Since", it) }
            }
        }.build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 304 -> {
                        prefs.edit { putLong("t:${l.id}", System.currentTimeMillis()); remove("err:${l.id}") }
                        false
                    }
                    !resp.isSuccessful -> throw IOException("HTTP ${resp.code}")
                    else -> {
                        if (!resp.request.url.isHttps) throw IOException("redirected to an insecure address")
                        val tmp = File(dir, "${l.id}.part")
                        val body = resp.body ?: throw IOException("empty response")
                        var total = 0L
                        body.byteStream().use { input ->
                            BufferedOutputStream(tmp.outputStream()).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_LIST_BYTES) throw IOException("list is larger than 40 MB")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        // Validate before replacing the previous good copy.
                        val stats = FilterParser.Stats()
                        tmp.reader().use { FilterParser.parse(it, stats) {} }
                        if (stats.accepted == 0) {
                            tmp.delete(); throw IOException("no usable domains in the download")
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete(); throw IOException("could not store list")
                        }
                        prefs.edit {
                            putLong("t:${l.id}", System.currentTimeMillis())
                            putInt("n:${l.id}", stats.accepted)
                            resp.header("ETag")?.let { putString("etag:${l.id}", it) } ?: remove("etag:${l.id}")
                            resp.header("Last-Modified")?.let { putString("lm:${l.id}", it) } ?: remove("lm:${l.id}")
                            remove("err:${l.id}")
                        }
                        true
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "download ${l.id} failed: ${e.message}")
            prefs.edit { putString("err:${l.id}", e.message ?: "Download failed") }
            false
        } finally {
            refreshStates()
        }
    }

    // ---- Compilation --------------------------------------------------------------------

    private fun signature(): String = allLists().filter { isEnabled(it) }.joinToString("|") { l ->
        val f = fileFor(l.id)
        "${l.id}:${if (l.url == null) "asset" else if (f.exists()) "${f.length()}-${f.lastModified()}" else "missing"}"
    } + "|v1"

    private fun loadCached(): Boolean {
        if (!compiledFile.exists() || prefs.getString("signature", null) != signature()) return false
        return try {
            val t0 = System.nanoTime()
            val lists = DataInputStream(BufferedInputStream(compiledFile.inputStream(), 64 * 1024)).use { CompiledLists.readFrom(it) }
            compiled.value = lists
            _compile.value = _compile.value.copy(totalDomains = lists.block.size, lastCompiledAt = compiledFile.lastModified())
            Log.i(TAG, "loaded ${lists.block.size} rules from cache in ${(System.nanoTime() - t0) / 1_000_000} ms")
            true
        } catch (e: IOException) {
            Log.w(TAG, "compiled cache unreadable: ${e.message}")
            false
        }
    }

    private fun openList(l: FilterList): Reader? {
        BUNDLED_ASSETS[l.id]?.let { return InputStreamReader(context.assets.open(it)) }
        val f = fileFor(l.id)
        return if (f.exists()) f.reader() else null
    }

    suspend fun rebuild() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _compile.value = _compile.value.copy(compiling = true, lastError = null)
            try {
                val t0 = System.nanoTime()
                val compiler = FilterCompiler()
                for (l in allLists()) {
                    if (!isEnabled(l)) continue
                    val reader = openList(l) ?: continue
                    val n = reader.use { compiler.addList(SourceInfo(l.id, l.name, l.category), it) }
                    if (l.url == null) prefs.edit { putInt("n:${l.id}", n) }
                }
                val lists = compiler.build()
                val tmp = File(context.filesDir, "compiled.tmp")
                DataOutputStream(BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { lists.writeTo(it) }
                tmp.renameTo(compiledFile)
                prefs.edit { putString("signature", signature()) }
                compiled.value = lists
                _compile.value = CompileState(totalDomains = lists.block.size, lastCompiledAt = System.currentTimeMillis())
                Log.i(TAG, "compiled ${lists.block.size} rules in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (e: Exception) {
                Log.e(TAG, "compile failed", e)
                _compile.value = _compile.value.copy(compiling = false, lastError = e.message ?: "Compile failed")
            }
            refreshStates()
        }
    }

    /** First run: fetch the default lists in the background. */
    fun ensureDownloaded() {
        if (allLists().any { it.url != null && isEnabled(it) && !fileFor(it.id).exists() }) updateAllAsync()
    }
}
