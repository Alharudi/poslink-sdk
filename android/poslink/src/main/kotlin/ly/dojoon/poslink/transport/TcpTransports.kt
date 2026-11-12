package ly.dojoon.poslink.transport

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/*
 * Wi-Fi / LAN link, also handy for testing on two ordinary phones. The terminal listens
 * ([TcpServerTransport]); the cashier connects ([TcpClientTransport]). Plain java.net, so these
 * also run in JVM tests. On Android the app must declare android.permission.INTERNET.
 */

private const val READ_BUFFER = 8192
private const val CONNECT_TIMEOUT_MS = 5000
private const val RETRY_MS = 2000L

private fun closeQuietly(c: Closeable?) {
    runCatching { c?.close() }
}

private fun Throwable.text(): String = message ?: javaClass.simpleName

/** Cashier side: connects to the terminal and reconnects every 2 s after a failure or a drop. */
class TcpClientTransport(val host: String, val port: Int) : BlockingTransport() {

    init {
        require(port in 1..65535) { "port must be 1..65535, was $port" }
    }

    override val name: String get() = "Wi-Fi ($host:$port)"

    private val lock = Any()
    private val writeLock = Any()

    /** Released by stop() and reset() to cut the wait before the next attempt short. */
    private val wakeups = Semaphore(0)

    @Volatile private var running = false

    /** Bumped by stop(), so the thread of an old run ends. */
    @Volatile private var generation = 0

    private var socket: Socket? = null // guarded by lock
    @Volatile private var output: OutputStream? = null

    override fun start(listener: Transport.Listener) {
        attachListener(listener)
        val gen = synchronized(lock) {
            if (running) return
            running = true
            ++generation
        }
        Thread({ linkLoop(gen) }, "poslink-tcp-client").apply { isDaemon = true }.start()
    }

    override fun stop() {
        val s = synchronized(lock) {
            running = false
            generation++
            val current = socket
            socket = null
            output = null
            current
        }
        wakeups.release()
        closeQuietly(s) // aborts connect(), ends the reader and a blocked write
        closeWriter()
        state(TransportStatus.STOPPED)
    }

    /** Drops the connection; the next attempt starts at once instead of after 2 s. */
    override fun reset() {
        val s = synchronized(lock) { socket }
        if (s != null) {
            log("Reset: reconnecting to the terminal")
            closeQuietly(s)
        }
        wakeups.release()
    }

    override fun write(data: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
            true
        } catch (e: IOException) {
            val s = synchronized(lock) { if (output === out) socket else null }
            if (s != null) {
                log("Send failed: ${e.text()}")
                closeQuietly(s) // the link thread notices and reconnects
            }
            false
        }
    }

    private fun active(gen: Int) = running && generation == gen

    private fun linkLoop(gen: Int) {
        while (active(gen)) {
            wakeups.drainPermits()
            state(TransportStatus.CONNECTING, "Connecting to $host:$port…")
            val s = Socket()
            synchronized(lock) {
                if (!active(gen)) {
                    closeQuietly(s)
                    return
                }
                socket = s
            }
            try {
                // Exception, not IOException: a DNS lookup without the INTERNET permission throws
                // SecurityException, which would otherwise kill the app from this thread.
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
            } catch (e: Exception) {
                closeQuietly(s)
                if (!release(s, gen)) return
                state(TransportStatus.WAITING, "Terminal not reachable at $host:$port (${e.text()})")
                pause(RETRY_MS)
                continue
            }
            val out = try {
                s.getOutputStream()
            } catch (e: IOException) {
                closeQuietly(s)
                if (!release(s, gen)) return
                pause(RETRY_MS)
                continue
            }
            val ticket = openWriter()
            val attached = synchronized(lock) {
                if (active(gen) && socket === s) {
                    output = out
                    true
                } else {
                    false
                }
            }
            if (!attached) {
                closeWriter(ticket)
                closeQuietly(s)
                return
            }
            state(TransportStatus.CONNECTED, "Connected to ${s.inetAddress?.hostAddress ?: host}")
            read(s)
            closeWriter(ticket)
            closeQuietly(s)
            if (!release(s, gen)) return
            state(TransportStatus.WAITING, "Connection lost")
            pause(RETRY_MS)
        }
    }

    /** Forgets [s] if it is still the current socket. Returns whether this run is still active. */
    private fun release(s: Socket, gen: Int): Boolean = synchronized(lock) {
        if (socket === s) {
            socket = null
            output = null
        }
        active(gen)
    }

    /** Returns when the connection closes. */
    private fun read(s: Socket) {
        val buf = ByteArray(READ_BUFFER)
        try {
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) emit(buf.copyOf(n))
            }
        } catch (e: IOException) {
            if (synchronized(lock) { socket === s }) log("Socket error: ${e.text()}")
        }
    }

    private fun pause(ms: Long) {
        try {
            wakeups.tryAcquire(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // nothing interrupts this thread; the loop condition ends it after stop()
        }
    }
}

/**
 * Terminal side: listens on [port] (all interfaces). A new cashier connection replaces the old
 * one. If listening fails (e.g. the port is taken), the state is ERROR and it tries again every
 * 2 s, like the Bluetooth server.
 */
class TcpServerTransport(val port: Int) : BlockingTransport() {

