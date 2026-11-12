# POS Link protocol v2

How a **cashier** (the app that asks for a payment, e.g. on a Sunmi Flex 3, a PC or a website) and a
**payment terminal** (the app that takes the card, e.g. on a Sunmi P3) talk over a USB cable,
Bluetooth or TCP. The same bytes travel on every transport.

Version 2 adds pairing and message authentication to version 1 (the `sunmi_usb` prototype). The
two versions don't interoperate; a v2 side reports a v1 peer as "needs an update".

Key words: MUST, SHOULD and MAY are used as in RFC 2119.

## 1. Roles

- The **terminal** waits; the **cashier** connects (USB host, Bluetooth client, TCP client).
- One terminal can be paired with many cashiers, and one cashier with many terminals. A link
  carries one cashier and one terminal at a time.

## 2. Frame

All integers are big-endian.

```
offset  size  field
0       1     STX = 0x02
1       1     version = 0x02
2       1     type
3       2     seq
5       4     counter
9       2     length            (payload bytes, 0…8192)
11      n     payload           (UTF-8 JSON object, or empty)
11+n    16    mac               (all zero in unauthenticated frames)
27+n    2     crc               CRC-16/CCITT-FALSE over bytes 1 … 26+n
```

- A frame is `29 + length` bytes. If that is an exact multiple of 64, the sender appends one
  `0x00` byte. USB bulk pipes otherwise wait for a zero-length packet.
- CRC-16/CCITT-FALSE: polynomial `0x1021`, initial value `0xFFFF`, no reflection, no final XOR.
  Check value for ASCII `123456789` is `0x29B1`.
- **Receivers** rebuild frames from the byte stream, because transports may split or merge them:
  - skip bytes until STX;
  - read the header and wait for the whole frame;
  - on a bad CRC or a length over 8192, drop one byte and look for the next STX.
- A frame with version `0x01` uses the v1 layout: a 7-byte header with length at offset 5, no
  counter, no mac. A receiver SHOULD parse it far enough to skip it and report "the other device
  runs protocol v1".
- **Authenticated frames** carry a counter ≥ 1 and a mac (see 6.3). **Unauthenticated frames**
  carry counter 0 and a zero mac. Section 3 says which types are which.

## 3. Message types

| Code | Name | Direction | Auth | Payload |
|---|---|---|---|---|
| 0x01 | `HELLO` | both | no | see 5.1 |
| 0x02 | `AUTH` | both | **yes** | empty |
| 0x03 | `PING` | both | yes | empty |
| 0x04 | `PONG` | both | yes | empty; `seq` = the PING's seq |
| 0x05 | `ACK` | both | yes | empty; `seq` = the acknowledged frame |
| 0x06 | `NAK` | both | yes | `code` |
| 0x10 | `STATUS` | terminal → cashier | yes | `state`: `idle` / `busy` |
| 0x20 | `SALE_REQUEST` | cashier → terminal | yes | `txnId` (UUID), `amount` (int, minor units), `currency` (ISO 4217), `reference`?, `createdAt` (ISO 8601 UTC) |
| 0x21 | `READY_TO_PAY` | terminal → cashier | yes | `txnId`, `amount`, `currency` |
| 0x22 | `PROGRESS` | terminal → cashier | yes | `txnId`, `stage`: `waitingCard` / `cardRead` / `pinEntry` / `authorizing` / `printing` |
| 0x23 | `SALE_RESULT` | terminal → cashier | yes | `txnId`, `outcome` (`approved` / `declined` / `cancelled` / `timeout` / `error`), `amount`, `currency`, `timestamp`, `responseCode`?, `message`?, `authCode`?, `rrn`?, `stan`?, `maskedPan`?, `cardScheme`?, `terminalId`?, `merchantId`? |
| 0x24 | `CANCEL` | cashier → terminal | yes | `txnId` |
| 0x25 | `TXN_STATUS_QUERY` | cashier → terminal | yes | `txnId` |
| 0x26 | `TXN_STATUS` | terminal → cashier | yes | `txnId`, `state` (`notFound` / `inProgress` / `completed` / `interrupted`), `result`? (a SALE_RESULT payload, when completed), `stage`? |
| 0x30 | `PAIR_REQUEST` | cashier → terminal | no | see 6.1 |
| 0x31 | `PAIR_KEY` | terminal → cashier | no | see 6.1 |
| 0x32 | `PAIR_REVEAL` | cashier → terminal | no | see 6.1 |
| 0x33 | `PAIR_CONFIRM` | both | no | see 6.1 |
| 0x34 | `PAIR_FAIL` | both | no | `code`: `NOT_PAIRING` / `BUSY` / `BAD_COMMIT` / `BAD_KEY` / `BAD_CONFIRM` / `REJECTED` / `TIMEOUT` |
| 0x7F | `ERROR` | both | yes | `code`, `message`, `txnId`? |

