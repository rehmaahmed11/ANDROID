# Feasibility analysis: multiple user profiles on a locked-down Android 9 phone

Target device as described: **Android 9 (API 28), no root, no custom recovery, completely stock
firmware, and the manufacturer has removed the multi-user feature from Settings.**

This document records what the platform actually permits, what this app therefore implements,
and what remains impossible. It is written as a set of claims with the mechanism behind each one,
because "the app can't do that" is only useful if you know *why*.

---

## 1. How Android multi-user really works

Android's users are not an app feature. They are accounts in the **system server**
(`UserManagerService`), each with its own `userId`, its own `/data/user/<id>`, its own
instance of every installed app, its own key-ring and its own storage encryption keys.
Switching user stops one set of processes and starts another.

Two firmware values decide whether a phone has this at all, and both are compile-time resource
overlays in `frameworks/base/core/res/res/values/config.xml`:

| Overlay value | Meaning |
| --- | --- |
| `config_multiuserMaximumUsers` | ceiling on the number of users the system will create. **Default 1 = single-user device.** |
| `config_enableMultiUserUI` | whether Settings draws the Users screens at all. **Default false.** |

`UserManager` consults them like this (AOSP):

```java
public static int getMaxSupportedUsers() {
    return Math.max(1, SystemProperties.getInt("fw.max_users",
            Resources.getSystem().getInteger(R.integer.config_multiuserMaximumUsers)));
}
public static boolean supportsMultipleUsers() {
    return getMaxSupportedUsers() > 1
            && SystemProperties.getBoolean("fw.show_multiuserui",
                   Resources.getSystem().getBoolean(R.bool.config_enableMultiUserUI));
}
```

