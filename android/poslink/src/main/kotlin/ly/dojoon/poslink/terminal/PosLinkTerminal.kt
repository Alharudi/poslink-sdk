package ly.dojoon.poslink.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ly.dojoon.poslink.journal.TransactionJournal
import ly.dojoon.poslink.journal.TxnRecord
import ly.dojoon.poslink.journal.TxnState
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.LinkConfig
import ly.dojoon.poslink.link.LinkListener
import ly.dojoon.poslink.link.LinkSession
import ly.dojoon.poslink.link.LinkStatus
import ly.dojoon.poslink.link.Pairing
import ly.dojoon.poslink.link.PairingRequest
import ly.dojoon.poslink.link.PairingResult
import ly.dojoon.poslink.link.PairingStore
import ly.dojoon.poslink.protocol.ErrorCodes
import ly.dojoon.poslink.protocol.Message
import ly.dojoon.poslink.protocol.MsgType
import ly.dojoon.poslink.protocol.PaymentStage
import ly.dojoon.poslink.protocol.Role
import ly.dojoon.poslink.protocol.SaleRequest
import ly.dojoon.poslink.protocol.SaleResult
import ly.dojoon.poslink.protocol.TxnStatusState
import ly.dojoon.poslink.transport.Transport
import org.json.JSONObject

/** What the terminal screen should show. */
sealed interface TerminalPhase {
    data object Idle : TerminalPhase
    data class WaitingCard(val request: SaleRequest) : TerminalPhase
    data class Processing(val request: SaleRequest, val stage: PaymentStage) : TerminalPhase
    data class Result(val result: SaleResult) : TerminalPhase
}

