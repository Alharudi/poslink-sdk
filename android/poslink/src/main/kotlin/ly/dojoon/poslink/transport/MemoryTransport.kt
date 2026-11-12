package ly.dojoon.poslink.transport

/**
 * Two transports wired to each other in memory, for tests (yours too): run a cashier and a
 * terminal in one process, with frame loss, small chunks, a stalled reader, a pulled cable, or a
 * device in the middle that tampers with or injects bytes.
 * Delivery is synchronous; the link sessions hand everything to their own thread anyway.
 */
class MemoryTransportPair {
    val a = MemoryTransport("A")
    val b = MemoryTransport("B")

    init {
        a.peer = b
        b.peer = a
    }

    /** Simulates pulling the cable. */
    fun disconnect() {
        a.setConnected(false)
        b.setConnected(false)
    }

    /** Simulates plugging it back in. */
    fun reconnect() {
        if (a.running && b.running) {
            a.setConnected(true)
            b.setConnected(true)
        }
    }
}

class MemoryTransport internal constructor(private val label: String) : Transport {
    internal lateinit var peer: MemoryTransport
    internal var running = false
    private var connected = false
    private var listener: Transport.Listener? = null

    /** Drop the next N outgoing sends (they report success, like a lost packet). */
    @Volatile var dropNext = 0

    /** Deliver each send in pieces of at most this many bytes. Null delivers each send whole. */
    @Volatile var chunkSize: Int? = null

    /** Called with each outgoing send; return different bytes to tamper with them, or null to drop. */
    @Volatile var interceptor: ((ByteArray) -> ByteArray?)? = null

    /** Every send, as written (for replay tests). */
    val sent = mutableListOf<ByteArray>()

    private var held: MutableList<Pair<ByteArray, (Boolean) -> Unit>>? = null

    override val name: String get() = "memory $label"

    @Synchronized override fun start(listener: Transport.Listener) {
        this.listener = listener
        running = true
        listener.onState(TransportState(TransportStatus.WAITING))
        if (peer.running) {
            setConnected(true)
            peer.setConnected(true)
        }
    }

    @Synchronized override fun stop() {
        running = false
        setConnected(false)
        peer.setConnected(false)
        listener?.onState(TransportState.STOPPED)
    }

    override fun reset() = Unit

    /** Like a USB accessory whose host stopped reading: sends neither arrive nor complete until [unstall]. */
    @Synchronized fun stall() {
        if (held == null) held = mutableListOf()
    }

    val heldFrames: Int @Synchronized get() = held?.size ?: 0

    fun unstall() {
        val frames = synchronized(this) { held.also { held = null } } ?: return
        for ((data, done) in frames) {
            if (connected && peer.connected) deliver(data)
            done(connected)
        }
    }

    /** Puts raw bytes on the wire towards the peer, as a device in the middle would. */
    fun inject(data: ByteArray) {
        if (connected && peer.connected) peer.listener?.onData(data)
    }

    override fun send(data: ByteArray, done: (Boolean) -> Unit) {
        val copy = data.copyOf()
        synchronized(this) {
            if (!connected) return done(false)
            sent += copy
            if (dropNext > 0) {
                dropNext--
                return done(true)
            }
            held?.let {
                it += copy to done
                return
            }
        }
        val intercept = interceptor
        val out = if (intercept == null) copy else intercept(copy)
        if (out != null) deliver(out)
        done(true)
    }

    private fun deliver(data: ByteArray) {
        if (!connected || !peer.connected) return
        val size = chunkSize ?: data.size
        var i = 0
        while (i < data.size) {
            val end = minOf(data.size, i + size)
            peer.listener?.onData(data.copyOfRange(i, end))
            i = end
        }
    }

    internal fun setConnected(value: Boolean) {
        val toFail: List<(Boolean) -> Unit>
        synchronized(this) {
            if (connected == value) return
            connected = value
            toFail = if (!value) held?.map { it.second }.orEmpty().also { held?.clear() } else emptyList()
        }
        toFail.forEach { it(false) }
        if (running) listener?.onState(TransportState(if (value) TransportStatus.CONNECTED else TransportStatus.WAITING))
    }
}
