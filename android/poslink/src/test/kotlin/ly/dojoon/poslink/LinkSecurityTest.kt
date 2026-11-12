package ly.dojoon.poslink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ly.dojoon.poslink.link.DeviceIdentity
import ly.dojoon.poslink.link.InMemoryPairingStore
import ly.dojoon.poslink.link.LinkListener
import ly.dojoon.poslink.link.LinkSession
import ly.dojoon.poslink.link.LinkStatus
import ly.dojoon.poslink.link.Pairing
import ly.dojoon.poslink.link.PairingRequest
import ly.dojoon.poslink.link.PairingResult
import ly.dojoon.poslink.protocol.FrameCodec
import ly.dojoon.poslink.protocol.Message
import ly.dojoon.poslink.protocol.MsgType
import ly.dojoon.poslink.protocol.PairFailure
import ly.dojoon.poslink.protocol.Role
import ly.dojoon.poslink.security.Crypto
import ly.dojoon.poslink.transport.MemoryTransportPair
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkSecurityTest {

    /** A cashier and a terminal on an in-memory cable. */
    private class Pair(scope: CoroutineScope, test: TestScope, shared: Boolean) {
        val wire = MemoryTransportPair()
        val cashierId = DeviceIdentity.random("Front desk")
        val terminalId = DeviceIdentity.random("SUNMI P3")
        val cashierStore = InMemoryPairingStore()
        val terminalStore = InMemoryPairingStore()
        val log = mutableListOf<String>()
        val cashier = LinkSession(wire.a, Role.CASHIER, cashierId, cashierStore, scope, clock = { test.testScheduler.currentTime }, log = { log += "C $it" })
        val terminal = LinkSession(wire.b, Role.TERMINAL, terminalId, terminalStore, scope, clock = { test.testScheduler.currentTime }, log = { log += "T $it" })
        val cashierMessages = mutableListOf<Message>()
        val terminalMessages = mutableListOf<Message>()
        var pairingRequest: PairingRequest? = null
        var terminalPairingResult: PairingResult? = null

        init {
            if (shared) {
                val key = Crypto.randomBytes(32)
                cashierStore.put(Pairing(terminalId.idHex, "SUNMI P3", key, 0))
                terminalStore.put(Pairing(cashierId.idHex, "Front desk", key, 0))
            }
            cashier.listener = object : LinkListener {
                override fun onMessage(message: Message) { cashierMessages += message }
            }
            terminal.listener = object : LinkListener {
                override fun onMessage(message: Message) { terminalMessages += message }
                override fun onPairingRequest(request: PairingRequest) { pairingRequest = request }
                override fun onPairingFinished(result: PairingResult) { terminalPairingResult = result }
            }
            terminal.start()
            cashier.start()
        }
    }

    private fun TestScope.settle(ms: Long = 100) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun pairedDevicesGoOnlineAndExchangeMessages() = runTest {
        val p = Pair(backgroundScope, this, shared = true)
        settle()
        assertEquals(LinkStatus.ONLINE, p.cashier.status.value)
        assertEquals(LinkStatus.ONLINE, p.terminal.status.value)
        val sent = async { p.cashier.send(MsgType.SALE_REQUEST, JSONObject().put("txnId", "t1")) }
        settle()
        assertTrue(sent.await())
        assertEquals("t1", p.terminalMessages.single().txnId)
    }

    @Test fun unpairedDevicesStayUnpaired() = runTest {
        val p = Pair(backgroundScope, this, shared = false)
        settle(10_000)
        assertEquals(LinkStatus.UNPAIRED, p.cashier.status.value)
        assertEquals(LinkStatus.UNPAIRED, p.terminal.status.value)
        val sent = async { p.cashier.send(MsgType.SALE_REQUEST, JSONObject().put("txnId", "t1")) }
        settle()
        assertEquals(false, sent.await())
        assertTrue(p.terminalMessages.isEmpty())
    }

    @Test fun pairingWithMatchingCodesThenOnline() = runTest {
        val p = Pair(backgroundScope, this, shared = false)
        settle()
        p.terminal.openPairing()
        settle()
        var cashierCode: String? = null
        val result = async { p.cashier.pair { code -> cashierCode = code; true } }
        settle()
        val request = assertNotNull(p.pairingRequest).let { p.pairingRequest!! }
        assertEquals("both screens show the same code", cashierCode, request.code)
        request.confirm()
        settle()
        val r = result.await()
        assertTrue(r is PairingResult.Paired)
        assertTrue(p.terminalPairingResult is PairingResult.Paired)
        assertEquals(p.cashierStore.get(p.terminalId.idHex)!!.key.toList(), p.terminalStore.get(p.cashierId.idHex)!!.key.toList())
        assertEquals(LinkStatus.ONLINE, p.cashier.status.value)
        assertEquals(LinkStatus.ONLINE, p.terminal.status.value)
    }

    @Test fun pairingRefusedWhenNotOpenOnTheTerminal() = runTest {
        val p = Pair(backgroundScope, this, shared = false)
        settle()
        val result = async { p.cashier.pair { true } }
        settle()
        val r = result.await() as PairingResult.Failed
        assertEquals(PairFailure.NOT_PAIRING, r.reason)
        assertEquals(LinkStatus.UNPAIRED, p.cashier.status.value)
    }

    @Test fun pairingRejectedOnTheTerminalCancelsTheCashierPrompt() = runTest {
        val p = Pair(backgroundScope, this, shared = false)
        settle()
        p.terminal.openPairing()
        settle()
        var promptCancelled = false
        val result = async {
            p.cashier.pair {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    promptCancelled = true
                }
            }
        }
        settle()
        p.pairingRequest!!.reject()
        settle()
        assertEquals(PairFailure.REJECTED, (result.await() as PairingResult.Failed).reason)
        assertTrue(promptCancelled)
        assertTrue(p.cashierStore.all().isEmpty() && p.terminalStore.all().isEmpty())
    }

    @Test fun cashierRejectingKeepsBothUnpaired() = runTest {
        val p = Pair(backgroundScope, this, shared = false)
        settle()
        p.terminal.openPairing()
        settle()
        val result = async { p.cashier.pair { false } }
        settle()
        assertEquals(PairFailure.REJECTED, (result.await() as PairingResult.Failed).reason)
        assertEquals(PairFailure.REJECTED, (p.terminalPairingResult as PairingResult.Failed).reason)
        assertTrue(p.terminalStore.all().isEmpty())
    }

    @Test fun tamperedFrameIsDroppedAndTheRetryGetsThrough() = runTest {
        val p = Pair(backgroundScope, this, shared = true)
        settle()
        var tampered = 0
        p.wire.a.interceptor = { bytes ->
            if (tampered == 0 && bytes[2] == MsgType.SALE_REQUEST.code.toByte()) {
                tampered++
                retamper(bytes)
            } else bytes
        }
        val sent = async { p.cashier.send(MsgType.SALE_REQUEST, JSONObject().put("txnId", "t1").put("amount", 1000)) }
        settle(5_000)
        assertTrue(sent.await())
        assertEquals("only the genuine copy is delivered", 1, p.terminalMessages.size)
        assertEquals(1000L, p.terminalMessages.single().payload.getLong("amount"))
        assertEquals(1, p.terminal.stats.rejectedFrames)
    }

    @Test fun forgedAndReplayedFramesAreRejected() = runTest {
        val p = Pair(backgroundScope, this, shared = true)
        settle()
        // A device in the middle forges an "approved" result without the key.
        p.wire.b.inject(FrameCodec.encode(MsgType.SALE_RESULT.code, 9, JSONObject().put("txnId", "t1").put("outcome", "approved").toString().toByteArray()))
        p.wire.b.inject(FrameCodec.encode(MsgType.SALE_RESULT.code, 9, "{}".toByteArray(), 99, Crypto.randomBytes(32), Role.TERMINAL))
        settle()
        assertTrue(p.cashierMessages.isEmpty())

        // It replays a genuine frame it recorded earlier.
        val sent = async { p.terminal.send(MsgType.STATUS, JSONObject().put("state", "idle")) }
        settle()
        assertTrue(sent.await())
        val genuine = p.wire.b.sent.last { it[2] == MsgType.STATUS.code.toByte() }
        p.wire.b.inject(genuine)
        settle()
        assertEquals("the replay is not delivered again", 1, p.cashierMessages.size)
        assertEquals(3, p.cashier.stats.rejectedFrames)
    }

    @Test fun reinstalledTerminalIsReportedAsUnpaired() = runTest {
        val p = Pair(backgroundScope, this, shared = true)
        p.terminalStore.remove(p.cashierId.idHex)
        settle(4_000) // at most one heartbeat
        assertEquals(LinkStatus.UNPAIRED, p.cashier.status.value)
        assertTrue(p.cashier.problem.value!!.contains("Pair again"))
    }

    @Test fun reconnectDerivesAFreshSessionKey() = runTest {
        val p = Pair(backgroundScope, this, shared = true)
        settle()
        val before = p.wire.a.sent.size
        p.wire.disconnect()
        settle()
        assertEquals(LinkStatus.OFFLINE, p.cashier.status.value)
        p.wire.reconnect()
        settle()
        assertEquals(LinkStatus.ONLINE, p.cashier.status.value)
        // Frames recorded before the reconnect are useless now.
        p.wire.a.inject(p.wire.a.sent[before - 1])
        settle()
        assertTrue(p.terminal.stats.rejectedFrames >= 1)
    }

    /** Flips a payload byte and fixes the CRC, so only the mac can catch it. */
    private fun retamper(frame: ByteArray): ByteArray {
        val b = frame.copyOf()
        b[FrameCodec.HEADER_LENGTH + 2] = (b[FrameCodec.HEADER_LENGTH + 2].toInt() xor 0x01).toByte()
        val end = if (b.size % 64 == 1 && b.last() == 0.toByte()) b.size - 1 else b.size
        val crc = ly.dojoon.poslink.protocol.Crc16.compute(b, 1, end - 2)
        b[end - 2] = (crc ushr 8).toByte()
        b[end - 1] = crc.toByte()
        return b
    }
}
