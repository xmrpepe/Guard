package com.rocksguard.core

import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.CompiledLists
import com.rocksguard.core.filter.FilterCompiler
import com.rocksguard.core.filter.FilterParser
import com.rocksguard.core.filter.SourceInfo
import com.rocksguard.core.filter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.StringReader

class FilterTest {
    private val hosts = """
        # comment
        127.0.0.1 localhost
        0.0.0.0 ads.example.com  # inline comment
        0.0.0.0 pixel.example.net tracker2.example.net
        0.0.0.0 0.0.0.0
        plain.example.org
    """.trimIndent()

    private val adblock = """
        [Adblock Plus]
        ! Title: test
        ||doubletrack.example^
        ||important.example^${'$'}important
        ||thirdparty-only.example^${'$'}script
        ||example.com/path^
        @@||safe.doubletrack.example^
        example.com##.banner
        *.wild.example
    """.trimIndent()

    @Test fun parsesFormats() {
        val accepted = ArrayList<FilterParser.Entry>()
        FilterParser.parse(StringReader(hosts + "\n" + adblock)) { accepted += it }
        val names = accepted.map { it.domain }
        assertEquals(
            listOf("ads.example.com", "pixel.example.net", "plain.example.org", "doubletrack.example", "important.example", "safe.doubletrack.example", "wild.example"),
            names,
        )
        assertTrue(accepted.first { it.domain == "safe.doubletrack.example" }.exception)
        assertFalse(accepted.first { it.domain == "ads.example.com" }.subdomains)
        val wild = accepted.first { it.domain == "wild.example" }
        assertTrue(wild.subdomains); assertFalse(wild.exact)
    }

    @Test fun normaliseRejectsJunk() {
        assertNull(FilterParser.normalise("192.168.1.1"))
        assertNull(FilterParser.normalise("no_dot"))
        assertNull(FilterParser.normalise("bad..example"))
        assertNull(FilterParser.normalise("sp ace.example"))
        assertEquals("ok.example", FilterParser.normalise("OK.Example."))
    }

    private fun snapshot(cats: Set<Category> = Category.entries.toSet(), userBlock: List<String> = emptyList(), userAllow: List<String> = emptyList()) =
        FilterCompiler().apply {
            addList(SourceInfo("hosts", "Test Hosts", Category.ADS), StringReader(hosts))
            addList(SourceInfo("ab", "Test Trackers", Category.TRACKERS), StringReader(adblock))
            addList(SourceInfo("threat", "Test Threats", Category.THREATS), StringReader("evil.example\n"))
        }.build().withSnapshot(userBlock, userAllow, cats)

    @Test fun hostsEntriesAreExact() {
        val s = snapshot()
        assertTrue(s.decide("ads.example.com").blocked)
        assertFalse(s.decide("sub.ads.example.com").blocked)
        assertFalse(s.decide("example.com").blocked)
    }

    @Test fun adblockEntriesCoverSubdomains() {
        val s = snapshot()
        val v = s.decide("a.b.doubletrack.example") as Verdict.BlockedByList
        assertEquals("doubletrack.example", v.matched)
        assertEquals(Category.TRACKERS, v.category)
        assertEquals("Test Trackers", v.source?.name)
        assertTrue(v.viaSubdomainRule)
    }

    @Test fun listExceptionsWin() {
        val s = snapshot()
        assertTrue(s.decide("safe.doubletrack.example") is Verdict.AllowedByListException)
        assertTrue(s.decide("x.safe.doubletrack.example") is Verdict.AllowedByListException)
    }

    @Test fun wildcardMatchesOnlySubdomains() {
        val s = snapshot()
        assertFalse(s.decide("wild.example").blocked)
        assertTrue(s.decide("x.wild.example").blocked)
    }

    @Test fun userRulesTakePrecedence() {
        val s = snapshot(userBlock = listOf("social.example"), userAllow = listOf("ads.example.com"))
        assertTrue(s.decide("ads.example.com") is Verdict.AllowedByUser)
        assertTrue(s.decide("feed.social.example") is Verdict.BlockedByUser)
    }

    @Test fun disabledCategoriesAreNotBlocked() {
        val s = snapshot(cats = setOf(Category.THREATS))
        assertFalse(s.decide("ads.example.com").blocked)
        assertTrue(s.decide("evil.example").blocked)
    }

    @Test fun compiledListsRoundTrip() {
        val lists = FilterCompiler().apply {
            addList(SourceInfo("ab", "Trackers", Category.TRACKERS), StringReader(adblock))
        }.build()
        val bytes = ByteArrayOutputStream().also { lists.writeTo(DataOutputStream(it)) }.toByteArray()
        val back = CompiledLists.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
        val s = back.withSnapshot(emptyList(), emptyList(), Category.entries.toSet())
        assertTrue(s.decide("x.doubletrack.example").blocked)
        assertEquals("Trackers", (s.decide("doubletrack.example") as Verdict.BlockedByList).source?.name)
    }

    /** Measures the memory/speed claims for a 300k-domain list. */
    @Test fun largeListIsCompactAndFast() {
        val sb = StringBuilder()
        repeat(300_000) { sb.append("||host").append(it).append(".example-ads").append(it % 997).append(".com^\n") }
        val rt = Runtime.getRuntime()
        System.gc()
        val t0 = System.nanoTime()
        val lists = FilterCompiler().apply { addList(SourceInfo("big", "Big", Category.ADS), StringReader(sb.toString())) }.build()
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val s = lists.withSnapshot(emptyList(), emptyList(), Category.entries.toSet())
        assertEquals(300_000, lists.block.size)
        val bytes = ByteArrayOutputStream().also { lists.writeTo(DataOutputStream(it)) }.size()
        val t1 = System.nanoTime()
        var blocked = 0
        repeat(200_000) { i -> if (s.decide("cdn.host$i.example-ads${i % 997}.com").blocked) blocked++ }
        val perLookupNs = (System.nanoTime() - t1) / 200_000
        assertEquals(200_000, blocked)
        println("BENCH build=${buildMs}ms serialized=${bytes / 1024}KiB lookup=${perLookupNs}ns heapUsed=${(rt.totalMemory() - rt.freeMemory()) / 1_048_576}MiB")
        assertTrue("serialized size ${bytes}", bytes < 4_000_000)
    }
}
