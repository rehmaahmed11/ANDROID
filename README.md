# Personas — multi-profile for a phone whose firmware hides multi-user

**Read this first, because it changes what "success" means.**

Android's *Users* feature is not an app-level switch. It lives in `system_server`
(`UserManagerService`), it is gated by the signature-level permissions `CREATE_USERS` /
`MANAGE_USERS`, and on your phone the manufacturer capped it with the firmware overlay
`config_multiuserMaximumUsers = 1`. A sideloaded APK on a stock, unrooted Android 9 device
**cannot create a second Android user and cannot change that overlay.** No app can — that is the
platform, not the app. Anyone selling you an APK that claims otherwise is either asking for root
or quietly faking it.

What this app does instead, all inside what an unprivileged app is allowed to do:

| | |
| --- | --- |
| **Does** | Up to 8 named profiles in one install, each with its own **AES-256-GCM encrypted** journal under its own key; optional per-profile PIN (key never stored, no recovery); auto-lock; **one home-screen icon per profile** (pinned shortcuts); a device analysis screen that reads your firmware's real limits; an adb-evidence importer; and an **isolation self-test you can run on the phone**. |
| **Does not** | Create real Android users; give two profiles separate copies of WhatsApp/Signal; separate notifications, accounts or app lists; survive someone with your unlocked phone and your PIN; bypass `fw.max_users`. |
| **Maybe enables** | A **managed (work) profile** — a genuine second Android user with separate apps and encryption, no root needed, if your firmware kept `android.software.managed_users`. The app checks, reports, and then hands you to the system's own provisioning flow. See [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) §4. |

Requires Android 8.0+ (API 26). Built and tested for Android 9. No root, no custom recovery, no
settings change, nothing written outside the app's own data directory.

