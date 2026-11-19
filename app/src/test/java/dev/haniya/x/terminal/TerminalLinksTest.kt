package dev.haniya.x.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {
    private fun r(i: Int, t: String, wrap: Boolean = false) = ScreenRow.ascii(i, t, wrap)

    @Test fun singleRow() {
        val rows = listOf(r(0, "Open https://example.com/login?x=1. Then continue"))
        assertEquals("https://example.com/login?x=1", TerminalLinks.urlAt(rows, 0, 10, 80))
        assertNull(TerminalLinks.urlAt(rows, 0, 2, 80))
        assertNull(TerminalLinks.urlAt(rows, 0, 70, 80))
    }

    @Test fun softWrapped() {
        val url = "https://accounts.google.com/o/oauth2/auth?client_id=abc&redirect_uri=http%3A%2F%2Flocalhost"
        val parts = listOf("  " + url.substring(0, 18)) + url.substring(18).chunked(20)
        val rows = parts.mapIndexed { i, t -> r(i, t, i < parts.lastIndex) } + r(parts.size, "next line")
        for (row in parts.indices) assertEquals(url, TerminalLinks.urlAt(rows, row, 3, 20))
        assertNull(TerminalLinks.urlAt(rows, parts.size, 1, 20))
    }

    @Test fun hardWrappedAtRightEdgeWithIndent() {
        val rows = listOf(
            r(0, "Visit: https://claude.ai/oauth/a"),
            r(1, "  uthorize?code=true&state=xyz"),
            r(2, ""),
        )
        assertEquals("https://claude.ai/oauth/authorize?code=true&state=xyz", TerminalLinks.urlAt(rows, 1, 5, 32))
        assertEquals("https://claude.ai/oauth/authorize?code=true&state=xyz", TerminalLinks.urlAt(rows, 0, 10, 32))
    }

    @Test fun notJoinedWithNextSentenceAtEdge() {
        val rows = listOf(r(0, "Go to https://example.com/device"), r(1, "and enter the code"))
        assertEquals("https://example.com/device", TerminalLinks.urlAt(rows, 0, 8, 32))
    }

    @Test fun notJoinedWhenRowIsShort() {
        val rows = listOf(r(0, "see https://a.example/x"), r(1, "more/stuff"))
        assertEquals("https://a.example/x", TerminalLinks.urlAt(rows, 0, 8, 80))
    }

    @Test fun trailingBracketDropped() {
        val rows = listOf(r(0, "(docs: https://example.com/a_(b)/c)"))
        assertEquals("https://example.com/a_(b)/c", TerminalLinks.urlAt(rows, 0, 12, 80))
    }

    /** Antigravity's sign-in screen: a URL hard-wrapped by the TUI inside 1-column margins (cols = 47). */
    @Test fun antigravitySignInScreen() {
        val lines = listOf(
            "                                               ",
            " Your browser should open automatically. If    ",
            " not:                                          ",
            "                                               ",
            " https://accounts.google.com/o/oauth2/auth?acc ",
            " ess_type=offline&client_id=1071006060591-tmhs ",
            " sin2h21lcre235vtolojh4g403ep.apps.googleuserc ",
            " ontent.com&code_challenge=Vb5g9xgW2zj9HHSjZtx ",
            " jtej87KeLsu72DIG1IHpYVgY&code_challenge_metho ",
            " d=S256&prompt=consent&redirect_uri=https%3A%2 ",
            " F%2Fantigravity.google%2Foauth-callback&respo ",
            " nse_type=code&scope=https%3A%2F%2Fwww.googlea ",
            " pis.com%2Fauth%2Fcloud-platform+https%3A%2F%2 ",
            " Fwww.googleapis.com%2Fauth%2Fuserinfo.email+h ",
            " ttps%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuser ",
            " info.profile+https%3A%2F%2Fwww.googleapis.com ",
            " %2Fauth%2Fcclog+https%3A%2F%2Fwww.googleapis. ",
            " com%2Fauth%2Fexperimentsandconfigs+https%3A%2 ",
            " F%2Fwww.googleapis.com%2Fauth%2Faicode+openid ",
            " &state=xw7RVs3JlXrF7K3GZID-MA                 ",
            "                                               ",
            " Copy and paste the URL or click on the link   ",
            " below:                                        ",
            " ───────────────────────────────────────────── ",
        )
        val rows = lines.mapIndexed { i, t -> r(i, t) }
        val start = lines.indexOfFirst { it.contains("https://accounts") }
        val url = TerminalLinks.urlAt(rows, start + 7, 21, 47)!!
        org.junit.Assert.assertTrue(url, url.startsWith("https://accounts.google.com/o/oauth2/auth?access_type=offline"))
        org.junit.Assert.assertTrue(url, url.endsWith("&state=xw7RVs3JlXrF7K3GZID-MA"))
        assertEquals(url, TerminalLinks.urlAt(rows, start, 5, 47))
        assertNull(TerminalLinks.urlAt(rows, start - 2, 5, 47))
    }
}
