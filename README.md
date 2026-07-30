# BluetoothJammer (WIP)
A tool for testing a Bluetooth device's resilience to RFCOMM connection flooding, in a
controlled environment.

> **Authorized testing only.** Only use this against devices you own or are explicitly
> authorized to test, in a controlled environment. Interfering with devices you don't own
> may be illegal in your jurisdiction.

# Preview
<table style="padding:10px">
  <tr>
    <td>
        <img src="./assets/attack.png" alt="1">
    </td>
   </tr>
</table>

# Build
APKs are built on GitHub Actions — no local Android SDK required to get a build.

- **CI:** every push and pull request runs lint, unit tests, and builds the
  debug & release APKs (`.github/workflows/android-build.yml`). Artifacts are
  attached to each run.
- **Releases:** pushing a `v*` tag builds and publishes a GitHub Release with
  the release APK (`.github/workflows/release.yml`).

To build locally instead:
```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
```

### Toolchain
Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.3.20 · compileSdk/targetSdk 35 · minSdk 29

### Architecture
`MainActivity`/`AttackActivity` are thin views over `MainViewModel`/`AttackViewModel`, so an
on-screen attack run survives configuration changes (e.g. rotation). The attack engine
(`api.AttackEngine`) talks to an `RfcommConnection` seam rather than `BluetoothSocket`
directly, which keeps its worker/retry/stop/stats logic unit-testable off-device.

# TODO
- [X] Material UI
- [X] Thread Option
- [X] Devices List — now discovers nearby (non-paired) devices, de-duplicated
- [X] Log Switch
- [X] Start/Stop button — stop now reliably cancels the attack coroutines
- [X] Auto randomize UUID
- [X] Optimize Attack Thread
- [ ] Release signing with a real keystore (currently debug-signed)
- [ ] ProGuard/R8 shrinking for release builds
- [ ] Migrate deprecated `startActivityForResult` to the Activity Result API

# Special Thanks
- ChatGPT-4o
- Original project by [eikarna](https://github.com/eikarna/BluetoothJammer)
