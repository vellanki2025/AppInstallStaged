package com.example.appinstall

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives the commit status of a PackageInstaller session.
 *
 * For a privileged, silent install the result arrives directly as
 * STATUS_SUCCESS / STATUS_FAILURE_*. For an unprivileged caller the framework
 * instead returns STATUS_PENDING_USER_ACTION with an Intent to launch the
 * confirmation dialog — handled here so the same build degrades gracefully.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Not privileged (or USER_ACTION_NOT_REQUIRED was not honored):
                // the system wants to show the install confirmation dialog.
                val confirmIntent: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
                Log.i(TAG, "Session $sessionId needs user confirmation")
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // For a STAGED session, success means the session is verified and
                // committed to apply on the next reboot — it is NOT live yet.
                val text = "Staged ${pkg ?: "package"} (session $sessionId). " +
                    "Applies on next reboot."
                Log.i(TAG, text)
                toast(context, text)
            }

            else -> {
                val text = "Install failed (session $sessionId): status=$status ${message ?: ""}"
                Log.e(TAG, text)
                toast(context, text)
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        private const val ACTION_INSTALL_STATUS =
            "com.example.appinstall.INSTALL_STATUS"

        /**
         * Build the IntentSender target the session commit reports back to.
         * Uses a mutable PendingIntent because the framework fills in result extras.
         */
        fun buildStatusIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
                setClass(context, InstallResultReceiver::class.java)
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }
}
