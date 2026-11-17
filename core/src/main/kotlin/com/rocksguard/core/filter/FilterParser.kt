package com.rocksguard.core.filter

import java.io.BufferedReader
import java.io.Reader

/**
 * Streaming parser for the three blocklist formats in common use:
 *  - hosts files:          `0.0.0.0 ads.example.com`      → exact name
 *  - plain domain lists:   `ads.example.com`              → exact name
 *  - Adblock DNS syntax:   `||example.com^`               → name and all subdomains
 *                          `@@||example.com^`             → exception
 *                          `*.example.com`                → subdomains only
 *
 * Browser-only rules (paths, cosmetic filters, regexes, rules restricted by
 * modifiers that have no DNS meaning) are skipped instead of being guessed at,
 * because a wrong guess either over-blocks or silently does nothing.
 */
object FilterParser {

    class Entry(val domain: String, val exact: Boolean, val subdomains: Boolean, val exception: Boolean)

    class Stats {
        var lines = 0
        var accepted = 0
        var skipped = 0
    }

    private val IGNORED_HOSTS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts", "0.0.0.0",
    )

    private val HOSTS_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "::0")

    // Modifiers that are harmless (or meaningful) for DNS-level blocking.
    private val ALLOWED_MODIFIERS = setOf("important", "all", "document", "doc", "popup", "third-party", "3p")

    fun parse(reader: Reader, stats: Stats = Stats(), sink: (Entry) -> Unit) {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, 16 * 1024)
        while (true) {
            val raw = br.readLine() ?: break
            stats.lines++
            val e = parseLine(raw)
            if (e == null) {
                if (raw.isNotBlank() && !isComment(raw.trim())) stats.skipped++
                continue
            }
            stats.accepted++
            sink(e)
        }
    }

    private fun isComment(s: String) = s.startsWith('#') || s.startsWith('!') || s.startsWith('[')

    fun parseLine(raw: String): Entry? {
        var line = raw.trim()
        if (line.isEmpty() || isComment(line)) return null
        // strip trailing inline comment (hosts style)
        val hash = line.indexOf(" #")
        if (hash > 0) line = line.substring(0, hash).trim()

        if (line.startsWith("||") || line.startsWith("@@||")) return parseAdblock(line)
        if (line.startsWith("@@") || line.startsWith("/") || line.startsWith("|") || line.contains("##") || line.contains("#@#")) return null

        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        val candidate = when {
            parts.size >= 2 && parts[0] in HOSTS_IPS -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        if (candidate.startsWith("*.")) {
            val d = normalise(candidate.substring(2)) ?: return null
            return Entry(d, exact = false, subdomains = true, exception = false)
        }
        val d = normalise(candidate) ?: return null
        if (d in IGNORED_HOSTS) return null
        return Entry(d, exact = true, subdomains = false, exception = false)
    }

    private fun parseAdblock(line: String): Entry? {
        val exception = line.startsWith("@@")
        var body = line.removePrefix("@@").removePrefix("||")
        val dollar = body.indexOf('$')
        if (dollar >= 0) {
            val mods = body.substring(dollar + 1).split(',').map { it.trim().lowercase() }
            if (mods.any { it !in ALLOWED_MODIFIERS }) return null
            body = body.substring(0, dollar)
        }
        body = body.removeSuffix("|")
        if (!body.endsWith('^')) return null
        body = body.removeSuffix("^")
        if (body.contains('/') || body.contains('*') || body.contains('^')) return null
        val d = normalise(body) ?: return null
        return Entry(d, exact = true, subdomains = true, exception = exception)
    }

    /** Lowercases and validates a domain name; returns null when it is not a usable hostname. */
    fun normalise(input: String): String? {
        val s = input.trim().trimEnd('.').lowercase()
        if (s.isEmpty() || s.length > 253 || !s.contains('.')) return null
        if (s.startsWith('.') || s.startsWith('-')) return null
        var labelLen = 0
        var allDigitsOrDots = true
        for (c in s) {
            when (c) {
                '.' -> {
                    if (labelLen == 0) return null
                    labelLen = 0
                }
                in 'a'..'z', '-', '_' -> { labelLen++; allDigitsOrDots = false }
                in '0'..'9' -> labelLen++
                else -> return null
            }
            if (labelLen > 63) return null
        }
        if (labelLen == 0) return null
        if (allDigitsOrDots) return null // IPv4 literal, not a domain
        return s
    }
}
