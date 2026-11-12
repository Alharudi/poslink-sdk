package ly.dojoon.poslink.cashier

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ly.dojoon.poslink.journal.TransactionJournal
import ly.dojoon.poslink.journal.TxnRecord
import ly.dojoon.poslink.journal.TxnState
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.LinkConfig
import ly.dojoon.poslink.link.LinkListener
import ly.dojoon.poslink.link.LinkSession
import ly.dojoon.poslink.link.LinkStatus
import ly.dojoon.poslink.link.Pairing
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
import ly.dojoon.poslink.protocol.str
import ly.dojoon.poslink.transport.Transport
import org.json.JSONObject
import java.util.UUID

/** How a [PosLinkCashier.sale] ended. */
sealed interface SaleOutcome {
    val txnId: String

    /** The terminal's final answer: approved, declined, cancelled, timeout or error. */
    data class Completed(val result: SaleResult) : SaleOutcome {
        override val txnId get() = result.txnId
    }

    /** The terminal refused it before any card was read, or never got it. No money moved; safe to charge again. */
    data class Refused(override val txnId: String, val code: String?, val message: String) : SaleOutcome

    /**
     * Nobody knows yet whether money moved (link lost, no answer). Never treat it as declined.
     * The cashier keeps asking the terminal when the link is back; watch [PosLinkCashier.updates]
     * or call [PosLinkCashier.status].
     */
    data class Unknown(override val txnId: String, val message: String) : SaleOutcome
}

/** Progress of a running sale, for the cashier's screen. */
sealed interface SaleEvent {
    data class Sent(val txnId: String) : SaleEvent
    data object ReadyToPay : SaleEvent
    data class Progress(val stage: PaymentStage) : SaleEvent
    data object ConnectionLost : SaleEvent
    data object Reconnected : SaleEvent
}

/** Answer to [PosLinkCashier.status]. */
data class TxnStatus(val state: TxnStatusState, val result: SaleResult? = null, val stage: PaymentStage? = null)

