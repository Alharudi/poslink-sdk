package ly.dojoon.poslink.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import ly.dojoon.poslink.android.MainThread
import ly.dojoon.poslink.android.registerPosLinkReceiver
import ly.dojoon.poslink.transport.BlockingTransport
import ly.dojoon.poslink.transport.Transport
import ly.dojoon.poslink.transport.TransportStatus

/** A USB device the cashier can see, for a device picker. */
data class UsbDeviceInfo(
    /** Pass to [UsbHostTransport.connectDevice]. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val interfaceCount: Int,
    /** Already switched to accessory mode (Google 18D1:2D0x). */
    val accessoryMode: Boolean,
    val hasPermission: Boolean,
) {
    /** "VVVV:PPPP" in hex, e.g. "0E8D:201C". */
    val id: String get() = "${hex4(vendorId)}:${hex4(productId)}"
}

/**
 * Cashier side (e.g. Flex 3). Acts as USB host:
 *  1. finds the terminal (a device picked once with [connectDevice] or matched by the device
 *     filter, or one already in accessory mode),
 *  2. sends the AOA handshake so the terminal re-enumerates as a Google accessory (18D1:2D00),
 *  3. opens the accessory's bulk endpoints and pumps bytes.
 *
 * All connection state is mutated on the main thread; only the reader thread and [write] run
 * elsewhere. Remembered terminals are kept in the SharedPreferences file "poslink_usb".
 *
 * To let Android open the app (and grant USB permission for good when the user ticks "always
 * open") when the terminal is plugged in, add this to the activity in the app's manifest, and
 * pass the activity's intent to [handleIntent] from onCreate() and onNewIntent():
 *
 * ```xml
 * <activity android:name=".MainActivity" android:launchMode="singleTop" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
 *         android:resource="@xml/poslink_device_filter" />
 * </activity>
 * ```
 *
 * Without it the transport still works: it asks for USB permission with the system dialog.
 *
 * @param uri sent in the AOA handshake. Android shows it on the terminal when no installed app
 *   handles the accessory; empty sends none.
 */