**The manifest declares no permissions.** The only `uses-permission` on the built APK is
`com.rehma.personas.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which the AndroidX build tools
inject so that the library's own receivers stay unexported on newer APIs: it is defined by
this app, granted to this app, and requests nothing from the user. `aapt2 dump badging` output
proving this is published by CI on every build (see §6).

---

## 1. Get the APK

Two ways, both produced by CI so the build is reproducible from this repo:

**A. Rolling release (easiest, phone only)**

1. Open <https://github.com/rehmaahmed11/ANDROID/releases/tag/sideload-latest>
2. Download `app-release.apk` (and `SHA256SUMS.txt`).

**B. Workflow artifact (any branch / PR)**

GitHub ▸ this repo ▸ **Actions** ▸ latest *Android build* run ▸ **Artifacts** ▸ `personas-apk`.

Verify the file before installing it — on the phone with [a checksum viewer] or on a computer:

```bash
sha256sum app-release.apk
# must match the line in SHA256SUMS.txt from the same release / artifact
```

If the hashes differ, delete the file: something between GitHub and your phone is not this project.

## 2. Install it

Do this on the phone you want the profiles on. **If you plan to use more than one Android user,
repeat step 4 for every user** — an APK installed for user 0 is invisible to user 10.

1. **Settings ▸ Biometrics and security (or Passwords & security) ▸ Install unknown apps ▸
   your browser (or Files) ▸ allow "Install unknown apps".** On stock AOSP Android 9 this is
   *Settings ▸ Apps & notifications ▸ Advanced ▸ Special app access ▸ Install unknown apps*.
   If your OEM has no such screen, sideload with adb instead (step 4).
2. Tap the downloaded `app-release.apk` in the notification shade or in **Files ▸ Downloads**.
3. Read the permission list — it is empty by design — and tap **Install**.
4. **adb route (works even when the OEM hides everything):** on a computer with platform-tools,
   phone in *Settings ▸ Developer options ▸ USB debugging*, then
   ```bash
   adb devices               # authorise the popup on the phone once
   adb install -r app-release.apk
   # for a second Android user, after switching to it or by id:
   adb shell pm install-existing --user <id> com.rehma.personas
   ```
5. Open **Personas** from the app drawer.

Uninstall is *Settings ▸ Apps ▸ Personas ▸ Uninstall*; nothing else on the device was touched.
Deleting a profile inside the app erases only that profile's files.

## 3. First run: 60 seconds

1. **New profile** → name it, pick a colour, and set a **PIN if this profile must be private**.
   The PIN is what turns "hidden behind a menu" into "unreadable ciphertext": the key is derived
   from it and never stored, so a lost PIN means the data is gone. That is the design, not a bug.
2. Repeat per person. Long-press a row for **Pin icon to home screen** — one icon per profile that
   opens straight into that profile.
3. **Device analysis** ▸ section 3: **Run isolation self-test**. 10/10 PASS means the isolation
   behaves as documented *on your handset*. Any FAIL: do not trust the profiles on that device.
4. For a definitive answer about your firmware: copy the command from section 2, run it on a
   computer, paste the output back, tap **Evaluate**. The app then states which route your phone
   allows. Rows it could not read stay `UNKNOWN` on purpose.

## 4. If you want real system users (the part no app can do)

Run `scripts/multiuser-check.sh` (or `.bat`) with the phone connected, and look at
`@maxusers`:

* **greater than 1** → the OS supports users; only the Settings menu is hidden. Create them from a
  computer:
  ```bash
  adb shell pm create-user "Second person"      # ceiling permits it
  adb shell pm list users                       # note the new id, usually 10
  adb shell am switch-user 10
  ```
  Still missing from Settings on some skins: `adb shell am start -a android.settings.USER_SETTINGS`
  or `am start -n com.android.settings/.Settings\$UserSettingsActivity`.
* **equals 1** → every path to a second full user is closed on a `user` build: `pm create-user`
  fails with *Maximum user limit is reached*, `setprop fw.max_users N` needs root, `adb root` is
  refused on production builds, and `/system` is read-only with no custom recovery to remount it.
  The only things left are the managed profile (§4 of the feasibility doc) and the profiles in
  this app. On many Samsung/MIUI Android 9 builds one more trick works and is worth knowing about
  because it is *temporary* rather than private: `adb shell pm create-user --guest Guest`.

## 5. Building from source

```bash
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
./gradlew testDebugUnitTest            # the isolation proofs, on the JVM
./gradlew assembleDebug assembleRelease
# app/build/outputs/apk/{debug,release}/ + SHA256SUMS.txt
```

`keystore.properties` in the repo root (git-ignored) with `storeFile/storePassword/keyAlias/
keyPassword` switches `assembleRelease` to your own signing key; without it the release APK is
debug-signed, which is all that sideloading onto your own phone needs.

Toolchain: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7 (wrapper jar checked in — the only file that
cannot be regenerated offline), JDK 17, `compileSdk 34`, `minSdk 26`, `targetSdk 28`.
`targetSdk 28` is deliberate: it matches the OS generation this project is written for, and keeps
scoped-storage and foreground-service churn out of a single-device utility.

## 6. CI: what is actually verified

Run 17 of [`Android build`](https://github.com/rehmaahmed11/ANDROID/actions/runs/39063307375)
(commit `28e7674`) finished **success** with every step green. Concretely, on the runner:

| check | result |
| --- | --- |
| `testDebugUnitTest` | 39 JUnit methods green, incl. the 10-case `IsolationSelfTest`, PBKDF2 known-answer vectors, codec tamper/tolerance cases, diagnostics verdicts |
| `assembleDebug` + `assembleRelease` | `app-debug.apk` 5,663,527 B · `app-release.apk` 4,615,724 B |
| `aapt2 dump badging` on the release APK | `package: name='com.rehma.personas' versionCode='1' versionName='1.0.0'`, `sdkVersion:'26'`, `targetSdkVersion:'28'`, `launchable-activity: com.rehma.personas.ui.MainActivity`, no permissions of ours |
| `apksigner verify --print-certs` | `Verifies`, `Verified using v2 scheme (APK Signature Scheme v2): true`, 1 signer, `CN=Android Debug` |
| published SHA-256 | release `c379568dfab5af86dc0cb4acd9456f1fae0ee559f77360fa98178135f582699a` |

Two defects were found *by* that verification and fixed rather than shipped: the release variant was
building **unsigned** (`app-release-unsigned.apk`, `DOES NOT VERIFY: Missing META-INF/MANIFEST.MF`) —
an APK that cannot install — and `UserManager.isManagedProfile()` was called as if it were API 24
when it is public from API 30. The verify step now exits non-zero if any published APK fails
`apksigner verify`, so the first class of mistake cannot come back quietly.

**Still not verified:** on-device behaviour on a real Android 9 handset (no device is attached to
the build environment). That gap is why the app ships the self-test and the adb-evidence screen.

## 7. Workflow definition

[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml) runs on **push to
`main`** (i.e. on merge), on every PR into `main`, and on `workflow_dispatch`:

1. JDK 17 + a pinned Android SDK install via `scripts/install-android-sdk.sh`;
2. `testDebugUnitTest` — the crypto/vault/codec/verdict tests, including the 10-case isolation
   self-test that also runs on the phone;
3. `assembleDebug assembleRelease` → `dist/` + `SHA256SUMS.txt`;
4. artifacts `personas-apk` and `personas-apk-sha256` (30/90-day retention), a job summary table,
   and on `main` only: a **rolling `sideload-latest` GitHub release** carrying both APKs and the
   checksums, so there is one stable download URL.

The workflow has no signing secret, so it *cannot* publish an APK that impersonates a store upload;
it is a sideload build. That is stated in the release notes as well as here.

## 8. Layout

```
app/src/main/java/com/rehma/personas/
  crypto/     Crypto.kt (PBKDF2/HKDF/AES-GCM) · PersonaVault.kt · VaultStorage.kt
              KeyWrapper.kt · AndroidKeyStoreWrapper.kt (+ file fallback)
  data/       Persona.kt · PersonasCodec · PersonaStore.kt · Journal.kt
  system/     DeviceProbe.kt (firmware analysis) · PersonaAdminReceiver.kt (managed profile)
  report/     IsolationSelfTest.kt · AdbDiagnostics.kt (parse → verdict → plan)
  ui/         MainActivity · PersonaEditorActivity · UnlockActivity · JournalActivity
              DiagnosticsActivity · WorkProfileActivity · PersonaShortcuts
app/src/test/ CryptoTest · PersonaVaultTest · DataLayerTest · ReportTest
docs/FEASIBILITY.md   platform analysis, rejected approaches, exact limits
scripts/              multiuser-check.sh/.bat · install-android-sdk.sh
```

Everything under `crypto/`, `data/` and `report/` is pure Kotlin with no Android imports — which
is what lets CI prove the isolation properties on a plain JVM instead of asserting them in prose.
