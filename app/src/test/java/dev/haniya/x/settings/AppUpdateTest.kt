package dev.haniya.x.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val body = """{"tag_name":"v0.1.4","html_url":"https://github.com/TodayYueC/HaniyaX/releases/tag/v0.1.4",
      "body":"- New thing\n\n---\n\n- 新功能",
      "assets":[{"name":"HaniyaX-0.1.4-arm64.apk.sha256","browser_download_url":"https://x/sum","size":90},
                {"name":"HaniyaX-0.1.4-arm64.apk","browser_download_url":"https://x/apk","size":30303038}]}"""

    @Test fun parsesRelease() {
        val r = AppUpdate.parse(body)!!
        assertEquals("0.1.4", r.version)
        assertEquals("https://x/apk", r.apkUrl)
        assertEquals("https://x/sum", r.sha256Url)
        assertEquals(30303038L, r.apkSize)
    }

    @Test fun comparesVersions() {
        val r = AppUpdate.parse(body)!!
        assertTrue(AppUpdate.isNewer(r, "0.1.3"))
        assertFalse(AppUpdate.isNewer(r, "0.1.4"))
        assertFalse(AppUpdate.isNewer(r, "0.2.0"))
    }

    @Test fun picksNotesByLanguage() {
        val r = AppUpdate.parse(body)!!
        assertEquals("- New thing", AppUpdate.notesFor(r.notes, chinese = false))
        assertEquals("- 新功能", AppUpdate.notesFor(r.notes, chinese = true))
    }

    @Test fun rejectsGarbage() {
        assertNull(AppUpdate.parse("not json"))
        assertNull(AppUpdate.parse("""{"tag_name":"nightly"}"""))
    }
}
