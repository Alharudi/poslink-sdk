# Terminal guide (Android)

The terminal app runs on the payment device (a Sunmi P3). It waits for a cashier, runs each sale
through your card payment code, and sends the result back. Do the [setup](setup.md) first.

- [1. Create and start](#1-create-and-start)
- [2. Show the payment on screen](#2-show-the-payment-on-screen)
- [3. Pair a cashier](#3-pair-a-cashier)
- [4. Connect your card payment (PaymentAdapter)](#4-connect-your-card-payment-paymentadapter)
- [5. What the SDK enforces for you](#5-what-the-sdk-enforces-for-you)
- [Threads](#threads) · [Known limitations](#known-limitations) · [Reference](#reference)

## 1. Create and start

```kotlin
val terminal = PosLinkTerminal(
    transport = UsbAccessoryTransport(context), // or BluetoothServerTransport(context)
    identity = AndroidIdentity.load(context, name = "Counter 1"), // the name the cashier shows
    pairings = KeystorePairingStore(context),
    journal = FileJournal(File(context.filesDir, "poslink-terminal.json")),
    payment = BankPayment(/* … */),             // your adapter, see step 4; SimulatedPayment() for demos
    info = AndroidIdentity.deviceInfo(appVersion = BuildConfig.VERSION_NAME),
    log = { Log.d("PosLink", it) },
)
terminal.start()
```

Create it **once** for the whole app and keep it running while the app is open. One terminal
serves one link: let the merchant choose USB or Bluetooth in your settings.

`terminal.status` gives the same `LinkStatus` values as on the cashier
([table](cashier.md#2-show-the-connection-status)). `ONLINE` means a paired cashier is connected.

## 2. Show the payment on screen

`terminal.phase` says what the screen should show:

| `TerminalPhase` | Show |
|---|---|
| `Idle` | Your home screen ("Ready") |
| `WaitingCard(request)` | The amount (`request.amount`, `request.currency`) and "Present card", plus a Cancel button |
| `Processing(request, stage)` | "Reading card…", "Enter PIN…", "Authorizing…", "Printing…" |
| `Result(result)` | Approved or declined, with `result.message` |

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        terminal.phase.collect { phase -> render(phase) }
    }
}
```

- The result stays for 6 seconds, then the phase goes back to `Idle`. Call
  `terminal.dismissResult()` to go back sooner.
- A Cancel button calls `terminal.cancelOnTerminal()`. It works only before a card is presented,
  and the cashier then gets outcome `CANCELLED`.

If your bank's payment app takes over the screen during the payment, you may only need `Idle` and
`Result`.

## 3. Pair a cashier

Pairing is done once per cashier. It starts here, on the terminal.

```kotlin
// "Pair a cashier" button in your settings:
pairButton.setOnClickListener { terminal.openPairing() } // open for 2 minutes

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        terminal.pairingRequest.collect { request ->
            if (request == null) {
                closePairingDialog()
            } else {
                showPairingDialog(
                    title = "Pair with ${request.cashierName}?",
                    code = request.code,                 // show it big: the cashier types it
                    onConfirm = { request.confirm() },
                    onReject = { request.reject() },
                )
            }
        }
    }
}

lifecycleScope.launch {
    terminal.pairingResults.collect { result ->
        when (result) {
            is PairingResult.Paired -> showMessage("Paired with ${result.peerName}")
            is PairingResult.Failed -> showMessage(result.message)
        }
    }
}
```

The flow for the person at the counter:

1. Tap **Pair a cashier** here.
2. Start pairing on the cashier. This screen shows a 6-digit code.
3. Type the code on the cashier, then tap **Confirm** here.

Outside the 2-minute window, pairing requests are refused, so nobody can pair while the merchant
isn't looking. `terminal.closePairing()` closes the window early.

`terminal.pairedCashiers()` lists the paired cashiers, and `terminal.forget(cashierDeviceId)`
removes one. A forgotten cashier can no longer take payments until it pairs again.

## 4. Connect your card payment (PaymentAdapter)

`PaymentAdapter` is where the card is actually charged: your bank's payment app, Sunmi PayLib, or
the built-in `SimulatedPayment` while you have none.

```kotlin
interface PaymentAdapter {
    val name: String
    val isSimulator: Boolean get() = false
    fun sale(request: SaleRequest): Flow<PaymentEvent>
    fun cancel(txnId: String): Boolean
}
```

### The rules

- **`sale(request)`** returns a `Flow`. It emits `PaymentEvent.Stage(stage)` as the payment moves
  on, and **ends with exactly one `PaymentEvent.Finished(result)`**.
- **`cancel(txnId)`** returns `true` only if no card has been presented yet. The flow must then
  finish with outcome `CANCELLED`. Return `false` when it's too late.
- **Never block.** Both are called on the SDK's thread. Use suspending calls or `callbackFlow`.
- **Choose the outcome carefully.** The cashier treats every outcome except `APPROVED` as *not
  paid*:
  - `APPROVED`: the bank approved, and money was taken.
  - `DECLINED`: the bank said no.
  - `CANCELLED`: cancelled before a card was read.
  - `TIMEOUT`: no card was presented in time.
  - `ERROR`: it failed, **and you are sure no money moved**.
- **Card data:** put only the masked card number (`476173******0119`) in `SaleResult`. Never the
  full number, track data, PIN or CVV.
- **Amount:** `result.amount` is what was actually charged, in minor units.

### Example

The bank SDK below is made up. Replace it with the one you have.

```kotlin
class BankPayment(private val bank: BankSdk) : PaymentAdapter {
    override val name = "Bank app"

    override fun sale(request: SaleRequest): Flow<PaymentEvent> = callbackFlow {
        trySend(PaymentEvent.Stage(PaymentStage.WAITING_CARD))
        bank.startSale(request.amount, request.currency, request.txnId, object : BankSdk.Callback {
            override fun onCardRead() { trySend(PaymentEvent.Stage(PaymentStage.CARD_READ)) }
            override fun onPinRequested() { trySend(PaymentEvent.Stage(PaymentStage.PIN_ENTRY)) }
            override fun onSentToBank() { trySend(PaymentEvent.Stage(PaymentStage.AUTHORIZING)) }
            override fun onFinished(r: BankSdk.Result) {
                trySend(PaymentEvent.Finished(SaleResult(
                    txnId = request.txnId,
                    outcome = when {
                        r.approved -> Outcome.APPROVED
                        r.cancelled -> Outcome.CANCELLED
                        r.noCard -> Outcome.TIMEOUT
                        else -> Outcome.DECLINED
                    },
                    amount = r.amount,
                    currency = request.currency,
                    responseCode = r.responseCode,
                    message = r.message,
                    authCode = r.authCode,
                    rrn = r.rrn,
                    stan = r.stan,
                    maskedPan = r.maskedPan,
                    cardScheme = r.scheme,
                    terminalId = r.terminalId,
                    merchantId = r.merchantId,
                )))
                close()
            }
        })
        awaitClose { } // a started payment is never abandoned half-way
    }

    override fun cancel(txnId: String): Boolean = bank.cancelIfNoCard(txnId)
}
```

### Try it with the simulator first

```kotlin
val simulator = SimulatedPayment()                 // waits for presentCard(); 60 s card timeout
// or SimulatedPayment(autoOutcome = Outcome.APPROVED) to approve every sale by itself

// "Card: approve" / "Card: decline" buttons on the terminal screen while WaitingCard:
simulator.presentCard(approve = true)
```

`terminal.isSimulator` is `true` with the simulator, so you can show those buttons only then. No
real card is charged.

## 5. What the SDK enforces for you

You don't need code for any of these:

- **One charge per `txnId`.** If a cashier sends the same `txnId` again, the stored result goes
  back and your adapter is not called.
- **One sale at a time.** Another sale meanwhile gets `BUSY`.
- **Results are delivered.** A result the cashier didn't confirm is re-sent when it reconnects
  (last 24 hours).
- **App restart mid-payment.** A payment that was running when the app died is marked unknown
  (`INTERRUPTED`), and the cashier is told to check the bank report.

`terminal.transactions()` lists the journal, newest first. `terminal.updates` emits every change,
for example to show a list of today's payments.

## Threads

| What | Runs on |
|---|---|
| `start`, `openPairing`, `closePairing`, `cancelOnTerminal`, `dismissResult`, `forget` | Any thread; they return at once |
| `request.confirm()`, `request.reject()` | Any thread |
| `PaymentAdapter.sale` (the flow) and `cancel` | The SDK's thread: don't block |
| `StateFlow`s and `SharedFlow`s | Wherever you collect them |

## Known limitations

These hold for version 0.1.0:

- **An adapter can't report "outcome unknown".** If your bank app can end without knowing whether
  the card was charged (for example it crashes after contacting the bank), there is no event for
  that yet. Don't report `DECLINED` or `ERROR` in that case. Show the merchant a message to check
  the bank report.
- **Don't throw from the flow.** Catch errors inside your adapter and finish with an outcome. An
  exception that escapes the flow crashes the app. After the restart, the payment shows as
  interrupted.
- **One link per terminal**, USB or Bluetooth, not both at once.

## Reference

```kotlin
class PosLinkTerminal(
    transport: Transport,
    identity: DeviceIdentity,
    pairings: PairingStore,
    journal: TransactionJournal,
    payment: PaymentAdapter,
    config: LinkConfig = LinkConfig(),
    info: Map<String, Any?> = emptyMap(),
    resultHoldMs: Long = 6000,                   // how long phase stays Result
    log: (String) -> Unit = {},
    // dispatcher, clock, scope: for tests
)

fun start()
suspend fun stop()

val status: StateFlow<LinkStatus>
val phase: StateFlow<TerminalPhase>
val pairingRequest: StateFlow<PairingRequest?> // .cashierName, .code, .confirm(), .reject()
val pairingResults: SharedFlow<PairingResult>
val updates: SharedFlow<TxnRecord>
val isSimulator: Boolean
val link: LinkSession                          // transportState, problem, peer, stats

fun openPairing(windowMs: Long = 120_000)
fun closePairing()
fun pairedCashiers(): List<Pairing>
fun forget(cashierDeviceId: String)
fun cancelOnTerminal()
fun dismissResult()
fun transactions(): List<TxnRecord>
```
