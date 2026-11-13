package com.example.appinstall

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Staged install via the PackageInstaller Session API.
 *
 * "Staged" (SessionParams.setStaged(true)) writes the session to disk and commits
 * it to apply **atomically on the next reboot** — the mechanism behind APEX and
 * system module updates, and the right model for an Automotive (AAOS) image where
 * packages should land during a controlled reboot rather than live at runtime.
 *
 * When several APKs are present they are staged together as children of a single
 * MULTI-PACKAGE staged session, so they all apply on the same reboot as a unit.
 *
 * Requires API 29+ (setStaged) and system privilege (INSTALL_PACKAGES) for the
 * silent, no-confirmation commit.
 */
object PackageInstallerHelper {

    private const val TAG = "PackageInstallerHelper"

    /** Sub-folder inside the drop directory that this app reads APKs from. */
    const val APK_SUBDIR = "staged-apks"

    /**
     * Optional absolute override for the APK drop directory. Leave null to use the
     * app-specific external files dir (no storage permission required):
     *
     *   /sdcard/Android/data/com.example.appinstall/files/staged-apks/
     *
     * For a provisioned vendor location set e.g. "/data/vendor/appinstall/apks"
     * (the directory must exist and be readable by this app's SELinux domain).
     */
    var apkDirOverride: String? = null

    /** The directory APKs are read from, created if missing. */
    fun stagedApkDir(context: Context): File {
        val dir = apkDirOverride?.let { File(it) }
            ?: File(context.getExternalFilesDir(null), APK_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** All *.apk files in the drop directory, sorted by name for deterministic order. */
    fun listApks(context: Context): List<File> =
        stagedApkDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Stage every APK in [files] for install on next reboot.
     *
     * One file  -> a single staged session.
     * Many files -> one multi-package staged parent with a child per APK.
     *
     * @return the id of the committed (parent) staged session.
     */
    fun installFilesStaged(context: Context, files: List<File>): Int {
        require(files.isNotEmpty()) { "No APKs to stage" }
        val appContext = context.applicationContext
        return if (files.size == 1) {
            stageSingle(appContext, files.first())
        } else {
            stageMultiPackage(appContext, files)
        }
    }

    // ---- single staged session -------------------------------------------------

    private fun stageSingle(appContext: Context, apk: File): Int {
        val installer = appContext.packageManager.packageInstaller
        val params = baseParams(staged = true, multiPackage = false, size = apk.length())
        val sessionId = installer.createSession(params)
        Log.i(TAG, "Single staged session $sessionId for ${apk.name}")

        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            writeApk(session, apk)
            val status = InstallResultReceiver.buildStatusIntent(appContext, sessionId)
            session.commit(status.intentSender)
            Log.i(TAG, "Committed staged session $sessionId; applies on reboot")
            return sessionId
        } catch (t: Throwable) {
            Log.e(TAG, "Staging failed for session $sessionId, abandoning", t)
            try { session?.abandon() } catch (_: Throwable) {}
            throw t
        } finally {
            try { session?.close() } catch (_: Throwable) {}
        }
    }

    // ---- multi-package staged session -----------------------------------------

    private fun stageMultiPackage(appContext: Context, files: List<File>): Int {
        val installer = appContext.packageManager.packageInstaller

        // Parent carries the staged + multi-package flags; children are plain sessions.
        val parentParams = baseParams(staged = true, multiPackage = true, size = -1L)
        val parentId = installer.createSession(parentParams)
        Log.i(TAG, "Multi-package staged parent session $parentId for ${files.size} APKs")

        var parent: PackageInstaller.Session? = null
        try {
            parent = installer.openSession(parentId)
            for (apk in files) {
                val childParams = baseParams(staged = false, multiPackage = false, size = apk.length())
                val childId = installer.createSession(childParams)
                installer.openSession(childId).use { child ->
                    writeApk(child, apk)
                }
                parent.addChildSessionId(childId)
                Log.i(TAG, "Added child $childId (${apk.name}) to parent $parentId")
            }
            val status = InstallResultReceiver.buildStatusIntent(appContext, parentId)
            parent.commit(status.intentSender)
            Log.i(TAG, "Committed multi-package staged session $parentId; applies on reboot")
            return parentId
        } catch (t: Throwable) {
            Log.e(TAG, "Multi-package staging failed for parent $parentId, abandoning", t)
            try { parent?.abandon() } catch (_: Throwable) {}
            throw t
        } finally {
            try { parent?.close() } catch (_: Throwable) {}
        }
    }

    // ---- shared helpers --------------------------------------------------------

    private fun baseParams(staged: Boolean, multiPackage: Boolean, size: Long):
        PackageInstaller.SessionParams {
        return PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(null) // read package name from the APK
            if (size > 0) setSize(size)

            if (staged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setStaged(true)
            }
            if (multiPackage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMultiPackage()
            }
            // On privileged builds, request no user confirmation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
    }

    private fun writeApk(session: PackageInstaller.Session, apk: File) {
        // "base.apk" is the conventional name for the base split.
        session.openWrite(apk.name.ifEmpty { "base.apk" }, 0, apk.length()).use { out ->
            apk.inputStream().use { input ->
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
}
