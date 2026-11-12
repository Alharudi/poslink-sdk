package ly.dojoon.poslink.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base for transports whose writes block: USB, Bluetooth and TCP.
 *
 * A write blocks while the other device isn't reading (a USB accessory driver holds only a few
 * packets; a Bluetooth or TCP socket blocks once its send window is full). So [send] never writes
 * on the caller's thread: it queues the bytes for a writer thread, which calls [write] one frame
 * at a time. The queue is bounded: past [MAX_QUEUED] a send is refused at once instead of piling
 * up stale frames.
 *
 * Each connection gets its own writer. The subclass calls [openWriter] when a connection is up
 * (before reporting [TransportStatus.CONNECTED]) and [closeWriter] when it drops or on stop().
 * Closing a writer answers every queued send with false, and a write stuck on a dead connection
 * can't delay the next one. Closing never interrupts the writer thread: interrupting a thread
 * blocked on a FileChannel closes that channel on the interrupting thread and waits there, which
 * is the main-thread stall the USB accessory avoids. Subclasses end a blocked write by closing
 * their socket or channel instead.
 *
 * Also holds the listener and reports states without repeating the same state twice in a row.
 */
abstract class BlockingTransport : Transport {

    private val writerLock = Any()
    private var writer: Writer? = null

    private val stateLock = Any()
    @Volatile private var listener: Transport.Listener? = null
    private var lastState: TransportState? = null

    /**
     * Writes all of [data] to the current connection, blocking as long as needed. Called on the
     * writer thread, one call at a time per connection. Returns false if the bytes were not sent.
     */
    protected abstract fun write(data: ByteArray): Boolean

    /** [done] may run before this returns (when no connection is up or the queue is full). */
    final override fun send(data: ByteArray, done: (Boolean) -> Unit) {
        val w = synchronized(writerLock) { writer }
        if (w == null) done(false) else w.submit(data, done)
    }

    /**
     * Starts a fresh writer for a new connection and closes the previous one, if any.
     * Returns a ticket for [closeWriter].
     */
    protected fun openWriter(): Any {
        val w = Writer()
        val old = synchronized(writerLock) {
            val previous = writer
            writer = w
            previous
        }
        old?.close()
        return w
    }

    /**
     * Closes the current writer: queued sends are answered with false, and later sends fail at
     * once until [openWriter]. With a [ticket], closes only if that writer is still the current
     * one, so the thread of an old connection can't close the writer of a newer one.
     */
    protected fun closeWriter(ticket: Any? = null) {
        val old = synchronized(writerLock) {
            val current = writer ?: return
            if (ticket != null && ticket !== current) return
            writer = null
            current
        }
        old.close()
    }

    /** Call at the top of start(). The next [state] is always delivered to the new listener. */
    protected fun attachListener(listener: Transport.Listener) {
        synchronized(stateLock) {
            this.listener = listener
            lastState = null
        }
    }

    /** Reports a state change (skipped if it equals the last one); a detail is also logged. */
    protected fun state(status: TransportStatus, detail: String? = null) {
        val s = TransportState(status, detail)
        synchronized(stateLock) {
            if (s == lastState) return
            lastState = s
            // Delivered under the lock so states from different threads arrive in order.
            listener?.onState(s)
        }
        if (detail != null) log(detail)
    }

    protected fun log(message: String) {
        listener?.onLog(message)
    }

    protected fun emit(data: ByteArray) {
        listener?.onData(data)
    }

    private inner class Writer {
        private val closed = AtomicBoolean(false)
        private val queued = AtomicInteger()
        private val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ) { r -> Thread(r, "poslink-writer").apply { isDaemon = true } }

        fun submit(data: ByteArray, done: (Boolean) -> Unit) {
            if (queued.incrementAndGet() > MAX_QUEUED) {
                queued.decrementAndGet()
                done(false)
                return
            }
            val task = Task(data, done)
            try {
                executor.execute(task)
            } catch (e: RejectedExecutionException) {
                task.answer(false)
            }
        }

        /** Answers every queued send with false. A write in progress finishes on its own. */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            executor.shutdown()
            val pending = ArrayList<Runnable>()
            executor.queue.drainTo(pending)
            for (r in pending) (r as? Task)?.answer(false)
        }

        private inner class Task(
            private val data: ByteArray,
            private val done: (Boolean) -> Unit,
        ) : Runnable {
            private val answered = AtomicBoolean(false)

            override fun run() {
                // A task taken from the queue just before close() must not reach the next connection.
                val ok = !closed.get() && runCatching { write(data) }.getOrDefault(false)
                answer(ok)
            }

            fun answer(ok: Boolean) {
                if (!answered.compareAndSet(false, true)) return
                queued.decrementAndGet()
                try {
                    done(ok)
                } catch (e: Exception) {
                    log("Send callback failed: $e")
                }
            }
        }
    }

    companion object {
        /** Sends waiting or in progress per connection; more are refused with false. */
        const val MAX_QUEUED = 32
    }
}
