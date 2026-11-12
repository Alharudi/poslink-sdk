package ly.dojoon.poslink

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ly.dojoon.poslink.cashier.PosLinkCashier
import ly.dojoon.poslink.cashier.SaleOutcome
import ly.dojoon.poslink.journal.InMemoryJournal
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.InMemoryPairingStore
import ly.dojoon.poslink.link.LinkStatus
import ly.dojoon.poslink.link.Pairing
import ly.dojoon.poslink.protocol.Outcome
import ly.dojoon.poslink.terminal.PosLinkTerminal
import ly.dojoon.poslink.terminal.SimulatedPayment
import ly.dojoon.poslink.transport.MemoryTransportPair
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The example from docs/android/testing.md, kept here so the documented code is known to compile
 * and pass on real dispatchers (no virtual time). Keep the two in sync.
 */
class DocsExampleTest {
    @Test fun approvedSale() = runBlocking {
        val wire = MemoryTransportPair()
        val cashierId = DeviceIdentity.random("Front desk")
        val terminalId = DeviceIdentity.random("Test terminal")

        // Skip pairing: give both sides the same key (tests only).
        val key = ByteArray(32) { 7 }
        val cashierPairings = InMemoryPairingStore().apply { put(Pairing(terminalId.idHex, terminalId.name, key, 0)) }
        val terminalPairings = InMemoryPairingStore().apply { put(Pairing(cashierId.idHex, cashierId.name, key, 0)) }

        val terminal = PosLinkTerminal(
            wire.b, terminalId, terminalPairings, InMemoryJournal(),
            SimulatedPayment(autoOutcome = Outcome.APPROVED, stepDelayMs = 10),
        )
        val cashier = PosLinkCashier(wire.a, cashierId, cashierPairings, InMemoryJournal())
        terminal.start()
        cashier.start()

        withTimeout(10_000) { cashier.status.first { it == LinkStatus.ONLINE } }
        val outcome = withTimeout(10_000) { cashier.sale(12500, "LYD") }

        assertTrue(outcome is SaleOutcome.Completed && outcome.result.approved)
        cashier.stop()
        terminal.stop()
    }
}
