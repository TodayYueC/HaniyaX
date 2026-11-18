package dev.haniya.x.home

import dev.haniya.x.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutOpsTest {
    private val all: (String) -> Boolean = { true }
    private fun ids(l: HomeLayout) = l.modules.map { it.id }

    @Test fun defaultLayoutHasBuiltinsInOrder() {
        assertEquals(listOf("projects", "tasks", "services"), ids(HomeLayout()))
    }

    @Test fun reconcileAppendsMissingBuiltinsAndNewPlugins() {
        val l = HomeLayout(modules = listOf(HomeModule("services")))
        val r = HomeLayoutOps.reconcile(l, listOf("a", "b")) { false }
        assertEquals(listOf("services", "projects", "tasks", "plugin:a", "plugin:b"), ids(r))
    }

    @Test fun reconcileDropsGonePluginsButKeepsOnesStillOnDisk() {
        val l = HomeLayout(modules = HomeLayout().modules + HomeModule("plugin:gone") + HomeModule("plugin:loading", hidden = true))
        val r = HomeLayoutOps.reconcile(l, emptyList()) { it == "gone" }
        assertEquals(listOf("projects", "tasks", "services", "plugin:loading"), ids(r))
        assertTrue(r.modules.last().hidden)
    }

    @Test fun reconcileDropsUnknownIdsAndDuplicates() {
        val l = HomeLayout(modules = listOf(HomeModule("weird"), HomeModule("tasks"), HomeModule("tasks", hidden = true), HomeModule("plugin:")))
        val r = HomeLayoutOps.reconcile(l, emptyList()) { true }
        assertEquals(listOf("tasks", "projects", "services"), ids(r))
        assertFalse(r.modules.first().hidden)
    }

    @Test fun reconcileReturnsSameInstanceWhenNothingChanges() {
        val l = HomeLayout(modules = HomeLayout().modules + HomeModule("plugin:x"))
        assertSame(l, HomeLayoutOps.reconcile(l, listOf("x")) { false })
    }

    @Test fun pinnedModulesComeFirst() {
        val l = HomeLayoutOps.setPinned(HomeLayout(), "services", true)
        assertEquals(listOf("services", "projects", "tasks"), ids(l))
        // Unpinning keeps the current position; the module just stops being held at the top.
        val unpinned = HomeLayoutOps.setPinned(l, "services", false)
        assertEquals(listOf("services", "projects", "tasks"), ids(unpinned))
        assertFalse(unpinned.modules.first().pinned)
        assertTrue(HomeLayoutOps.canMove(unpinned, "services", +1, all))
    }

    @Test fun moveSwapsWithVisibleNeighbourOnly() {
        val l = HomeLayoutOps.setHidden(HomeLayout(), "tasks", true)
        // projects, (tasks hidden), services: moving services up jumps over the hidden module.
        val r = HomeLayoutOps.move(l, "services", -1, all)
        assertEquals(listOf("services", "tasks", "projects"), ids(r))
        assertEquals(listOf("services", "projects"), HomeLayoutOps.visible(r, all).map { it.id })
    }

    @Test fun moveDoesNotCrossThePinnedGroup() {
        val l = HomeLayoutOps.setPinned(HomeLayout(), "tasks", true) // tasks, projects, services
        assertEquals(l, HomeLayoutOps.move(l, "projects", -1, all))
        assertFalse(HomeLayoutOps.canMove(l, "projects", -1, all))
        assertTrue(HomeLayoutOps.canMove(l, "projects", +1, all))
        assertFalse(HomeLayoutOps.canMove(l, "tasks", -1, all))
        assertFalse(HomeLayoutOps.canMove(l, "services", +1, all))
    }

    @Test fun moveIgnoresUnavailableModules() {
        val l = HomeLayout(modules = HomeLayout().modules + HomeModule("plugin:p"))
        val available: (String) -> Boolean = { it != "services" }
        val r = HomeLayoutOps.move(l, "plugin:p", -1, available)
        assertEquals(listOf("projects", "plugin:p", "services", "tasks"), ids(r))
    }

    @Test fun visibleAndHiddenRespectAvailability() {
        val l = HomeLayoutOps.setHidden(HomeLayout(modules = HomeLayout().modules + HomeModule("plugin:p")), "plugin:p", true)
        assertEquals(listOf("plugin:p"), HomeLayoutOps.hidden(l, all).map { it.id })
        assertTrue(HomeLayoutOps.hidden(l) { !it.startsWith("plugin:") }.isEmpty())
        assertEquals(listOf("projects", "tasks", "services"), HomeLayoutOps.visible(l, all).map { it.id })
    }

    @Test fun pluginIdParsing() {
        assertEquals("abc", HomeLayoutOps.pluginIdOf("plugin:abc"))
        assertEquals(null, HomeLayoutOps.pluginIdOf("plugin:"))
        assertEquals(null, HomeLayoutOps.pluginIdOf("projects"))
        assertEquals("plugin:x", HomeLayoutOps.pluginModuleId("x"))
    }

    @Test fun layoutWithStartTargetsRoundTripsThroughJson() {
        for (start in listOf(StartTarget.Home, StartTarget.Project("/root/projects/web", "claude"), StartTarget.Project("/root/a"), StartTarget.Task("t1"))) {
            val l = HomeLayout(modules = listOf(HomeModule("tasks", pinned = true), HomeModule("plugin:x", hidden = true)), start = start)
            val text = AppJson.encodeToString(HomeLayout.serializer(), l)
            assertEquals(l, AppJson.decodeFromString(HomeLayout.serializer(), text))
        }
    }

    @Test fun missingFieldsDecodeToDefaults() {
        val l = AppJson.decodeFromString(HomeLayout.serializer(), "{}")
        assertEquals(HomeLayout(), l)
    }
}
