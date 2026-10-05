package com.winschneid.adblocker.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsParserTest {
    private fun parse(line: String): List<String> {
        val out = ArrayList<String>()
        val count = HostsParser.parseLine(line, out::add)
        assertEquals(out.size, count)
        return out
    }

    @Test
    fun parsesHostsFileLines() {
        assertEquals(listOf("ads.example.com"), parse("0.0.0.0 ads.example.com"))
        assertEquals(listOf("ads.example.com"), parse("127.0.0.1\tADS.Example.com   # comment"))
        assertEquals(listOf("a.example.com", "b.example.net"), parse("0.0.0.0 a.example.com b.example.net"))
        assertEquals(listOf("v6.example.com"), parse(":: v6.example.com"))
        assertEquals(listOf("v6.example.com"), parse("fe80::1%lo0 v6.example.com"))
        assertEquals(listOf("ads.example.com"), parse("0.0.0.0 ads.example.com\r"))
    }

    @Test
    fun skipsLoopbackAndReservedEntries() {
        assertTrue(parse("127.0.0.1 localhost").isEmpty())
        assertTrue(parse("127.0.0.1 localhost.localdomain").isEmpty())
        assertTrue(parse("::1 localhost ip6-localhost ip6-loopback").isEmpty())
        assertTrue(parse("255.255.255.255 broadcasthost").isEmpty())
        assertTrue(parse("0.0.0.0 0.0.0.0").isEmpty())
        assertTrue(parse("fe00::0 ip6-localnet").isEmpty())
    }

    @Test
    fun parsesPlainDomainsAndAdblockDomainRules() {
        assertEquals(listOf("ads.example.com"), parse("ads.example.com"))
        assertEquals(listOf("tracker.example.net"), parse("||tracker.example.net^"))
        assertEquals(listOf("tracker.example.net"), parse("||tracker.example.net^ # comment"))
        assertTrue(parse("||example.com/path^").isEmpty())
        assertTrue(parse("||ads.*.example.com^").isEmpty())
        assertTrue(parse("||example.com^\$third-party").isEmpty())
        assertTrue(parse("||example.com").isEmpty())
        assertTrue(parse("@@||good.example.com^").isEmpty())
        assertTrue(parse("/banner/*/img^").isEmpty())
    }

    @Test
    fun skipsCommentsHeadersAndUnknownSyntax() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   ").isEmpty())
        assertTrue(parse("# 0.0.0.0 commented.example.com").isEmpty())
        assertTrue(parse("! Title: some list").isEmpty())
        assertTrue(parse("[Adblock Plus 2.0]").isEmpty())
        assertTrue(parse("address=/ads.example.com/0.0.0.0").isEmpty())
        assertTrue(parse("example.com CNAME other.example.com").isEmpty())
    }

    @Test
    fun normalizesDomains() {
        assertEquals("example.com", HostsParser.normalizeDomain("Example.COM."))
        assertEquals("wild.example.com", HostsParser.normalizeDomain("*.wild.example.com"))
        assertEquals("xn--wgv71a119e.jp", HostsParser.normalizeDomain("日本語.JP"))
        assertEquals("_dmarc.example.com", HostsParser.normalizeDomain("_dmarc.example.com"))
        assertNull(HostsParser.normalizeDomain("localhost"))
        assertNull(HostsParser.normalizeDomain("bad?.example.com"))
        assertNull(HostsParser.normalizeDomain("bad..example.com"))
        assertNull(HostsParser.normalizeDomain(".example.com"))
        assertNull(HostsParser.normalizeDomain("192.168.0.1"))
        assertNull(HostsParser.normalizeDomain("::1"))
        assertNull(HostsParser.normalizeDomain("a".repeat(64) + ".example.com"))
        assertNull(HostsParser.normalizeDomain(""))
    }

    @Test
    fun parsesRealWorldHostsFile() {
        val text = javaClass.getResourceAsStream("/hosts_sample.txt")!!.bufferedReader().use { it.readText() }
        val domains = LinkedHashSet<String>()
        val count = HostsParser.parse(text) { domains.add(it) }

        assertTrue("expected a reasonable number of entries, got $count", count >= 50)
        assertEquals(count, domains.size) // the sample has no duplicates
        assertFalse(domains.contains("localhost"))
        assertFalse(domains.contains("0.0.0.0"))
        assertFalse(domains.any { HostsParser.isIpLiteral(it) })
        for (domain in domains) {
            assertEquals(domain, HostsParser.normalizeDomain(domain))
            assertTrue("$domain should come from a 0.0.0.0 line", text.contains("0.0.0.0 $domain"))
        }
    }

    @Test
    fun parsesFromReader() {
        val reader = "0.0.0.0 a.example.com\n# c\n||b.example.com^\n".reader().buffered()
        val out = ArrayList<String>()
        assertEquals(2, HostsParser.parse(reader, out::add))
        assertEquals(listOf("a.example.com", "b.example.com"), out)
    }
}
