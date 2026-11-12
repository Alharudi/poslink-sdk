# POS Link SDK

Cross-platform SDK that links a **cashier** app (any platform) to a **payment terminal** app on a
Sunmi P3 over USB or Bluetooth, and runs payments (sale, cancel, status) securely. It grew out of
the `sunmi_usb` Flutter prototype (the sibling folder `../sunmi_usb`,
https://github.com/Alharudi/sunmi_usb). That prototype speaks protocol v1; this SDK speaks v2, and
the two don't interoperate. Full background: docs/BACKGROUND.md.

## Decisions (made by the owner, don't re-open)

- Name **POS Link**, namespace **`ly.dojoon.poslink`**, license **Apache-2.0** (public).
- Security from v1 of the SDK: pairing (6-digit code, P-256 ECDH with commitment) plus
  HMAC-SHA256 on every frame. This is protocol v2.
- One native implementation per ecosystem (Kotlin, TypeScript, C#, Python), with no compiled
  binaries in packages. All must pass `spec/vectors`.
- Same API names everywhere: `sale`, `cancel`, `status`, `pair`; outcomes Completed / Refused / Unknown.
- **Bluetooth = Classic RFCOMM only.** Sunmi PCI-mode terminals switch BLE off in the firmware.
- AOA identity strings: manufacturer `Dojoon`, model `PosLink`, version `2`.

## Layout

- `spec/PROTOCOL.md`: the protocol, the source of truth. Change it first, then the code.
- `spec/vectors/*.json`: conformance vectors, generated. Never edit them by hand.
  Regenerate with `python/.venv/bin/python spec/tools/generate_vectors.py`; CI-style check with `--check`.
- `python/src/poslink/`: reference implementation (frames + crypto); becomes the Python SDK later.
- `android/`: Gradle project; `:poslink` is the Android SDK (AGP 9.1, built-in Kotlin, minSdk 24).
  - `protocol/`, `security/`, `link/`, `cashier/`, `terminal/`, `journal/`, `transport/`: pure
    Kotlin, unit-tested on the JVM.
  - `android/`: USB (AOA host and accessory), Bluetooth (RFCOMM client and server), Keystore
    pairing storage, identity.

## Commands

```bash
cd android && ./gradlew :poslink:testDebugUnitTest      # all JVM tests (vectors, security, sale flows)
cd android && ./gradlew :poslink:assembleRelease        # AAR
cd android && ./gradlew :poslink:publishReleasePublicationToStagingRepository  # local Maven repo in poslink/build/staging-repo
cd android && ./gradlew :poslink:centralBundle          # signed zip for Maven Central (needs a key; see docs/releasing.md)
cd android && ./gradlew :poslink:publishReleasePublicationToPagesRepository -Pposlink.pagesDir=../poslink-pages  # into a gh-pages checkout
python/.venv/bin/python spec/tools/generate_vectors.py --check
uv run docs/tools/build_pdf.py                           # docs as one PDF in docs/build/ (needs Chrome; fails on broken links)
```

Python: always use the venv at `python/.venv`, because Homebrew Python blocks a global pip install.

## Publishing and docs

- Coordinates `ly.dojoon.poslink:poslink-android`; the version is read from `PosLinkInfo.SDK_VERSION`.
- Consumers need Kotlin 2.0+ and compileSdk 34+: keep `apiVersion`/`languageVersion` 2.0, stdlib 2.0.21,
  coroutines `api` 1.9.0 and `minCompileSdk = 34` unless the owner decides otherwise.
- Never upload to Maven Central (or any registry) without the owner's go-ahead. Releases are permanent.
- Repo: github.com/Alharudi/poslink-sdk (public; the owner's account, because dojoon-ly members can't
  create repos). Moving it changes the Pages address, so don't move it without the owner. Apps install from GitHub Pages
  (`https://alharudi.github.io/poslink-sdk/maven`, the `gh-pages` branch). A version on Pages is
  never replaced; any change gets a new version number.
- `docs/` is for app developers. When the public API changes, update `docs/android/*.md` in the
  same change. `DocsExampleTest` is the example in `docs/android/testing.md`; keep them identical.
- The PDF guide is built from the same Markdown. A new page for app developers goes into `CHAPTERS`
  in `docs/tools/build_pdf.py`; rebuild the PDF after doc changes (it checks every link).

## Phases

0 spec + vectors (done) → 1 Android SDK + sample app (in progress) → 2 Flutter plugin wrapping the
Android SDK, then move the sunmi_usb app onto it → 3 TypeScript SDK (WebUSB, node-usb, Web Serial
over Bluetooth) → 4 .NET SDK (Windows Bluetooth first) → 5 React Native, Python, desktop helper.

## Hardware facts (verified; see docs/BACKGROUND.md)

- **P3 (SUNMI OS 3.0.39, Android 11):**
  - normal USB id `0E8D:201C` (MediaTek VID, so always match VID+PID);
  - AOA works with developer options off; it comes back as `18D1:2D01`.
- **P3 MIX (SUNMI OS 4.1.22, Android 13, PCI mode):** BLE is dead (GATT
  service stopped). Bluetooth Classic works: the P3 MIX ↔ P3 link was verified.
- **USB accessory driver:** reads need a 16 KB buffer; writes block when the host isn't reading.
  Hence the bounded writer and the back-pressure rules.
- **Platform limits:**
  - iPhone/iPad can't connect locally.
  - Windows USB needs a WinUSB driver; Windows Bluetooth needs no driver.
- **Sunmi production devices** install apps only via the SUNMI App Store.
- **Money:** amounts are integers in minor units. LYD has 3 decimals (12.500 LYD = 12500).

## Working rules (from the owner)

- Explain in plain, short language.
- P3 devices are on this Mac's USB, and adb works. Installing builds and reading logcat is fine.
  **Never send adb input taps or swipes**: the owner tests by hand on the devices.
- Ask before publishing packages, pushing, creating or making a GitHub repo public. Commit only
  when asked.
- Test on hardware before calling a transport done, and say clearly what was and wasn't tested.
