package ly.dojoon.poslink.security

/** Standard base64 with padding (RFC 4648 section 4). java.util.Base64 needs API 26; this works on 24. */
internal object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val reverse = IntArray(128) { -1 }.also { r -> ALPHABET.forEachIndexed { i, c -> r[c.code] = i } }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** Null if [text] isn't valid padded base64. */
    fun decode(text: String?): ByteArray? {
        if (text == null || text.length % 4 != 0) return null
        val out = java.io.ByteArrayOutputStream(text.length / 4 * 3)
        var i = 0
        while (i < text.length) {
            val c = IntArray(4)
            for (k in 0 until 4) {
                val ch = text[i + k]
                c[k] = when {
                    ch == '=' && i + k >= text.length - 2 -> -2
                    ch.code < 128 && reverse[ch.code] >= 0 -> reverse[ch.code]
                    else -> return null
                }
            }
            if (c[0] < 0 || c[1] < 0 || (c[2] == -2 && c[3] != -2)) return null
            out.write((c[0] shl 2) or (c[1] shr 4))
            if (c[2] >= 0) out.write(((c[1] and 15) shl 4) or (c[2] shr 2))
            if (c[2] >= 0 && c[3] >= 0) out.write(((c[2] and 3) shl 6) or c[3])
            i += 4
        }
        return out.toByteArray()
    }
}
