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
    /** A current-format layout with the services card added (most tests move modules around in it). */
    private val base = HomeLayout(modules = listOf(HomeModule("projects"), HomeModule("tasks"), HomeModule("services")), version = 2)

    @Test fun defaultLayoutHasBuiltinsInOrder() {
        assertEquals(listOf("projects", "tasks"), ids(HomeLayout()))
    }

    @Test fun v1LayoutKeepsServicesButHidesIt() {
        val v1 = HomeLayout(modules = listOf(HomeModule("projects"), HomeModule("tasks"), HomeModule("services")))
        val r = HomeLayoutOps.reconcile(v1, emptyList()) { false }
        assertEquals(2, r.version)
        assertTrue(r.modules.first { it.id == "services" }.hidden)
        // Already migrated: the user's later choice to show it is kept.
        val shown = HomeLayoutOps.add(r, "services")
        assertFalse(HomeLayoutOps.reconcile(shown, emptyList()) { false }.modules.first { it.id == "services" }.hidden)
    }

    @Test fun addAppendsNewCardsAndUnhidesExisting() {
        val a = HomeLayoutOps.add(base, "agent:claude")
        assertEquals(listOf("projects", "tasks", "services", "agent:claude"), ids(a))
        val hidden = HomeLayoutOps.setHidden(a, "tasks", true)
        val back = HomeLayoutOps.add(hidden, "tasks")
        assertEquals(ids(hidden), ids(back))
        assertFalse(back.modules.first { it.id == "tasks" }.hidden)
    }

    @Test fun reconcileKeepsAddedCards() {
        val l = HomeLayoutOps.add(HomeLayoutOps.add(base, "agent:codex"), "project:/root/projects/app")
        val r = HomeLayoutOps.reconcile(l + "dsh", emptyList()) { false }
        assertEquals(listOf("projects", "tasks", "services", "agent:codex", "project:/root/projects/app", "dsh"), ids(r))
        assertEquals("codex", HomeLayoutOps.agentIdOf("agent:codex"))
        assertEquals("/root/projects/app", HomeLayoutOps.projectPathOf("project:/root/projects/app"))
    }

    private operator fun HomeLayout.plus(id: String) = HomeLayoutOps.add(this, id)

    @Test fun reconcileAppendsMissingBuiltinsAndNewPlugins() {
        val l = HomeLayout(modules = listOf(HomeModule("services")), version = 2)
        val r = HomeLayoutOps.reconcile(l, listOf("a", "b")) { false }
        assertEquals(listOf("services", "projects", "tasks", "plugin:a", "plugin:b"), ids(r))
    }

    @Test fun reconcileDropsGonePluginsButKeepsOnesStillOnDisk() {
        val l = base.copy(modules = base.modules + HomeModule("plugin:gone") + HomeModule("plugin:loading", hidden = true))
        val r = HomeLayoutOps.reconcile(l, emptyList()) { it == "gone" }
        assertEquals(listOf("projects", "tasks", "services", "plugin:loading"), ids(r))
        assertTrue(r.modules.last().hidden)
    }

    @Test fun reconcileDropsUnknownIdsAndDuplicates() {
        val l = HomeLayout(modules = listOf(HomeModule("weird"), HomeModule("tasks"), HomeModule("tasks", hidden = true), HomeModule("plugin:")))
        val r = HomeLayoutOps.reconcile(l, emptyList()) { true }
        assertEquals(listOf("tasks", "projects"), ids(r))
        assertFalse(r.modules.first().hidden)
    }

    @Test fun reconcileReturnsSameInstanceWhenNothingChanges() {
        val l = base.copy(modules = base.modules + HomeModule("plugin:x"))
        assertSame(l, HomeLayoutOps.reconcile(l, listOf("x")) { false })
    }

    @Test fun pinnedModulesComeFirst() {
        val l = HomeLayoutOps.setPinned(base, "services", true)
        assertEquals(listOf("services", "projects", "tasks"), ids(l))
        // Unpinning keeps the current position; the module just stops being held at the top.
        val unpinned = HomeLayoutOps.setPinned(l, "services", false)
        assertEquals(listOf("services", "projects", "tasks"), ids(unpinned))
        assertFalse(unpinned.modules.first().pinned)
        assertTrue(HomeLayoutOps.canMove(unpinned, "services", +1, all))
    }

    @Test fun moveSwapsWithVisibleNeighbourOnly() {
        val l = HomeLayoutOps.setHidden(base, "tasks", true)
        // projects, (tasks hidden), services: moving services up jumps over the hidden module.
        val r = HomeLayoutOps.move(l, "services", -1, all)
        assertEquals(listOf("services", "tasks", "projects"), ids(r))
        assertEquals(listOf("services", "projects"), HomeLayoutOps.visible(r, all).map { it.id })
    }

    @Test fun moveDoesNotCrossThePinnedGroup() {
        val l = HomeLayoutOps.setPinned(base, "tasks", true) // tasks, projects, services
        assertEquals(l, HomeLayoutOps.move(l, "projects", -1, all))
        assertFalse(HomeLayoutOps.canMove(l, "projects", -1, all))
        assertTrue(HomeLayoutOps.canMove(l, "projects", +1, all))
        assertFalse(HomeLayoutOps.canMove(l, "tasks", -1, all))
        assertFalse(HomeLayoutOps.canMove(l, "services", +1, all))
    }

    @Test fun moveIgnoresUnavailableModules() {
        val l = base.copy(modules = base.modules + HomeModule("plugin:p"))
        val available: (String) -> Boolean = { it != "services" }
        val r = HomeLayoutOps.move(l, "plugin:p", -1, available)
        assertEquals(listOf("projects", "plugin:p", "services", "tasks"), ids(r))
    }

    @Test fun visibleAndHiddenRespectAvailability() {
        val l = HomeLayoutOps.setHidden(base.copy(modules = base.modules + HomeModule("plugin:p")), "plugin:p", true)
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
