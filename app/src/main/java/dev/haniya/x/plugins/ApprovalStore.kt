package dev.haniya.x.plugins

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dev.haniya.x.core.Paths
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Host-only store of approved command hashes: Paths.state/plugin-approvals.json. The guest cannot reach
 * this directory, the control API has no approve endpoint, and each record is MACed with an Android
 * Keystore key. Call from Dispatchers.IO.
 */
internal class ApprovalStore {
    private val file get() = File(Paths.state, "plugin-approvals.json")
    private val signer: ApprovalSigner by lazy { keystoreSigner() }
    private var cache: MutableMap<String, ApprovalRecord>? = null

    @Synchronized fun all(): Map<String, ApprovalRecord> = load().toMap()

    @Synchronized fun get(id: String): ApprovalRecord? = load()[id]

    @Synchronized fun put(id: String, hash: String) {
        val m = load()
        m[id] = ApprovalRecord(hash, System.currentTimeMillis())
        save(m)
    }

    @Synchronized fun remove(id: String) {
        val m = load()
        if (m.remove(id) != null) save(m)
    }

    @Synchronized fun retainOnly(ids: Set<String>) {
        val m = load()
        if (m.keys.retainAll(ids)) save(m)
    }

    private fun load(): MutableMap<String, ApprovalRecord> {
        cache?.let { return it }
        val m = if (file.exists()) runCatching { ApprovalCodec.decode(file.readText(), signer) }.getOrElse {
            Log.w(TAG, "approvals unreadable", it); emptyMap()
        } else emptyMap()
        return m.toMutableMap().also { cache = it }
    }

    private fun save(m: Map<String, ApprovalRecord>) {
        val text = ApprovalCodec.encode(m, signer)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        cache = m.toMutableMap()
    }

    private fun keystoreSigner(): ApprovalSigner = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        ApprovalSigner { d -> Mac.getInstance("HmacSHA256").run { init(key); doFinal(d) } }
    } catch (e: Exception) {
        // Keystore unavailable (rare): fall back to a random per-install key kept next to the store.
        Log.w(TAG, "Android Keystore unavailable, using file key", e)
        val kf = File(Paths.state, "plugin-approvals.key")
        val raw = if (kf.exists() && kf.length() == 32L) kf.readBytes() else ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it); kf.writeBytes(it)
        }
        ApprovalSigner.hmac(raw)
    }

    private companion object {
        const val TAG = "HxApprovals"
        const val ALIAS = "haniyax-plugin-approvals"
    }
}
