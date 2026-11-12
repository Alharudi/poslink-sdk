package ly.dojoon.poslink.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import ly.dojoon.poslink.transport.TransportStatus
import java.io.IOException
import java.io.OutputStream

/**
 * Terminal side over Bluetooth Classic (RFCOMM). Listens with a secure service record, so the
 * link only works with a paired cashier and is encrypted by Bluetooth. A new connection replaces
 * the old one, like the TCP server.
 */
@SuppressLint("MissingPermission")
class BluetoothServerTransport(
    context: Context,
) : BluetoothTransport(context, PosLinkBluetooth.serverPermissions()) {

    override val name: String = "Bluetooth (listening)"

    private val lock = Any()
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    // socket, output and writerTicket change together under lock.
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private var writerTicket: Any? = null
    private val writeLock = Any()

    override fun openLink() {
        val a = adapter ?: return
        val ss = try {
            a.listenUsingRfcommWithServiceRecord(PosLinkBluetooth.SERVICE_NAME, PosLinkBluetooth.SERVICE_UUID)
        } catch (e: Exception) {
            state(TransportStatus.ERROR, "Cannot listen for Bluetooth connections: ${e.message}")
            main.post(5000) { if (isOpen && serverSocket == null) openLink() }
            return
        }
        serverSocket = ss
        val name = runCatching { a.name }.getOrNull()
        state(
            TransportStatus.WAITING,
            "Waiting for the cashier over Bluetooth" + (name?.let { ". This device is \"$it\"" } ?: ""),
        )
        Thread({ acceptLoop(ss) }, "poslink-bt-accept").apply { isDaemon = true }.start()
    }

    override fun closeLink() {
        val ss = serverSocket
        serverSocket = null
        runCatching { ss?.close() } // ends accept()
        val s = synchronized(lock) {
            val current = socket
            socket = null
            output = null
            writerTicket = null
            current
        }
        closeWriter()
        runCatching { s?.close() } // ends the reader and a blocked write
    }

    override fun resetLink() {
        val s = socket ?: return
        log("Reset: closing the Bluetooth connection")
        disconnected(s, "Waiting for the cashier to reconnect")
    }

    override fun write(data: ByteArray): Boolean {
        val s = socket ?: return false
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
            true
        } catch (e: IOException) {
            if (socket === s) {
                log("Bluetooth write failed: ${e.message}")
                disconnected(s, "Connection lost")
            }
            false
        }
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (serverSocket === ss) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (serverSocket !== ss) return
                // The listening socket died (e.g. Bluetooth restarted); open a new one.
                log("Bluetooth listening stopped: ${e.message}")
                main.post(0) {
                    if (serverSocket !== ss) return@post
                    serverSocket = null
                    runCatching { ss.close() }
                    main.post(2000) { if (isOpen && serverSocket == null) openLink() }
                }
                return
            }
            attach(ss, s)
        }
    }

    private fun attach(ss: BluetoothServerSocket, s: BluetoothSocket) {
        val out = try {
            s.outputStream
        } catch (e: IOException) {
            runCatching { s.close() }
            return
        }
        val ticket = openWriter()
        var accepted = false
        val old = synchronized(lock) {
            if (serverSocket !== ss) return@synchronized null // closed while accept() returned
            accepted = true
            val previous = socket
            socket = s
            output = out
            writerTicket = ticket
            previous
        }
        if (!accepted) {
            closeWriter(ticket)
            runCatching { s.close() }
            return
        }
        if (old != null) {
            log("A new cashier connection replaces the old one")
            runCatching { old.close() }
            // A down/up pair, so the link session starts a fresh handshake (new nonces, new key).
            state(TransportStatus.WAITING, "Replacing the cashier connection")
        }
        val who = runCatching { s.remoteDevice.name }.getOrNull() ?: s.remoteDevice.address
        state(TransportStatus.CONNECTED, "Connected to the cashier over Bluetooth ($who)")
        Thread({ readLoop(s) }, "poslink-bt-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(4096)
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) emit(buf.copyOf(n))
            }
        } catch (e: IOException) {
            if (socket === s) log("Bluetooth read ended: ${e.message}")
        }
        disconnected(s, "Cashier disconnected")
    }

    private fun disconnected(s: BluetoothSocket, reason: String) {
        var wasCurrent = false
        val ticket = synchronized(lock) {
            if (socket !== s) return@synchronized null
            wasCurrent = true
            socket = null
            output = null
            writerTicket.also { writerTicket = null }
        }
        runCatching { s.close() }
        if (!wasCurrent) return
        if (ticket != null) closeWriter(ticket)
        if (isOpen) state(TransportStatus.WAITING, reason)
    }

    companion object {
        /**
         * Runtime permissions to request before start(): BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE
         * (to make the terminal visible for pairing) on Android 12+.
         */
        @JvmStatic
        fun requiredPermissions(): Array<String> = PosLinkBluetooth.serverPermissions()
    }
}