Source: [AOSP `UserManager.java`](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/UserManager.java),
[AOSP "Support multiple users"](https://source.android.com/docs/devices/admin/multi-user).

Consequences for this project:

* "The manufacturer disabled multiple users" means they built the firmware with
  `config_multiuserMaximumUsers = 1` (and often `fw.max_users=1`, `fw.show_multiuserui=0` in
  `build.prop`). The ceiling is enforced in `system_server`, **not** in the Settings app.
  Hiding a menu row is a cosmetic act; the number behind it is the actual policy.
* `fw.max_users` is the one override that could raise the ceiling at runtime, and setting it
  requires writing to the property service as root. On a `user` build, `adb root` refuses
  ("adbd cannot run as root in production builds"), so the property cannot be set. There is no
  other supported path to it. Reading it is possible, and the app does read it, so you can see
  the value for yourself.
* `MAX-users` is verifiable without root: `adb shell pm get-max-users` prints the ceiling. That
  single number decides which half of this document applies to your phone.

## 2. Why an installed app cannot create a user

The APIs that would do it — `UserManager.createUser(...)`, `createProfileForUser(...)`,
`removeUser(...)`, and even the read-only `getUserCount()` / `getMaxSupportedUsers()` — are
annotated `@hide` **and** guarded by:

| Permission | Protection level | Can a sideloaded app hold it? |
| --- | --- | --- |
| `android.permission.CREATE_USERS` | `signature\|privileged` (system app only) | No |
| `android.permission.MANAGE_USERS` | `signature\|system` | No |
| `android.permission.INTERACT_ACROSS_USERS_FULL` | `signature` | No |

`signature` means the caller must be signed with the key that signed the platform — the OEM's
build key. `adb shell pm grant` cannot fix this: `pm grant` only moves *runtime* (dangerous /
development) permissions, and refuses signature-level ones. The permissions are therefore
unreachable for any APK you or I can build.

`shell` (what adb gives you) does hold `CREATE_USERS`, which is exactly why
`adb shell pm create-user` works on some devices and *still* fails on yours: it reaches the same
`UserManagerService` ceiling and returns

```
Error: android.os.ServiceSpecificException: Cannot add user. Maximum user limit is reached. (code 6)
```

The app cannot run that command for you either: since API 21 an app may not spawn a shell, and
before that it needed root. So Personas prints the commands, and you run them.

## 3. Options that were considered and rejected

| Approach | Verdict | Reason |
| --- | --- | --- |
| `pm create-user` via `Runtime.exec()` | **impossible** | no shell exec for unprivileged apps; `exec` of `pm` runs as the app's own UID and is denied `CREATE_USERS` |
| Requesting `CREATE_USERS`/`MANAGE_USERS` in the manifest | **impossible** | signature-level: install-time grant fails, `pm grant` refuses. Declaring them would also change nothing about behaviour |
| Reading `UserManager.getUserCount()` reflectively | **rejected as a foundation** | hidden-API greylist on Android 9 (works with a warning), still `SecurityException` without `MANAGE_USERS`. The app reports the *attempt* honestly under `UNKNOWN` rather than depending on it |
| `setprop fw.max_users N` / editing `/system/build.prop` | **impossible without root** | needs root or a repartitioned, writable `/system`; the brief rules both out |
| Magisk module | **out of scope** | requires an unlocked bootloader + custom recovery, explicitly excluded |
| App cloners / "Parallel Space", Samsung Secure Folder, Xiaomi Dual Apps | **rejected** | cloners fake a second instance through accessibility tricks with no data isolation and are hostile territory for account security; Secure Folder / Dual Apps are OEM features that are *also* unavailable on this firmware (they are the same `config_user_types` machinery) |
| `DevicePolicyManager.setLogoutEnabled`, `DISALLOW_USER_SWITCH` etc. | **n/a** | device-owner-only APIs |
| Work profile via `ACTION_PROVISION_MANAGED_PROFILE` | **kept, with conditions** | the only system-enforced separation an ordinary app may legitimately trigger — see §4 |
| Per-persona encrypted storage inside one install | **implemented** | fully within app privileges; gives real data separation, not process separation |

## 4. The one legitimate system-level route: managed profile

`DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE` asks the platform to create a *managed
profile* — a second Android user with its own app instances, its own encryption, badged icons and
a separate Settings entry, and *no* extra privileges for the calling app beyond profile ownership.
It needs no root, no custom recovery and no unsigned firmware. It works on stock Android 9
**provided** the firmware kept `android.software.managed_users`.

Honest constraints, all of them checked by the app and shown on its Work-profile screen:

* `PackageManager.hasSystemFeature("android.software.managed_users")` must be true. Some OEMs
  strip it along with the user UI; if it is gone, this route is gone.
* `DevicePolicyManager.isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE)` must be true.
  It is false if a managed profile already exists (only one per parent user on Android 9), if a
  device owner set `DISALLOW_ADD_MANAGED_PROFILE`, or if the device is not in a state that allows
  provisioning.
* The user must complete the system's own provisioning dialog; the app cannot click it for them.
* Profile ownership is sticky and awkward: "Forget the managed profile" is the way out, and it
  wipes everything inside. This is a *work* profile, whose design goal is separable-and-wipeable,
  not "a second person's private space". It also badges icons with a briefcase.
* Becoming **device** owner instead would unlock the full user-management API surface, but
  `adb shell dpm set-device-owner` refuses to run while any account (Google, OEM) is on the
  device — i.e. it requires a factory reset first. The app deliberately does not ask for that
  role; the Work-profile screen prints the command only so you can see the boundary.

So: if `pm get-max-users` reports more than 1, use real system users and throw this app away —
they are strictly better. If it reports 1 and `managed_users` is present, the managed profile is
your best available approximation. If both are gone, per-persona encryption in this app is what
is left.

## 5. What this app actually provides

1. **Profile registry** (`PersonaStore`) — up to 8 named personas with colour and label, in
   app-private storage.
2. **Per-persona encrypted vault** (`PersonaVault`, `Crypto`) — AES-256-GCM blobs, one file per
   persona, each under its own key:
   * PIN set → key = PBKDF2-HMAC-SHA256(PIN, 16-byte salt, 150 000 iterations) → HKDF-SHA256
     expand into independent data/verifier sub-keys. The key is never stored anywhere.
   * no PIN → key = random 32 bytes, stored wrapped by a **non-exportable AndroidKeyStore** key
     (a file-backed fallback wrapper is used if the platform keystore is broken, and the UI says
     so instead of pretending otherwise).
   * Every blob carries the persona id as **AAD**, so a ciphertext file copied from profile A's
     slot into profile B's does not decrypt. Tampering fails the GCM tag. There is no "forgot
     PIN": the recovery path would be the backdoor.
3. **Switching UX** — tap to open a persona, auto-lock after N seconds in the background, keys
   held in memory only.
4. **Per-profile home-screen icons** via pinned launcher shortcuts (`ShortcutManagerCompat`) on
   API 26+, one icon per persona that opens straight into that vault. This is the closest legal
   stand-in for "a second app icon for a second person".
5. **Device analysis** (`DeviceProbe`) — reads `config_multiuserMaximumUsers`,
   `config_enableMultiUserUI`, `fw.max_users`, the `managed_users` feature flag, the current
   Android user id (derived from the app UID, no hidden API), the `DISALLOW_ADD_USER`
   restriction, and whether this app holds `CREATE_USERS`/`MANAGE_USERS`. Each row is stamped
   `AVAILABLE`/`LIMITED`/`BLOCKED`/`UNKNOWN`. Hidden-API reads are wrapped: where Android 9
   blocks them the row says `UNKNOWN`, which is the truth rather than a guess.
6. **Evidence collection** (`AdbDiagnostics`) — you run `scripts/multiuser-check.sh` and paste
   the output; the app parses it and states which route is open. It never claims a result it
   could not read.
7. **Isolation self-test** (`IsolationSelfTest`) — 10 executable cases (cross-profile
   unreadability, ciphertext-swap rejection, bit-flip detection, wrong-PIN rejection, key
   rotation, wipe completeness, codec robustness). **The same 10 run in CI as unit tests and can
   be re-run on the phone** from Diagnostics ▸ section 3, so a claim about behaviour on your
   specific crypto provider is checkable on that device.

## 6. Limits of what this app gives you

These are Android's limits, not laziness:

* **One process, one UID, one notification shade.** Personas profiles are not separate Linux
  users. Two personas cannot have separate WhatsApp/Signal installs, separate homescreen apps,
  separate ringtones or separate account logins — those live outside this app's reach by design.
* **Not a hostile-tenant boundary.** Data is isolated from *another persona in this app*
  cryptographically, and from *other apps* by Android's normal app sandbox. It is not protected
  against someone with an unlocked phone, ADB-based extraction of the app dir (which is denied to
  `shell` for app-private files on a `user` build), or a hardware attacker with the PIN.
* **A determined adult with physical access can read an unlocked profile.** The mitigation is a
  PIN plus auto-lock, and PINs are per-persona and never stored.
* **No kernel/`/data` separation**, so one profile's journal and another's share a filesystem and
  an inode namespace; only the keys differ.
* **No system-level enforcement of "person 2 cannot see person 1's apps"** — that is what the
  managed-profile route in §4 is for, if your firmware permits it.

## 7. Verification status

What has actually been executed at the time of writing:

* unit tests (`./gradlew testDebugUnitTest`) — run by CI on every push/PR; the badge and the
  latest run in **Actions** are the evidence, not this file;
* `assembleDebug` / `assembleRelease` — CI-produced APKs with published SHA-256;
* behaviour on a physical Android 9 handset — **not verified here**; no device is attached to
  this environment. That is why the app ships the self-test and the adb evidence screen: they
  move the "is this true on *my* phone?" question from my word to your screen.
