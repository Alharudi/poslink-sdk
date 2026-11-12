package ly.dojoon.poslink

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.dojoon.poslink.cashier.PosLinkCashier
import ly.dojoon.poslink.cashier.SaleEvent
import ly.dojoon.poslink.cashier.SaleOutcome
import ly.dojoon.poslink.journal.InMemoryJournal
import ly.dojoon.poslink.journal.TxnState
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.InMemoryPairingStore
import ly.dojoon.poslink.link.LinkSession
import ly.dojoon.poslink.link.LinkStatus
import ly.dojoon.poslink.link.Pairing
import ly.dojoon.poslink.link.PairingResult
import ly.dojoon.poslink.protocol.ErrorCodes
import ly.dojoon.poslink.protocol.FrameCodec
import ly.dojoon.poslink.protocol.MsgType
import ly.dojoon.poslink.protocol.Outcome
import ly.dojoon.poslink.protocol.Role
import ly.dojoon.poslink.protocol.SaleRequest
import ly.dojoon.poslink.security.Crypto
import ly.dojoon.poslink.terminal.PaymentAdapter
import ly.dojoon.poslink.terminal.PaymentEvent
import ly.dojoon.poslink.terminal.PosLinkTerminal
import ly.dojoon.poslink.terminal.SimulatedPayment
import ly.dojoon.poslink.terminal.TerminalPhase
import ly.dojoon.poslink.transport.MemoryTransportPair
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class SaleFlowTest {

    private class CountingPayment(val inner: SimulatedPayment) : PaymentAdapter by inner {
        var sales = 0
        override fun sale(request: SaleRequest): Flow<PaymentEvent> {
            sales++
            return inner.sale(request)
        }
    }

    private inner class Harness(test: TestScope, auto: Outcome? = Outcome.APPROVED, paired: Boolean = true) {
        val wire = MemoryTransportPair()
        val payment = CountingPayment(SimulatedPayment(autoOutcome = auto, cardTimeoutMs = 60_000, stepDelayMs = 10))
        val cashierJournal = InMemoryJournal()
        val terminalJournal = InMemoryJournal()
        val cashierId = DeviceIdentity.random("Front desk")
        val terminalId = DeviceIdentity.random("SUNMI P3")
        val cashierPairings = InMemoryPairingStore()
        val terminalPairings = InMemoryPairingStore()
        private val dispatcher = test.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        private val clock = { test.testScheduler.currentTime }
        val cashier = PosLinkCashier(wire.a, cashierId, cashierPairings, cashierJournal, dispatcher = dispatcher, clock = clock, scope = test.backgroundScope)
        val terminal = PosLinkTerminal(wire.b, terminalId, terminalPairings, terminalJournal, payment, dispatcher = dispatcher, clock = clock, resultHoldMs = 100, scope = test.backgroundScope)

        init {
            if (paired) {
                val key = Crypto.randomBytes(32)
                cashierPairings.put(Pairing(terminalId.idHex, terminalId.name, key, 0))
                terminalPairings.put(Pairing(cashierId.idHex, cashierId.name, key, 0))
            }
            terminal.start()
            cashier.start()
        }

        /** Application payloads the terminal sent, decoded from the wire. */
        fun terminalSent(type: MsgType): List<JSONObject> = wire.b.sent.flatMap { FrameCodec.Decoder().add(it) }
            .filter { it.type == type.code }
            .map { JSONObject(String(it.payload)) }
    }

    private fun TestScope.waitUntil(reason: String, maxMs: Long = 120_000, condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            if (waited > maxMs) fail("Timed out: $reason")
            advanceTimeBy(10)
            runCurrent()
            waited += 10
        }
    }

    private fun TestScope.online(h: Harness) =
        waitUntil("handshake") { h.cashier.status.value == LinkStatus.ONLINE && h.terminal.status.value == LinkStatus.ONLINE }

    @Test fun approvedSaleEndToEnd() = runTest {
        val h = Harness(this)
        online(h)
        val events = mutableListOf<SaleEvent>()
        val sale = async { h.cashier.sale(12500, "LYD", reference = "INV-1") { events += it } }
        waitUntil("result") { sale.isCompleted }
        val result = (sale.await() as SaleOutcome.Completed).result
        assertEquals(Outcome.APPROVED, result.outcome)
        assertEquals(12500L, result.amount)
        assertEquals("LYD", result.currency)
        assertNotNull(result.authCode)
        assertTrue(events.first() is SaleEvent.Sent && SaleEvent.ReadyToPay in events)
        assertEquals(TxnState.COMPLETED, h.cashierJournal.get(result.txnId)!!.state)
        waitUntil("delivered flag") { h.terminalJournal.get(result.txnId)!!.resultDelivered }
    }

    @Test fun declinedSale() = runTest {
        val h = Harness(this, auto = Outcome.DECLINED)
        online(h)
        val sale = async { h.cashier.sale(1000, "LYD") }
        waitUntil("result") { sale.isCompleted }
        val result = (sale.await() as SaleOutcome.Completed).result
        assertEquals(Outcome.DECLINED, result.outcome)
        assertEquals("05", result.responseCode)
    }

    @Test fun repeatedSaleRequestReturnsStoredResultWithoutChargingAgain() = runTest {
        val h = Harness(this)
        online(h)
        val sale = async { h.cashier.sale(5000, "LYD") }
        waitUntil("result") { sale.isCompleted }
        val txnId = sale.await().txnId
        val request = h.cashierJournal.get(txnId)!!.request
        val resent = async { h.cashier.link.send(MsgType.SALE_REQUEST, request.toJson()) }
        waitUntil("second result") { h.terminalSent(MsgType.SALE_RESULT).size == 2 }
        assertTrue(resent.await())
        assertEquals("card charged once", 1, h.payment.sales)
    }

    @Test fun cancelBeforeTheCardIsPresented() = runTest {
        val h = Harness(this, auto = null)
        online(h)
        var ready = false
        val sale = async { h.cashier.sale(2000, "LYD") { if (it == SaleEvent.ReadyToPay) ready = true } }
        waitUntil("ready to pay") { ready }
        val cancelled = async { h.cashier.cancel(h.cashierJournal.recent().first().txnId) }
        waitUntil("result") { sale.isCompleted }
        assertTrue(cancelled.await())
        assertEquals(Outcome.CANCELLED, (sale.await() as SaleOutcome.Completed).result.outcome)
    }

    @Test fun busyTerminalRefusesASecondSale() = runTest {
        val h = Harness(this, auto = null)
        online(h)
        async { h.cashier.sale(2000, "LYD") }
        waitUntil("terminal waiting for card") { h.terminal.phase.value is TerminalPhase.WaitingCard }
        val other = SaleRequest("other-sale", 1, "LYD")
        async { h.cashier.link.send(MsgType.SALE_REQUEST, other.toJson()) }
        waitUntil("busy error") { h.terminalSent(MsgType.ERROR).isNotEmpty() }
        assertEquals(ErrorCodes.BUSY, h.terminalSent(MsgType.ERROR).single().getString("code"))
        assertEquals(1, h.payment.sales)
    }

    @Test fun lostFramesAreRetriedTransparently() = runTest {
        val h = Harness(this)
        online(h)
        h.wire.a.dropNext = 2
        val sale = async { h.cashier.sale(750, "LYD") }
        waitUntil("result") { sale.isCompleted }
        assertTrue((sale.await() as SaleOutcome.Completed).result.approved)
        assertTrue(h.cashier.link.stats.retries >= 1)
        assertEquals(1, h.payment.sales)
    }

    @Test fun resultReachesTheCashierAfterTheCableIsPulledMidPayment() = runTest {
        val h = Harness(this, auto = null)
        online(h)
        val events = mutableListOf<SaleEvent>()
        val sale = async { h.cashier.sale(9900, "LYD") { events += it } }
        waitUntil("terminal waiting for card") { h.terminal.phase.value is TerminalPhase.WaitingCard }

        h.wire.disconnect()
        waitUntil("offline") { h.cashier.status.value == LinkStatus.OFFLINE }
        h.payment.inner.presentCard(approve = true)
        waitUntil("terminal finished") { h.terminal.phase.value is TerminalPhase.Result || h.terminal.phase.value == TerminalPhase.Idle }
        val txnId = h.cashierJournal.recent().first().txnId
        assertFalse(h.terminalJournal.get(txnId)!!.resultDelivered)
        assertFalse(sale.isCompleted)

        h.wire.reconnect()
        waitUntil("recovered result") { sale.isCompleted }
        assertTrue((sale.await() as SaleOutcome.Completed).result.approved)
        assertTrue(SaleEvent.ConnectionLost in events && SaleEvent.Reconnected in events)
        assertEquals(TxnState.COMPLETED, h.cashierJournal.get(txnId)!!.state)
        waitUntil("delivered flag") { h.terminalJournal.get(txnId)!!.resultDelivered }
    }

    @Test fun noAcknowledgementGivesUnknownThenStatusRecoversIt() = runTest {
        val h = Harness(this)
        online(h)
        h.wire.b.dropNext = Int.MAX_VALUE // the terminal hears everything, nothing it sends arrives
        val sale = async { h.cashier.sale(4321, "LYD") }
        waitUntil("unknown") { sale.isCompleted }
        val unknown = sale.await() as SaleOutcome.Unknown
        assertEquals(TxnState.UNKNOWN, h.cashierJournal.get(unknown.txnId)!!.state)

        waitUntil("terminal finished") { h.terminalJournal.get(unknown.txnId)?.state == TxnState.COMPLETED }
        h.wire.b.dropNext = 0
        val status = async { h.cashier.status(unknown.txnId) }
        waitUntil("status answer") { status.isCompleted }
        assertTrue(status.await()!!.result!!.approved)
        assertEquals(TxnState.COMPLETED, h.cashierJournal.get(unknown.txnId)!!.state)
        assertEquals(1, h.payment.sales)
    }

    @Test fun saleWorksWhenEveryFrameArrivesIn20BytePieces() = runTest {
        val h = Harness(this)
        h.wire.a.chunkSize = 20
        h.wire.b.chunkSize = 20
        online(h)
        val sale = async { h.cashier.sale(12500, "LYD") }
        waitUntil("result") { sale.isCompleted }
        assertTrue((sale.await() as SaleOutcome.Completed).result.approved)
    }

    @Test fun pairingThenSaleThroughThePublicApi() = runTest {
        val h = Harness(this, paired = false)
        waitUntil("unpaired") { h.cashier.status.value == LinkStatus.UNPAIRED }
        h.terminal.openPairing()
        runCurrent()
        var shownOnCashier: String? = null
        val pairing = async { h.cashier.pair { code -> shownOnCashier = code; true } }
        waitUntil("pairing request on the terminal") { h.terminal.pairingRequest.value != null }
        val request = h.terminal.pairingRequest.value!!
        assertEquals(shownOnCashier, request.code)
        assertEquals("Front desk", request.cashierName)
        request.confirm()
        waitUntil("paired") { pairing.isCompleted }
        assertTrue(pairing.await() is PairingResult.Paired)
        online(h)
        val sale = async { h.cashier.sale(100, "LYD") }
        waitUntil("result") { sale.isCompleted }
        assertTrue((sale.await() as SaleOutcome.Completed).result.approved)
        assertEquals(1, h.terminal.pairedCashiers().size)
    }

    @Test fun aPeerThatStopsReadingGetsAFewFramesNotABacklogOfHeartbeats() = runTest {
        val h = Harness(this)
        h.wire.b.stall() // the terminal's writes go nowhere, like USB when the cashier isn't reading
        advanceTimeBy(3000 * 12)
        runCurrent()
        assertTrue("only the first HELLO and AUTH wait, got ${h.wire.b.heldFrames}", h.wire.b.heldFrames <= 2)
        assertTrue(h.terminal.link.stats.heldHeartbeats > 5)
        assertFalse(h.cashier.status.value == LinkStatus.ONLINE)
        h.wire.b.unstall()
        online(h)
    }

    @Test fun aMessageIsNeverQueuedTwiceWhileThePipeIsBlocked() = runTest {
        val h = Harness(this, auto = null)
        online(h)
        h.wire.a.stall()
        val sent = async { h.cashier.link.send(MsgType.CANCEL, JSONObject().put("txnId", "nothing-running")) }
        advanceTimeBy(1500 * 5 / 2)
        runCurrent()
        assertEquals("retries wait for the first copy to leave", 1, h.wire.a.heldFrames)
        h.wire.a.unstall()
        waitUntil("ack") { sent.isCompleted }
        assertTrue(sent.await())
    }

    @Test fun twoDevicesWithTheSameRoleDoNotGoOnline() = runTest {
        val wire = MemoryTransportPair()
        val store = InMemoryPairingStore()
        val a = LinkSession(wire.a, Role.CASHIER, DeviceIdentity.random("A"), store, backgroundScope, clock = { testScheduler.currentTime })
        val b = LinkSession(wire.b, Role.CASHIER, DeviceIdentity.random("B"), store, backgroundScope, clock = { testScheduler.currentTime })
        a.start()
        b.start()
        waitUntil("problem reported") { a.problem.value != null && b.problem.value != null }
        assertFalse(a.status.value.canSend || b.status.value.canSend)
    }
}
