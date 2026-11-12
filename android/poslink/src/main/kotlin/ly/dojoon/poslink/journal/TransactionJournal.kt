package ly.dojoon.poslink.journal

import ly.dojoon.poslink.protocol.Iso8601
import ly.dojoon.poslink.protocol.SaleRequest
import ly.dojoon.poslink.protocol.SaleResult
import ly.dojoon.poslink.protocol.putOpt
import ly.dojoon.poslink.protocol.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TxnState(val wire: String) {
    /** Cashier created it; the terminal hasn't confirmed receiving it yet. */
    PENDING("pending"),

    /** The terminal is running the payment. */
    IN_PROGRESS("inProgress"),

    /** Final result known. */
    COMPLETED("completed"),

    /** Outcome not known (link lost, app restarted mid-payment). Must be checked, never assumed. */
    UNKNOWN("unknown"),

    /** The terminal refused it before any card was read (busy, never received). No money moved. */
    REJECTED("rejected");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

data class TxnRecord(
    val request: SaleRequest,
    val state: TxnState,
    val result: SaleResult? = null,
    /** Terminal side: the cashier ACKed the SALE_RESULT. */
    val resultDelivered: Boolean = false,
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val txnId: String get() = request.txnId

    fun toJson(): JSONObject = JSONObject()
        .put("request", request.toJson())
        .put("state", state.wire)
        .putOpt("result", result?.toJson())
        .put("resultDelivered", resultDelivered)
        .putOpt("note", note)
        .put("updatedAt", Iso8601.format(updatedAt))

    companion object {
        fun fromJson(j: JSONObject) = TxnRecord(
            request = SaleRequest.fromJson(j.getJSONObject("request")),
            state = TxnState.fromWire(j.str("state")),
            result = j.optJSONObject("result")?.let { SaleResult.fromJson(it) },
            resultDelivered = j.optBoolean("resultDelivered"),
            note = j.str("note"),
            updatedAt = Iso8601.parse(j.str("updatedAt")) ?: System.currentTimeMillis(),
        )
    }
}

/**
 * Transaction journal, newest first. Both sides keep one: the terminal for duplicate-charge
 * protection and result re-delivery, the cashier for recovery after a dropped link.
 */
interface TransactionJournal {
    fun recent(): List<TxnRecord>
    fun get(txnId: String): TxnRecord?
    fun put(record: TxnRecord)
    fun clear()
}

open class InMemoryJournal(private val capacity: Int = 500) : TransactionJournal {
    protected val records = ArrayList<TxnRecord>()

    @Synchronized override fun recent(): List<TxnRecord> = records.toList()

    @Synchronized override fun get(txnId: String) = records.firstOrNull { it.txnId == txnId }

    @Synchronized override fun put(record: TxnRecord) {
        records.removeAll { it.txnId == record.txnId }
        records.add(0, record)
        while (records.size > capacity) records.removeAt(records.size - 1)
        persist()
    }

    @Synchronized override fun clear() {
        records.clear()
        persist()
    }

    protected open fun persist() = Unit
}

/** Journal saved as JSON in [file], written atomically (temp file + rename) after every change. */
class FileJournal(private val file: File, capacity: Int = 500) : InMemoryJournal(capacity) {
    init {
        if (file.exists()) {
            runCatching {
                val list = JSONArray(file.readText())
                for (i in 0 until list.length()) records += TxnRecord.fromJson(list.getJSONObject(i))
            }.onFailure { records.clear() } // unreadable: start empty rather than crash
        }
    }

    override fun persist() {
        val json = JSONArray().also { a -> records.forEach { a.put(it.toJson()) } }.toString()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
