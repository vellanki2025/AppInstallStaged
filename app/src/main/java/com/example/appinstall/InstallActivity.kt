package com.example.appinstall

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.appinstall.databinding.ActivityInstallBinding
import java.util.concurrent.Executors

/**
 * Entry point. Lets the user pick an APK (or opens on one via VIEW), then runs a
 * staged PackageInstaller session off the main thread.
 */
class InstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallBinding
    private val io = Executors.newSingleThreadExecutor()

    private val pickApk =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) startStagedInstall(uri) else log("No APK selected")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.text = buildString {
            append("Privileged: ")
            append(if (hasInstallPackagesPermission()) "YES (silent staged install)"
                   else "NO (will fall back to user confirmation)")
        }

        binding.pickButton.setOnClickListener {
            // "application/vnd.android.package-archive" is the APK MIME type.
            pickApk.launch("application/vnd.android.package-archive")
        }

        binding.rebootButton.setOnClickListener { rebootToApply() }

        // If launched on an APK (VIEW action), install it directly.
        intent?.data?.let { startStagedInstall(it) }
    }

    private fun startStagedInstall(apkUri: Uri) {
        log("Starting staged install for $apkUri")
        io.execute {
            try {
                val size = querySize(apkUri)
                contentResolver.openInputStream(apkUri)?.use { stream ->
                    val sessionId = PackageInstallerHelper.installStaged(
                        context = this,
                        apkStream = stream,
                        apkSizeBytes = size,
                        displayName = apkUri.lastPathSegment ?: "apk",
                        staged = true,
                    )
                    runOnUiThread {
                        log("Committed staged session $sessionId. " +
                            "It will apply on the next reboot — tap 'Reboot now' to apply it.")
                    }
                } ?: runOnUiThread { log("Could not open APK stream") }
            } catch (t: Throwable) {
                Log.e(TAG, "Install error", t)
                runOnUiThread {
                    log("Error: ${t.message}")
                    Toast.makeText(this, "Install error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Reboot the device to apply any committed staged sessions. PowerManager.reboot()
     * requires the REBOOT permission, which only the privileged deployment holds; on an
     * unprivileged build this throws SecurityException and the user must reboot manually.
     */
    private fun rebootToApply() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            log("Rebooting to apply staged session(s)...")
            pm.reboot("apply-staged-install")
        } catch (t: Throwable) {
            Log.e(TAG, "Reboot failed", t)
            log("Reboot not permitted (${t.javaClass.simpleName}). Reboot the device manually to apply.")
            Toast.makeText(this, "Reboot manually to apply the staged install", Toast.LENGTH_LONG).show()
        }
    }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Throwable) { -1L }

    private fun hasInstallPackagesPermission(): Boolean =
        checkSelfPermission("android.permission.INSTALL_PACKAGES") ==
            PackageManager.PERMISSION_GRANTED

    private fun log(msg: String) {
        Log.i(TAG, msg)
        binding.logText.append("$msg\n")
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InstallActivity"
    }
}
