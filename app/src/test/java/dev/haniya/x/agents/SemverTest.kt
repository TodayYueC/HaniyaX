package dev.haniya.x.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {
    @Test fun parsesReleasesAndPrereleases() {
        assertEquals(Semver(2, 1, 325), Semver.parse("2.1.325"))
        assertEquals(Semver(0, 169, 0), Semver.parse("v0.169.0"))
        assertEquals(Semver(0, 0, 0, listOf("beta-17236")), Semver.parse("0.0.0-beta-17236"))
        assertEquals(Semver(1, 2, 3, listOf("alpha", "4")), Semver.parse("1.2.3-alpha.4+build.7"))
        assertNull(Semver.parse("2.1"))
        assertNull(Semver.parse("latest"))
        assertNull(Semver.parse(null))
        assertNull(Semver.parse("2.1.3 extra"))
    }

    @Test fun ordersNumericallyNotLexically() {
        assertTrue(Semver.parse("2.1.325")!! > Semver.parse("2.1.99")!!)
        assertTrue(Semver.parse("0.170.0")!! > Semver.parse("0.169.9")!!)
        assertTrue(Semver.parse("10.0.0")!! > Semver.parse("9.9.9")!!)
    }

    @Test fun prereleaseRanksBelowRelease() {
        assertTrue(Semver.parse("2.3.0")!! > Semver.parse("2.3.0-rc.1")!!)
        assertTrue(Semver.parse("2.3.0-rc.2")!! > Semver.parse("2.3.0-rc.1")!!)
        assertTrue(Semver.parse("2.3.0-rc.10")!! > Semver.parse("2.3.0-rc.9")!!)
        assertTrue(Semver.parse("1.0.0-beta")!! > Semver.parse("1.0.0-alpha.1")!!)
        // numeric identifiers rank below alphanumeric ones; more identifiers rank higher
        assertTrue(Semver.parse("1.0.0-alpha.beta")!! > Semver.parse("1.0.0-alpha.1")!!)
        assertTrue(Semver.parse("1.0.0-alpha.1")!! > Semver.parse("1.0.0-alpha")!!)
    }

    @Test fun isNewerIgnoresUnparseable() {
        assertTrue(Semver.isNewer("2.1.326", "2.1.325"))
        assertFalse(Semver.isNewer("2.1.325", "2.1.325"))
        assertFalse(Semver.isNewer("2.1.324", "2.1.325"))
        assertFalse(Semver.isNewer("garbage", "2.1.325"))
        assertFalse(Semver.isNewer("3.0.0", ""))
        assertFalse(Semver.isNewer(null, "1.0.0"))
    }

    @Test fun extractsVersionFromToolOutput() {
        assertEquals("2.1.325", Semver.extract("2.1.325 (Claude Code)"))
        assertEquals("0.169.0", Semver.extract("codex-cli 0.169.0"))
        assertEquals("2.3.0", Semver.extract("opencode 2.3.0\n"))
        assertEquals("1.4.0-beta.2", Semver.extract("tool v1.4.0-beta.2."))
        assertNull(Semver.extract("command not found"))
        // an IP-like or longer dotted number does not start mid-token
        assertEquals("1.2.3", Semver.extract("build 1.2.3 on host"))
    }

    @Test fun compareFallsBackForUnparseable() {
        assertTrue(Semver.compare("1.0.0", "x") > 0)
        assertTrue(Semver.compare("x", "1.0.0") < 0)
        assertEquals(0, Semver.compare("1.0.0", "v1.0.0"))
    }
}
