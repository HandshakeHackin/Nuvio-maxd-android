package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.handshakehackin.nuviomaxd.androidtv.update.AndroidRelease
import io.github.handshakehackin.nuviomaxd.androidtv.update.ApkInstaller
import io.github.handshakehackin.nuviomaxd.androidtv.update.ReleasePolicy
import io.github.handshakehackin.nuviomaxd.androidtv.update.UpdateClient
import io.github.handshakehackin.nuviomaxd.androidtv.update.UpdateException
import java.io.File
import java.util.concurrent.Executors

class UpdateActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val client = UpdateClient()
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var action: Button
    private var release: AndroidRelease? = null
    private var downloaded: File? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = tvScreen(getString(R.string.app_updates))
        tvText(body, getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        tvText(body, getString(R.string.updates_description))
        if (BuildConfig.DEBUG) tvText(body, getString(R.string.debug_updates))
        status = tvText(body, getString(R.string.update_initial))
        check = tvButton(body, getString(R.string.check_updates)) { checkUpdates() }
        action = tvButton(body, getString(R.string.download_update)) {
            if (downloaded == null) download() else install()
        }.apply { visibility = View.GONE }
        tvButton(body, getString(R.string.back)) { finish() }
        check.requestFocus()
    }

    private fun checkUpdates() {
        downloaded?.delete()
        downloaded = null
        release = null
        action.visibility = View.GONE
        status.setText(R.string.checking_updates)
        background({ client.check() }) { found ->
            when {
                found == null -> status.setText(R.string.no_release)
                !ReleasePolicy.isNewer(found, BuildConfig.VERSION_CODE.toLong()) -> status.setText(R.string.up_to_date)
                found.minSdk > Build.VERSION.SDK_INT -> status.text = getString(R.string.android_required, found.minSdk)
                else -> {
                    release = found
                    status.text = getString(R.string.update_available, found.versionName, found.notes)
                    action.setText(R.string.download_update)
                    action.visibility = View.VISIBLE
                    action.isEnabled = !BuildConfig.DEBUG
                    if (action.isEnabled) action.requestFocus()
                }
            }
        }
    }

    private fun download() {
        val candidate = release ?: return
        status.text = getString(R.string.downloading_update, 0)
        background({
            val file = client.download(candidate, File(cacheDir, "updates")) { percentage ->
                updateUi { status.text = getString(R.string.downloading_update, percentage) }
            }
            try { ApkInstaller.verify(this, file, candidate); file }
            catch (error: Exception) { file.delete(); throw error }
        }) { file ->
            downloaded = file
            status.setText(R.string.update_ready)
            refreshInstallButton()
            action.requestFocus()
        }
    }

    private fun install() {
        val file = downloaded ?: return
        val candidate = release ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                status.setText(R.string.allow_updates_help)
            } catch (_: ActivityNotFoundException) { status.setText(R.string.installer_error) }
            return
        }
        background({ ApkInstaller.verify(this, file, candidate) }) {
            try { startActivity(ApkInstaller.intent(this, file)) }
            catch (_: ActivityNotFoundException) { status.setText(R.string.installer_error) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::action.isInitialized && downloaded != null && !busy) refreshInstallButton()
    }

    private fun refreshInstallButton() {
        action.setText(if (packageManager.canRequestPackageInstalls()) R.string.install_update else R.string.allow_updates)
    }

    private fun <T> background(work: () -> T, success: (T) -> Unit) {
        busy = true
        check.isEnabled = false
        action.isEnabled = false
        worker.execute {
            try {
                val result = work()
                updateUi { finished(); success(result) }
            } catch (_: InterruptedException) {
                // Leaving the screen cancels this attempt; never launch an installer from here.
            } catch (error: Exception) {
                updateUi {
                    downloaded?.delete()
                    downloaded = null
                    action.setText(R.string.download_update)
                    finished()
                    status.text = if (error is UpdateException) error.message else getString(R.string.update_error)
                }
            }
        }
    }

    private fun finished() {
        busy = false
        check.isEnabled = true
        action.isEnabled = !BuildConfig.DEBUG
    }

    private fun updateUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
