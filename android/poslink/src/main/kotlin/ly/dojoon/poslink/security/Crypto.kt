package ly.dojoon.poslink.security

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256, HMAC-SHA256, HKDF-SHA256 and P-256 ECDH, from the platform's standard providers. */
object Crypto {
    const val PUBLIC_KEY_LENGTH = 65
    const val NONCE_LENGTH = 16
    const val DEVICE_ID_LENGTH = 16

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
}

/** A P-256 key pair. Public keys travel as 65-byte uncompressed points (`04 ‖ X ‖ Y`). */
class P256KeyPair private constructor(private val privateKey: ECPrivateKey, val publicKey: ByteArray) {

    /** ECDH shared secret (the 32-byte X coordinate). Throws [IllegalArgumentException] for an invalid point. */
    fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
        val peer = decodePublicKey(peerPublicKey)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    companion object {
        private val params: ECParameterSpec by lazy {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            (g.generateKeyPair().public as ECPublicKey).params
        }

        fun generate(): P256KeyPair {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            val kp = g.generateKeyPair()
            return P256KeyPair(kp.private as ECPrivateKey, encode((kp.public as ECPublicKey).w))
        }

        /** For test vectors: a key pair from a fixed 32-byte private scalar. */
        fun fromPrivateScalar(scalar: ByteArray): P256KeyPair {
            val d = BigInteger(1, scalar)
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(ECPrivateKeySpec(d, params)) as ECPrivateKey
            return P256KeyPair(priv, encode(multiplyGenerator(d)))
        }

        fun decodePublicKey(raw: ByteArray): ECPublicKey {
            require(raw.size == Crypto.PUBLIC_KEY_LENGTH && raw[0] == 0x04.toByte()) {
                "public key must be a 65-byte uncompressed P-256 point"
            }
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            require(isOnCurve(x, y)) { "public key is not a point on P-256" }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }

        private fun encode(w: ECPoint): ByteArray = byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)

        private fun fixed32(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }

        // y² = x³ - 3x + b (mod p)
        private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
            val p = (params.curve.field as java.security.spec.ECFieldFp).p
            if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
            val a = params.curve.a
            val b = params.curve.b
            return y.modPow(BigInteger.valueOf(2), p) == x.modPow(BigInteger.valueOf(3), p).add(a.multiply(x)).add(b).mod(p)
        }

        // Plain double-and-add; only used to derive public keys for test vectors.
        private fun multiplyGenerator(k: BigInteger): ECPoint {
            val p = (params.curve.field as java.security.spec.ECFieldFp).p
            val a = params.curve.a
            var result: ECPoint = ECPoint.POINT_INFINITY
            var addend = params.generator
            for (i in 0 until k.bitLength()) {
                if (k.testBit(i)) result = add(result, addend, p, a)
                addend = add(addend, addend, p, a)
            }
            return result
        }

        private fun add(p1: ECPoint, p2: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
            if (p1 == ECPoint.POINT_INFINITY) return p2
            if (p2 == ECPoint.POINT_INFINITY) return p1
            val (x1, y1) = p1.affineX to p1.affineY
            val (x2, y2) = p2.affineX to p2.affineY
            val lambda = if (x1 == x2) {
                if ((y1 + y2).mod(p).signum() == 0) return ECPoint.POINT_INFINITY
                (x1.pow(2).multiply(BigInteger.valueOf(3)).add(a)).multiply(y1.shiftLeft(1).modInverse(p)).mod(p)
            } else {
                (y2 - y1).multiply((x2 - x1).modInverse(p)).mod(p)
            }
            val x3 = (lambda.pow(2) - x1 - x2).mod(p)
            val y3 = (lambda.multiply(x1 - x3) - y1).mod(p)
            return ECPoint(x3, y3)
        }
    }
}

/** Pairing and session key math (spec section 6). */
object PairingCrypto {
    fun commit(publicKey: ByteArray, nonce: ByteArray): ByteArray = Crypto.sha256(publicKey, nonce)

    fun transcriptHash(
        idCashier: ByteArray, idTerminal: ByteArray, pkCashier: ByteArray, pkTerminal: ByteArray,
        nonceCashier: ByteArray, nonceTerminal: ByteArray,
    ): ByteArray = Crypto.sha256(
        Crypto.ascii("poslink/2 pair"), idCashier, idTerminal, pkCashier, pkTerminal, nonceCashier, nonceTerminal,
    )

    fun pairingKey(shared: ByteArray, transcript: ByteArray): ByteArray =
        Crypto.hkdf(shared, transcript, Crypto.ascii("poslink/2 pairing key"), 32)

    fun code(shared: ByteArray, transcript: ByteArray): String {
        val b = Crypto.hkdf(shared, transcript, Crypto.ascii("poslink/2 code"), 4)
        val value = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    fun confirmMac(key: ByteArray, senderRole: Byte, transcript: ByteArray): ByteArray =
        Crypto.hmacSha256(key, Crypto.ascii("poslink/2 confirm"), byteArrayOf(senderRole), transcript)

    fun sessionKey(
        key: ByteArray, nonceCashier: ByteArray, nonceTerminal: ByteArray, idCashier: ByteArray, idTerminal: ByteArray,
    ): ByteArray = Crypto.hkdf(key, nonceCashier + nonceTerminal, Crypto.ascii("poslink/2 session") + idCashier + idTerminal, 32)
}

internal fun ByteArray.toHex(): String = joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
