package dev.haniya.x.home

import dev.haniya.x.projects.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLogicTest {
    @Test fun validNames() {
        for (n in listOf("web", "my-app", "a_b.c", "项目1", "  padded  ")) assertNull(n, ProjectLogic.validateName(n))
    }

    @Test fun invalidNames() {
        assertEquals("请输入项目名称", ProjectLogic.validateName("   "))
        assertNotNull(ProjectLogic.validateName(".hidden"))
        assertNotNull(ProjectLogic.validateName("-rf"))
        assertNotNull(ProjectLogic.validateName("a/b"))
        assertNotNull(ProjectLogic.validateName("a b"))
        assertNotNull(ProjectLogic.validateName("x".repeat(65)))
        assertNotNull(ProjectLogic.validateName("$(rm)"))
    }

    @Test fun gitUrls() {
        assertNull(ProjectLogic.validateGitUrl(""))
        assertNull(ProjectLogic.validateGitUrl("https://github.com/a/b.git"))
        assertNull(ProjectLogic.validateGitUrl("git@github.com:a/b.git"))
        assertNull(ProjectLogic.validateGitUrl("ssh://git@host/x"))
        assertNotNull(ProjectLogic.validateGitUrl("github.com/a/b"))
        assertNotNull(ProjectLogic.validateGitUrl("https://x/a b"))
    }

    @Test fun repoNames() {
        assertEquals("b", ProjectLogic.repoNameFromUrl("https://github.com/a/b.git"))
        assertEquals("b", ProjectLogic.repoNameFromUrl("https://github.com/a/b/"))
        assertEquals("repo", ProjectLogic.repoNameFromUrl("git@github.com:repo.git"))
        assertEquals("b", ProjectLogic.repoNameFromUrl("git@github.com:a/b.git"))
        assertNull(ProjectLogic.repoNameFromUrl("https://github.com/a/.git"))
    }

    @Test fun shellQuoting() {
        assertEquals("'abc'", ProjectLogic.shellQuote("abc"))
        assertEquals("'it'\\''s'", ProjectLogic.shellQuote("it's"))
    }

    @Test fun pathNormalization() {
        assertEquals("/root/projects/a", ProjectLogic.normalizePath(" /root//projects/a/ "))
        assertEquals("/", ProjectLogic.normalizePath("/"))
        assertEquals("/x", ProjectLogic.normalizePath("x"))
    }

    @Test fun mergeKeepsPersistedAddsScannedAndSorts() {
        val persisted = listOf(
            Project("/root/projects/old", "old", lastOpenedAt = 10),
            Project("/root/projects/pinned", "pinned", lastOpenedAt = 1, pinned = true),
            Project("/root/elsewhere/gone", "gone", lastOpenedAt = 99),
        )
        val scanned = listOf(
            ScannedDir("/root/projects/old", "old", 500),
            ScannedDir("/root/projects/new1", "new1", 100),
            ScannedDir("/root/projects/new2", "new2", 200),
            ScannedDir("/root/projects/hidden", "hidden", 300),
        )
        val out = ProjectLogic.merge(persisted, scanned, hidden = setOf("/root/projects/hidden")) { it != "/root/elsewhere/gone" }
        assertEquals(listOf("pinned", "old", "new2", "new1"), out.map { it.name })
        // The persisted entry wins over the scanned directory of the same path.
        assertEquals(10L, out.first { it.name == "old" }.lastOpenedAt)
    }

    @Test fun gitErrorsAreReadable() {
        assertTrue(ProjectLogic.gitError("", 128, timedOut = true).contains("10 分钟"))
        assertTrue(ProjectLogic.gitError("bash: git: command not found", 127, false).contains("apt install git"))
        assertTrue(ProjectLogic.gitError("fatal: unable to access: Could not resolve host: github.com", 128, false).contains("网络"))
        assertTrue(ProjectLogic.gitError("remote: Repository not found.\nfatal: repository 'x' not found", 128, false).contains("找不到"))
        assertTrue(ProjectLogic.gitError("fatal: could not read Username for 'https://github.com': terminal prompts disabled", 128, false).contains("登录"))
        assertEquals("克隆失败：something odd", ProjectLogic.gitError("Cloning into 'x'...\nfatal: something odd\n", 128, false))
        assertEquals("克隆失败（退出码 3）", ProjectLogic.gitError("", 3, false))
    }
}
