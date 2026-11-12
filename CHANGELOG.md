# Changelog

All notable changes to the POS Link SDKs. Versions follow [Semantic Versioning](https://semver.org):
until 1.0.0, a minor version (0.x.0) may change the API.

## Android 0.1.0 (2026-11-12, pre-release)

First version, protocol v2. Pre-release: not yet tested on the P3 hardware. Hosted on GitHub Pages
(see [setup](docs/android/setup.md#1-add-the-dependency)); not on Maven Central yet.

- Cashier (`PosLinkCashier`): `sale`, `cancel`, `status`, `pair`, with Completed / Refused / Unknown
  outcomes and automatic recovery of unknown outcomes after a dropped link.
- Terminal (`PosLinkTerminal`): one charge per `txnId`, one sale at a time, results re-sent until
  the cashier confirms them, pairing with a 6-digit code, and a `PaymentAdapter` for the card
  payment (`SimulatedPayment` included).
- Security: P-256 pairing with a commitment, plus HMAC-SHA256 and a counter on every frame. Keys
  are wrapped by the Android Keystore.
- Transports: USB (AOA host and accessory), Bluetooth Classic (RFCOMM client and server), TCP, and
  in-memory for tests.
- Known limitations: see [the terminal guide](docs/android/terminal.md#known-limitations).
