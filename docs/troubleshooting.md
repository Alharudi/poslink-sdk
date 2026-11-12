# Troubleshooting

First, turn on the SDK's log and read `link.transportState` and `link.problem`. Most problems
explain themselves there.

```kotlin
PosLinkCashier(/* … */, log = { Log.d("PosLink", it) }) // same for PosLinkTerminal
```

```bash
adb logcat -s PosLink
```

## The status stays OFFLINE

**Bluetooth**

| Check | Fix |
|---|---|
| `transportState` is `PERMISSION` | Ask for `requiredPermissions()` ([setup](android/setup.md#2-permissions)). The link starts on its own once they are granted. |
| `ERROR: Bluetooth is off…` | Turn Bluetooth on. The link notices on its own. |
| The two devices aren't paired in Android settings | Pair them in **Settings → Bluetooth** ([setup](android/setup.md#4-bluetooth-pair-the-devices-in-android-settings-once)). |
| The cashier has the wrong address | Pick the terminal again from `PosLinkBluetooth.bondedDevices()`. |
| The terminal app isn't running, or hasn't called `start()` | Open it. Its status should say it's waiting for the cashier. |
| The terminal runs the old prototype app (protocol v1) | v1 and v2 listen on different Bluetooth service ids, so they never meet. Install the v2 app. |

The cashier retries every 2 to 15 seconds, so after a fix it can take up to 15 seconds to connect.

**USB**

| Check | Fix |
|---|---|
| `transportState` is `PERMISSION: Allow USB access on the screen` | Tap **OK** in the system dialog. Tick "Always open" so it isn't asked again. |
| `ERROR: USB permission denied…` | Unplug and replug the cable to get the dialog again. |
| `WAITING: Looking for the terminal…` and nothing happens | Use a **data** cable: many cables only charge. Then check that the terminal's USB id is in your device filter ([setup](android/setup.md#3-usb-let-android-open-your-app-when-the-cable-is-plugged-in)). |
| `… does not answer the Android accessory (AOA) handshake` | That device can't act as a USB accessory. Plug the other end in, or use another device. |
| The terminal shows "No installed app works with this USB accessory" | The terminal app isn't installed, or its manifest lacks the accessory filter. |
| `ERROR: Unknown USB accessory: …` | The cashier app uses another identity: an old v1 app, or a different product. |

The cashier is always the USB **host**: it needs USB host support (USB-C or OTG). The terminal is
the accessory, and needs no developer options or USB debugging.

## Stuck at HANDSHAKING

The transport is connected, but the other app doesn't answer. Look at `link.problem`:

| Problem | Fix |
|---|---|
| `Both devices are set to "cashier"` (or `"terminal"`) | One device must be the cashier and the other the terminal. |
| `The other device runs POS Link protocol v1` | Update that app to the v2 SDK. |
| `The other device speaks protocol vN…` | Update both apps to the same SDK major version. |
| No problem shown | The other app is busy or frozen. Restart it. |

## UNPAIRED, although it worked before

The problem says something like `The terminal no longer knows this device`. One of the two apps
was reinstalled or had its data cleared, so its key is gone. Pair again. You don't have to remove
the old pairing first.

## Stuck at AUTHENTICATING, or "failed the security check"

The two sides have different keys for each other. This happens, for example, when the cashier was
paired again with a reinstalled terminal while another cashier kept an old pairing. Call `forget()`
on both sides for that peer, then pair again.

If it happens with no reinstall at all, something may be changing bytes on the link (a bad cable or
hub). After 5 failed checks the SDK resets the connection.

## Pairing fails

| `PairingResult.Failed.reason` | Fix |
|---|---|
| `NOT_PAIRING` | Tap **Pair a cashier** on the terminal first. Pairing stays open for 2 minutes. |
| `BAD_CONFIRM` | The codes didn't match. Try again, and type the code exactly as the terminal shows it. |
| `REJECTED`, `TIMEOUT` | Someone tapped Reject, or nobody finished in 2 minutes. Try again. |
| `NOT_CONNECTED` | Pairing needs a connected terminal: status must be at least `UNPAIRED`. |

## Sales

| What you see | Why | Fix |
|---|---|---|
| `Refused`: "Terminal not connected" | Status isn't `ONLINE` | Check the status before charging. Enable the Pay button only when `ONLINE`. |
| `Refused` with code `BUSY` | A payment is already running on the terminal, or on this cashier | Wait, or cancel on the terminal |
| `Refused` with code `BAD_REQUEST` | Amount ≤ 0, currency not 3 letters, or empty `txnId` | Fix the request |
| `Unknown` | The link dropped mid-payment, or the terminal went silent for 150 s | See [the cashier guide](android/cashier.md#6-when-the-outcome-is-unknown). Never treat it as declined. |
| `NOT_RESPONDING` during a sale | The terminal app froze, or the link is flaky | The sale keeps waiting. If the link drops, it becomes `Unknown` and is resolved later. |

## Bluetooth LE

POS Link doesn't use Bluetooth LE. Sunmi terminals in payment-security (PCI) mode stop Android's
LE service as soon as Bluetooth turns on, so LE can't work there. Bluetooth Classic works.

## Installing on Sunmi devices

- **Development devices** take normal `adb install`.
- **Production devices** install only from the SUNMI App Store. Publish the terminal app there.
- On Sunmi P3 firmware 3.0.x, the P3's USB id is `0E8D:201C`, with developer options on or off.
  If your firmware shows a different id, add it to your device filter.
