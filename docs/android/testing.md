# Testing without devices or cards

You can test most of your integration without a terminal, a cable or a card.

| Level | Use | Needs |
|---|---|---|
| Unit tests on your computer | `MemoryTransportPair` + `SimulatedPayment` | Nothing |
| Two phones or emulators | `TcpServerTransport` / `TcpClientTransport` + `SimulatedPayment` | The same Wi-Fi |
| The real link | USB or Bluetooth + `SimulatedPayment` | Two devices; no card |
| End to end | USB or Bluetooth + your `PaymentAdapter` | Test cards from your bank |

## Unit tests (JVM)

`MemoryTransportPair` is a cable in memory: a cashier and a terminal run in one test, in
milliseconds. Pre-share a key to skip pairing:

```kotlin
@Test fun approvedSale() = runBlocking {
    val wire = MemoryTransportPair()
    val cashierId = DeviceIdentity.random("Front desk")
    val terminalId = DeviceIdentity.random("Test terminal")

    // Skip pairing: give both sides the same key (tests only).
    val key = ByteArray(32) { 7 }
    val cashierPairings = InMemoryPairingStore().apply { put(Pairing(terminalId.idHex, terminalId.name, key, 0)) }
    val terminalPairings = InMemoryPairingStore().apply { put(Pairing(cashierId.idHex, cashierId.name, key, 0)) }

    val terminal = PosLinkTerminal(
        wire.b, terminalId, terminalPairings, InMemoryJournal(),
        SimulatedPayment(autoOutcome = Outcome.APPROVED, stepDelayMs = 10),
    )
    val cashier = PosLinkCashier(wire.a, cashierId, cashierPairings, InMemoryJournal())
    terminal.start()
    cashier.start()

    withTimeout(10_000) { cashier.status.first { it == LinkStatus.ONLINE } }
    val outcome = withTimeout(10_000) { cashier.sale(12500, "LYD") }

    assertTrue(outcome is SaleOutcome.Completed && outcome.result.approved)
    cashier.stop()
    terminal.stop()
}
```

This exact example is also a test in the SDK (`DocsExampleTest`), so it is known to pass.

Android's `org.json` doesn't work in local unit tests, so add the real one to your test
dependencies:

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}
```

### Breaking things on purpose

`MemoryTransport` (`wire.a`, `wire.b`) can misbehave on command, so you can test your app's
handling of bad days:

| Call | Simulates |
|---|---|
| `wire.disconnect()` / `wire.reconnect()` | Pulling the cable and plugging it back in |
| `wire.a.dropNext = 2` | The next 2 sends get lost (the SDK retries them) |
| `wire.a.chunkSize = 20` | Bytes arrive in small pieces, like Bluetooth |
| `wire.b.stall()` / `wire.b.unstall()` | The other side stops reading, like a busy USB accessory |
| `wire.a.interceptor = { bytes -> … }` | A device in the middle that changes or drops bytes |

`SimulatedPayment` options:

| Option | Default | Use |
|---|---|---|
| `autoOutcome` | `null` (wait for `presentCard`) | `Outcome.APPROVED` or `DECLINED` to finish by itself |
| `cardTimeoutMs` | 60 000 | Ends with `TIMEOUT` if no card is presented |
| `stepDelayMs` | 900 | Time per stage (card read, PIN, authorizing). Use 10 in tests. |

### Virtual time

For timeouts that take minutes (the 150-second no-update timeout, the 2-minute pairing window), run
the SDK on `kotlinx-coroutines-test` virtual time. Pass the test's dispatcher, clock and scope:

```kotlin
@Test fun noUpdateBecomesUnknown() = runTest {
    val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    val clock = { testScheduler.currentTime }
    val cashier = PosLinkCashier(
        wire.a, cashierId, cashierPairings, InMemoryJournal(),
        dispatcher = dispatcher, clock = clock, scope = backgroundScope,
    )
    // same for the terminal; then advanceTimeBy(…) instead of waiting
}
```

The SDK's own tests in `android/poslink/src/test` use this pattern, including lost frames, a pulled
cable and tampering.

## Two phones on the same Wi-Fi

```kotlin
// Terminal phone
PosLinkTerminal(TcpServerTransport(8765), /* … */, payment = SimulatedPayment())

// Cashier phone (use the terminal phone's IP address)
PosLinkCashier(TcpClientTransport("192.168.1.50", 8765), /* … */)
```

Add `<uses-permission android:name="android.permission.INTERNET" />` to both apps.

On two emulators on one computer, the cashier connects to `10.0.2.2`, and you forward the port to
the terminal emulator with `adb -s <terminal-emulator> forward tcp:8765 tcp:8765`.

## The sample app

`android/sample` is a small app that plays either role over USB, Bluetooth or Wi-Fi, with the
simulator on the terminal side. Install it on two devices to see a working setup before writing
your own:

```bash
cd android && ./gradlew :sample:installDebug
```

Its source (`MainActivity.kt`) is a compact, complete example of both sides.
