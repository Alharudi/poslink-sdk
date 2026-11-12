package ly.dojoon.poslink.protocol

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Frame types (spec section 3). */
enum class MsgType(val code: Int, val authenticated: Boolean, val linkLevel: Boolean) {
    HELLO(0x01, false, true),
    AUTH(0x02, true, true),
    PING(0x03, true, true),
    PONG(0x04, true, true),
    ACK(0x05, true, true),
    NAK(0x06, true, true),
    STATUS(0x10, true, false),
    SALE_REQUEST(0x20, true, false),
    READY_TO_PAY(0x21, true, false),
    PROGRESS(0x22, true, false),
    SALE_RESULT(0x23, true, false),
    CANCEL(0x24, true, false),
    TXN_STATUS_QUERY(0x25, true, false),
    TXN_STATUS(0x26, true, false),
    PAIR_REQUEST(0x30, false, true),
    PAIR_KEY(0x31, false, true),
    PAIR_REVEAL(0x32, false, true),
    PAIR_CONFIRM(0x33, false, true),
    PAIR_FAIL(0x34, false, true),
    ERROR(0x7F, true, false);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): MsgType? = byCode[code]
    }
}

/** An application message from the peer, delivered once. */
class Message(val type: MsgType, val payload: JSONObject) {
    val txnId: String? get() = payload.str("txnId")
    override fun toString() = "$type $payload"
}

/** Error codes in ERROR and NAK payloads. */
object ErrorCodes {
    const val BUSY = "BUSY"
    const val CANNOT_CANCEL = "CANNOT_CANCEL"
    const val UNKNOWN_TXN = "UNKNOWN_TXN"
    const val TXN_INTERRUPTED = "TXN_INTERRUPTED"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    const val BAD_PAYLOAD = "BAD_PAYLOAD"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
}

/** Codes in PAIR_FAIL. */
enum class PairFailure {
    NOT_PAIRING, BUSY, BAD_COMMIT, BAD_KEY, BAD_CONFIRM, REJECTED, TIMEOUT,

    /** Local only: the link dropped or isn't connected to a peer. */
    NOT_CONNECTED;

    companion object {
        fun fromWire(code: String?) = entries.firstOrNull { it.name == code } ?: REJECTED
    }
}

// ---- small JSON helpers (org.json returns "null" strings and 0s for missing keys) ----

internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.putOpt(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)

/** ISO-8601 UTC timestamps without java.time (minSdk 24). */
object Iso8601 {
    private val fraction = Regex("""\.(\d{1,9})""")

    fun format(epochMillis: Long): String = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(epochMillis)

    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        // Normalise the fraction to milliseconds (Dart writes microseconds, Python none).
        val normalised = fraction.replace(text) { "." + it.groupValues[1].padEnd(3, '0').take(3) }
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            runCatching { return utc(pattern).parse(normalised)?.time }
        }
        return null
    }

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
