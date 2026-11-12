package ly.dojoon.poslink.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import ly.dojoon.poslink.transport.TransportStatus
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlin.math.min

/**
 * Cashier side over Bluetooth Classic (RFCOMM). Connects to the paired terminal at [address]
 * (see [PosLinkBluetooth.bondedDevices]) and reconnects on its own after the link drops or the
 * terminal goes out of range, backing off from 2 s to 15 s while it is unreachable.
 */
@SuppressLint("MissingPermission")
class BluetoothClientTransport(
    context: Context,
    address: String,
) : BluetoothTransport(context, PosLinkBluetooth.clientPermissions()) {

    /** The terminal's address, upper case as Android expects ("00:11:22:AA:BB:CC"). */
    val address: String = address.trim().uppercase(Locale.ROOT)

    override val name: String get() = "Bluetooth ($address)"

    /** Bumped by [closeLink], so the connection thread of an old link stops. */
    @Volatile private var generation = 0
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private val writeLock = Any()

    override fun openLink() {
        val a = adapter ?: return
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            state(TransportStatus.ERROR, "\"$address\" is not a Bluetooth address. Pick the paired terminal.")
            return
        }
        val device = a.getRemoteDevice(address)
        val gen = ++generation
        Thread({ linkLoop(device, gen) }, "poslink-bt-link").apply { isDaemon = true }.start()
    }

    override fun closeLink() {
        generation++
        val s = socket
        socket = null
        output = null
        closeWriter()
        runCatching { s?.close() } // aborts connect(), ends the reader and a blocked write
    }

    override fun resetLink() {
        val s = socket ?: return
        log("Reset: reconnecting to the terminal")
        runCatching { s.close() }
    }

    override fun write(data: ByteArray): Boolean {
        val out = output ?: return false
        val s = socket
        return try {
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
            true
        } catch (e: IOException) {
            log("Bluetooth write failed: ${e.message}")
            runCatching { s?.close() } // the link thread notices and reconnects
            false
        }
    }

    private fun linkLoop(device: BluetoothDevice, gen: Int) {
        val label = runCatching { device.name }.getOrNull() ?: device.address
        var delay = 2000L
        while (running && generation == gen) {
            state(TransportStatus.CONNECTING, "Connecting to $label…")
            val s = try {
                device.createRfcommSocketToServiceRecord(PosLinkBluetooth.SERVICE_UUID)
            } catch (e: IOException) {
                null
            }
            if (s == null) {
                state(TransportStatus.ERROR, "Cannot create a Bluetooth connection to $label")
                pause(5000)
                continue
            }
            socket = s
            try {
                runCatching { adapter?.cancelDiscovery() } // a running discovery slows connect() a lot
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                if (generation != gen) return
                socket = null
                state(
                    TransportStatus.WAITING,
                    "Terminal $label not reachable. Is POS Link open on it with Bluetooth? Retrying…",
                )
                pause(delay)
                delay = min(delay * 2, 15_000L)
                continue
            }
            if (generation != gen) {
                runCatching { s.close() }
                return
            }
            delay = 2000L
            val ticket = openWriter()
            output = s.outputStream
            state(TransportStatus.CONNECTED, "Connected to $label over Bluetooth")
            read(s)
            output = null
            closeWriter(ticket)
            if (socket === s) socket = null
            runCatching { s.close() }
            if (!running || generation != gen) return
            state(TransportStatus.WAITING, "Connection to $label lost")
            pause(1000)
        }
    }

    /** Returns when the connection closes. */
    private fun read(s: BluetoothSocket) {
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
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // nothing interrupts this thread; the loop condition ends it after a close
        }
    }

    companion object {
        /** Runtime permissions to request before start(): BLUETOOTH_CONNECT on Android 12+. */
        @JvmStatic
        fun requiredPermissions(): Array<String> = PosLinkBluetooth.clientPermissions()
    }
}
