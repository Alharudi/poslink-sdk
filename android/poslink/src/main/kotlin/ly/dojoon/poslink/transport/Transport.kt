package ly.dojoon.poslink.transport

enum class TransportStatus { STOPPED, WAITING, PERMISSION, CONNECTING, CONNECTED, ERROR }

data class TransportState(val status: TransportStatus, val detail: String? = null) {
    val isConnected: Boolean get() = status == TransportStatus.CONNECTED

    override fun toString() = if (detail == null) status.name else "${status.name}: $detail"

    companion object {
        val STOPPED = TransportState(TransportStatus.STOPPED)
    }
}

/**
 * A raw, ordered byte pipe to the other device: USB, Bluetooth, TCP or in-memory.
 * Framing, security, acknowledgements and heartbeats live above it, in the link session.
 * A transport may split or merge the bytes it carries.
 */
interface Transport {
    /** For logs and UI, e.g. "USB (accessory)". */
    val name: String

    /** Starts connecting or listening. Events may arrive on any thread. */
    fun start(listener: Listener)

    fun stop()

    /**
     * Hands [data] to the link without blocking the caller. [done] is called once, on any thread:
     * true when the bytes were written, false when the link is down or the write queue is full.
     */
    fun send(data: ByteArray, done: (Boolean) -> Unit)

    /** Drops the current connection and connects again. */
    fun reset()

    interface Listener {
        fun onState(state: TransportState)
        fun onData(data: ByteArray)
        fun onLog(message: String)
    }
}
