package com.winschneid.adblocker.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainMatcherTest {
    private val matcher = DomainMatcher(
        blocked = setOf("doubleclick.net", "ads.example.com"),
        userAllow = setOf("good.example.com", "allowed.doubleclick.net"),
        userDeny = setOf("bad.example.org"),
    )

    @Test
    fun blocksExactAndSubdomainMatches() {
        assertEquals(Decision.BLOCKED, matcher.check("doubleclick.net"))
        assertEquals(Decision.BLOCKED, matcher.check("ad.doubleclick.net"))
        assertEquals(Decision.BLOCKED, matcher.check("stats.g.DoubleClick.NET."))
        assertEquals(Decision.BLOCKED, matcher.check("x.ads.example.com"))
    }

    @Test
    fun allowsEverythingElse() {
        assertEquals(Decision.ALLOWED, matcher.check("example.com"))
        assertEquals(Decision.ALLOWED, matcher.check("notdoubleclick.net"))
        assertEquals(Decision.ALLOWED, matcher.check("www.example.com"))
        assertEquals(Decision.ALLOWED, matcher.check("localhost"))
        assertEquals(Decision.ALLOWED, matcher.check(""))
    }

    @Test
    fun userRulesTakePrecedence() {
        assertEquals(Decision.ALLOWED_BY_USER, matcher.check("good.example.com"))
        assertEquals(Decision.ALLOWED_BY_USER, matcher.check("allowed.doubleclick.net"))
        assertEquals(Decision.ALLOWED_BY_USER, matcher.check("sub.allowed.doubleclick.net"))
        assertEquals(Decision.BLOCKED_BY_USER, matcher.check("bad.example.org"))
        assertEquals(Decision.BLOCKED_BY_USER, matcher.check("cdn.bad.example.org"))
    }

    @Test
    fun topLevelDomainsNeverMatch() {
        val tldMatcher = DomainMatcher(setOf("com"))
        assertEquals(Decision.ALLOWED, tldMatcher.check("example.com"))
        assertNull(DomainMatcher.findMatch(setOf("com"), "example.com"))
    }

    @Test
    fun reportsMatchingEntry() {
        assertEquals("doubleclick.net", matcher.matchingBlockEntry("ad.doubleclick.net"))
        assertEquals("ads.example.com", matcher.matchingBlockEntry("ads.example.com"))
        assertNull(matcher.matchingBlockEntry("example.com"))
        assertEquals(2, matcher.blockedCount)
        assertEquals(0, DomainMatcher.EMPTY.blockedCount)
    }

    @Test
    fun builtinListIsWellFormed() {
        for (domain in BuiltinBlocklist.domains) {
            assertEquals(domain, HostsParser.normalizeDomain(domain))
        }
        val builtin = DomainMatcher(BuiltinBlocklist.domains)
        assertEquals(Decision.BLOCKED, builtin.check("pagead2.googlesyndication.com"))
        assertEquals(Decision.ALLOWED, builtin.check("www.google.com"))
    }
}
