# Task: build the POS Link SDK (cross-platform)

You are starting a new project: an SDK that lets any app, on any platform and in any language,
connect a cashier device to a Sunmi payment terminal (P3) over a USB cable or Bluetooth and run
payments (sale, cancel, status). It grows out of a working Flutter app. Reuse that app's protocol
and native code; don't reinvent them.

## Before writing any code

1. Read the reference project. It is the source of truth for the protocol and the native
   transports.
   - Local: the sibling folder `../sunmi_usb` (this session should be started with `--add-dir`
     pointing at it).
   - GitHub: https://github.com/Alharudi/sunmi_usb
   Read docs/PROTOCOL.md and README.md first, then the files under "Reference code" below.
2. Ask me these questions and wait for my answers:
   a. Product name and package namespace (for example "POS Link", `ly.dojoon.poslink`). This is
      hard to change after publishing.
   b. Public open source (which license?) or private packages for partners only.
   c. Whether to add pairing + message signing now as protocol v2 (recommended; see Security).
3. Write a CLAUDE.md for this repo with the facts and rules from this prompt, so later sessions
   keep them.
4. Propose the repo layout and a detailed Phase 0 + Phase 1 plan, and wait for my OK.

## Goal and architecture (already decided)

- One monorepo. `spec/` is the source of truth: PROTOCOL.md plus conformance test vectors (JSON).
  Every SDK must pass the same vectors.
- **One native implementation per ecosystem, no compiled binaries shipped in packages.** The
  protocol is small (about 600 lines per language). Pure Kotlin, TypeScript, C# and Python
  packages install cleanly. A shared Rust/C++ core was rejected because it would ship binaries
  for every platform, and Android needs Kotlin for USB/Bluetooth anyway.
- Every SDK has 3 layers:
  1. Transports: USB, Bluetooth, whatever the platform allows.
  2. Protocol core: frames, CRC, ACK/retry, duplicate filter, heartbeat, handshake,
     back-pressure, unknown-outcome recovery. Pure and transport-agnostic ("sans-IO"), with an
     injectable clock so tests are deterministic.
  3. High-level API with the **same names in every language**.
- Two roles: **cashier** connects (what integrators use) and **terminal** waits (what the P3 app
  uses). The Android SDK provides both; the other SDKs provide the cashier role only.

### API shape (same everywhere, adapted to each language's style)

- `connect()` / `disconnect()`, a state stream: offline, connecting, handshaking, online,
  not responding.
- `sale(amount, currency, reference?)` emits readyToPay, progress(stage), then the final result.
  It is idempotent per txnId. When the outcome is unknown (link lost mid-sale), it recovers by
  itself with a status query after reconnecting. It never reports "declined" when it doesn't
  know.
- `cancel(txnId)`, `status(txnId)`.
- Terminal role: a handler for incoming sale requests, backed by a payment-adapter interface.
- Typed error codes: BUSY, CANNOT_CANCEL, UNKNOWN_TXN, TXN_INTERRUPTED, BAD_REQUEST, …

### Packages

