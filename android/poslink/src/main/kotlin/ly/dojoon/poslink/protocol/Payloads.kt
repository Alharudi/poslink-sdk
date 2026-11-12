package ly.dojoon.poslink.protocol

import org.json.JSONObject

/** Where a running payment is. */
enum class PaymentStage(val wire: String) {
    WAITING_CARD("waitingCard"),
    CARD_READ("cardRead"),
    PIN_ENTRY("pinEntry"),
    AUTHORIZING("authorizing"),
    PRINTING("printing");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: AUTHORIZING
    }
}

/** Final outcome of a card payment. */
enum class Outcome(val wire: String) {
    APPROVED("approved"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    ERROR("error");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: ERROR
    }
}

/** Answer to TXN_STATUS_QUERY. */
enum class TxnStatusState(val wire: String) {
    NOT_FOUND("notFound"),
    IN_PROGRESS("inProgress"),
    COMPLETED("completed"),
    INTERRUPTED("interrupted");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: NOT_FOUND
    }
}

/**
 * A sale the cashier asks for. [amount] is in the currency's minor unit (LYD has 3 decimals:
 * 12.500 LYD = 12500).
 */
data class SaleRequest(
    val txnId: String,
    val amount: Long,
    val currency: String,
    val reference: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("txnId", txnId)
        .put("amount", amount)
        .put("currency", currency)
        .putOpt("reference", reference)
        .put("createdAt", Iso8601.format(createdAt))

    /** A reason the terminal must refuse it, or null when it is valid. */
    fun validate(): String? = when {
        txnId.isEmpty() -> "txnId is required"
        amount <= 0 -> "amount must be positive"
        currency.length != 3 -> "currency must be an ISO 4217 code"
        else -> null
    }

    companion object {
        fun fromJson(j: JSONObject) = SaleRequest(
            txnId = j.str("txnId") ?: "",
            amount = j.long("amount") ?: 0,
            currency = j.str("currency") ?: "",
            reference = j.str("reference"),
            createdAt = Iso8601.parse(j.str("createdAt")) ?: System.currentTimeMillis(),
        )
    }
}

/** The terminal's final answer for a sale. Only a masked PAN may appear here. */
data class SaleResult(
    val txnId: String,
    val outcome: Outcome,
    val amount: Long,
    val currency: String,
    val timestamp: Long = System.currentTimeMillis(),
    val responseCode: String? = null,
    val message: String? = null,
    val authCode: String? = null,
    val rrn: String? = null,
    val stan: String? = null,
    val maskedPan: String? = null,
    val cardScheme: String? = null,
    val terminalId: String? = null,
    val merchantId: String? = null,
) {
    val approved: Boolean get() = outcome == Outcome.APPROVED

    fun toJson(): JSONObject = JSONObject()
        .put("txnId", txnId)
        .put("outcome", outcome.wire)
        .put("amount", amount)
        .put("currency", currency)
        .put("timestamp", Iso8601.format(timestamp))
        .putOpt("responseCode", responseCode)
        .putOpt("message", message)
        .putOpt("authCode", authCode)
        .putOpt("rrn", rrn)
        .putOpt("stan", stan)
        .putOpt("maskedPan", maskedPan)
        .putOpt("cardScheme", cardScheme)
        .putOpt("terminalId", terminalId)
        .putOpt("merchantId", merchantId)

    companion object {
        fun fromJson(j: JSONObject) = SaleResult(
            txnId = j.str("txnId") ?: "",
            outcome = Outcome.fromWire(j.str("outcome")),
            amount = j.long("amount") ?: 0,
            currency = j.str("currency") ?: "",
            timestamp = Iso8601.parse(j.str("timestamp")) ?: System.currentTimeMillis(),
            responseCode = j.str("responseCode"),
            message = j.str("message"),
            authCode = j.str("authCode"),
            rrn = j.str("rrn"),
            stan = j.str("stan"),
            maskedPan = j.str("maskedPan"),
            cardScheme = j.str("cardScheme"),
            terminalId = j.str("terminalId"),
            merchantId = j.str("merchantId"),
        )
    }
}
