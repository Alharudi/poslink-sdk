# Android setup

The same library is used on both sides: the cashier app and the terminal app.

## Requirements

- Runs on Android 7.0 (API 24) or newer.
- Your app compiles against Android SDK 34 or newer (`compileSdk = 34`).
- Kotlin 2.0 or newer. Kotlin coroutines (1.9.0 or newer) come with the library.
- Java callers work too, but the API is built for Kotlin (`suspend` functions and `StateFlow`).

## 1. Add the dependency

The library is hosted on the project's GitHub Pages. Add that address once, in
`settings.gradle.kts`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://alharudi.github.io/poslink-sdk/maven")
            content { includeGroup("ly.dojoon.poslink") } // only POS Link comes from here
        }
    }
}
```

Then add the library to your app:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("ly.dojoon.poslink:poslink-android:0.1.0")
}
```

The library will also go to Maven Central later. From then on, the `maven { … }` block isn't
needed; the dependency line stays the same.

<details>
<summary>Got the SDK as a zip file instead?</summary>

Unzip it (for example to `libs/poslink-repo` in your project), then add that folder as a repository
in `settings.gradle.kts`, instead of the web address:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("libs/poslink-repo") }
    }
}
```

The dependency line stays the same.
</details>

## 2. Permissions

The library's manifest already adds these to your app:

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Android 12+) | Connect over Bluetooth |
| `BLUETOOTH_ADVERTISE` (Android 12+) | Terminal: be visible for Bluetooth pairing |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and older) | Same, older Android |

USB needs no permission in the manifest. Android asks the user once with a system dialog.

On **Android 12 and newer**, the Bluetooth permissions must also be granted at runtime ("Nearby
devices"). The SDK can't show the prompt, so your app asks before or after `start()`:

```kotlin
val needed = BluetoothClientTransport.requiredPermissions() // or BluetoothServerTransport's
val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
```

Until they are granted, the link reports `TransportStatus.PERMISSION` and waits. It connects on its
own once they are granted. Nothing needs restarting.

**Wi-Fi link only:** add `<uses-permission android:name="android.permission.INTERNET" />`
yourself. The library doesn't add it.

## 3. USB: let Android open your app when the cable is plugged in

This step is optional but recommended. Without it, USB still works, but the user gets a
permission dialog each time.

Add the intent filter for your side to your main activity. The XML filter files come with the
library:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">

    <!-- Cashier app: a terminal was plugged in -->
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/poslink_device_filter" />

    <!-- Terminal app: a cashier connected -->
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
        android:resource="@xml/poslink_accessory_filter" />
</activity>
```

Then pass the activity's intents to the transport:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    usbTransport.handleIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    usbTransport.handleIntent(intent)
}
```

If the user ticks **"Always open"** in the dialog, Android remembers the choice and stops asking.

> **Your terminal isn't a Sunmi P3?** `poslink_device_filter` lists the P3's USB id (`0E8D:201C`).
> For other devices, copy the file into your app's `res/xml/` under a new name, add your device's
> vendor and product id (in decimal), and point the `meta-data` at your copy. Always match vendor
> **and** product id, because many devices share a vendor id.

## 4. Bluetooth: pair the devices in Android settings once

Bluetooth needs the two devices paired in Android settings first. This is separate from POS Link
pairing.

1. On the terminal, open **Settings → Bluetooth**. It is visible to others while that screen is
   open.
2. On the cashier, open **Settings → Bluetooth**, tap the terminal's name and confirm the code on
   both devices.

Then let the cashier user pick the terminal from the paired devices:

```kotlin
val devices = PosLinkBluetooth.bondedDevices(context) // null until BLUETOOTH_CONNECT is granted
// show devices (name + address); save the chosen address
val transport = BluetoothClientTransport(context, chosen.address)
```

`hasPosLinkService` tells you which devices have offered the POS Link service before. Use it to
sort the list, not to hide devices: Android's cached list can be out of date.

## 5. Pick a transport

| | Cashier app | Terminal app |
|---|---|---|
| USB | `UsbHostTransport(context)` | `UsbAccessoryTransport(context)` |
| Bluetooth | `BluetoothClientTransport(context, address)` | `BluetoothServerTransport(context)` |
| Wi-Fi | `TcpClientTransport(host, port = 8765)` | `TcpServerTransport(port = 8765)` |

A transport reconnects on its own: after a pulled cable, Bluetooth out of range, or a terminal
restart. You never call connect again.

Next: the [cashier guide](cashier.md) or the [terminal guide](terminal.md).

## Keep one instance for the whole app

Create the `PosLinkCashier` or `PosLinkTerminal` once, for example in your `Application` class or a
singleton, and call `start()` once. Don't create one per screen: an Activity is destroyed on every
rotation, while the link must survive it.

```kotlin
class App : Application() {
    lateinit var cashier: PosLinkCashier
        private set

    override fun onCreate() {
        super.onCreate()
        cashier = PosLinkCashier(/* see the cashier guide */)
        cashier.start()
    }
}
```

## Files the SDK keeps

| What | Where | If deleted |
|---|---|---|
| This app's POS Link id | SharedPreferences `poslink_identity` | The app looks like a new device; pair again |
| Pairing keys (`KeystorePairingStore`) | `noBackupFilesDir/poslink_pairings.json`, encrypted with a key in the Android Keystore | Pair again |
| Transactions (`FileJournal`) | The file you pass in | The duplicate-charge and recovery history is lost |
| USB terminals picked on the cashier | SharedPreferences `poslink_usb` | Pick the device again |

Pairing keys are in `noBackupFilesDir`, so they are never copied by Android backup. The Keystore key
can't leave the device, so a copied file is useless anywhere else.

## Code shrinking (R8)

Nothing to configure. Release builds with R8 turned on work as they are.
