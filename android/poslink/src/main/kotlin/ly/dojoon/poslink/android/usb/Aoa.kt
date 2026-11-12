package ly.dojoon.poslink.android.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.Locale

/**
 * Android Open Accessory (AOA) values. The cashier (USB host) sends the identity strings during
 * the handshake; the terminal (USB accessory) only accepts accessories whose manufacturer and
 * model match, and res/xml/poslink_accessory_filter.xml uses the same values.
 *
 * Protocol v2 changed the identity on purpose, so v1 apps ("SunmiUsb") and v2 apps don't
 * connect to each other.
 */
object Aoa {
    const val MANUFACTURER = "Dojoon"
    const val MODEL = "PosLink"
    const val DESCRIPTION = "POS Link payment terminal link"
    const val VERSION = "2"
    const val SERIAL = "0001"

    const val GOOGLE_VID = 0x18D1

    /** 0x2D00 accessory, 0x2D01 accessory+adb, 0x2D02..0x2D05 audio/hid variants. */
    @JvmField
    val ACCESSORY_PIDS: Set<Int> = setOf(0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05)

    const val REQ_GET_PROTOCOL = 51
    const val REQ_SEND_STRING = 52
    const val REQ_START = 53

    /** Largest single USB bulk transfer we issue; also the accessory read buffer size. */
    const val BUFFER_SIZE = 16384

    @JvmStatic
    fun isAccessoryMode(device: UsbDevice): Boolean =
        device.vendorId == GOOGLE_VID && device.productId in ACCESSORY_PIDS

    @JvmStatic
    fun matches(accessory: UsbAccessory): Boolean =
        accessory.manufacturer == MANUFACTURER && accessory.model == MODEL
}

/** Unique per app, so two apps using the SDK never get each other's USB permission answers. */
internal fun usbPermissionAction(context: Context) = "${context.packageName}.poslink.USB_PERMISSION"

@Suppress("DEPRECATION")
internal fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)

@Suppress("DEPRECATION")
internal fun Intent.usbAccessory(): UsbAccessory? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
    else getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

internal fun hex4(v: Int) = String.format(Locale.ROOT, "%04X", v)
