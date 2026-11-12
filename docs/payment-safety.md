# Payment safety checklist

Go through this before real money goes through your integration. The SDK prevents double charges
and fake results on the link. Your app still has to use the results correctly.

## The three rules

1. **One order, one `txnId`.** Create it before the first attempt, save it with the order, and
   reuse it for every retry of that order. The terminal charges a `txnId` at most once. A new
   `txnId` is a new charge.
2. **Only `Completed` with `result.approved` means paid.** Everything else means not paid, except
   `Unknown`.
3. **`Unknown` is not "declined".** The card may have been charged. Don't charge again with a new
   `txnId` until you know. The SDK finds out by itself when the link is back.

## Cashier app

- [ ] Each order stores its `txnId`, written **before** `sale()` is called.
- [ ] Retrying a payment for the same order passes the same `txnId`.
- [ ] Only `SaleOutcome.Completed` with `result.approved == true` marks the order paid.
- [ ] `SaleOutcome.Unknown` puts the order in a visible "needs checking" state, never "failed".
- [ ] `cashier.updates` is collected app-wide, so late results (after a dropped link) update the
      order. See the [cashier guide](android/cashier.md#6-when-the-outcome-is-unknown).
- [ ] Orders in "needs checking" have a **Check payment** action (`cashier.status(txnId)`).
- [ ] Amounts are in minor units, with the right number of decimals for the currency
      (LYD: 3, so 12.500 LYD = `12500`).
- [ ] The receipt uses the values from `SaleResult`: amount, auth code, RRN, masked card number.
- [ ] Production uses `FileJournal` and `KeystorePairingStore`, never the in-memory versions.
- [ ] Pairing asks the user to **type** the code shown on the terminal.

## Terminal app

- [ ] Your `PaymentAdapter` reports `APPROVED` only when the bank approved.
- [ ] It reports `ERROR` or `DECLINED` only when no money moved.
- [ ] It never throws out of its flow (see [known limitations](android/terminal.md#known-limitations)).
- [ ] Only the masked card number leaves the adapter. Never the full number, track data, PIN or
      CVV.
- [ ] Production uses `FileJournal` and `KeystorePairingStore`.
- [ ] **Pair a cashier** sits in a merchant-only screen (behind a PIN, for example).
- [ ] `SimulatedPayment` is not in the production build.
- [ ] On Sunmi production devices, the app is installed through the SUNMI App Store. Production
      devices don't accept other installs.

## Failure drills

Run each drill on the real devices, with the real link (USB or Bluetooth), before going live. Use
test cards, or the simulator first.

| # | Do this | Expected |
|---|---|---|
| 1 | Pull the cable (or turn Bluetooth off) while the terminal says "Present card", then reconnect | The sale goes on, or ends with a clear outcome. No double charge. |
| 2 | Pull the cable during "Authorizing", reconnect after the terminal shows the result | The cashier gets the real result after reconnecting (`updates`, or the running `sale()`) |
| 3 | Force-stop the cashier app mid-payment, then reopen it | The sale is listed as unknown, then resolved once the link is back |
| 4 | Force-stop the terminal app mid-payment, then reopen it | The cashier gets `Unknown` (interrupted). The bank report shows the truth. |
| 5 | Start a second sale while one is running | `Refused` with code `BUSY` |
| 6 | After an approved sale, call `sale()` again with the same `txnId` | The same approved result comes back. The bank report shows one charge. |
| 7 | Cancel before presenting a card, then again after presenting one | First: `CANCELLED`. Second: the payment finishes normally. |
| 8 | Reinstall the terminal app | The cashier shows `UNPAIRED` and a problem message. Pair again, then sales work. |
| 9 | Pair with a wrong code typed on the cashier | Pairing fails. No pairing is saved on either side. |

## What the link protects against

- **A device in the middle** (a USB hub, a cable with a chip, a Bluetooth relay) can't fake a
  result, change an amount or replay an old "approved". Every message is signed with the key from
  pairing and numbered, and anything else is dropped.
- **A stranger can't pair** with the terminal unless someone at the terminal opens pairing and
  confirms the code.
- **Copying the app's files** to another device doesn't copy the pairing. The key that unlocks it
  stays in that device's Android Keystore.

It does not protect against someone who controls an unlocked, already-paired cashier. That person
can start sales like any cashier user, but the customer still has to present a card. Keep the
cashier app behind your normal staff login.
