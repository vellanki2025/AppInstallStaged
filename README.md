# AppInstall — Staged APK Installer (system-privileged)

A minimal Android app that installs an APK through a **staged**
`PackageInstaller` session. Built on the assumption that it runs with
**system privilege**, so the install is committed silently (no confirmation UI).

## What "staged session" means here

A session created with `SessionParams.setStaged(true)` is written to disk and
committed to apply **atomically on the next reboot**. Staged sessions are the
same mechanism Android uses for APEX and system module updates, and they require
a privileged caller. See `PackageInstallerHelper.installStaged()`.

**This is intentional here: the install does not go live immediately.** A
successful commit means the session is verified and queued; the package only
appears after a reboot. `STATUS_SUCCESS` in `InstallResultReceiver` is reported
as "Applies on next reboot", and `InstallActivity` exposes a **Reboot now**
button (`PowerManager.reboot()`, which needs the privileged `REBOOT` permission)
to apply it on demand. On an unprivileged build that reboot call is denied and
the device must be rebooted manually.

If you ever want an immediate, live install instead, pass `staged = false` to
`installStaged()` — but that is explicitly *not* what this project does.

Flow:

1. `InstallActivity` picks an APK (or is opened on one via a `VIEW` intent).
2. `PackageInstallerHelper` creates a `MODE_FULL_INSTALL` session with
   `setStaged(true)` and, on API 31+, `USER_ACTION_NOT_REQUIRED`.
3. The APK is streamed into `base.apk`, then `session.commit()` fires.
4. `InstallResultReceiver` receives the commit status broadcast.

## The privilege requirement

`INSTALL_PACKAGES` is a `signature|privileged` permission. It is granted **only**
when the app is either:

- signed with the **platform key**, or
- installed into a **privileged system partition** (`/system/priv-app/` or
  `/product/priv-app/`) **and** whitelisted.

A normal store-installed app cannot hold it. In that case the code falls back
automatically: `STATUS_PENDING_USER_ACTION` launches the system install dialog,
so the build still works unprivileged — just not silently.

### Deploying as a privileged system app

1. Build a release APK and sign it with the platform key (or let the ROM sign it).
2. Push it to the privileged partition:
   ```
   adb root && adb remount
   adb push app-release.apk /system/priv-app/AppInstall/AppInstall.apk
   ```
3. Install the permission whitelist:
   ```
   adb push system/etc/permissions/privapp-permissions-com.example.appinstall.xml \
       /system/etc/permissions/
   ```
4. `adb reboot`. Confirm the grant:
   ```
   adb shell dumpsys package com.example.appinstall | grep INSTALL_PACKAGES
   ```

Without step 3 an enforcing build may refuse to boot, by design.

## Build

```
gradle wrapper        # once, to generate gradlew + wrapper jar
./gradlew assembleDebug
```

Requires Android Gradle Plugin 8.5, compileSdk 34, `minSdk 29` (needed for
`setStaged`). The debug build installs via the user-confirmation fallback; the
silent staged path only activates on a privileged deployment.

## Files

| File | Role |
|------|------|
| `InstallActivity.kt` | UI, APK picker, kicks off the install off-thread |
| `PackageInstallerHelper.kt` | Creates the staged session, streams the APK, commits |
| `InstallResultReceiver.kt` | Receives commit status; handles the unprivileged fallback |
| `AndroidManifest.xml` | Declares `INSTALL_PACKAGES` / `DELETE_PACKAGES` / `REBOOT` and the receiver |
| `system/etc/permissions/privapp-permissions-*.xml` | Privileged-permission whitelist for the system image |
