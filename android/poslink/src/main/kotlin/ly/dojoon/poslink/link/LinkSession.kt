package ly.dojoon.poslink.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ly.dojoon.poslink.PosLinkInfo
import ly.dojoon.poslink.protocol.ErrorCodes
import ly.dojoon.poslink.protocol.Frame
import ly.dojoon.poslink.protocol.FrameCodec
import ly.dojoon.poslink.protocol.Message
import ly.dojoon.poslink.protocol.MsgType
import ly.dojoon.poslink.protocol.PairFailure
import ly.dojoon.poslink.protocol.Role
import ly.dojoon.poslink.protocol.long
import ly.dojoon.poslink.protocol.str
import ly.dojoon.poslink.security.Base64
import ly.dojoon.poslink.security.Crypto
import ly.dojoon.poslink.security.P256KeyPair
import ly.dojoon.poslink.security.PairingCrypto
import ly.dojoon.poslink.security.hexToBytes
import ly.dojoon.poslink.transport.Transport
import ly.dojoon.poslink.transport.TransportState
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor

enum class LinkStatus {
    /** Transport not connected (cable out, socket closed, Bluetooth off). */
    OFFLINE,

    /** Transport connected, waiting for the other app's HELLO. */
    HANDSHAKING,

    /** The other app answered, but the two devices aren't paired (or the peer lost the pairing). */
    UNPAIRED,

    /** A pairing is running. */
    PAIRING,

    /** Paired; waiting for the peer to prove it has the same key. */
    AUTHENTICATING,

    /** Authenticated and talking. */
    ONLINE,

    /** Was online, but the peer stopped answering heartbeats. */
    NOT_RESPONDING;

    val canSend: Boolean get() = this == ONLINE || this == NOT_RESPONDING
}

data class LinkConfig(
    val ackTimeoutMs: Long = 1500,
    val maxRetries: Int = 3,
    val heartbeatMs: Long = 3000,
    val staleAfterMs: Long = 10_000,
    val pairingTimeoutMs: Long = 120_000,
    val maxAuthFailures: Int = 5,
)

/** What the peer said about itself in its HELLO. */
class PeerInfo internal constructor(val payload: JSONObject) {
    val role: Role? = Role.fromWire(payload.str("role"))
    val deviceId: String? = payload.str("deviceId")?.lowercase()?.takeIf { it.length == 32 && it.all(Char::isLetterOrDigit) }
    val sessionId: String = payload.str("sessionId") ?: ""
    val appVersion: String? = payload.str("appVersion")
    val description: String
        get() = listOfNotNull(payload.str("manufacturer"), payload.str("model")).joinToString(" ").ifEmpty { "Unknown device" }
    val name: String = payload.str("name") ?: description
    internal val nonce: ByteArray? = Base64.decode(payload.str("nonce"))?.takeIf { it.size == Crypto.NONCE_LENGTH }
    internal val deviceIdBytes: ByteArray? get() = deviceId?.let { runCatching { it.hexToBytes() }.getOrNull() }

    override fun toString() = "$name (${role?.wire}, ${deviceId?.take(8)})"
}

class LinkStats {
    var framesSent = 0
    var framesReceived = 0
    var retries = 0
    var failedSends = 0
    var duplicates = 0
    var heldHeartbeats = 0
    var rejectedFrames = 0
}

sealed interface PairingResult {
    data class Paired(val peerDeviceId: String, val peerName: String) : PairingResult
    data class Failed(val reason: PairFailure, val message: String) : PairingResult
}

/**
 * Terminal side: a cashier wants to pair. Show [code] next to [cashierName], then call [confirm]
 * if the cashier shows (or the merchant typed) the same code, or [reject].
 */
class PairingRequest internal constructor(
    val cashierName: String,
    val cashierDeviceId: String,
    val code: String,
    private val answer: (Boolean) -> Unit,
) {
    fun confirm() = answer(true)
    fun reject() = answer(false)
}

/** Callbacks from the session, always on its own thread. */
interface LinkListener {
    /** An application message from the peer, delivered once. */
    fun onMessage(message: Message) {}

    /** The link became usable: authenticated for a new connection or a restarted peer. */
    fun onOnline() {}

    /** Terminal only. */
    fun onPairingRequest(request: PairingRequest) {}

    fun onPairingFinished(result: PairingResult) {}
}

