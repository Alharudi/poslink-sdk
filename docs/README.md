# POS Link documentation

POS Link connects a **cashier app** (the till) to a **payment terminal app** (on a Sunmi P3) over a
USB cable or Bluetooth. The cashier asks for a payment, the customer pays on the terminal, and the
result comes back to the cashier.

## Start here

| I want to… | Read |
|---|---|
| Understand how it works (5 minutes) | [How it works](#how-it-works), below |
| Add it to an Android app | [Android setup](android/setup.md) |
| Write the cashier side (take payments) | [Cashier guide](android/cashier.md) |
| Write the terminal side (the app on the P3) | [Terminal guide](android/terminal.md) |
| Test without devices or cards | [Testing](android/testing.md) |
| Go live without double charges | [Payment safety checklist](payment-safety.md) |
| Fix a problem | [Troubleshooting](troubleshooting.md) |
| Build an SDK for another language | [Protocol spec](../spec/PROTOCOL.md) and [test vectors](../spec/vectors/) |
| Publish a new version (maintainers) | [Releasing](releasing.md) |

## How it works

### Two roles

- **Cashier:** the app where the order is made (a till, a tablet, a PC). It *connects*.
- **Terminal:** the app on the payment device that reads the card. It *waits* for the cashier.

One cashier talks to one terminal at a time.

### Three ways to connect

| Link | Cashier | Terminal | Notes |
|---|---|---|---|
| **USB cable** | USB host (`UsbHostTransport`) | USB accessory (`UsbAccessoryTransport`) | Most reliable. The terminal needs no developer options. |
| **Bluetooth** | `BluetoothClientTransport` | `BluetoothServerTransport` | Bluetooth Classic. Pair the two devices in Android settings first. |
| **Wi-Fi / LAN** | `TcpClientTransport` | `TcpServerTransport` | For development and fixed LAN setups. |

Bluetooth LE is not used: Sunmi terminals in payment-security (PCI) mode switch it off.

### Pair once, then every message is signed

The first time a cashier meets a terminal, they **pair**:

1. The merchant taps **Pair a cashier** on the terminal (pairing stays open for 2 minutes).
2. The cashier starts pairing. The terminal shows a **6-digit code**.
3. The person types that code on the cashier, then taps **Confirm** on the terminal.

Both sides now share a secret key. After that, every message between them is signed with it, so a
device in the middle can't fake an "approved" or replay an old one. Pairing survives restarts.
Pair again only if one app is reinstalled or its data is cleared.

This is separate from Bluetooth pairing in Android settings, which is also needed for Bluetooth.

### A sale has three possible outcomes

```kotlin
when (val outcome = cashier.sale(amount = 12500, currency = "LYD")) {
    is SaleOutcome.Completed -> // the terminal's final answer: approved, declined, cancelled…
    is SaleOutcome.Refused   -> // refused before any card was read: no money moved
    is SaleOutcome.Unknown   -> // nobody knows yet whether money moved: NEVER treat as declined
}
```

`Unknown` happens when the link drops in the middle of a payment. The SDK keeps asking the
terminal and tells you the real result when the link is back. See the
[payment safety checklist](payment-safety.md).

### Built-in protection

- **No double charges:** each sale has a `txnId`. The terminal charges a `txnId` at most once; a
  repeated request gets the stored result back.
- **One sale at a time:** a second sale while one is running is refused (`BUSY`).
- **Lost messages:** every message is acknowledged and retried.
- **Pulled cable or app restart:** results are kept and re-sent for 24 hours until the cashier
  confirms it got them.

### Amounts

Amounts are whole numbers in the currency's smallest unit. Libyan dinar has 3 decimals, so
12.500 LYD is `12500`. Currency is the 3-letter ISO 4217 code (`"LYD"`).

## Platforms

| Cashier runs on | Status |
|---|---|
| Android | ✅ Android SDK (this repository) |
| Flutter | Planned (wraps the Android SDK) |
| Windows / macOS / Linux, web (Chrome/Edge) | Planned (TypeScript and .NET SDKs) |
| iPhone / iPad | ✗ Apple doesn't allow it without MFi hardware |

The terminal side always runs on Android (the Sunmi P3) and uses the Android SDK.

Until an SDK exists for your platform, you can implement the [protocol](../spec/PROTOCOL.md)
yourself. The [test vectors](../spec/vectors/) tell you when your implementation is byte-for-byte
right.