| Package | Registry | Covers | Transports |
|---|---|---|---|
| Android SDK (Kotlin, AAR) | Maven | native Android, both roles | USB host + accessory (AOA), Bluetooth Classic |
| Flutter plugin | pub.dev | Flutter apps (incl. the existing app) | wraps the Android SDK |
| React Native module | npm | React Native apps | wraps the Android SDK |
| JavaScript SDK (TypeScript) | npm | browsers (Chrome/Edge), Node, Electron | WebUSB, Web Serial (Bluetooth), node-usb |
| .NET SDK (C#) | NuGet | Windows POS software, also .NET on Linux/macOS | Windows Bluetooth RFCOMM, WinUSB |
| Python SDK | PyPI | kiosks, scripts, testing | pyusb, sockets/serial |
| Desktop helper | installer | any other language (Delphi, PHP, Java…) via localhost REST/WebSocket | built on the .NET or TS SDK |

iPhone/iPad are out of scope: they can't connect locally (see facts). They would need a later
network/cloud route.

## Phases (each with a "done" check)

- **Phase 0: scaffold + spec.** Copy PROTOCOL.md into `spec/` and build the conformance vectors:
  - encoded frames as hex with their decoded form;
  - scripted link scenarios: lost frame and retry, duplicate delivery, cable pull mid-sale,
    status-query recovery, busy terminal, cancel before and after the card.

  Generate the vectors from the existing Dart and Python implementations, which agree with each
  other: tools/poslink_protocol.py reproduces the spec's example frame
  `020120000700107b22616d6f756e74223a31323530307dbe65`.
- **Phase 1: Android SDK.**
  - Port the protocol from Dart: lib/core/protocol/*, lib/core/link/link_session.dart, the
    protocol parts of lib/features/cashier/cashier_controller.dart and
    lib/features/terminal/terminal_controller.dart, and lib/data/transaction_store.dart.
  - Move the native transports from android/app/src/main/kotlin/com/example/sunmi_usb/{link,usb,bt}.
  - Port test/frame_test.dart and test/sale_flow_test.dart, and add a small sample app.
  - Done when: unit tests and conformance vectors pass, the sample cashier runs a sale against a
    P3 over USB *and* over Bluetooth, and the SDK is published to mavenLocal.
- **Phase 2: Flutter plugin** wrapping the Android SDK. Rebuild the existing app (or a sample) on
  it. Done when a sale runs on hardware over both links.
- **Phase 3: TypeScript SDK.**
  - Core plus WebUSB (browser) and node-usb (Node/Electron).
  - Try Web Serial over Bluetooth in desktop Chrome.
  - Done when a browser test page on macOS Chrome runs a sale with the P3.
- **Phase 4: .NET SDK.** Bluetooth RFCOMM on Windows first (needs no driver), WinUSB later. Done
  when a console sample runs a sale from a Windows PC.
- **Phase 5:** React Native module, Python SDK (grow it from tools/aoa_probe.py and
  tools/poslink_protocol.py), and the desktop helper. Build each one when an integrator needs it.

## Hardware facts and pitfalls (verified, don't re-discover them)

**P3 over USB (AOA).** Tested on a P3 with SUNMI OS 3.0.39, Android 11, user build.
- Normal USB id is `0E8D:201C`. That is MediaTek's vendor id, so always match VID+PID, never the
  VID alone.
- It reports AOA protocol 2 and switches to accessory mode as `18D1:2D01` (the firmware forces
  ADB on), with developer options and USB debugging both off.
- A full sale was verified with the Mac as USB host (`tools/aoa_probe.py --sale 12500`).

**AOA identity strings.** The host sends manufacturer `SunmiUsb` and model `PosLink`. The
terminal only accepts these (res/xml/accessory_filter.xml). Keep them, or change them deliberately
together with a protocol/version bump.

**USB accessory driver (f_accessory).**
- Reads need a buffer of at least 16 KB.
- Writes block once a few packets are queued and the host isn't reading, and queued frames
  survive app restarts.
- So the design uses:
  - a bounded writer queue (32 frames) and one writer thread per transport;
  - skipped heartbeats while a write is in flight;
  - never a second queued copy of a reliable message.
- Use FileChannel and close it off the main thread: closing wakes the blocked I/O.

**Framing.** A frame whose length is an exact multiple of 64 bytes gets one `0x00` pad byte
(works around USB zero-length packets). Receivers skip non-STX bytes between frames.

**Bluetooth Classic.** Secure RFCOMM, UUID `ac0c0004-cf31-4ff0-828c-7470cd97fc7d`, service name
`POS Link`, devices paired first.
- Verified 2026-11-11 between a P3 MIX (cashier) and the P3 (terminal): the handshake completed.
- Android 12+ needs BLUETOOTH_CONNECT. The terminal's "make visible" needs BLUETOOTH_ADVERTISE.

**Don't build Bluetooth LE for Sunmi terminals.**
- On PCI-mode units (P3 MIX, SUNMI OS 4.1.22, Android 13, PCI PTS 6.x,
  `persist.sys.pci.bluetooth=1`), the firmware stops Android's GATT service right after
  Bluetooth turns on. Every LE scan, advertise and GATT call then hangs, even Google Play
  services'.
- Side note for any Android BLE code: scan registration blocks the calling thread for 2 s and
  openGattServer for up to 10 s.
- A copy of the removed BLE code exists but isn't needed.

**Idea to test.** Also listen on the standard serial-port UUID
`00001101-0000-1000-8000-00805F9B34FB`. Paired PCs would then see the terminal as a serial port
(COMx, /dev/tty.*), and any language could use it with a plain serial library. Check first that
nothing else on the P3 already uses that UUID.

**Platform limits.**
- iPhone/iPad have no USB accessory host, and Bluetooth Classic needs Apple MFi: no local
  connection is possible.
- Windows needs a one-time WinUSB driver install for USB. Bluetooth RFCOMM there needs no driver.
- macOS/Linux USB works with libusb (verified on macOS). Apple-silicon Macs may ask "Allow
  accessory to connect?".

**Sunmi production devices.**
- Apps install only via the SUNMI App Store / partner channel.
- Debug mode needs a Sunmi auth code and can't be undone.
- With developer options on, the P3 draws a "This device at risk. Don't deal!" watermark.

**Payment and money.**
- Payment on the terminal is still a simulator (MockPaymentAdapter). The real processor (bank
  app or Sunmi PayLib) is not chosen yet.
- Money is LYD with 3 decimals. Amounts are integers in minor units (12.500 LYD = 12500).

## Payment safety rules (every SDK must keep them)

1. One charge per txnId: a repeated SALE_REQUEST returns the stored result.
2. One sale at a time: a different txnId during a sale gets ERROR BUSY.
3. Cancel only works before a card is presented.
4. Never assume the outcome. If the link drops, the sale is *unknown*; ask TXN_STATUS_QUERY after
   reconnecting (notFound / inProgress / completed / interrupted).
5. The terminal keeps results until they are ACKed and re-sends unacknowledged ones (last 24 h)
   when the link comes back.
6. Only a masked PAN may cross the link.

Link timings: ACK timeout 1.5 s with 3 retries; heartbeat 3 s; "not responding" after 10 s;
transport reset after 20 s.

## Security (decide before external users depend on v1)

Today, any device on the cable or Bluetooth link can claim to be the terminal and send "approved".
Bluetooth pairing encrypts the radio but doesn't prove which app is talking. Recommended for
protocol v2:
- a one-time pairing code shown on the terminal and entered on the cashier;
- HMAC-SHA256 over every frame;
- replay protection (seq + session).

## Reference code (in the sunmi_usb repo)

- Protocol: lib/core/protocol/frame.dart, messages.dart, payloads.dart
- Link reliability: lib/core/link/link_session.dart, transport.dart, memory_transport.dart (test
  transport with loss, stall and chunking), tcp_transport.dart, native_transport.dart
- Roles: lib/features/cashier/cashier_controller.dart, lib/features/terminal/terminal_controller.dart
- Journal: lib/data/transaction_store.dart. Payment interface: lib/payment/
- Native Android: android/app/src/main/kotlin/com/example/sunmi_usb/
  - link/: plugin, bounded writer, transport interface
  - usb/: AOA host and accessory
  - bt/: Bluetooth Classic client and server, permissions, on/off handling
- Tests: test/frame_test.dart, test/sale_flow_test.dart
- Tools: tools/aoa_probe.py (Mac as USB cashier), tools/poslink_protocol.py

## Working rules

- Explain things in plain, short language.
- The P3 devices are connected to my Mac by USB, and adb works.
  - Installing builds and reading logcat is fine.
  - I test on the devices by hand, so don't send adb input taps or swipes.
- Python: use a venv (Homebrew Python blocks a global pip install).
- Ask before publishing packages, pushing, or making a repo public. Commit only when I ask.
- Test on hardware before calling a transport done, and say clearly what was and wasn't tested.