/**
 * Secure, reliable message channel over a [Transport] (spec/PROTOCOL.md section 5 and 6).
 *
 * * HELLO handshake with a fresh nonce per connection; AUTH with a session key derived from the
 *   pairing key, so every frame after that carries a mac and a counter.
 * * Pairing (cashier starts it with [pair]; terminal accepts it after [openPairing]).
 * * Application messages are ACKed by seq and retried; duplicates are ACKed but delivered once.
 * * PING heartbeat; not responding after [LinkConfig.staleAfterMs]; transport reset after twice that.
 * * Back-pressure: heartbeats are held while earlier frames haven't left, and a retry never queues
 *   a second copy (a USB accessory write blocks while the other side isn't reading).
 *
 * All state lives on [scope], which must be single-threaded (e.g. `Dispatchers.Default.limitedParallelism(1)`).
 */
class LinkSession(
    val transport: Transport,
    val role: Role,
    private val identity: DeviceIdentity,
    private val pairings: PairingStore,
    private val scope: CoroutineScope,
    private val config: LinkConfig = LinkConfig(),
    /** Sent in HELLO for display: manufacturer, model, appVersion. */
    private val info: Map<String, Any?> = emptyMap(),
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) : Transport.Listener {

    var listener: LinkListener? = null

    val sessionId: String = UUID.randomUUID().toString()
    val stats = LinkStats()

    private val _status = MutableStateFlow(LinkStatus.OFFLINE)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** A problem that blocks the link, e.g. "both devices are cashiers". Null when there is none. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _peer = MutableStateFlow<PeerInfo?>(null)
    val peer: StateFlow<PeerInfo?> = _peer.asStateFlow()

    private val _transportState = MutableStateFlow(TransportState.STOPPED)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    private val decoder = FrameCodec.Decoder()
    private val pending = HashMap<Int, PendingSend>()
    private val recentSeqs = ArrayDeque<Int>()
    private val recentSet = HashSet<Int>()

    private var started = false
    private var heartbeat: Job? = null
    private var connected = false
    private var linkNonce = ByteArray(Crypto.NONCE_LENGTH)
    private var peerHello: PeerInfo? = null
    private var lastPeerSessionId: String? = null
    private var sessionKey: ByteArray? = null
    private var txCounter = 0L
    private var rxCounter = 0L
    private var peerAuthed = false
    private var stale = false
    private var peerForgotUs = false
    private var peerReplied = false
    private var authFailures = 0
    private var nextSeq = 0
    private var lastRx = 0L
    private var resetRequested = false
    private var inFlight = 0
    private var linkEpoch = 0
    private var heldTicks = 0

    private var pairingWindowUntil = 0L
    private var cashierRun: CashierPairing? = null
    private var terminalRun: TerminalPairing? = null

    // ---- lifecycle ----

    fun start() {
        scope.launch {
            if (started) return@launch
            started = true
            heartbeat = scope.launch {
                while (isActive) {
                    delay(config.heartbeatMs)
                    tick()
                }
            }
            log("Starting ${transport.name}")
            transport.start(this@LinkSession)
        }
    }

    suspend fun stop() = withContext(dispatcher) {
        if (!started) return@withContext
        started = false
        heartbeat?.cancel()
        transport.stop()
        abortPairing("The link was stopped")
        failAllPending()
        connected = false
        peerHello = null
        sessionKey = null
        peerAuthed = false
        updateStatus()
    }

    // ---- sending ----

    /** Sends an application message; true once the peer ACKed it. Call from any coroutine. */
    suspend fun send(type: MsgType, payload: JSONObject = JSONObject()): Boolean =
        withContext(dispatcher) { sendMessage(type, payload) }.await()

    /** Same as [send], for callers already on the session's thread that don't want to wait. */
    internal fun sendMessage(type: MsgType, payload: JSONObject = JSONObject()): CompletableDeferred<Boolean> {
        require(!type.linkLevel) { "link-level messages are sent by the session itself" }
        val result = CompletableDeferred<Boolean>()
        if (!_status.value.canSend) {
            stats.failedSends++
            log("Not sent, link is ${_status.value}: $type")
            result.complete(false)
            return result
        }
        val seq = allocSeq()
        val p = PendingSend(type, seq, encodePayload(payload), result)
        pending[seq] = p
        log("→ $type #$seq $payload")
        transmit(p)
        return result
    }

    private fun transmit(p: PendingSend) {
        p.attempts++
        // A retry only helps if the previous copy actually left; if it is still queued (peer not
        // reading), another copy just grows the backlog.
        if (!p.writing) {
            val bytes = encodeAuthenticated(p.type, p.seq, p.payload)
            if (bytes != null) {
                p.writing = true
                write(bytes) { p.writing = false }
            }
        }
        p.timer?.cancel()
        p.timer = scope.launch {
            delay(config.ackTimeoutMs)
            if (pending[p.seq] !== p) return@launch
            if (p.attempts > config.maxRetries) {
                pending.remove(p.seq)
                stats.failedSends++
                log("✗ ${p.type} #${p.seq} not acknowledged after ${p.attempts} attempts")
                p.result.complete(false)
            } else {
                stats.retries++
                log("↻ retry ${p.type} #${p.seq}")
                transmit(p)
            }
        }
    }

    private fun sendLinkLevel(type: MsgType, seq: Int, payload: JSONObject? = null) {
        val body = payload?.let { encodePayload(it) } ?: FrameCodec.EMPTY
        val bytes = if (type.authenticated) encodeAuthenticated(type, seq, body) ?: return else FrameCodec.encode(type.code, seq, body)
        write(bytes)
    }

    private fun encodeAuthenticated(type: MsgType, seq: Int, payload: ByteArray): ByteArray? {
        val key = sessionKey ?: return null
        txCounter++
        return FrameCodec.encode(type.code, seq, payload, txCounter, key, role)
    }

    /** Every write goes through here so [backlogged] knows whether the pipe is draining. */
    private fun write(bytes: ByteArray, onDone: (() -> Unit)? = null) {
        stats.framesSent++
        val epoch = linkEpoch
        inFlight++
        transport.send(bytes) {
            scope.launch {
                if (epoch == linkEpoch) inFlight--
                onDone?.invoke()
            }
        }
    }

    private val backlogged: Boolean get() = inFlight > 0

    private fun resetFlowControl() {
        linkEpoch++
        inFlight = 0
        heldTicks = 0
    }

    private fun sendHello(needReply: Boolean, known: Boolean? = null) {
        val p = JSONObject()
        info.forEach { (k, v) -> if (v != null) p.put(k, v) }
        p.put("protocol", PosLinkInfo.PROTOCOL_VERSION)
            .put("role", role.wire)
            .put("deviceId", identity.idHex)
            .put("name", identity.name)
            .put("sessionId", sessionId)
            .put("sdkVersion", PosLinkInfo.SDK_VERSION)
            .put("nonce", Base64.encode(linkNonce))
            .put("needReply", needReply)
        if (known != null) p.put("known", known)
        sendLinkLevel(MsgType.HELLO, 0, p)
    }

    // ---- transport events (any thread → session thread) ----

    override fun onState(state: TransportState) {
        scope.launch { onTransportState(state) }
    }

    override fun onData(data: ByteArray) {
        scope.launch { onBytes(data) }
    }

    override fun onLog(message: String) = log("[${transport.name}] $message")

    private fun onTransportState(s: TransportState) {
        if (!started) return
        _transportState.value = s
        if (s.isConnected) {
            if (!connected) {
                connected = true
                resetFlowControl()
                decoder.reset()
                lastRx = clock()
                resetRequested = false
                linkNonce = Crypto.randomBytes(Crypto.NONCE_LENGTH)
                peerHello = null
                sessionKey = null
                peerAuthed = false
                stale = false
                peerForgotUs = false
                peerReplied = false
                authFailures = 0
                updateStatus()
                sendHello(needReply = true)
            }
        } else if (connected) {
            connected = false
            log("Link down (${s.detail ?: s.status})")
            resetFlowControl()
            abortPairing("The link dropped")
            peerHello = null
            sessionKey = null
            peerAuthed = false
            failAllPending()
            updateStatus()
        }
    }

    private fun onBytes(data: ByteArray) {
        if (!started) return
        if (!connected && _transportState.value.isConnected) onTransportState(_transportState.value)
        lastRx = clock()
        resetRequested = false
        val before = decoder.crcErrors
        for (frame in decoder.add(data)) onFrame(frame)
        if (decoder.crcErrors != before) log("Dropped ${decoder.crcErrors - before} corrupted frame(s)")
    }

    private fun onFrame(f: Frame) {
        stats.framesReceived++
        if (f.version == 1) {
            setProblem("The other device runs POS Link protocol v1. Update its app.")
            return
        }
        val type = MsgType.fromCode(f.type)
        if (type == null) {
            log("Unknown message type 0x${f.type.toString(16)}")
            if (acceptAuthenticated(f)) sendLinkLevel(MsgType.NAK, f.seq, JSONObject().put("code", ErrorCodes.UNKNOWN_TYPE))
            return
        }
        if (type.authenticated) {
            val wasAuthed = peerAuthed
            if (!acceptAuthenticated(f)) return
            // Answer the peer's first AUTH once: if our own AUTH reached it before it had the
            // key (e.g. right after pairing), this one proves the key without waiting for a PING.
            if (type == MsgType.AUTH && !wasAuthed) sendLinkLevel(MsgType.AUTH, 0)
        }
        when (type) {
            MsgType.HELLO -> decodePayload(f.payload)?.let { onHello(it) }
            MsgType.AUTH, MsgType.PONG -> Unit
            MsgType.PING -> if (backlogged) stats.heldHeartbeats++ else sendLinkLevel(MsgType.PONG, f.seq)
            MsgType.ACK -> pending.remove(f.seq)?.let { it.timer?.cancel(); it.result.complete(true) }
            MsgType.NAK -> pending.remove(f.seq)?.let {
                log("NAK for #${f.seq}: ${decodePayload(f.payload)}")
                it.timer?.cancel()
                it.result.complete(false)
            }
            MsgType.PAIR_REQUEST, MsgType.PAIR_KEY, MsgType.PAIR_REVEAL, MsgType.PAIR_CONFIRM, MsgType.PAIR_FAIL ->
                decodePayload(f.payload)?.let { onPairFrame(type, it) }
            else -> onAppFrame(type, f)
        }
    }

    /** Checks mac and counter; the first valid frame proves the peer has the key. */
    private fun acceptAuthenticated(f: Frame): Boolean {
        val key = sessionKey
        if (key == null) {
            stats.rejectedFrames++
            return false
        }
        if (!f.verify(key, role.peer) || f.counter <= rxCounter) {
            stats.rejectedFrames++
            if (++authFailures >= config.maxAuthFailures) {
                authFailures = 0
                setProblem("Messages from the other device failed the security check. Pair the devices again if this continues.")
                log("Security check failed repeatedly; resetting ${transport.name}")
                transport.reset()
            }
            return false
        }
        authFailures = 0
        rxCounter = f.counter
        if (stale) {
            stale = false
            log("Peer is answering again")
            updateStatus()
        }
        if (!peerAuthed) {
            peerAuthed = true
            if (_problem.value?.startsWith("Messages from the other device") == true) setProblem(null)
            log("Authenticated ${peerHello?.name ?: "peer"}")
            updateStatus()
            listener?.onOnline()
        }
        return true
    }

    private fun onHello(p: JSONObject) {
        val peer = PeerInfo(p)
        val protocol = p.long("protocol")
        if (protocol != PosLinkInfo.PROTOCOL_VERSION.toLong()) {
            setProblem("The other device speaks protocol v$protocol, this one v${PosLinkInfo.PROTOCOL_VERSION}. Update both apps.")
            return
        }
        if (peer.role == null || peer.role == role) {
            setProblem("Both devices are set to \"${role.wire}\". Change one of them.")
            return
        }
        if (peer.deviceId == null || peer.nonce == null) {
            setProblem("The other device sent an invalid HELLO")
            return
        }
        if (_problem.value != null && !peerForgotUs) setProblem(null)
        if (lastPeerSessionId != peer.sessionId) {
            lastPeerSessionId = peer.sessionId
            recentSeqs.clear()
            recentSet.clear()
            log("Peer: ${peer.description} \"${peer.name}\", ${peer.role.wire}, app ${peer.appVersion}")
        }
        val nonceChanged = peerHello?.nonce?.contentEquals(peer.nonce) != true
        peerHello = peer
        _peer.value = peer
        val needReply = p.optBoolean("needReply")
        if (needReply) {
            if (backlogged) stats.heldHeartbeats++ else sendHello(needReply = false, known = pairings.get(peer.deviceId) != null)
        } else if (p.has("known")) {
            peerReplied = true
            val known = p.optBoolean("known")
            if (!known && pairings.get(peer.deviceId) != null && !peerForgotUs) {
                peerForgotUs = true
                sessionKey = null
                peerAuthed = false
                setProblem("The ${peer.role.wire} no longer knows this device (its app may have been reinstalled). Pair again.")
            } else if (known && peerForgotUs) {
                peerForgotUs = false
                setProblem(null)
                authenticate()
            }
        }
        if (nonceChanged || (sessionKey == null && !peerForgotUs)) authenticate()
        updateStatus()
    }

    /** Derives the session key for the current link nonces and proves it with AUTH. */
    private fun authenticate() {
        val peer = peerHello ?: return
        val peerId = peer.deviceIdBytes ?: return
        val peerNonce = peer.nonce ?: return
        val pairing = pairings.get(peer.deviceId!!)
        if (pairing == null || peerForgotUs) {
            sessionKey = null
            peerAuthed = false
            updateStatus()
            return
        }
        val cashier = role == Role.CASHIER
        sessionKey = PairingCrypto.sessionKey(
            key = pairing.key,
            nonceCashier = if (cashier) linkNonce else peerNonce,
            nonceTerminal = if (cashier) peerNonce else linkNonce,
            idCashier = if (cashier) identity.deviceId else peerId,
            idTerminal = if (cashier) peerId else identity.deviceId,
        )
        txCounter = 0
        rxCounter = 0
        peerAuthed = false
        stale = false
        authFailures = 0
        sendLinkLevel(MsgType.AUTH, 0)
        updateStatus()
    }

    private fun onAppFrame(type: MsgType, f: Frame) {
        val payload = decodePayload(f.payload)
        if (payload == null) {
            log("Bad JSON in $type #${f.seq}")
            sendLinkLevel(MsgType.NAK, f.seq, JSONObject().put("code", ErrorCodes.BAD_PAYLOAD))
            return
        }
        // Always ACK, even duplicates: the earlier ACK may have been lost.
        sendLinkLevel(MsgType.ACK, f.seq)
        if (f.seq in recentSet) {
            stats.duplicates++
            log("Duplicate $type #${f.seq} ignored")
            return
        }
        recentSeqs.addLast(f.seq)
        recentSet.add(f.seq)
        while (recentSeqs.size > 256) recentSet.remove(recentSeqs.removeFirst())
        log("← $type #${f.seq} $payload")
        listener?.onMessage(Message(type, payload))
    }

    private fun tick() {
        if (!connected) return
        if (backlogged) {
            stats.heldHeartbeats++
            if (++heldTicks == 2) log("The other device is not reading the link; holding heartbeats")
        } else {
            heldTicks = 0
            when (_status.value) {
                LinkStatus.HANDSHAKING -> sendHello(needReply = true)
                LinkStatus.AUTHENTICATING -> {
                    // The reply to our HELLO says whether the peer still knows us; it can be
                    // skipped under back-pressure, so ask again until it arrives.
                    if (!peerReplied) sendHello(needReply = true)
                    sendLinkLevel(MsgType.AUTH, 0)
                }
                LinkStatus.ONLINE, LinkStatus.NOT_RESPONDING -> sendLinkLevel(MsgType.PING, 0)
                else -> Unit
            }
        }
        val silence = clock() - lastRx
        if (_status.value == LinkStatus.ONLINE && silence > config.staleAfterMs) {
            log("No answer from the other device for ${silence / 1000}s")
            stale = true
            updateStatus()
        }
        val watched = _status.value in setOf(
            LinkStatus.HANDSHAKING, LinkStatus.AUTHENTICATING, LinkStatus.ONLINE, LinkStatus.NOT_RESPONDING,
        )
        if (watched && silence > config.staleAfterMs * 2 && !resetRequested) {
            resetRequested = true
            log("Resetting ${transport.name}")
            transport.reset()
        }
    }

    // ---- pairing ----

    /**
     * Cashier: pairs with the terminal on this link. Opens pairing on the terminal first (the
     * merchant does that on the terminal). [confirm] gets the 6-digit code; return true once the
     * person has checked it against the terminal's screen (asking them to type it is best).
     * [confirm] runs on the session's thread and is cancelled if the terminal gives up.
     */
    suspend fun pair(confirm: suspend (code: String) -> Boolean): PairingResult = withContext(dispatcher) {
        check(role == Role.CASHIER) { "only the cashier starts a pairing" }
        val peer = peerHello
        if (!connected || peer == null) return@withContext PairingResult.Failed(PairFailure.NOT_CONNECTED, "Not connected to a terminal")
        if (cashierRun != null) return@withContext PairingResult.Failed(PairFailure.BUSY, "A pairing is already running")
        val run = CashierPairing()
        cashierRun = run
        updateStatus()
        val result = try {
            withTimeout(config.pairingTimeoutMs) { runCashierPairing(run, peer, confirm) }
        } catch (e: TimeoutCancellationException) {
            sendPairFail(PairFailure.TIMEOUT)
            PairingResult.Failed(PairFailure.TIMEOUT, "Pairing timed out")
        } finally {
            if (cashierRun === run) cashierRun = null
            updateStatus()
        }
        log("Pairing: $result")
        listener?.onPairingFinished(result)
        result
    }

    private suspend fun runCashierPairing(run: CashierPairing, peer: PeerInfo, confirm: suspend (String) -> Boolean): PairingResult {
        sendLinkLevel(
            MsgType.PAIR_REQUEST, 0,
            JSONObject().put("deviceId", identity.idHex).put("name", identity.name)
                .put("commit", Base64.encode(PairingCrypto.commit(run.keys.publicKey, run.nonce))),
        )
        val keyMsg = awaitOrFail(run, run.keyMessage) ?: return run.failure.await()
        val pkT = Base64.decode(keyMsg.str("publicKey"))
        val nT = Base64.decode(keyMsg.str("nonce"))?.takeIf { it.size == Crypto.NONCE_LENGTH }
        val idT = peer.deviceIdBytes
        if (pkT == null || nT == null || idT == null || keyMsg.str("deviceId")?.lowercase() != peer.deviceId) {
            return failPairing(PairFailure.BAD_KEY, "The terminal sent an invalid key")
        }
        val shared = try {
            run.keys.sharedSecret(pkT)
        } catch (e: IllegalArgumentException) {
            return failPairing(PairFailure.BAD_KEY, "The terminal sent an invalid key")
        }
        sendLinkLevel(
            MsgType.PAIR_REVEAL, 0,
            JSONObject().put("publicKey", Base64.encode(run.keys.publicKey)).put("nonce", Base64.encode(run.nonce)),
        )
        val th = PairingCrypto.transcriptHash(identity.deviceId, idT, run.keys.publicKey, pkT, run.nonce, nT)
        val key = PairingCrypto.pairingKey(shared, th)
        val code = PairingCrypto.code(shared, th)

        // Wait for the person, but stop asking if the terminal gives up meanwhile.
        val confirmed: Boolean? = coroutineScope {
            val user = async { confirm(code) }
            select<Boolean?> {
                user.onAwait { it }
                run.failure.onAwait {
                    user.cancel()
                    null
                }
            }
        }
        if (confirmed == null) return run.failure.await()
        if (!confirmed) return failPairing(PairFailure.REJECTED, "Pairing cancelled on the cashier")
        sendLinkLevel(MsgType.PAIR_CONFIRM, 0, JSONObject().put("mac", Base64.encode(PairingCrypto.confirmMac(key, Role.CASHIER.byte, th))))
        val peerMac = awaitOrFail(run, run.peerConfirm) ?: return run.failure.await()
        if (!Crypto.constantTimeEquals(peerMac, PairingCrypto.confirmMac(key, Role.TERMINAL.byte, th))) {
            return failPairing(PairFailure.BAD_CONFIRM, "The codes didn't match")
        }
        val name = keyMsg.str("name") ?: peer.name
        finishPairing(peer.deviceId!!, name, key)
        return PairingResult.Paired(peer.deviceId, name)
    }

    private suspend fun <T> awaitOrFail(run: CashierPairing, value: Deferred<T>): T? = select<T?> {
        value.onAwait { it }
        run.failure.onAwait { null }
    }

    private fun failPairing(reason: PairFailure, message: String): PairingResult.Failed {
        sendPairFail(reason)
        return PairingResult.Failed(reason, message)
    }

    /** Terminal: accept pairing requests for [windowMs] (the merchant tapped "Pair a cashier"). */
    fun openPairing(windowMs: Long = config.pairingTimeoutMs) {
        scope.launch {
            pairingWindowUntil = clock() + windowMs
            log("Pairing open for ${windowMs / 1000}s")
        }
    }

    fun closePairing() {
        scope.launch {
            pairingWindowUntil = 0
            terminalRun?.let {
                sendPairFail(PairFailure.REJECTED)
                endTerminalPairing(it, PairingResult.Failed(PairFailure.REJECTED, "Pairing closed on the terminal"))
            }
        }
    }

    private fun onPairFrame(type: MsgType, p: JSONObject) {
        when (role) {
            Role.CASHIER -> {
                val run = cashierRun ?: return
                when (type) {
                    MsgType.PAIR_KEY -> run.keyMessage.complete(p)
                    MsgType.PAIR_CONFIRM -> Base64.decode(p.str("mac"))?.let { run.peerConfirm.complete(it) }
                    MsgType.PAIR_FAIL -> run.failure.complete(peerFailure(p, "terminal"))
                    else -> Unit
                }
            }
            Role.TERMINAL -> when (type) {
                MsgType.PAIR_REQUEST -> onPairRequest(p)
                MsgType.PAIR_REVEAL -> onPairReveal(p)
                MsgType.PAIR_CONFIRM -> onPairConfirm(p)
                MsgType.PAIR_FAIL -> terminalRun?.let { endTerminalPairing(it, peerFailure(p, "cashier")) }
                else -> Unit
            }
        }
    }

    private fun peerFailure(p: JSONObject, who: String): PairingResult.Failed {
        val reason = PairFailure.fromWire(p.str("code"))
        val message = when (reason) {
            PairFailure.NOT_PAIRING -> "Pairing is not open on the terminal. Tap \"Pair a cashier\" there first."
            PairFailure.BUSY -> "The terminal is already pairing with another cashier"
            PairFailure.REJECTED -> "Pairing was rejected on the $who"
            PairFailure.TIMEOUT -> "Pairing timed out"
            else -> "Pairing failed on the $who ($reason)"
        }
        return PairingResult.Failed(reason, message)
    }

    private fun onPairRequest(p: JSONObject) {
        val peer = peerHello ?: return
        if (clock() > pairingWindowUntil) return sendPairFail(PairFailure.NOT_PAIRING)
        if (terminalRun != null) return sendPairFail(PairFailure.BUSY)
        val commit = Base64.decode(p.str("commit"))?.takeIf { it.size == 32 }
        val cashierId = peer.deviceIdBytes
        if (commit == null || cashierId == null || p.str("deviceId")?.lowercase() != peer.deviceId) {
            return sendPairFail(PairFailure.BAD_KEY)
        }
        val run = TerminalPairing(peer.deviceId!!, cashierId, p.str("name") ?: peer.name, commit)
        terminalRun = run
        run.timeout = scope.launch {
            delay(config.pairingTimeoutMs)
            if (terminalRun === run) {
                sendPairFail(PairFailure.TIMEOUT)
                endTerminalPairing(run, PairingResult.Failed(PairFailure.TIMEOUT, "Pairing timed out"))
            }
        }
        updateStatus()
        sendLinkLevel(
            MsgType.PAIR_KEY, 0,
            JSONObject().put("deviceId", identity.idHex).put("name", identity.name)
                .put("publicKey", Base64.encode(run.keys.publicKey)).put("nonce", Base64.encode(run.nonce)),
        )
    }

    private fun onPairReveal(p: JSONObject) {
        val run = terminalRun ?: return
        if (run.key != null) return
        val pkC = Base64.decode(p.str("publicKey"))
        val nC = Base64.decode(p.str("nonce"))?.takeIf { it.size == Crypto.NONCE_LENGTH }
        if (pkC == null || nC == null || !Crypto.constantTimeEquals(PairingCrypto.commit(pkC, nC), run.commit)) {
            sendPairFail(PairFailure.BAD_COMMIT)
            return endTerminalPairing(run, PairingResult.Failed(PairFailure.BAD_COMMIT, "The cashier's key didn't match its commitment"))
        }
        val shared = try {
            run.keys.sharedSecret(pkC)
        } catch (e: IllegalArgumentException) {
            sendPairFail(PairFailure.BAD_KEY)
            return endTerminalPairing(run, PairingResult.Failed(PairFailure.BAD_KEY, "The cashier sent an invalid key"))
        }
        val th = PairingCrypto.transcriptHash(run.cashierIdBytes, identity.deviceId, pkC, run.keys.publicKey, nC, run.nonce)
        run.transcript = th
        run.key = PairingCrypto.pairingKey(shared, th)
        val request = PairingRequest(run.cashierName, run.cashierId, PairingCrypto.code(shared, th)) { accepted ->
            scope.launch { onLocalAnswer(run, accepted) }
        }
        listener?.onPairingRequest(request)
    }

    private fun onLocalAnswer(run: TerminalPairing, accepted: Boolean) {
        if (terminalRun !== run || run.localConfirmed) return
        if (!accepted) {
            sendPairFail(PairFailure.REJECTED)
            return endTerminalPairing(run, PairingResult.Failed(PairFailure.REJECTED, "Pairing rejected on the terminal"))
        }
        run.localConfirmed = true
        val key = run.key ?: return
        sendLinkLevel(MsgType.PAIR_CONFIRM, 0, JSONObject().put("mac", Base64.encode(PairingCrypto.confirmMac(key, Role.TERMINAL.byte, run.transcript!!))))
        if (run.peerConfirmed) completeTerminalPairing(run)
    }

    private fun onPairConfirm(p: JSONObject) {
        val run = terminalRun ?: return
        val key = run.key ?: return
        val mac = Base64.decode(p.str("mac"))
        if (mac == null || !Crypto.constantTimeEquals(mac, PairingCrypto.confirmMac(key, Role.CASHIER.byte, run.transcript!!))) {
            sendPairFail(PairFailure.BAD_CONFIRM)
            return endTerminalPairing(run, PairingResult.Failed(PairFailure.BAD_CONFIRM, "The codes didn't match"))
        }
        run.peerConfirmed = true
        if (run.localConfirmed) completeTerminalPairing(run)
    }

    private fun completeTerminalPairing(run: TerminalPairing) {
        pairingWindowUntil = 0
        terminalRun = null
        run.timeout?.cancel()
        finishPairing(run.cashierId, run.cashierName, run.key!!)
        val result = PairingResult.Paired(run.cashierId, run.cashierName)
        log("Pairing: $result")
        listener?.onPairingFinished(result)
    }

    private fun endTerminalPairing(run: TerminalPairing, result: PairingResult) {
        if (terminalRun !== run) return
        run.timeout?.cancel()
        terminalRun = null
        updateStatus()
        log("Pairing: $result")
        listener?.onPairingFinished(result)
    }

    private fun finishPairing(peerDeviceId: String, peerName: String, key: ByteArray) {
        pairings.put(Pairing(peerDeviceId, peerName, key, System.currentTimeMillis()))
        peerForgotUs = false
        setProblem(null)
        authenticate()
    }

    private fun abortPairing(reason: String) {
        cashierRun?.failure?.complete(PairingResult.Failed(PairFailure.NOT_CONNECTED, reason))
        terminalRun?.let { endTerminalPairing(it, PairingResult.Failed(PairFailure.NOT_CONNECTED, reason)) }
    }

    private fun sendPairFail(reason: PairFailure) {
        if (reason != PairFailure.NOT_CONNECTED) sendLinkLevel(MsgType.PAIR_FAIL, 0, JSONObject().put("code", reason.name))
    }

    /** Forgets a paired peer. If it is on this link now, the link becomes unpaired. */
    fun forget(peerDeviceId: String) {
        scope.launch {
            pairings.remove(peerDeviceId)
            if (peerHello?.deviceId == peerDeviceId) {
                sessionKey = null
                peerAuthed = false
                failAllPending()
                updateStatus()
            }
        }
    }

    // ---- helpers ----

    private fun updateStatus() {
        _status.value = when {
            !connected -> LinkStatus.OFFLINE
            peerHello == null -> LinkStatus.HANDSHAKING
            cashierRun != null || terminalRun != null -> LinkStatus.PAIRING
            sessionKey == null -> LinkStatus.UNPAIRED
            !peerAuthed -> LinkStatus.AUTHENTICATING
            stale -> LinkStatus.NOT_RESPONDING
            else -> LinkStatus.ONLINE
        }
    }

    private fun setProblem(problem: String?) {
        if (_problem.value == problem) return
        _problem.value = problem
        if (problem != null) log(problem)
    }

    private fun allocSeq(): Int {
        nextSeq = nextSeq % 0xFFFF + 1 // 1..65535; 0 is for link-level frames
        return nextSeq
    }

    private fun failAllPending() {
        pending.values.forEach {
            it.timer?.cancel()
            it.result.complete(false)
        }
        pending.clear()
    }

    private fun encodePayload(p: JSONObject): ByteArray =
        if (p.length() == 0) FrameCodec.EMPTY else p.toString().toByteArray(Charsets.UTF_8)

    private fun decodePayload(bytes: ByteArray): JSONObject? {
        if (bytes.isEmpty()) return JSONObject()
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            null
        }
    }

    private class PendingSend(val type: MsgType, val seq: Int, val payload: ByteArray, val result: CompletableDeferred<Boolean>) {
        var attempts = 0
        var writing = false
        var timer: Job? = null
    }

    private class CashierPairing {
        val keys = P256KeyPair.generate()
        val nonce = Crypto.randomBytes(Crypto.NONCE_LENGTH)
        val keyMessage = CompletableDeferred<JSONObject>()
        val peerConfirm = CompletableDeferred<ByteArray>()
        val failure = CompletableDeferred<PairingResult.Failed>()
    }

    private class TerminalPairing(val cashierId: String, val cashierIdBytes: ByteArray, val cashierName: String, val commit: ByteArray) {
        val keys = P256KeyPair.generate()
        val nonce = Crypto.randomBytes(Crypto.NONCE_LENGTH)
        var transcript: ByteArray? = null
        var key: ByteArray? = null
        var localConfirmed = false
        var peerConfirmed = false
        var timeout: Job? = null
    }
}
