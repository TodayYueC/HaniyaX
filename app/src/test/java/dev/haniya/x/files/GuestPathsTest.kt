package dev.haniya.x.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPathsTest {
    @Test fun normalizeResolvesDots() {
        assertEquals("/root/a", GuestPaths.normalize("/root/./x/../a/"))
        assertEquals("/", GuestPaths.normalize("/../.."))
    }

    @Test fun parentAndChild() {
        assertEquals("/root", GuestPaths.parent("/root/projects"))
        assertNull(GuestPaths.parent("/"))
        assertEquals("/root/projects/web", GuestPaths.child("/root/projects", "web"))
        // A crafted name cannot escape through child().
        assertEquals("/root", GuestPaths.child("/root/projects", ".."))
    }

    @Test fun nameValidation() {
        assertNull(GuestPaths.validName("my app.txt"))
        assertNotNull(GuestPaths.validName("a/b"))
        assertNotNull(GuestPaths.validName(".."))
        assertNotNull(GuestPaths.validName("  "))
    }

    @Test fun phoneStorageDetection() {
        assertTrue(GuestPaths.isPhoneStorage("/sdcard/Download"))
        assertTrue(GuestPaths.isPhoneStorage("/storage/emulated/0"))
        assertFalse(GuestPaths.isPhoneStorage("/sdcardx"))
        assertFalse(GuestPaths.isPhoneStorage("/root/sdcard"))
    }

    @Test fun displayPaths() {
        assertEquals("~/projects/web", GuestPaths.display("/root/projects/web"))
        assertEquals("手机存储/Download", GuestPaths.display("/sdcard/Download"))
    }

    @Test fun textDetection() {
        assertTrue(GuestPaths.isTextName("index.tsx"))
        assertTrue(GuestPaths.isTextName(".gitignore"))
        assertTrue(GuestPaths.isTextName("Makefile"))
        assertFalse(GuestPaths.isTextName("photo.jpg"))
    }
}