/**
 * The payment-terminal side (e.g. a Sunmi P3): waits for a cashier, takes sale requests, runs them
 * through a [PaymentAdapter] and sends the results back.
 *
 * Rules that protect against double charges (spec section 7):
 *  * a txnId is charged at most once; a repeated SALE_REQUEST gets the stored result back,
 *  * one sale at a time; another txnId meanwhile gets ERROR BUSY,
 *  * a result the cashier didn't ACK is re-sent when the link comes back (last 24 h).
 *
 * Pairing: call [openPairing] when the merchant taps "Pair a cashier", then show
 * [pairingRequest]'s code with Confirm / Reject buttons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosLinkTerminal(
    transport: Transport,
    identity: DeviceIdentity,
    private val pairings: PairingStore,
    private val journal: TransactionJournal,
    private val payment: PaymentAdapter,
    config: LinkConfig = LinkConfig(),
    info: Map<String, Any?> = emptyMap(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val resultHoldMs: Long = 6000,
    private val log: (String) -> Unit = {},
    scope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val ownsScope = scope == null

    /** The secure link to the cashier: status, peer, problem. */
    val link = LinkSession(transport, Role.TERMINAL, identity, pairings, this.scope, config, info, clock, log)

    val status: StateFlow<LinkStatus> get() = link.status

    private val _phase = MutableStateFlow<TerminalPhase>(TerminalPhase.Idle)
    val phase: StateFlow<TerminalPhase> = _phase.asStateFlow()

    private val _pairingRequest = MutableStateFlow<PairingRequest?>(null)

    /** A cashier asking to pair: show its code; null when there is none. */
    val pairingRequest: StateFlow<PairingRequest?> = _pairingRequest.asStateFlow()

    private val _pairingResults = MutableSharedFlow<PairingResult>(extraBufferCapacity = 8)
    val pairingResults: SharedFlow<PairingResult> = _pairingResults.asSharedFlow()

    /** Every journal change (new sale, result, delivery). */
    private val _updates = MutableSharedFlow<TxnRecord>(extraBufferCapacity = 64)
    val updates: SharedFlow<TxnRecord> = _updates.asSharedFlow()

    private var current: SaleRequest? = null
    private var stage: PaymentStage? = null
    private var paymentJob: Job? = null
    private var resultTimer: Job? = null

    val isSimulator: Boolean get() = payment.isSimulator
    private val busy: Boolean get() = _phase.value is TerminalPhase.WaitingCard || _phase.value is TerminalPhase.Processing

    fun start() {
        scope.launch {
            // Payments that were running when the app died have an unknown outcome.
            journal.recent().filter { it.state == TxnState.IN_PROGRESS }.forEach {
                put(it.copy(state = TxnState.UNKNOWN, note = "App restarted during payment", updatedAt = now()))
            }
            link.listener = listener
            link.start()
        }
    }

    suspend fun stop() {
        link.stop()
        paymentJob?.cancel()
        if (ownsScope) scope.cancel()
    }

    // ---- pairing ----

    fun openPairing(windowMs: Long = 120_000) = link.openPairing(windowMs)
    fun closePairing() = link.closePairing()
    fun pairedCashiers(): List<Pairing> = pairings.all()
    fun forget(cashierDeviceId: String) = link.forget(cashierDeviceId)

    // ---- terminal screen actions ----

    /** The merchant cancels the running sale on the terminal (only before a card is presented). */
    fun cancelOnTerminal() {
        scope.launch {
            val c = current ?: return@launch
            if (!payment.cancel(c.txnId)) log("Too late to cancel: card already presented")
        }
    }

    /** Back to idle after showing a result. */
    fun dismissResult() {
        scope.launch {
            if (_phase.value !is TerminalPhase.Result) return@launch
            resultTimer?.cancel()
            _phase.value = TerminalPhase.Idle
        }
    }

    fun transactions(): List<TxnRecord> = journal.recent()

    // ---- protocol ----

    private val listener = object : LinkListener {
        override fun onMessage(message: Message) {
            when (message.type) {
                MsgType.SALE_REQUEST -> onSaleRequest(SaleRequest.fromJson(message.payload))
                MsgType.CANCEL -> onCancel(message.txnId ?: "")
                MsgType.TXN_STATUS_QUERY -> onStatusQuery(message.txnId ?: "")
                else -> log("Ignoring ${message.type}")
            }
        }

        override fun onOnline() = onLinkOnline()

        override fun onPairingRequest(request: PairingRequest) {
            _pairingRequest.value = request
        }

        override fun onPairingFinished(result: PairingResult) {
            _pairingRequest.value = null
            _pairingResults.tryEmit(result)
        }
    }

    private fun onSaleRequest(req: SaleRequest) {
        req.validate()?.let { return sendError(ErrorCodes.BAD_REQUEST, it, req.txnId) }

        val existing = journal.get(req.txnId)
        if (existing != null) {
            when (existing.state) {
                TxnState.COMPLETED -> {
                    log("Duplicate sale ${req.txnId}: re-sending the stored result, card NOT charged again")
                    deliverResult(existing.result!!)
                }
                TxnState.IN_PROGRESS, TxnState.PENDING -> if (current?.txnId == req.txnId) {
                    link.sendMessage(MsgType.PROGRESS, JSONObject().put("txnId", req.txnId).put("stage", (stage ?: PaymentStage.WAITING_CARD).wire))
                }
                TxnState.UNKNOWN, TxnState.REJECTED -> sendError(
                    ErrorCodes.TXN_INTERRUPTED,
                    "This sale was interrupted on the terminal. Check the terminal report before retrying.",
                    req.txnId,
                )
            }
            return
        }
        if (busy) return sendError(ErrorCodes.BUSY, "Terminal is busy with another payment", req.txnId)

        resultTimer?.cancel()
        current = req
        stage = PaymentStage.WAITING_CARD
        _phase.value = TerminalPhase.WaitingCard(req)
        put(TxnRecord(req, TxnState.IN_PROGRESS, updatedAt = now()))
        link.sendMessage(MsgType.READY_TO_PAY, JSONObject().put("txnId", req.txnId).put("amount", req.amount).put("currency", req.currency))
        sendStatus()

        paymentJob = scope.launch {
            payment.sale(req).collect { event ->
                when (event) {
                    is PaymentEvent.Stage -> {
                        stage = event.stage
                        _phase.value = if (event.stage == PaymentStage.WAITING_CARD) TerminalPhase.WaitingCard(req) else TerminalPhase.Processing(req, event.stage)
                        link.sendMessage(MsgType.PROGRESS, JSONObject().put("txnId", req.txnId).put("stage", event.stage.wire))
                    }
                    is PaymentEvent.Finished -> finish(req, event.result)
                }
            }
        }
    }

    private fun finish(req: SaleRequest, result: SaleResult) {
        if (current?.txnId != req.txnId) return
        current = null
        stage = null
        put(TxnRecord(req, TxnState.COMPLETED, result, updatedAt = now()))
        _phase.value = TerminalPhase.Result(result)
        resultTimer = scope.launch {
            delay(resultHoldMs)
            if (_phase.value is TerminalPhase.Result) _phase.value = TerminalPhase.Idle
        }
        deliverResult(result)
        sendStatus()
    }

    private fun deliverResult(result: SaleResult) {
        val sent = link.sendMessage(MsgType.SALE_RESULT, result.toJson())
        scope.launch {
            val ok = sent.await()
            val record = journal.get(result.txnId)
            if (ok && record != null && !record.resultDelivered) put(record.copy(resultDelivered = true, updatedAt = now()))
            if (!ok) log("Result for ${result.txnId} not acknowledged; it will be re-sent when the cashier reconnects")
        }
    }

    private fun onCancel(txnId: String) {
        if (current?.txnId == txnId) {
            // On success the payment flow finishes as CANCELLED and the result goes out normally.
            if (!payment.cancel(txnId)) sendError(ErrorCodes.CANNOT_CANCEL, "Card already presented; wait for the result", txnId)
            return
        }
        val r = journal.get(txnId)
        if (r?.state == TxnState.COMPLETED) deliverResult(r.result!!) else sendError(ErrorCodes.UNKNOWN_TXN, "No running sale with this id", txnId)
    }

    private fun onStatusQuery(txnId: String) {
        val r = journal.get(txnId)
        val state = when (r?.state) {
            null -> TxnStatusState.NOT_FOUND
            TxnState.COMPLETED -> TxnStatusState.COMPLETED
            TxnState.IN_PROGRESS, TxnState.PENDING -> TxnStatusState.IN_PROGRESS
            TxnState.UNKNOWN, TxnState.REJECTED -> TxnStatusState.INTERRUPTED
        }
        val payload = JSONObject().put("txnId", txnId).put("state", state.wire)
        if (state == TxnStatusState.COMPLETED) payload.put("result", r!!.result!!.toJson())
        if (state == TxnStatusState.IN_PROGRESS) stage?.let { payload.put("stage", it.wire) }
        val sent = link.sendMessage(MsgType.TXN_STATUS, payload)
        if (state == TxnStatusState.COMPLETED && !r!!.resultDelivered) {
            scope.launch { if (sent.await()) journal.get(txnId)?.let { put(it.copy(resultDelivered = true, updatedAt = now())) } }
        }
    }

    private fun onLinkOnline() {
        sendStatus()
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        journal.recent()
            .filter { it.state == TxnState.COMPLETED && !it.resultDelivered && it.updatedAt > cutoff }
            .take(10)
            .forEach {
                log("Re-sending undelivered result for ${it.txnId}")
                deliverResult(it.result!!)
            }
    }

    private fun sendStatus() {
        link.sendMessage(MsgType.STATUS, JSONObject().put("state", if (busy) "busy" else "idle"))
    }

    private fun sendError(code: String, message: String, txnId: String?) {
        link.sendMessage(MsgType.ERROR, JSONObject().put("code", code).put("message", message).also { if (txnId != null) it.put("txnId", txnId) })
    }

    private fun put(record: TxnRecord) {
        journal.put(record)
        _updates.tryEmit(record)
    }

    private fun now() = System.currentTimeMillis()
}