- **Link-level** types are `0x01`–`0x06` and `0x30`–`0x34`. They are never ACKed and never retried.
- **Application** types are `0x10`–`0x26` and `0x7F`.
- A receiver MUST drop an authenticated-type frame whose mac or counter fails the check (6.3). It
  MUST also drop any authenticated-type frame received before a session key exists.

Amounts are integers in the currency's minor unit. LYD has 3 decimals, so `12.500 LYD` is `12500`.

Card data rule: only a **masked** PAN may cross the link. Never a full PAN, track data or a PIN.

Error codes in `ERROR` and `NAK`: `BUSY`, `CANNOT_CANCEL`, `UNKNOWN_TXN`, `TXN_INTERRUPTED`,
`BAD_REQUEST`, `UNKNOWN_TYPE`, `BAD_PAYLOAD`, `UNSUPPORTED_VERSION`.

## 4. Identity

Each installation has a **device id**: 16 random bytes created on first use and kept for good,
written as 32 lowercase hex digits. Each device also has a **name** that people can read (e.g.
`SUNMI P3`, `Front desk PC`). Pairings are stored by the peer's device id.

## 5. Link

### 5.1 Handshake

When the transport connects, each side picks a fresh 16-byte random **link nonce** and sends
`HELLO` with `needReply: true` every heartbeat until it receives the peer's HELLO.

`HELLO` payload:

| Field | Meaning |
|---|---|
| `protocol` | `2` |
| `role` | `cashier` / `terminal` |
| `deviceId` | 32 hex digits |
| `name`, `manufacturer`, `model`, `appVersion`, `sdkVersion` | for display and logs |
| `sessionId` | UUID, new each time the app (or SDK instance) starts |
| `nonce` | base64 of the 16-byte link nonce; the same for the whole connection |
| `needReply` | `true` until the peer's HELLO has arrived |
| `known` | only in a reply (`needReply: false`): `true` if the replying side has a pairing for the HELLO's sender |

The receiver of a HELLO:

1. Checks `protocol` and that the roles differ. If not, it reports the problem and goes no further.
2. Answers a `needReply: true` HELLO with its own HELLO (`needReply: false`, plus `known`).
3. If it has a pairing for the peer's `deviceId`, it derives the session key (6.2) from the two
   current nonces and sends `AUTH`. The link is **online** once a valid `AUTH` from the peer
   arrives.
4. If it has no pairing, the link is **unpaired**: only `HELLO`, `PAIR_*` and nothing else.
5. If a reply says `known: false` while this side has a pairing, the peer has lost it (e.g. the app
   was reinstalled). The link is unpaired, and the UI SHOULD say "pair again".

If a HELLO arrives with a different `nonce` than before (the peer reconnected), the receiver MUST
derive a new session key and send `AUTH` again. If it has a different `sessionId`, the peer app
restarted.

### 5.2 Reliability

- Every application frame carries a fresh `seq` (1…65535, wrapping) and MUST be ACKed.
- The sender retries after 1.5 s, up to 3 times. Each retry is a **new** frame: same seq and
  payload, new counter and mac.
- The receiver ACKs duplicates again, but delivers each `seq` only once per peer session. It
  remembers the last 256 seqs.
- `ACK` / `NAK` reuse the seq of the frame they answer. `HELLO`, `AUTH`, `PING` and `PAIR_*` use
  seq 0.

### 5.3 Heartbeat

`PING` every 3 s once online. After 10 s with nothing received, the link is **not responding**;
after 20 s the side resets the transport. During the handshake, `HELLO` takes PING's place.

### 5.4 Back-pressure

