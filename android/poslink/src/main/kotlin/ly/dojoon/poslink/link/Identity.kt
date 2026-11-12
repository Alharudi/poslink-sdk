package ly.dojoon.poslink.link

import ly.dojoon.poslink.security.Crypto
import ly.dojoon.poslink.security.hexToBytes
import ly.dojoon.poslink.security.toHex

/**
 * Who this device is to its peers: a 16-byte id that never changes for the installation, and a
 * name people can read ("Front desk", "SUNMI P3"). Pairings are stored by the peer's id.
 */
class DeviceIdentity(val deviceId: ByteArray, val name: String) {
    init {
        require(deviceId.size == Crypto.DEVICE_ID_LENGTH) { "deviceId must be ${Crypto.DEVICE_ID_LENGTH} bytes" }
    }

    val idHex: String get() = deviceId.toHex()

    companion object {
        fun random(name: String) = DeviceIdentity(Crypto.randomBytes(Crypto.DEVICE_ID_LENGTH), name)
        fun fromHex(idHex: String, name: String) = DeviceIdentity(idHex.hexToBytes(), name)
    }
}

/** A paired peer and the 32-byte key both sides derived during pairing. */
class Pairing(val peerDeviceId: String, val peerName: String, val key: ByteArray, val createdAt: Long) {
    override fun toString() = "Pairing($peerName, $peerDeviceId)"
}

/**
 * Where pairing keys live. On Android use `KeystorePairingStore` (keys wrapped by the Android
 * Keystore); [InMemoryPairingStore] is for tests.
 */
interface PairingStore {
    fun get(peerDeviceId: String): Pairing?
    fun put(pairing: Pairing)
    fun remove(peerDeviceId: String)
    fun all(): List<Pairing>
}

class InMemoryPairingStore : PairingStore {
    private val map = LinkedHashMap<String, Pairing>()

    @Synchronized override fun get(peerDeviceId: String) = map[peerDeviceId]

    @Synchronized override fun put(pairing: Pairing) {
        map[pairing.peerDeviceId] = pairing
    }

    @Synchronized override fun remove(peerDeviceId: String) {
        map.remove(peerDeviceId)
    }

    @Synchronized override fun all() = map.values.toList()
}