    init {
        require(port in 0..65535) { "port must be 0..65535, was $port" }
    }

    override val name: String get() = "Wi-Fi (listening on :$port)"

    private val lock = Any()
    private val writeLock = Any()

    @Volatile private var running = false

    // All guarded by lock.
    /** Bumped by start() and stop(), so the listening thread of an old run ends. */
    private var generation = 0
    private var listening = false
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var writerTicket: Any? = null

    @Volatile private var output: OutputStream? = null

    override fun start(listener: Transport.Listener) {
        attachListener(listener)
        synchronized(lock) {
            if (running) return
            running = true
            generation++
        }
        listen()
    }

    override fun stop() {
        val (ss, s) = synchronized(lock) {
            running = false
            generation++
            listening = false
            val pair = serverSocket to socket
            serverSocket = null
            socket = null
            output = null
            writerTicket = null
            pair
        }
        closeQuietly(ss) // ends accept()
        closeQuietly(s) // ends the reader and a blocked write
        closeWriter()
        state(TransportStatus.STOPPED)
    }

    /** Drops the current cashier connection. */
    override fun reset() {
        if (!running) return
        val s = synchronized(lock) { socket }
        if (s != null) {
            log("Reset: closing the connection")
            disconnected(s, "Waiting for the cashier to reconnect")
        }
        listen()
    }

    override fun write(data: ByteArray): Boolean {
        val s = synchronized(lock) { socket } ?: return false
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
            true
        } catch (e: IOException) {
            if (synchronized(lock) { socket === s }) {
                log("Send failed: ${e.text()}")
                disconnected(s, "Connection lost")
            }
            false
        }
    }

    /** Starts the listening thread for this run unless it is already running. */
    private fun listen() {
        val gen = synchronized(lock) {
            if (!running || listening) return
            listening = true
            generation
        }
        Thread({
            try {
                listenLoop(gen)
            } finally {
                synchronized(lock) { if (generation == gen) listening = false }
            }
        }, "poslink-tcp-accept").apply { isDaemon = true }.start()
    }

    private fun active(gen: Int) = synchronized(lock) { running && generation == gen }

    /** Listens, accepts, and listens again 2 s after the listening socket fails, until stop(). */
    private fun listenLoop(gen: Int) {
        while (active(gen)) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true // a restart can bind again while old connections linger
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                if (!active(gen)) return
                state(TransportStatus.ERROR, "Cannot listen on port $port: ${e.text()}")
                pause(RETRY_MS)
                continue
            }
            val ok = synchronized(lock) {
                if (running && generation == gen) {
                    serverSocket = ss
                    true
                } else {
                    false
                }
            }
            if (!ok) {
                closeQuietly(ss)
                return
            }
            state(TransportStatus.WAITING, "Waiting for the cashier on port ${ss.localPort}")
            val error = acceptLoop(ss)
            val current = synchronized(lock) {
                if (serverSocket === ss) {
                    serverSocket = null
                    true
                } else {
                    false
                }
            }
            closeQuietly(ss)
            if (!current || !active(gen)) return
            state(TransportStatus.ERROR, "Stopped listening on port $port: ${error.text()}. Retrying…")
            pause(RETRY_MS)
        }
    }

    /** Returns the error that ended accept(), which is how stop() ends it too. */
    private fun acceptLoop(ss: ServerSocket): IOException {
        while (true) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                return e
            }
            attach(ss, s)
        }
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // nothing interrupts this thread; the loop condition ends it after stop()
        }
    }

    private fun attach(ss: ServerSocket, s: Socket) {
        val out = try {
            s.tcpNoDelay = true
            s.getOutputStream()
        } catch (e: IOException) {
            closeQuietly(s)
            return
        }
        val ticket = openWriter()
        var accepted = false
        val old = synchronized(lock) {
            if (serverSocket !== ss) return@synchronized null
            accepted = true
            val previous = socket
            socket = s
            output = out
            writerTicket = ticket
            previous
        }
        if (!accepted) { // stopped meanwhile
            closeWriter(ticket)
            closeQuietly(s)
            return
        }
        if (old != null) {
            log("New cashier connection replaces the old one")
            closeQuietly(old)
            // A down/up pair, so the link session starts a fresh handshake (new nonces, new key).
            state(TransportStatus.WAITING, "Replacing the cashier connection")
        }
        state(TransportStatus.CONNECTED, "Connected to ${s.inetAddress?.hostAddress}")
        Thread({ readLoop(s) }, "poslink-tcp-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(s: Socket) {
        val buf = ByteArray(READ_BUFFER)
        try {
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) emit(buf.copyOf(n))
            }
        } catch (e: IOException) {
            if (synchronized(lock) { socket === s }) log("Socket error: ${e.text()}")
        }
        disconnected(s, "Cashier disconnected")
    }

    private fun disconnected(s: Socket, reason: String) {
        var wasCurrent = false
        val ticket = synchronized(lock) {
            if (socket !== s) return@synchronized null
            wasCurrent = true
            socket = null
            output = null
            writerTicket.also { writerTicket = null }
        }
        closeQuietly(s)
        if (!wasCurrent) return
        if (ticket != null) closeWriter(ticket)
        if (running) state(TransportStatus.WAITING, reason)
    }
}
