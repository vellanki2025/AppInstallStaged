# AppInstall — Staged APK Installer (system-privileged)

A minimal Android app that installs an APK through a **staged**
`PackageInstaller` session. Built on the assumption that it runs with
**system privilege**, so the install is committed silently (no confirmation UI).

## Background

This app is the final step of a provisioning pipeline, not the whole thing:

- **The APKs are already downloaded.** Some upstream component (an OTA/fleet
  agent, a provisioning service, a USB load, etc.) fetches the packages and
  places them in the drop folder. This app does **not** download anything; it
  only stages whatever files are already present.
- **The APKs are already signed and trusted.** They are expected to be signed
  with a key the platform accepts (typically the platform/OEM key). This app
  does not verify or re-sign them — signature enforcement is the platform's job
  at install time.
- **No runtime permissions are requested or granted.** Because the app runs
  system-privileged (platform-signed or in `priv-app` with the whitelist) and
  reads from its own app-specific directory, there are no runtime permission
  prompts and nothing for a user to approve. `INSTALL_PACKAGES`, `DELETE_PACKAGES`,
  and `REBOOT` are install-time privileged grants, not user-facing dialogs, and
  the drop folder needs no storage permission at all.

In short: packages arrive pre-downloaded and pre-signed, and this app silently
stages them for the next reboot with zero user interaction.

## What "staged session" means here

A session created with `SessionParams.setStaged(true)` is written to disk and
committed to apply **atomically on the next reboot**. Staged sessions are the
same mechanism Android uses for APEX and system module updates, and they require
a privileged caller. See `PackageInstallerHelper.installFilesStaged()`.

**This is intentional here: the install does not go live immediately.** A
successful commit means the session is verified and queued; the package only
appears after a reboot. `STATUS_SUCCESS` in `InstallResultReceiver` is reported
as "Applies on next reboot", and `InstallActivity` exposes a **Reboot now**
button (`PowerManager.reboot()`, which needs the privileged `REBOOT` permission)
to apply it on demand. On an unprivileged build that reboot call is denied and
the device must be rebooted manually.

If you ever want an immediate, live install instead, drop the `setStaged(true)`
call in `PackageInstallerHelper.baseParams()` — but that is explicitly *not* what
this project does.

Flow (Automotive / AAOS provisioning):

1. APK files are dropped into a known folder on the vehicle (see **Drop folder**).
2. On launch, `InstallActivity` scans that folder for `*.apk`.
3. `PackageInstallerHelper` stages them: one APK -> a single staged session;
   several APKs -> one **multi-package** staged parent session with a child per
   APK, so they all apply together. On API 31+ `USER_ACTION_NOT_REQUIRED` is set.
4. `session.commit()` fires; `InstallResultReceiver` receives the status.
5. The packages install on the **next reboot** — tap **Reboot now** (or reboot
   the vehicle) to apply.

## Drop folder

Default (no storage permission needed):

```
/sdcard/Android/data/com.example.appinstall/files/staged-apks/
```

Push one or more APKs there, then launch the app (or tap **Rescan folder**):

```
adb push app1.apk /sdcard/Android/data/com.example.appinstall/files/staged-apks/
adb push app2.apk /sdcard/Android/data/com.example.appinstall/files/staged-apks/
```

The folder is created on first launch, so push after the app is installed. To
use a provisioned vendor location instead, set `PackageInstallerHelper.apkDirOverride`
to an absolute path (e.g. `/data/vendor/appinstall/apks`); that directory must
already exist and be readable by this app's SELinux domain.

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
| `InstallActivity.kt` | Scans the drop folder on launch and stages all APKs off-thread |
| `PackageInstallerHelper.kt` | Drop-folder path, lists APKs, builds the (multi-package) staged session |
| `InstallResultReceiver.kt` | Receives commit status; handles the unprivileged fallback |
| `AndroidManifest.xml` | Declares `INSTALL_PACKAGES` / `DELETE_PACKAGES` / `REBOOT` and the receiver |
| `system/etc/permissions/privapp-permissions-*.xml` | Privileged-permission whitelist for the system image |
