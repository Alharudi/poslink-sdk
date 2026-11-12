package ly.dojoon.poslink.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import ly.dojoon.poslink.android.MainThread
import ly.dojoon.poslink.android.registerPosLinkReceiver
import ly.dojoon.poslink.transport.BlockingTransport
import ly.dojoon.poslink.transport.Transport
import ly.dojoon.poslink.transport.TransportStatus
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Terminal side (e.g. Sunmi P3). Acts as the USB accessory: the cashier is the USB host and has
 * already switched this device into accessory mode. We open the accessory with the public
 * UsbManager API, which needs no developer options or USB debugging.
 *
 * To let Android open the app (and grant USB permission) when the cashier starts accessory mode,
 * add this to the activity in the app's manifest, and pass the activity's intent to
 * [handleIntent] from onCreate() and onNewIntent():
 *
 * ```xml
 * <activity android:name=".MainActivity" android:launchMode="singleTop" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
 *         android:resource="@xml/poslink_accessory_filter" />
 * </activity>
 * ```
 *
 * Without it the transport still works: it polls for the accessory and asks for permission with
 * the system dialog.
 */
class UsbAccessoryTransport(context: Context) : BlockingTransport() {

    override val name: String = "USB (accessory)"

    private val context: Context = context.applicationContext ?: context
    private val usbManager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = usbPermissionAction(this.context)
    private val main = MainThread()
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var pfd: ParcelFileDescriptor? = null

    // Channels, not plain streams: closing a channel wakes a thread blocked in read/write on it.
    @Volatile private var output: FileChannel? = null
    @Volatile private var input: FileChannel? = null
    private var openFailures = 0
    private var permissionRequested = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    permissionRequested = false
                    close("Cable disconnected")
                }
                permissionAction -> {
                    permissionRequested = false
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("USB accessory permission ${if (granted) "granted" else "denied"}")
                    if (granted) {
                        scan()
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
            if (pfd == null) scan()
            main.postRunnable(this, 1000)
        }
    }

    override fun start(listener: Transport.Listener) {
        attachListener(listener)
        main.run {
            if (running) return@run
            running = true
            // The answer to a dialog from an earlier run went to the receiver stop() removed.
            permissionRequested = false
            openFailures = 0
            registerReceiver()
            state(TransportStatus.WAITING, "Connect the USB cable to the cashier")
            main.postRunnable(poll, 0)
        }
    }

    override fun stop() {
        main.run {
            running = false
            main.cancelAll()
            close(null)
            closeWriter()
            if (receiverRegistered) {
                runCatching { context.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
            state(TransportStatus.STOPPED)
        }
    }

    /**
     * Re-opening an accessory without a replug is unreliable on many Android builds, and the host
     * re-opens its side on its own, so a reset on this side only logs.
     */
    override fun reset() {
        log("Reset requested; waiting for the cashier to reconnect")
    }

    /** Forward USB_ACCESSORY_ATTACHED intents that launched the activity (see the class docs). */
    fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) main.post(100) { scan() }
    }

    /**
     * Blocks while the cashier isn't reading, once the driver's few packet buffers are full.
     * [close] never takes [writeLock]: it closes the channel instead, which ends a blocked write.
     */
    override fun write(data: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                val buf = ByteBuffer.wrap(data)
                while (buf.hasRemaining()) out.write(buf)
            }
            true
        } catch (e: IOException) {
            if (output === out) {
                log("USB write failed: ${e.message}")
                main.post(0) { if (output === out) close("Connection lost") }
            }
            false
        }
    }

    private fun scan() {
        if (!running || pfd != null) return
        val accessories = usbManager.accessoryList ?: emptyArray()
        if (accessories.isEmpty()) {
            state(TransportStatus.WAITING, "Connect the USB cable to the cashier")
            return
        }
        val acc = accessories.firstOrNull { Aoa.matches(it) }
        if (acc == null) {
            val other = accessories.first()
            state(TransportStatus.ERROR, "Unknown USB accessory: ${other.manufacturer} ${other.model}")
            return
        }
        if (!usbManager.hasPermission(acc)) {
            requestPermission(acc)
            return
        }
        open(acc)
    }

    private fun open(acc: UsbAccessory) {
        state(TransportStatus.CONNECTING, "Opening USB link…")
        val fd = try {
            usbManager.openAccessory(acc)
        } catch (e: Exception) {
            log("openAccessory failed: ${e.message}")
            null
        }
        if (fd == null) {
            // The driver allows one open at a time; right after a close the old one can take a
            // moment to be released, so only report after a few tries (the poll retries each second).
            if (++openFailures >= 3) state(TransportStatus.ERROR, "Cannot open the USB link. Replug the cable.")
            return
        }
        openFailures = 0
        val inChannel = FileInputStream(fd.fileDescriptor).channel
        output = FileOutputStream(fd.fileDescriptor).channel
        input = inChannel
        pfd = fd
        openWriter()
        state(TransportStatus.CONNECTED, "Connected to cashier (${acc.manufacturer} ${acc.model} ${acc.version})")
        startReader(fd, inChannel)
    }

    private fun startReader(fd: ParcelFileDescriptor, inChannel: FileChannel) {
        Thread({
            // Accessory reads must use a buffer of at least 16 KB or packets can be lost.
            val buf = ByteBuffer.allocate(Aoa.BUFFER_SIZE)
            try {
                while (running && pfd === fd) {
                    buf.clear()
                    val n = inChannel.read(buf)
                    if (n < 0) break
                    if (n > 0) emit(buf.array().copyOf(n))
                }
            } catch (e: IOException) {
                if (pfd === fd) log("USB read ended: ${e.message}")
            }
            main.post(0) { if (pfd === fd) close("Connection lost") }
        }, "poslink-usb-accessory-reader").apply { isDaemon = true }.start()
    }

    private fun close(reason: String?) {
        val fd = pfd ?: return
        pfd = null
        val out = output
        val inChannel = input
        output = null
        input = null
        // Answers queued sends with false. It doesn't interrupt the writer thread: that would close
        // the channel here, on the main thread, and wait for the blocked write.
        closeWriter()
        // Closing the channels wakes the reader and any write that is blocked because the cashier
        // stopped reading. That waits for those threads, so do it off the main thread.
        Thread({
            runCatching { out?.close() }
            runCatching { inChannel?.close() }
            runCatching { fd.close() }
        }, "poslink-usb-accessory-close").apply { isDaemon = true }.start()
        if (reason != null && running) state(TransportStatus.WAITING, reason)
    }

    private fun requestPermission(acc: UsbAccessory) {
        if (!permissionRequested) {
            permissionRequested = true
            val intent = Intent(permissionAction).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            usbManager.requestPermission(acc, PendingIntent.getBroadcast(context, 1, intent, flags))
            log("Asking for USB accessory permission")
        }
        state(TransportStatus.PERMISSION, "Allow USB access on the screen")
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(permissionAction)
        }
        context.registerPosLinkReceiver(receiver, filter)
        receiverRegistered = true
    }
}
