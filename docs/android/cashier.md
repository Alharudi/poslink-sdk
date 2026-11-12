# Cashier guide (Android)

The cashier app asks the terminal for payments. Do the [setup](setup.md) first.

- [1. Create and start](#1-create-and-start)
- [2. Show the connection status](#2-show-the-connection-status)
- [3. Pair with the terminal (once)](#3-pair-with-the-terminal-once)
- [4. Take a payment](#4-take-a-payment)
- [5. Handle the outcome](#5-handle-the-outcome)
- [6. When the outcome is Unknown](#6-when-the-outcome-is-unknown)
- [7. Cancel a payment](#7-cancel-a-payment)
- [Threads](#threads) · [Reference](#reference)

## 1. Create and start

```kotlin
val cashier = PosLinkCashier(
    transport = BluetoothClientTransport(context, terminalAddress), // or UsbHostTransport(context)
    identity = AndroidIdentity.load(context, name = "Front desk"),   // the name the terminal shows
    pairings = KeystorePairingStore(context),
    journal = FileJournal(File(context.filesDir, "poslink-cashier.json")),
    info = AndroidIdentity.deviceInfo(appVersion = BuildConfig.VERSION_NAME),
    log = { Log.d("PosLink", it) },
)
cashier.start()
```

Create it **once** for the whole app (see [setup](setup.md#keep-one-instance-for-the-whole-app)).
`start()` returns at once. The link connects in the background and reconnects on its own.
Call `stop()` (a `suspend` function) only when the app no longer needs the terminal.

## 2. Show the connection status

`cashier.status` is a `StateFlow<LinkStatus>`:

| Status | Meaning | Show the user |
|---|---|---|
| `OFFLINE` | No cable, Bluetooth off or out of range, terminal app closed | "Terminal not connected" |
| `HANDSHAKING`, `AUTHENTICATING` | Connected, checking each other | "Connecting…" |
| `UNPAIRED` | The terminal answered, but the two aren't paired | A **Pair** button ([step 3](#3-pair-with-the-terminal-once)) |
| `PAIRING` | A pairing is running | "Pairing…" |
| `ONLINE` | Ready for payments | "Terminal ready" |
| `NOT_RESPONDING` | Was online, the terminal stopped answering for 10 s | "Terminal not responding" |

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        cashier.status.collect { status ->
            payButton.isEnabled = status == LinkStatus.ONLINE
            pairButton.isVisible = status == LinkStatus.UNPAIRED
            statusText.text = describe(status)
        }
    }
}
```

More detail, when you need it:

| Property | What it tells you |
|---|---|
| `cashier.link.transportState` | The raw connection, e.g. `PERMISSION` (ask for Bluetooth permissions), `ERROR: Bluetooth is off…`. `detail` is a sentence you can show. |
| `cashier.link.problem` | Something that blocks the link and that the user must fix, e.g. both devices are set up as cashiers. `null` when all is well. |
| `cashier.link.peer` | The terminal: `name`, `deviceId`, `appVersion`. |
| `cashier.terminalState` | `"idle"` or `"busy"`, as the terminal last reported. |

## 3. Pair with the terminal (once)

A person does two things: opens pairing on the terminal, then types the terminal's code on the cashier.

```kotlin
// When the user taps "Pair" (status is UNPAIRED):
val result = cashier.pair { code ->
    // Runs on the SDK's thread: switch to the main thread for UI.
    val typed = withContext(Dispatchers.Main) {
        askUserToType("Enter the 6-digit code shown on the terminal")
    }
    typed == code
}
when (result) {
    is PairingResult.Paired -> showMessage("Paired with ${result.peerName}")
    is PairingResult.Failed -> showMessage(result.message)
}
```

Tell the user to tap **Pair a cashier** on the terminal *before* tapping Pair here. The terminal
then shows the code and a Confirm button.

**Make the user type the code.** Don't show both codes with a "Do they match?" button: people tap
yes without looking. Typing is what stops a device in the middle.

Your `confirm` block is cancelled if the terminal gives up first (rejected there, or after 2
minutes), so close your dialog when the coroutine is cancelled.

| `result.reason` | Why | What to do |
|---|---|---|
| `NOT_PAIRING` | Pairing isn't open on the terminal | Tap **Pair a cashier** on the terminal, then try again |
| `REJECTED` | Someone tapped Reject, or `confirm` returned `false` | Try again |
| `BAD_CONFIRM` | The codes didn't match | Try again; check you're near the right terminal |
| `TIMEOUT` | Nobody finished within 2 minutes | Try again |
| `BUSY` | The terminal is pairing with another cashier | Wait, then try again |
| `NOT_CONNECTED` | No terminal on the link | Check the cable or Bluetooth |
| `BAD_KEY`, `BAD_COMMIT` | The terminal's answer was invalid | Try again. If it repeats, something is tampering with the link |

The pairing is saved on both devices and survives restarts. `cashier.pairedTerminals()` lists the
saved pairings, and `cashier.forget(terminalDeviceId)` deletes one.

Pair again only when status shows `UNPAIRED` after it worked before. That means one of the two apps
was reinstalled or had its data cleared.

## 4. Take a payment

```kotlin
// Save the txnId with the order BEFORE calling sale(), so you can always ask about it later.
val txnId = order.paymentTxnId ?: UUID.randomUUID().toString().also { saveTxnId(order, it) }

val outcome = cashier.sale(
    amount = 12500,           // 12.500 LYD, in minor units
    currency = "LYD",
    reference = order.number, // optional; shown in logs and reports
    txnId = txnId,
) { event ->
    // Runs on the SDK's thread.
    runOnUiThread { showProgress(event) }
}
```

`sale()` suspends until the payment ends, usually 10 to 60 seconds. Call it from a coroutine
(for example `lifecycleScope.launch { … }`). Only one sale runs at a time per cashier.

Progress events, in order:

| `SaleEvent` | Meaning |
|---|---|
| `Sent(txnId)` | The request is on its way |
| `ReadyToPay` | The terminal shows the amount and waits for the card |
| `Progress(stage)` | `CARD_READ`, `PIN_ENTRY`, `AUTHORIZING`, `PRINTING` |
| `ConnectionLost` | The link dropped mid-payment; the sale keeps waiting |
| `Reconnected` | The link is back; the SDK checks the sale's status |

Cancelling the coroutine that called `sale()` only stops *waiting*. The payment goes on at the
terminal. To cancel the payment itself, see [step 7](#7-cancel-a-payment).

## 5. Handle the outcome

```kotlin
when (outcome) {
    is SaleOutcome.Completed -> {
        val result = outcome.result
        if (result.approved) markPaid(order, result)         // print the receipt
        else showNotPaid(result.outcome, result.message)     // declined, cancelled, timeout, error
    }
    is SaleOutcome.Refused -> showError(outcome.message)     // no money moved: safe to try again
    is SaleOutcome.Unknown -> markNeedsCheck(order, outcome.message) // see step 6
}
```

| Outcome | Money taken? | What to do |
|---|---|---|
| `Completed` with `result.approved` | **Yes** | Mark paid, print the receipt |
| `Completed`, not approved (`DECLINED`, `CANCELLED`, `TIMEOUT`, `ERROR`) | No | Show `result.message`; the customer may pay another way |
| `Refused` | No | Show `message`; trying again is safe |
| `Unknown` | **Maybe** | Don't charge again with a new `txnId`. See [step 6](#6-when-the-outcome-is-unknown) |

`Refused.code` is `BUSY` (terminal busy with another payment), `BAD_REQUEST` (invalid amount,
currency or `txnId`), or `null` (the terminal is not connected, or it never got the sale).

What the result carries (`SaleResult`):

| Field | Example | Notes |
|---|---|---|
| `outcome`, `approved` | `APPROVED`, `true` | |
| `amount`, `currency` | `12500`, `"LYD"` | What was actually charged |
| `responseCode`, `message` | `"00"`, `"Approved"` | From the bank |
| `authCode`, `rrn`, `stan` | `"123456"`, … | For the receipt and for disputes |
| `maskedPan`, `cardScheme` | `"476173******0119"`, `"VISA"` | Never the full card number |
| `terminalId`, `merchantId` | | |
| `timestamp` | epoch milliseconds | |

Any field except `outcome`, `amount` and `currency` can be `null`, depending on the bank and on how
far the payment got.

## 6. When the outcome is Unknown

`Unknown` means the link dropped (or the terminal went silent for 150 s) during the payment. The
card **may have been charged**. Never treat it as declined.

You don't have to poll. When the link comes back, the SDK asks the terminal about every unfinished
sale from the last 24 hours and reports each answer on `cashier.updates`. Collect it once,
app-wide, right after creating the cashier (it doesn't replay past changes):

```kotlin
appScope.launch {
    cashier.updates.collect { record ->
        if (record.state == TxnState.COMPLETED) applyResult(record.txnId, record.result!!)
    }
}
```

To ask now, for example from a "Check payment" button:

```kotlin
val status = cashier.status(txnId) // null if the terminal didn't answer within 10 s
when (status?.state) {
    TxnStatusState.COMPLETED -> applyResult(txnId, status.result!!)
    TxnStatusState.IN_PROGRESS -> showMessage("Still paying on the terminal")
    TxnStatusState.NOT_FOUND -> showMessage("The terminal never got it. No money was taken.")
    TxnStatusState.INTERRUPTED -> showMessage("The terminal restarted during the payment. Check its bank report.")
    null -> showMessage("Can't reach the terminal")
}
```

After an app restart, `cashier.transactions()` still lists the sales whose state is `UNKNOWN`,
so you can show them as "needs checking".

You can also simply call `sale()` again **with the same `txnId`**. The terminal charges a `txnId`
at most once: if it already finished, you get the stored result back and nothing is charged.
A **new** `txnId` is a new charge.

## 7. Cancel a payment

```kotlin
cashier.cancel(txnId) // true when the terminal got the request
```

This works only before the customer presents a card. If it worked, the running `sale()` ends with
`Completed` and outcome `CANCELLED`. If the card was already read, nothing changes: the payment
finishes normally.

## Threads

| What | Runs on | Note |
|---|---|---|
| `sale`, `pair`, `status`, `cancel`, `stop` | Any coroutine (main is fine) | They suspend; they never block |
| The `onEvent` callback of `sale` | The SDK's thread | Switch to main for UI |
| The `confirm` block of `pair` | The SDK's thread | Use `withContext(Dispatchers.Main)` for UI |
| `StateFlow`s and `updates` | Wherever you collect them | |

## Reference

```kotlin
class PosLinkCashier(
    transport: Transport,
    identity: DeviceIdentity,
    pairings: PairingStore,
    journal: TransactionJournal,
    config: LinkConfig = LinkConfig(),          // timings; the defaults are tuned for USB and Bluetooth
    info: Map<String, Any?> = emptyMap(),       // shown to the terminal: AndroidIdentity.deviceInfo()
    noUpdateTimeoutMs: Long = 150_000,          // silence during a sale before it becomes Unknown
    log: (String) -> Unit = {},
    // dispatcher, clock, scope: for tests
)

fun start()
suspend fun stop()

val status: StateFlow<LinkStatus>
val terminalState: StateFlow<String?>          // "idle" / "busy"
val updates: SharedFlow<TxnRecord>             // every journal change, incl. recovered results
val link: LinkSession                          // transportState, problem, peer, stats

suspend fun pair(confirm: suspend (code: String) -> Boolean): PairingResult
fun pairedTerminals(): List<Pairing>
fun forget(terminalDeviceId: String)

suspend fun sale(amount: Long, currency: String, reference: String? = null,
                 txnId: String = UUID.randomUUID().toString(),
                 onEvent: (SaleEvent) -> Unit = {}): SaleOutcome
suspend fun cancel(txnId: String): Boolean
suspend fun status(txnId: String, timeoutMs: Long = 10_000): TxnStatus?
fun transactions(): List<TxnRecord>            // newest first, from the journal
```

The generated API reference (KDoc) is attached to the library, so Android Studio shows it on
hover.
