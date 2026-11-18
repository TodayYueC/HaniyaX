package dev.haniya.x.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Approval records. They live ONLY host-side (Paths.state/plugin-approvals.json, outside the guest rootfs),
 * and every record carries an HMAC made with a non-exportable Android Keystore key, so a record written by
 * anything other than the app UI does not verify. Codec + signer interface are pure JVM (unit tested).
 */
data class ApprovalRecord(val hash: String, val approvedAt: Long)

fun interface ApprovalSigner {
    fun sign(data: ByteArray): ByteArray

    companion object {
        /** Plain HMAC-SHA256 with a raw key (tests, and fallback when the Keystore is unavailable). */
        fun hmac(key: ByteArray) = ApprovalSigner { d ->
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(d) }
        }
    }
}

object ApprovalCodec {
    private fun payload(id: String, r: ApprovalRecord) = "hx-approval-record-v1\n$id\n${r.hash}\n${r.approvedAt}".toByteArray(Charsets.UTF_8)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun encode(records: Map<String, ApprovalRecord>, signer: ApprovalSigner): String {
        val o = buildJsonObject {
            put("version", 1)
            put("approvals", buildJsonObject {
                for ((id, r) in records.toSortedMap()) put(id, buildJsonObject {
                    put("hash", r.hash)
                    put("at", r.approvedAt)
                    put("sig", hex(signer.sign(payload(id, r))))
                })
            })
        }
        return o.toString()
    }

    /** Decodes and verifies; records whose signature does not match are dropped. */
    fun decode(text: String, signer: ApprovalSigner): Map<String, ApprovalRecord> {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val a = root["approvals"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, ApprovalRecord>()
        for ((id, e) in a) {
            val o = e as? JsonObject ?: continue
            val hash = (o["hash"] as? JsonPrimitive)?.contentOrNull ?: continue
            val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: continue
            val sig = (o["sig"] as? JsonPrimitive)?.contentOrNull ?: continue
            val r = ApprovalRecord(hash, at)
            val expect = runCatching { hex(signer.sign(payload(id, r))) }.getOrNull() ?: continue
            if (MessageDigest.isEqual(expect.toByteArray(), sig.toByteArray())) out[id] = r
        }
        return out
    }
}
