package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.os.Bundle
import android.util.Pair
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private var player: ExoPlayer? = null
    private var position = 0L
    private var playWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!validStream(intent.getStringExtra("url").orEmpty())) { finish(); return }
        position = savedInstanceState?.getLong("position") ?: 0L
        playWhenReady = savedInstanceState?.getBoolean("playing") ?: true
        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setErrorMessageProvider { Pair(0, getString(R.string.playback_error)) }
        }
        setContentView(view)
        view.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!::view.isInitialized) return
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(intent.getStringExtra("url")!!))
            it.seekTo(position)
            it.playWhenReady = playWhenReady
            it.prepare()
        }
        view.keepScreenOn = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", player?.playWhenReady ?: playWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        player?.let { position = it.currentPosition; playWhenReady = it.playWhenReady; it.release() }
        player = null
        if (::view.isInitialized) { view.player = null; view.keepScreenOn = false }
        super.onStop()
    }
}