A USB accessory write blocks while the host isn't reading, and a Bluetooth write blocks once the
socket's window is full. While any earlier frame is still waiting to leave, a side:

- skips heartbeats (`HELLO`, `PING`, `PONG`);
- doesn't queue a second copy of a message for a retry.

A native writer SHOULD refuse sends past 32 queued frames.

### 5.5 Link states (for the API)

`offline` → `handshaking` → `unpaired` | `authenticating` → `online` ⇄ `notResponding`, and
`pairing` while a pairing runs.

A configuration problem ("both devices are cashiers", "peer runs protocol v1", "security check
failed") is reported next to the state.

## 6. Security

Cryptography used (all available in the standard libraries of Android/Java, browsers (WebCrypto),
Node, .NET 8 and Python):

- ECDH on **P-256** (secp256r1). Public keys travel as 65-byte uncompressed points
  (`0x04 ‖ X ‖ Y`). The shared secret is the 32-byte X coordinate.
- **SHA-256**, **HMAC-SHA256**, and **HKDF-SHA256** (RFC 5869, extract then expand).
- Binary fields in JSON are standard base64 (RFC 4648 §4, with padding).
- `‖` means concatenation. Strings are ASCII bytes. Role bytes: cashier `C` (0x43), terminal
  `T` (0x54).

### 6.1 Pairing

Pairing happens once per cashier–terminal pair. It needs a person who can see both screens.

1. On the terminal, the merchant opens pairing ("Pair a cashier"), which stays open for
   120 seconds. Outside that window the terminal answers `PAIR_REQUEST` with
   `PAIR_FAIL NOT_PAIRING`.
2. **Cashier → `PAIR_REQUEST`**: `{deviceId, name, commit}`, where
   `commit = SHA256(pkC ‖ nC)`. `pkC` is a fresh P-256 public key and `nC` a fresh 16-byte nonce.
3. **Terminal → `PAIR_KEY`**: `{deviceId, name, publicKey: pkT, nonce: nT}`, with its own fresh
   key and 16-byte nonce. If a pairing is already running, it sends `PAIR_FAIL BUSY` instead.
4. **Cashier → `PAIR_REVEAL`**: `{publicKey: pkC, nonce: nC}`. The terminal MUST check
   `SHA256(pkC ‖ nC) = commit`, or else answer `PAIR_FAIL BAD_COMMIT`. Both sides MUST check
   that the peer's key is a valid P-256 point, or else answer `BAD_KEY`.
5. Both sides compute:
   ```
   Z    = ECDH(own private key, peer public key)                 32 bytes
   TH   = SHA256("poslink/2 pair" ‖ idC ‖ idT ‖ pkC ‖ pkT ‖ nC ‖ nT)
          (idC, idT = the 16-byte device ids)
   K    = HKDF(ikm = Z, salt = TH, info = "poslink/2 pairing key", 32)
   code = HKDF(ikm = Z, salt = TH, info = "poslink/2 code", 4)
          read as an unsigned 32-bit integer, mod 1 000 000, written as 6 digits with leading zeros
   ```
6. Both apps show the 6-digit **code**. The person checks that the codes match.
   - On the cashier, apps SHOULD ask the person to **type** the code shown on the terminal, which
     forces a real comparison.
   - On the terminal, the merchant taps Confirm.
   - Each side then sends **`PAIR_CONFIRM`** `{mac}`, where
     `mac = HMAC(K, "poslink/2 confirm" ‖ role byte ‖ TH)` and the role byte is the sender's.
   - Rejecting sends `PAIR_FAIL REJECTED`.
7. A side has **paired** once it has confirmed locally *and* received a valid peer
   `PAIR_CONFIRM` (otherwise `PAIR_FAIL BAD_CONFIRM`). It then stores
   `{peer deviceId, peer name, K, created}`. Both sides then run the normal authentication (5.1
   step 3) on the current link.
8. The pairing fails with `TIMEOUT` if it doesn't finish within 120 s. Keys and nonces from a
   failed pairing MUST be discarded.

**Why this is safe.** The cashier commits to its key before it sees the terminal's. A device in
the middle therefore can't pick keys that make both screens show the same code, and has a
1-in-1 000 000 chance per attempt. K is never sent.

### 6.2 Session key

For each connection, both sides derive:

```
SK = HKDF(ikm = K, salt = nonceCashier ‖ nonceTerminal,
          info = "poslink/2 session" ‖ idC ‖ idT, 32)
```

`nonceCashier` and `nonceTerminal` are the link nonces from the two HELLOs.

### 6.3 Frame authentication

```
mac = first 16 bytes of HMAC(SK, role byte of the sender ‖ bytes 1 … 10+n of the frame)
```

That covers version, type, seq, counter, length and payload.

- Each direction has its own **counter**. It starts at 1 for each new session key and goes up by
  1 with every authenticated frame sent.
- A receiver MUST drop a frame whose mac doesn't match, or whose counter is not greater than the
  last one it accepted in that direction.
- After 5 dropped frames in a row, it SHOULD report "security check failed" and reset the
  transport.

Payloads are authenticated, not encrypted. They hold no secrets: only amounts and a masked PAN.
Bluetooth Classic encrypts the radio link anyway.

### 6.4 Storing keys

Pairing keys MUST be stored in the platform's protected storage where one exists: Android
Keystore, Windows DPAPI, macOS Keychain, or a non-extractable WebCrypto key in IndexedDB. Removing
a pairing deletes K. A terminal SHOULD let the merchant see and remove paired cashiers.

## 7. Payment rules

1. **One charge per `txnId`.** If the terminal gets a `SALE_REQUEST` for a `txnId` it has already
   finished, it re-sends the stored `SALE_RESULT` and does not charge again.
2. **One sale at a time.** A different `txnId` during a running sale gets `ERROR BUSY`.
3. **Cancel** works only before a card is presented; otherwise the answer is `ERROR CANNOT_CANCEL`.
4. **Never assume the outcome.** If the cashier loses the link or gets no answer, the sale is
   *unknown*, not *declined*. When the link is back, the cashier sends `TXN_STATUS_QUERY`:
   - `notFound`: the terminal never got it; no money moved and it is safe to charge again;
   - `inProgress`: wait;
   - `completed`: apply the included result;
   - `interrupted`: the terminal app restarted mid-payment; check the terminal's report by hand.
5. **Result delivery.** The terminal keeps every result until the cashier ACKs it, and re-sends
   unacknowledged results from the last 24 h when the link comes back.

## 8. Transports

Each transport is an ordered byte pipe. The terminal always waits and the cashier connects.

### 8.1 USB (Android Open Accessory)

- The cashier is the USB host; the terminal is the AOA accessory.
- In the AOA handshake the host sends manufacturer `Dojoon`, model `PosLink`, description
  `POS Link payment terminal link`, version `2`, URI (optional) and serial `0001`. The terminal
  accepts only that manufacturer and model.
- Data uses the accessory's bulk endpoint pair. An accessory read MUST use a buffer of at least
  16 KB.
- After the switch the device appears as `18D1:2D00` or `18D1:2D01` (accessory, or accessory + ADB).
- **Sunmi P3** normal-mode id is `0E8D:201C`. That is MediaTek's vendor id, so always match vendor
  and product id together.

### 8.2 Bluetooth Classic (RFCOMM)

- The terminal listens on a secure RFCOMM service record: UUID
  `ac0c0005-cf31-4ff0-828c-7470cd97fc7d`, name `POS Link`.
- Protocol v1 used `ac0c0004-…`. The UUID changed with the version, so a terminal can run a v1 and
  a v2 app side by side without a cashier reaching the wrong one.
- The devices must be Bluetooth-paired first. That is separate from POS Link pairing (6.1).
- One cashier at a time: a new connection replaces the old one.

Bluetooth LE is not used. Sunmi terminals in PCI mode (`persist.sys.pci.bluetooth=1`) stop
Android's GATT service as soon as Bluetooth turns on.

### 8.3 TCP

The terminal listens (default port 8765); the cashier connects. For development and LAN setups.

## 9. Test vectors

`spec/vectors/` holds the conformance vectors. Every implementation MUST reproduce them:

- `frames.json`: encoded frames, both unauthenticated and authenticated, including the padding
  case.
- `crypto.json`: a full pairing (fixed keys and nonces → TH, Z, K, code, confirm macs), a session
  key, and a frame mac.

They are generated by `python/` (the reference implementation), using `spec/tools/generate_vectors.py`.
