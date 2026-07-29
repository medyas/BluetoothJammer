# BluetoothJammer (WIP)
Jam/DoS your neighbour's bluetooth speaker/devices for peace!

> **Educational / authorized-testing use only.** Only use this against devices
> you own or are explicitly authorized to test. Interfering with others'
> devices may be illegal in your jurisdiction.

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
Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.1.0 · compileSdk/targetSdk 35 · minSdk 24

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
