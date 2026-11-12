package ly.dojoon.poslink.android.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import ly.dojoon.poslink.android.MainThread
import ly.dojoon.poslink.android.registerPosLinkReceiver
import ly.dojoon.poslink.transport.BlockingTransport
import ly.dojoon.poslink.transport.Transport
import ly.dojoon.poslink.transport.TransportStatus
import java.util.UUID

/**
 * Bluetooth identifiers and helpers shared by the Bluetooth transports.
 * Other platforms must use the same values.
 *
 * Only Bluetooth Classic is used: Sunmi payment terminals in PCI mode switch Bluetooth LE off in
 * the firmware (seen on a P3 MIX, SUNMI OS 4.1.22), while Classic keeps working.
 */
object PosLinkBluetooth {
    /** RFCOMM service record the terminal listens on. */
    @JvmField
    // Protocol v2. v1 (the sunmi_usb prototype) used ac0c0004, so both apps can listen side by side.
    val SERVICE_UUID: UUID = UUID.fromString("ac0c0005-cf31-4ff0-828c-7470cd97fc7d")
    const val SERVICE_NAME = "POS Link"

    internal const val OFF_TEXT = "Bluetooth is off. Turn it on to connect."

    @JvmStatic
    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Paired devices that can carry Bluetooth Classic, sorted by name, for picking the terminal
     * on the cashier. Null without the BLUETOOTH_CONNECT permission
     * (see [BluetoothClientTransport.requiredPermissions]).
     */
    @JvmStatic
    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<PairedBluetoothDevice>? {
        if (missing(context, clientPermissions()).isNotEmpty()) return null
        val adapter = adapter(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .map { d ->
                    PairedBluetoothDevice(
                        address = d.address,
                        name = d.name,
                        hasPosLinkService = d.uuids?.any { it.uuid == SERVICE_UUID } == true,
                    )
                }
                .sortedBy { it.name?.lowercase() ?: "~" }
        }.getOrNull()
    }

    /**
     * Runtime permissions. Android 12+ asks for "Nearby devices"; older versions grant
     * BLUETOOTH/BLUETOOTH_ADMIN at install.
     */
    internal fun clientPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    /** The terminal also needs ADVERTISE to make itself visible for pairing. */
    internal fun serverPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    internal fun missing(context: Context, permissions: Array<String>): List<String> =
        permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}

/**
 * A paired Bluetooth device. [hasPosLinkService] is true when the device's cached service list
 * (from its last SDP query) contains [PosLinkBluetooth.SERVICE_UUID]; false can also mean the list
 * is stale, so don't hide devices because of it.
 */
data class PairedBluetoothDevice(
    val address: String,
    val name: String?,
    val hasPosLinkService: Boolean,
)

/**
 * What every Bluetooth transport shares: checking runtime permissions, waiting for Bluetooth to
 * be on, and closing the link when it is turned off. Subclasses implement [openLink],
 * [closeLink] and [resetLink], which run on the main thread.
 *
 * The SDK can't show permission prompts. When a permission is missing the state is
 * [TransportStatus.PERMISSION]; the app asks for the subclass's `requiredPermissions()` and the
 * link starts on its own once they are granted (checked again on start(), on reset() and every
 * 2 s while waiting).
 */
abstract class BluetoothTransport internal constructor(
    context: Context,
    private val permissions: Array<String>,
) : BlockingTransport() {

    protected val context: Context = context.applicationContext ?: context
    protected val adapter: BluetoothAdapter? = PosLinkBluetooth.adapter(this.context)
    internal val main = MainThread()

    @Volatile protected var running = false
        private set

    /** True between [openLink] and [closeLink]. Delayed work checks it so it doesn't run after a close. */
    @Volatile protected var isOpen = false
        private set

    private var receiverRegistered = false
    private val recheck = Runnable { begin() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> begin()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    shut()
                    if (running) state(TransportStatus.ERROR, PosLinkBluetooth.OFF_TEXT)
                }
            }
        }
    }

    final override fun start(listener: Transport.Listener) {
        attachListener(listener)
        main.run {
            if (running) return@run
            running = true
            registerReceiver()
            begin()
        }
    }

    final override fun stop() {
        main.run {
            running = false
            main.cancelAll()
            shut()
            closeWriter()
            if (receiverRegistered) {
                runCatching { context.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
            state(TransportStatus.STOPPED)
        }
    }

    /** Drops the connection, or, while the link can't open, checks permissions and Bluetooth again. */
    final override fun reset() {
        main.run {
            if (!running) return@run
            if (isOpen) resetLink() else begin()
        }
    }

    /** Opens the link once the permissions are granted and Bluetooth is on. */
    private fun begin() {
        if (!running || isOpen) return
        val a = adapter ?: return state(TransportStatus.ERROR, "This device has no Bluetooth")
        val missing = PosLinkBluetooth.missing(context, permissions)
        if (missing.isNotEmpty()) {
            main.repost(recheck, PERMISSION_RECHECK_MS)
            val names = missing.joinToString { it.substringAfterLast('.') }
            return state(
                TransportStatus.PERMISSION,
                "Allow Bluetooth access (\"Nearby devices\") for this app. Missing: $names",
            )
        }
        if (!a.isEnabled) return state(TransportStatus.ERROR, PosLinkBluetooth.OFF_TEXT)
        isOpen = true
        openLink()
    }

    private fun shut() {
        if (!isOpen) return
        isOpen = false
        closeLink()
    }

    /** Bluetooth is on and the permissions are granted. */
    protected abstract fun openLink()

    /** Undo [openLink]: stop listening or connecting and drop the connection. */
    protected abstract fun closeLink()

    /** Drop the current connection; the link connects again on its own. */
    protected abstract fun resetLink()

    private fun registerReceiver() {
        if (receiverRegistered) return
        context.registerPosLinkReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        receiverRegistered = true
    }

    private companion object {
        const val PERMISSION_RECHECK_MS = 2000L
    }
}