/**
 * The cashier side: connects to a payment terminal and asks it for payments.
 *
 * ```
 * val cashier = PosLinkCashier(transport, identity, pairings, journal)
 * cashier.start()
 * // once: cashier.pair { code -> askUserToTypeTheCodeShownOnTheTerminal(code) }
 * when (val outcome = cashier.sale(amount = 12500, currency = "LYD", reference = "INV-42") { showProgress(it) }) {
 *     is SaleOutcome.Completed -> if (outcome.result.approved) printReceipt(outcome.result)
 *     is SaleOutcome.Refused -> showError(outcome.message)        // safe to try again
 *     is SaleOutcome.Unknown -> showCheckTerminal(outcome.message) // never assume declined
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosLinkCashier(
    transport: Transport,
    identity: DeviceIdentity,
    private val pairings: PairingStore,
    private val journal: TransactionJournal,
    config: LinkConfig = LinkConfig(),
    info: Map<String, Any?> = emptyMap(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** A running sale with no update from the terminal for this long becomes [SaleOutcome.Unknown]. */
    private val noUpdateTimeoutMs: Long = 150_000,
    private val log: (String) -> Unit = {},
    scope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val ownsScope = scope == null

    /** The secure link to the terminal: status, peer, problem. */
    val link = LinkSession(transport, Role.CASHIER, identity, pairings, this.scope, config, info, clock, log)

    val status: StateFlow<LinkStatus> get() = link.status

    private val _terminalState = MutableStateFlow<String?>(null)

    /** "idle" or "busy", as last reported by the terminal. */
    val terminalState: StateFlow<String?> = _terminalState.asStateFlow()

    private val _updates = MutableSharedFlow<TxnRecord>(extraBufferCapacity = 64)

    /** Every journal change, including results recovered after a dropped link. */
    val updates: SharedFlow<TxnRecord> = _updates.asSharedFlow()

    private var active: ActiveSale? = null
    private var wasOnline = false
    private val statusWaiters = HashMap<String, MutableList<CompletableDeferred<TxnStatus>>>()

    private inner class ActiveSale(val request: SaleRequest, val onEvent: (SaleEvent) -> Unit) {
        val outcome = CompletableDeferred<SaleOutcome>()
        var phase = Phase.SENDING
        var watchdog: Job? = null
    }

    private enum class Phase { SENDING, WAITING_CARD, PROCESSING }

    fun start() {
        scope.launch {
            link.listener = listener
            link.start()
            scope.launch {
                link.status.collect { s ->
                    val online = s.canSend
                    val a = active
                    if (wasOnline && !online && a != null) a.onEvent(SaleEvent.ConnectionLost)
                    wasOnline = online
                }
            }
        }
    }

    suspend fun stop() {
        link.stop()
        if (ownsScope) scope.cancel()
    }

    // ---- pairing ----

    /**
     * Pairs with the terminal on this link (open pairing on the terminal first). [confirm] gets the
     * 6-digit code; ask the person to type the code the terminal shows and return whether it matched.
     */
    suspend fun pair(confirm: suspend (code: String) -> Boolean): PairingResult = link.pair(confirm)

    fun pairedTerminals(): List<Pairing> = pairings.all()
    fun forget(terminalDeviceId: String) = link.forget(terminalDeviceId)

    // ---- sales ----

    /**
     * Asks the terminal for a payment and waits for the final answer. [amount] is in minor units
     * (12.500 LYD = 12500). [onEvent] reports progress on the cashier's internal thread.
     * Cancelling the calling coroutine stops waiting only; use [cancel] to cancel on the terminal.
     */
    suspend fun sale(
        amount: Long,
        currency: String,
        reference: String? = null,
        txnId: String = UUID.randomUUID().toString(),
        onEvent: (SaleEvent) -> Unit = {},
    ): SaleOutcome {
        val sale = withContext(dispatcher) { begin(SaleRequest(txnId, amount, currency, reference), onEvent) }
        return sale.await()
    }

    private fun begin(req: SaleRequest, onEvent: (SaleEvent) -> Unit): CompletableDeferred<SaleOutcome> {
        val done = CompletableDeferred<SaleOutcome>()
        req.validate()?.let { problem -> return done.also { it.complete(SaleOutcome.Refused(req.txnId, ErrorCodes.BAD_REQUEST, problem)) } }
        if (!link.status.value.canSend) return done.also { it.complete(SaleOutcome.Refused(req.txnId, null, "Terminal not connected")) }
        if (active != null) return done.also { it.complete(SaleOutcome.Refused(req.txnId, ErrorCodes.BUSY, "Another sale is still running on this cashier")) }
        val sale = ActiveSale(req, onEvent)
        active = sale
        put(TxnRecord(req, TxnState.PENDING))
        armWatchdog(sale)
        onEvent(SaleEvent.Sent(req.txnId))
        val delivered = link.sendMessage(MsgType.SALE_REQUEST, req.toJson())
        scope.launch {
            if (!delivered.await() && active === sale && sale.phase == Phase.SENDING) {
                markUnknown(sale, "The terminal did not confirm the sale. Check its status before charging again.")
            }
        }
        sale.outcome.invokeOnCompletion { scope.launch { if (active === sale) { sale.watchdog?.cancel(); active = null } } }
        return sale.outcome
    }

    /** Asks the terminal to cancel [txnId]; only works before a card is presented. True if delivered. */
    suspend fun cancel(txnId: String): Boolean = link.send(MsgType.CANCEL, JSONObject().put("txnId", txnId))

    /** Asks the terminal what happened to [txnId], and updates the journal. Null if it didn't answer. */
    suspend fun status(txnId: String, timeoutMs: Long = 10_000): TxnStatus? {
        val waiter = CompletableDeferred<TxnStatus>()
        val delivered = withContext(dispatcher) {
            statusWaiters.getOrPut(txnId) { mutableListOf() } += waiter
            link.sendMessage(MsgType.TXN_STATUS_QUERY, JSONObject().put("txnId", txnId))
        }.await()
        return if (delivered) withTimeoutOrNull(timeoutMs) { waiter.await() } else null
    }

    fun transactions(): List<TxnRecord> = journal.recent()

    // ---- protocol ----

    private val listener = object : LinkListener {
        override fun onMessage(message: Message) = onTerminalMessage(message)
        override fun onOnline() = onLinkOnline()
    }

    private fun onTerminalMessage(m: Message) {
        val sale = active?.takeIf { it.request.txnId == m.txnId }
        when (m.type) {
            MsgType.STATUS -> _terminalState.value = m.payload.str("state")
            MsgType.READY_TO_PAY -> if (sale != null && sale.phase == Phase.SENDING) {
                sale.phase = Phase.WAITING_CARD
                armWatchdog(sale)
                updateState(sale.request.txnId, TxnState.IN_PROGRESS)
                sale.onEvent(SaleEvent.ReadyToPay)
            }
            MsgType.PROGRESS -> if (sale != null) {
                applyStage(sale, PaymentStage.fromWire(m.payload.str("stage")))
                updateState(sale.request.txnId, TxnState.IN_PROGRESS)
            }
            MsgType.SALE_RESULT -> applyResult(SaleResult.fromJson(m.payload))
            MsgType.TXN_STATUS -> onTxnStatus(m.payload)
            MsgType.ERROR -> onError(m.payload)
            else -> log("Ignoring ${m.type}")
        }
    }

    private fun applyStage(sale: ActiveSale, stage: PaymentStage) {
        sale.phase = if (stage == PaymentStage.WAITING_CARD) Phase.WAITING_CARD else Phase.PROCESSING
        armWatchdog(sale)
        sale.onEvent(SaleEvent.Progress(stage))
    }

    private fun applyResult(r: SaleResult) {
        val record = journal.get(r.txnId)
        if (record != null) {
            put(record.copy(state = TxnState.COMPLETED, result = r, updatedAt = System.currentTimeMillis()))
        } else {
            log("Result for a sale this cashier doesn't know: ${r.txnId}")
            put(TxnRecord(SaleRequest(r.txnId, r.amount, r.currency, createdAt = r.timestamp), TxnState.COMPLETED, r))
        }
        active?.takeIf { it.request.txnId == r.txnId }?.let { finish(it, SaleOutcome.Completed(r)) }
    }

    private fun onTxnStatus(p: JSONObject) {
        val txnId = p.str("txnId") ?: return
        val state = TxnStatusState.fromWire(p.str("state"))
        val result = p.optJSONObject("result")?.let { SaleResult.fromJson(it) }
        val stage = p.str("stage")?.let { PaymentStage.fromWire(it) }
        statusWaiters.remove(txnId)?.forEach { it.complete(TxnStatus(state, result, stage)) }
        val sale = active?.takeIf { it.request.txnId == txnId }
        when (state) {
            TxnStatusState.COMPLETED -> if (result != null) applyResult(result)
            TxnStatusState.IN_PROGRESS -> if (sale != null) applyStage(sale, stage ?: PaymentStage.WAITING_CARD)
            TxnStatusState.NOT_FOUND -> {
                val message = "The terminal never received this sale. No money was taken; you can charge again."
                updateState(txnId, TxnState.REJECTED, message)
                if (sale != null) finish(sale, SaleOutcome.Refused(txnId, null, message))
            }
            TxnStatusState.INTERRUPTED -> {
                val message = "The terminal was restarted during this payment. Check the terminal's transaction report."
                updateState(txnId, TxnState.UNKNOWN, message)
                if (sale != null) finish(sale, SaleOutcome.Unknown(txnId, message))
            }
        }
    }

    private fun onError(p: JSONObject) {
        val code = p.str("code")
        val text = p.str("message") ?: code ?: "Terminal error"
        val sale = active?.takeIf { it.request.txnId == p.str("txnId") }
        if (sale == null) {
            log("Terminal error for another sale: $code $text")
            return
        }
        when (code) {
            ErrorCodes.BUSY -> refuse(sale, code, "The terminal is busy with another payment.")
            ErrorCodes.BAD_REQUEST -> refuse(sale, code, "The terminal rejected the sale: $text")
            ErrorCodes.TXN_INTERRUPTED -> markUnknown(sale, text)
            ErrorCodes.CANNOT_CANCEL -> log("Too late to cancel: the card was already presented")
            else -> log("Terminal error: $code $text")
        }
    }

    private fun onLinkOnline() {
        val sale = active
        if (sale != null) sale.onEvent(SaleEvent.Reconnected)
        // Ask about every sale whose outcome this cashier doesn't know yet (last 24 h).
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        journal.recent()
            .filter { it.state in setOf(TxnState.PENDING, TxnState.IN_PROGRESS, TxnState.UNKNOWN) && it.updatedAt > cutoff }
            .take(10)
            .forEach {
                log("Checking the outcome of ${it.txnId}")
                link.sendMessage(MsgType.TXN_STATUS_QUERY, JSONObject().put("txnId", it.txnId))
            }
    }

    private fun armWatchdog(sale: ActiveSale) {
        sale.watchdog?.cancel()
        sale.watchdog = scope.launch {
            delay(noUpdateTimeoutMs)
            if (active === sale) markUnknown(sale, "No update from the terminal for ${noUpdateTimeoutMs / 1000}s. Check its status.")
        }
    }

    private fun markUnknown(sale: ActiveSale, message: String) {
        updateState(sale.request.txnId, TxnState.UNKNOWN, message)
        finish(sale, SaleOutcome.Unknown(sale.request.txnId, message))
    }

    private fun refuse(sale: ActiveSale, code: String?, message: String) {
        updateState(sale.request.txnId, TxnState.REJECTED, message)
        finish(sale, SaleOutcome.Refused(sale.request.txnId, code, message))
    }

    private fun finish(sale: ActiveSale, outcome: SaleOutcome) {
        sale.watchdog?.cancel()
        if (active === sale) active = null
        sale.outcome.complete(outcome)
    }

    private fun updateState(txnId: String, state: TxnState, note: String? = null) {
        val r = journal.get(txnId) ?: return
        if (r.state == TxnState.COMPLETED || (r.state == state && note == null)) return
        put(r.copy(state = state, note = note ?: r.note, updatedAt = System.currentTimeMillis()))
    }

    private fun put(record: TxnRecord) {
        journal.put(record)
        _updates.tryEmit(record)
    }
}