class UsbHostTransport @JvmOverloads constructor(
    context: Context,
    private val uri: String = "",
) : BlockingTransport() {

    override val name: String = "USB (host)"

    private val context: Context = context.applicationContext ?: context
    private val usbManager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val permissionAction = usbPermissionAction(this.context)
    private val main = MainThread()
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var claimed: UsbInterface? = null

    private val pendingPermission = mutableSetOf<String>()
    private val lastSwitchAttempt = mutableMapOf<String, Long>()
    private val unsupported = mutableSetOf<String>()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanSoon(300)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.usbDevice()
                    if (d != null) {
                        pendingPermission.remove(d.deviceName)
                        unsupported.remove(d.deviceName)
                        if (d.deviceName == device?.deviceName) closeConnection("Cable disconnected")
                    }
                    scanSoon(300)
                }
                permissionAction -> {
                    val d = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    d?.let { pendingPermission.remove(it.deviceName) }
                    log("USB permission ${if (granted) "granted" else "denied"} for ${d?.let { label(it) }}")
                    if (granted) {
                        scanSoon(100)
                    } else {
                        state(TransportStatus.ERROR, "USB permission denied. Unplug and replug the cable to ask again.")
                    }
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            if (connection == null) scan()
            main.postRunnable(this, 2000)
        }
    }

    override fun start(listener: Transport.Listener) {
        attachListener(listener)
        main.run {
            if (running) return@run
            running = true
            // A permission dialog or AOA attempt from an earlier run is no longer tracked
            // (its broadcast went to the receiver stop() removed), so start clean.
            pendingPermission.clear()
            lastSwitchAttempt.clear()
            unsupported.clear()
            registerReceiver()
            state(TransportStatus.WAITING, "Looking for the terminal…")
            main.postRunnable(poll, 0)
        }
    }

    override fun stop() {
        main.run {
            running = false
            main.cancelAll()
            closeConnection(null)
            closeWriter()
            if (receiverRegistered) {
                runCatching { context.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
            state(TransportStatus.STOPPED)
        }
    }

    override fun reset() {
        main.run {
            if (connection != null) {
                log("Resetting USB connection")
                closeConnection("Reconnecting…")
            }
        }
    }

    /**
     * Forward USB_DEVICE_ATTACHED intents that launched the activity (see the class docs).
     * A device that matched poslink_device_filter and is not already in accessory mode must be
     * the terminal (it is in the filter on purpose), so it is remembered. Permission was
     * granted by the system dialog. Other intents are ignored.
     */
    fun handleIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val d = intent.usbDevice()
        if (d != null && !Aoa.isAccessoryMode(d)) {
            val ids = remembered()
            if (idOf(d) !in ids) {
                prefs.edit().putStringSet(KEY_REMEMBERED, ids + idOf(d)).apply()
                log("Remembered ${label(d)} as the terminal (from device filter)")
            }
        }
        scanSoon(200)
    }

    override fun write(data: ByteArray): Boolean = synchronized(writeLock) {
        val conn = connection ?: return false
        val ep = epOut ?: return false
        var offset = 0
        while (offset < data.size) {
            val len = minOf(Aoa.BUFFER_SIZE, data.size - offset)
            val n = conn.bulkTransfer(ep, data, offset, len, 2000)
            if (n < 0) {
                log("USB write failed")
                return false
            }
            offset += n
        }
        true
    }

    // ---- device list / selection (for a device picker) ----

    fun listDevices(): List<UsbDeviceInfo> = describeDevices(usbManager)

    /** The user picked this device as the terminal: remember it and connect. */
    fun connectDevice(deviceName: String): Boolean {
        val d = usbManager.deviceList[deviceName] ?: return false
        val ids = remembered().toMutableSet().apply { add(idOf(d)) }
        prefs.edit().putStringSet(KEY_REMEMBERED, ids).apply()
        log("Remembered ${label(d)} as the terminal")
        main.run {
            unsupported.remove(d.deviceName)
            lastSwitchAttempt.remove(d.deviceName)
            scanSoon(0)
        }
        return true
    }

    fun forgetDevices() {
        prefs.edit().remove(KEY_REMEMBERED).apply()
        log("Forgot remembered terminals")
    }

    // ---- connection logic (main thread) ----

    private fun scanSoon(delayMs: Long) {
        main.post(delayMs) { scan() }
    }

    private fun scan() {
        if (!running || connection != null) return
        val devices = usbManager.deviceList.values

        devices.firstOrNull { Aoa.isAccessoryMode(it) }?.let {
            openAccessoryDevice(it)
            return
        }

        val remembered = remembered()
        val candidate = devices.firstOrNull { idOf(it) in remembered && it.deviceName !in unsupported }
        if (candidate != null) {
            switchToAccessory(candidate)
            return
        }

        state(
            TransportStatus.WAITING,
            if (devices.isEmpty()) "Connect the USB cable to the terminal"
            else "Terminal not recognised. Select it in the list of USB devices.",
        )
    }

    private fun switchToAccessory(d: UsbDevice) {
        if (!ensurePermission(d)) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastSwitchAttempt[d.deviceName] ?: 0L) < 5000) return
        lastSwitchAttempt[d.deviceName] = now

        state(TransportStatus.CONNECTING, "Switching ${label(d)} to accessory mode…")
        val conn = usbManager.openDevice(d)
        if (conn == null) {
            state(TransportStatus.ERROR, "Cannot open ${label(d)}")
            return
        }
        try {
            val version = getProtocol(conn)
            if (version < 1) {
                unsupported += d.deviceName
                state(TransportStatus.ERROR, "${label(d)} does not answer the Android accessory (AOA) handshake")
                return
            }
            log("${label(d)} supports AOA v$version")
            val strings = listOf(Aoa.MANUFACTURER, Aoa.MODEL, Aoa.DESCRIPTION, Aoa.VERSION, uri, Aoa.SERIAL)
            for ((index, value) in strings.withIndex()) {
                val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
                val r = conn.controlTransfer(OUT_VENDOR, Aoa.REQ_SEND_STRING, 0, index, bytes, bytes.size, 1000)
                if (r < 0) {
                    state(TransportStatus.ERROR, "AOA handshake failed while sending identity strings")
                    return
                }
            }
            val r = conn.controlTransfer(OUT_VENDOR, Aoa.REQ_START, 0, 0, null, 0, 1000)
            if (r < 0) {
                state(TransportStatus.ERROR, "AOA start request was rejected")
                return
            }
            log("Accessory start sent; waiting for the terminal to reconnect")
        } finally {
            conn.close()
        }
        scanSoon(1500)
    }

    private fun openAccessoryDevice(d: UsbDevice) {
        if (!ensurePermission(d)) return
        state(TransportStatus.CONNECTING, "Opening terminal…")
        val conn = usbManager.openDevice(d)
        if (conn == null) {
            state(TransportStatus.ERROR, "Cannot open the terminal in accessory mode")
            return
        }
        var found: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) {
                found = Triple(intf, inEp, outEp)
                break
            }
        }
        if (found == null || !conn.claimInterface(found.first, true)) {
            conn.close()
            state(TransportStatus.ERROR, "Terminal accessory interface not available")
            return
        }
        val (intf, inEp, outEp) = found
        device = d
        claimed = intf
        epOut = outEp
        connection = conn
        openWriter()
        state(TransportStatus.CONNECTED, "Terminal connected over USB")
        startReader(conn, inEp, d)
    }

    private fun startReader(conn: UsbDeviceConnection, inEp: UsbEndpoint, d: UsbDevice) {
        Thread({
            val buf = ByteArray(Aoa.BUFFER_SIZE)
            var fastFailures = 0
            while (running && connection === conn) {
                val t0 = SystemClock.elapsedRealtime()
                val n = conn.bulkTransfer(inEp, buf, buf.size, 1000)
                if (n > 0) {
                    fastFailures = 0
                    emit(buf.copyOf(n))
                } else if (n < 0) {
                    // bulkTransfer returns -1 for both timeout and error; tell them apart.
                    if (!usbManager.deviceList.containsKey(d.deviceName)) break
                    if (SystemClock.elapsedRealtime() - t0 < 20) {
                        if (++fastFailures > 50) break
                    } else {
                        fastFailures = 0
                    }
                }
            }
            main.post(0) { if (connection === conn) closeConnection("Connection lost") }
        }, "poslink-usb-host-reader").apply { isDaemon = true }.start()
    }

    private fun closeConnection(reason: String?) {
        val conn = connection ?: return
        closeWriter() // queued sends answer false; a write in progress ends within its timeout
        synchronized(writeLock) {
            connection = null
            epOut = null
        }
        runCatching { claimed?.let { conn.releaseInterface(it) } }
        runCatching { conn.close() }
        claimed = null
        device = null
        if (reason != null && running) {
            state(TransportStatus.WAITING, reason)
            scanSoon(1000)
        }
    }

    private fun ensurePermission(d: UsbDevice): Boolean {
        if (usbManager.hasPermission(d)) return true
        if (pendingPermission.add(d.deviceName)) {
            val intent = Intent(permissionAction).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            usbManager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
            log("Asking for USB permission for ${label(d)}")
        }
        state(TransportStatus.PERMISSION, "Allow USB access on the screen")
        return false
    }

    private fun getProtocol(conn: UsbDeviceConnection): Int {
        val buf = ByteArray(2)
        val r = conn.controlTransfer(IN_VENDOR, Aoa.REQ_GET_PROTOCOL, 0, 0, buf, 2, 1000)
        if (r != 2) return -1
        return ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        context.registerPosLinkReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun remembered(): Set<String> = prefs.getStringSet(KEY_REMEMBERED, emptySet()) ?: emptySet()

    companion object {
        private const val PREFS = "poslink_usb"
        private const val KEY_REMEMBERED = "remembered_devices"
        private const val OUT_VENDOR = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR
        private const val IN_VENDOR = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR

        internal fun idOf(d: UsbDevice) = "${hex4(d.vendorId)}:${hex4(d.productId)}"

        internal fun label(d: UsbDevice): String {
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device" }
            return "$name (${idOf(d)})"
        }

        /** The USB devices attached now, without a transport. */
        @JvmStatic
        fun describeDevices(context: Context): List<UsbDeviceInfo> =
            describeDevices(context.getSystemService(Context.USB_SERVICE) as UsbManager)

        @JvmStatic
        fun describeDevices(usbManager: UsbManager): List<UsbDeviceInfo> =
            usbManager.deviceList.values.map { d ->
                UsbDeviceInfo(
                    deviceName = d.deviceName,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    manufacturer = d.manufacturerName,
                    product = d.productName,
                    interfaceCount = d.interfaceCount,
                    accessoryMode = Aoa.isAccessoryMode(d),
                    hasPermission = usbManager.hasPermission(d),
                )
            }
    }
}
