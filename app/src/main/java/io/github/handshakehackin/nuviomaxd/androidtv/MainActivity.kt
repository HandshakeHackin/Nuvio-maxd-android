package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import java.net.URI

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = tvScreen(getString(R.string.tv_title))
        tvText(body, getString(R.string.preview_description))
        tvText(body, getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        val play = tvButton(body, getString(R.string.play_stream)) { enterStream() }
        tvButton(body, getString(R.string.app_updates)) { startActivity(Intent(this, UpdateActivity::class.java)) }
        tvButton(body, getString(R.string.about)) {
            AlertDialog.Builder(this).setTitle(R.string.about)
                .setMessage(R.string.about_body).setPositiveButton(android.R.string.ok, null).show()
        }
        play.requestFocus()
    }

    private fun enterStream() {
        val input = EditText(this).apply {
            hint = getString(R.string.stream_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.stream_title)
            .setMessage(R.string.stream_help).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.play, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (validStream(url)) {
                    dialog.dismiss()
                    startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url))
                } else Toast.makeText(this, R.string.invalid_stream, Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
        input.requestFocus()
    }
}

internal fun validStream(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
} catch (_: Exception) { false }
