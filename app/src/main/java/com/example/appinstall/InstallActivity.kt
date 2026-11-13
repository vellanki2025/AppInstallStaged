package com.example.appinstall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.appinstall.databinding.ActivityInstallBinding
import java.util.concurrent.Executors

/**
 * On launch, scans the APK drop directory and stages every APK found in a single
 * (multi-package) staged session, so they all install on the next reboot.
 *
 * Drop directory (default): /sdcard/Android/data/com.example.appinstall/files/staged-apks/
 * Override it via PackageInstallerHelper.apkDirOverride.
 */
class InstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dir = PackageInstallerHelper.stagedApkDir(this)
        binding.statusText.text = buildString {
            append("Privileged: ")
            append(if (hasInstallPackagesPermission()) "YES (silent staged install)\n"
                   else "NO (will fall back to user confirmation)\n")
            append("Drop folder:\n")
            append(dir.absolutePath)
        }

        binding.rescanButton.setOnClickListener { scanAndStage() }
        binding.rebootButton.setOnClickListener { rebootToApply() }

        // Automatically stage whatever is already in the folder on launch.
        scanAndStage()
    }

    private fun scanAndStage() {
        io.execute {
            try {
                val apks = PackageInstallerHelper.listApks(this)
                if (apks.isEmpty()) {
                    val dir = PackageInstallerHelper.stagedApkDir(this)
                    runOnUiThread { log("No APKs found in ${dir.absolutePath}") }
                    return@execute
                }

                runOnUiThread {
                    log("Found ${apks.size} APK(s):")
                    apks.forEach { log("  - ${it.name} (${it.length()} bytes)") }
                    log("Staging...")
                }

                val sessionId = PackageInstallerHelper.installFilesStaged(this, apks)

                runOnUiThread {
                    log("Committed staged session $sessionId for ${apks.size} APK(s).")
                    log("They will install on the next reboot — tap 'Reboot now' to apply.")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Staging error", t)
                runOnUiThread {
                    log("Error: ${t.message}")
                    Toast.makeText(this, "Staging error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Reboot to apply the committed staged session(s). PowerManager.reboot() needs
     * the privileged REBOOT permission; unprivileged, it throws and the user must
     * reboot the vehicle manually.
     */
    private fun rebootToApply() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            log("Rebooting to apply staged session(s)...")
            pm.reboot("apply-staged-install")
        } catch (t: Throwable) {
            Log.e(TAG, "Reboot failed", t)
            log("Reboot not permitted (${t.javaClass.simpleName}). Reboot the vehicle manually to apply.")
            Toast.makeText(this, "Reboot manually to apply the staged install", Toast.LENGTH_LONG).show()
        }
    }

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
