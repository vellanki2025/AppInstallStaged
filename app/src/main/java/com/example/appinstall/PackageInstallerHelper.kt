package com.example.appinstall

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.InputStream

/**
 * Wraps the PackageInstaller Session API to perform a STAGED, silent install.
 *
 * "Staged" (PackageInstaller.SessionParams.setStaged(true)) means the session is
 * written to disk and then committed to be applied atomically on the next reboot.
 * Staged sessions are the mechanism behind APEX and rebootless/rebootful system
 * updates, and require a privileged caller. Combined with the INSTALL_PACKAGES
 * platform permission the commit proceeds with no user confirmation UI.
 *
 * Requires API 29+ for setStaged().
 */
object PackageInstallerHelper {

    private const val TAG = "PackageInstallerHelper"

    /**
     * Create a staged session, stream the APK bytes into it, and commit.
     *
     * @param context     any context; the application context is used internally.
     * @param apkStream    an open stream of the APK to install (caller closes nothing;
     *                     this method consumes and closes the session side).
     * @param apkSizeBytes known size of the APK, or -1 if unknown.
     * @param displayName  label stored on the session (for logging/diagnostics).
     * @param staged       true for a staged (reboot-applied) session; false for an
     *                     ordinary immediate session. Defaults to true per request.
     * @return the session id that was committed.
     */
    fun installStaged(
        context: Context,
        apkStream: InputStream,
        apkSizeBytes: Long = -1L,
        displayName: String = "staged-install",
        staged: Boolean = true,
    ): Int {
        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(null) // let the installer read it from the APK
            if (apkSizeBytes > 0) setSize(apkSizeBytes)

            if (staged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The staged flag — the core of a "staged session".
                setStaged(true)
            }

            // Replace an existing package of the same name without asking.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setInstallLocation(PackageInfoCompat.INSTALL_LOCATION_AUTO)
            }

            // On privileged builds, request that no user action be required.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        Log.i(TAG, "Created session $sessionId (staged=$staged, displayName=$displayName)")

        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            writeApk(session, apkStream)

            val statusIntent = InstallResultReceiver.buildStatusIntent(appContext, sessionId)
            session.commit(statusIntent.intentSender)
            Log.i(TAG, "Committed session $sessionId; awaiting status broadcast")
            return sessionId
        } catch (t: Throwable) {
            Log.e(TAG, "Install failed for session $sessionId, abandoning", t)
            try { session?.abandon() } catch (_: Throwable) { /* best effort */ }
            throw t
        } finally {
            try { session?.close() } catch (_: Throwable) { /* best effort */ }
        }
    }

    private fun writeApk(session: PackageInstaller.Session, apkStream: InputStream) {
        // "base.apk" is the conventional name for the base split.
        session.openWrite("base.apk", 0, -1).use { out ->
            apkStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var read = input.read(buf)
                while (read >= 0) {
                    out.write(buf, 0, read)
                    read = input.read(buf)
                }
            }
            session.fsync(out)
        }
    }

    /** Minimal constant holder to avoid pulling extra deps for one value. */
    private object PackageInfoCompat {
        const val INSTALL_LOCATION_AUTO = 0
    }
}
