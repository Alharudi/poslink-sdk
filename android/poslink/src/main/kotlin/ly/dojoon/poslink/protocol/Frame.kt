package ly.dojoon.poslink.protocol

import ly.dojoon.poslink.security.Crypto

/**
 * POS Link v2 frame (spec/PROTOCOL.md section 2):
 *
 * `STX | version | type | seq u16 | counter u32 | length u16 | payload | mac[16] | crc u16`
 */
class Frame(
    val version: Int,
    val type: Int,
    val seq: Int,
    val counter: Long,
    val payload: ByteArray,
    val mac: ByteArray,
    /** Bytes 1 … 10+n of the frame, which the mac covers. Empty for v1 frames. */
    internal val authenticatedBytes: ByteArray,
) {
    val isUnauthenticated: Boolean get() = counter == 0L && mac.all { it == 0.toByte() }

    /** Checks the mac with the session key and the sender's role byte. */
    fun verify(sessionKey: ByteArray, senderRole: Role): Boolean =
        version == FrameCodec.VERSION &&
            Crypto.constantTimeEquals(FrameCodec.mac(sessionKey, senderRole, authenticatedBytes), mac)
}

enum class Role(val byte: Byte, val wire: String) {
    CASHIER('C'.code.toByte(), "cashier"),
    TERMINAL('T'.code.toByte(), "terminal");

    val peer: Role get() = if (this == CASHIER) TERMINAL else CASHIER

    companion object {
        fun fromWire(value: String?): Role? = entries.firstOrNull { it.wire == value }
    }
}

object FrameCodec {
    const val STX = 0x02
    const val VERSION = 2
    const val HEADER_LENGTH = 11
    const val MAC_LENGTH = 16
    const val CRC_LENGTH = 2
    const val MAX_PAYLOAD = 8192
    private const val PACKET_ALIGN = 64
    private const val V1_HEADER_LENGTH = 7

    /** Encodes an unauthenticated frame (counter 0, zero mac). */
    fun encode(type: Int, seq: Int, payload: ByteArray = EMPTY): ByteArray =
        build(type, seq, 0, payload) { ByteArray(MAC_LENGTH) }

    /** Encodes an authenticated frame. */
    fun encode(type: Int, seq: Int, payload: ByteArray, counter: Long, sessionKey: ByteArray, sender: Role): ByteArray {
        require(counter >= 1) { "authenticated frames need counter >= 1" }
        return build(type, seq, counter, payload) { body -> mac(sessionKey, sender, body) }
    }

    internal fun mac(sessionKey: ByteArray, sender: Role, authenticatedBytes: ByteArray): ByteArray =
        Crypto.hmacSha256(sessionKey, byteArrayOf(sender.byte), authenticatedBytes).copyOf(MAC_LENGTH)

    private inline fun build(type: Int, seq: Int, counter: Long, payload: ByteArray, mac: (ByteArray) -> ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload of ${payload.size} bytes exceeds $MAX_PAYLOAD" }
        require(seq in 0..0xFFFF && counter in 0..0xFFFF_FFFFL) { "seq or counter out of range" }
        val body = ByteArray(HEADER_LENGTH - 1 + payload.size)
        body[0] = VERSION.toByte()
        body[1] = type.toByte()
        putU16(body, 2, seq)
        putU32(body, 4, counter)
        putU16(body, 8, payload.size)
        payload.copyInto(body, HEADER_LENGTH - 1)
        val protectedBytes = body + mac(body)
        val length = 1 + protectedBytes.size + CRC_LENGTH
        val out = ByteArray(if (length % PACKET_ALIGN == 0) length + 1 else length)
        out[0] = STX.toByte()
        protectedBytes.copyInto(out, 1)
        putU16(out, 1 + protectedBytes.size, Crc16.compute(protectedBytes))
        return out
    }

    private fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }

    internal val EMPTY = ByteArray(0)

    /** Rebuilds frames from an arbitrary byte stream; resynchronises on the next STX. */
    class Decoder {
        private var buffer = EMPTY
        var crcErrors = 0
            private set
        var discardedBytes = 0
            private set

        fun add(chunk: ByteArray): List<Frame> {
            val data = if (buffer.isEmpty()) chunk else buffer + chunk
            val frames = ArrayList<Frame>(1)
            var i = 0
            while (true) {
                while (i < data.size && (data[i].toInt() and 0xFF) != STX) {
                    if (data[i].toInt() != 0) discardedBytes++
                    i++
                }
                if (data.size - i < 2) break
                val version = data[i + 1].toInt() and 0xFF
                val length: Int
                val total: Int
                when (version) {
                    VERSION -> {
                        if (data.size - i < HEADER_LENGTH) break
                        length = u16(data, i + 9)
                        total = HEADER_LENGTH + length + MAC_LENGTH + CRC_LENGTH
                    }
                    1 -> {
                        if (data.size - i < V1_HEADER_LENGTH) break
                        length = u16(data, i + 5)
                        total = V1_HEADER_LENGTH + length + CRC_LENGTH
                    }
                    else -> {
                        discardedBytes++
                        i++
                        continue
                    }
                }
                if (length > MAX_PAYLOAD) {
                    discardedBytes++
                    i++
                    continue
                }
                if (data.size - i < total) break
                val crcAt = i + total - CRC_LENGTH
                if (Crc16.compute(data, i + 1, crcAt) != u16(data, crcAt)) {
                    crcErrors++
                    i++
                    continue
                }
                frames += if (version == VERSION) {
                    val payloadEnd = i + HEADER_LENGTH + length
                    Frame(
                        version = version,
                        type = data[i + 2].toInt() and 0xFF,
                        seq = u16(data, i + 3),
                        counter = u32(data, i + 5),
                        payload = data.copyOfRange(i + HEADER_LENGTH, payloadEnd),
                        mac = data.copyOfRange(payloadEnd, payloadEnd + MAC_LENGTH),
                        authenticatedBytes = data.copyOfRange(i + 1, payloadEnd),
                    )
                } else {
                    Frame(1, data[i + 2].toInt() and 0xFF, u16(data, i + 3), 0, data.copyOfRange(i + V1_HEADER_LENGTH, crcAt), ByteArray(MAC_LENGTH), EMPTY)
                }
                i += total
            }
            buffer = if (i >= data.size) EMPTY else data.copyOfRange(i, data.size)
            return frames
        }

        fun reset() {
            buffer = EMPTY
        }

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, at: Int): Long {
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
            return v
        }
    }
}

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF. */
object Crc16 {
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}
