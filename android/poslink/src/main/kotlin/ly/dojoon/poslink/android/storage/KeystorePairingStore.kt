package ly.dojoon.poslink.android.storage

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.Pairing
import ly.dojoon.poslink.link.PairingStore
import ly.dojoon.poslink.security.Base64
import ly.dojoon.poslink.security.Crypto
import ly.dojoon.poslink.security.hexToBytes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pairing keys on Android. Each key is encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it, then saved in the app's private files. A copied file is
 * useless on another device.
 */
class KeystorePairingStore(context: Context, fileName: String = "poslink_pairings.json") : PairingStore {
    private val file = File(context.noBackupFilesDir, fileName)
    private val pairings = LinkedHashMap<String, Pairing>()

    init {
        load()
    }

    @Synchronized override fun get(peerDeviceId: String) = pairings[peerDeviceId]

    @Synchronized override fun put(pairing: Pairing) {
        pairings[pairing.peerDeviceId] = pairing
        save()
    }

    @Synchronized override fun remove(peerDeviceId: String) {
        if (pairings.remove(peerDeviceId) != null) save()
    }

    @Synchronized override fun all() = pairings.values.toList()

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val list = JSONArray(file.readText())
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val key = decrypt(Base64.decode(o.getString("iv"))!!, Base64.decode(o.getString("key"))!!)
                val p = Pairing(o.getString("peerDeviceId"), o.getString("peerName"), key, o.getLong("createdAt"))
                pairings[p.peerDeviceId] = p
            }
        }.onFailure {
            // Unreadable (e.g. the Keystore key was wiped): the devices must pair again.
            pairings.clear()
        }
    }

    private fun save() {
        val list = JSONArray()
        for (p in pairings.values) {
            val (iv, encrypted) = encrypt(p.key)
            list.put(
                JSONObject().put("peerDeviceId", p.peerDeviceId).put("peerName", p.peerName)
                    .put("createdAt", p.createdAt).put("iv", Base64.encode(iv)).put("key", Base64.encode(encrypted)),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(false)
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun decrypt(iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private companion object {
        const val ALIAS = "ly.dojoon.poslink.pairing-wrap"
    }
}

/** This installation's POS Link identity, created on first use and kept in the app's preferences. */
object AndroidIdentity {
    private const val PREFS = "poslink_identity"

    /** [name] is what the other device shows, e.g. "Front desk"; defaults to the device model. */
    fun load(context: Context, name: String? = null): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null)?.takeIf { it.length == 32 }
            ?: Crypto.randomBytes(Crypto.DEVICE_ID_LENGTH).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                .also { prefs.edit().putString("deviceId", it).apply() }
        val displayName = name ?: listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim()
        return DeviceIdentity(id.hexToBytes(), displayName)
    }

    /** Sent to the peer for display and logs. */
    fun deviceInfo(appVersion: String? = null): Map<String, Any?> =
        mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "appVersion" to appVersion)
}
