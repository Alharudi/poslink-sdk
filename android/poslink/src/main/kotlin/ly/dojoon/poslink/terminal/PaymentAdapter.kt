package ly.dojoon.poslink.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import ly.dojoon.poslink.protocol.Outcome
import ly.dojoon.poslink.protocol.PaymentStage
import ly.dojoon.poslink.protocol.SaleRequest
import ly.dojoon.poslink.protocol.SaleResult
import kotlin.random.Random

sealed interface PaymentEvent {
    data class Stage(val stage: PaymentStage) : PaymentEvent
    data class Finished(val result: SaleResult) : PaymentEvent
}

/**
 * What actually charges the card on the terminal: the bank's payment app (Intent/AIDL), Sunmi
 * PayLib, or [SimulatedPayment] while there is none.
 */
interface PaymentAdapter {
    val name: String

    /** True for the simulator, so the terminal UI can show "simulate card" buttons. */
    val isSimulator: Boolean get() = false

    /** Runs one sale: emits stages and ends with exactly one [PaymentEvent.Finished]. */
    fun sale(request: SaleRequest): Flow<PaymentEvent>

    /** Cancels the running sale if no card has been presented yet; false when it is too late. */
    fun cancel(txnId: String): Boolean
}

/**
 * Simulated card payment so the whole flow works without a card reader. Call [presentCard] from
 * the terminal UI, or set [autoOutcome] (tests, demos). No real card is charged.
 */
class SimulatedPayment(
    private val autoOutcome: Outcome? = null,
    private val cardTimeoutMs: Long = 60_000,
    private val stepDelayMs: Long = 900,
) : PaymentAdapter {

    private inner class Active(val request: SaleRequest, val scope: ProducerScope<PaymentEvent>) {
        val done = CompletableDeferred<Unit>()
        var cardPresented = false

        fun present(approve: Boolean) {
            synchronized(this@SimulatedPayment) {
                if (cardPresented || done.isCompleted) return
                cardPresented = true
            }
            scope.launch {
                for (stage in listOf(PaymentStage.CARD_READ, PaymentStage.PIN_ENTRY, PaymentStage.AUTHORIZING)) {
                    if (done.isCompleted) return@launch
                    scope.send(PaymentEvent.Stage(stage))
                    delay(stepDelayMs)
                }
                if (approve) finish(Outcome.APPROVED, "00", "Approved", digits(6))
                else finish(Outcome.DECLINED, "05", "Do not honour", null)
            }
        }

        fun finish(outcome: Outcome, responseCode: String?, message: String?, authCode: String?) {
            synchronized(this@SimulatedPayment) {
                if (done.isCompleted) return
                if (active === this) active = null
            }
            val carded = cardPresented
            scope.trySend(
                PaymentEvent.Finished(
                    SaleResult(
                        txnId = request.txnId,
                        outcome = outcome,
                        amount = request.amount,
                        currency = request.currency,
                        responseCode = responseCode,
                        message = message,
                        authCode = authCode,
                        rrn = if (carded) digits(12) else null,
                        stan = if (carded) digits(6) else null,
                        maskedPan = if (carded) "476173******0119" else null,
                        cardScheme = if (carded) "VISA" else null,
                        terminalId = "SIM00001",
                        merchantId = "SIMMERCHANT001",
                    ),
                ),
            )
            done.complete(Unit)
        }
    }

    private var active: Active? = null

    override val name = "Simulator"
    override val isSimulator = true

    val waitingForCard: Boolean @Synchronized get() = active?.cardPresented == false

    override fun sale(request: SaleRequest): Flow<PaymentEvent> = channelFlow {
        val sale = Active(request, this)
        val previous = synchronized(this@SimulatedPayment) { active.also { active = sale } }
        previous?.finish(Outcome.CANCELLED, null, "Replaced by a new sale", null)
        send(PaymentEvent.Stage(PaymentStage.WAITING_CARD))
        val timeout = launch {
            delay(cardTimeoutMs)
            if (!sale.cardPresented) sale.finish(Outcome.TIMEOUT, null, "No card presented", null)
        }
        autoOutcome?.let { outcome ->
            launch {
                delay(stepDelayMs)
                sale.present(outcome == Outcome.APPROVED)
            }
        }
        sale.done.await()
        timeout.cancel()
    }

    /** The customer taps or inserts a card. */
    fun presentCard(approve: Boolean) {
        synchronized(this) { active }?.present(approve)
    }

    override fun cancel(txnId: String): Boolean {
        val sale = synchronized(this) { active } ?: return false
        if (sale.request.txnId != txnId || sale.cardPresented) return false
        sale.finish(Outcome.CANCELLED, null, "Cancelled", null)
        return true
    }

    private fun digits(n: Int) = (1..n).map { Random.nextInt(10) }.joinToString("")
}
