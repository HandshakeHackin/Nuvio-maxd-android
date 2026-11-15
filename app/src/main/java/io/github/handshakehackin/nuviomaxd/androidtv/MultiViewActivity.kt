package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import io.github.handshakehackin.nuviomaxd.androidtv.core.CoreBridge
import io.github.handshakehackin.nuviomaxd.androidtv.core.objects
import io.github.handshakehackin.nuviomaxd.androidtv.player.PlaybackSources

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiViewActivity:Activity(){
    private val players=mutableListOf<ExoPlayer>()
    private val tiles=mutableListOf<FrameLayout>()
    private var selected=0
    private var audible=0
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)}
    override fun onStart(){
        super.onStart()
        val payload=Routes.read(this)
        if(payload.optString("profileKey")!=CoreBridge.profileKey){finish();return}
        val streams=payload.optJSONArray("streams")?.objects().orEmpty().take(4)
        if(streams.size!=4){finish();return}
        tiles.clear()
        val grid=GridLayout(this).apply{rowCount=2;columnCount=2;setBackgroundColor(Color.BLACK);isFocusableInTouchMode=true}
        setContentView(grid)
        streams.forEachIndexed{index,stream->
            val tile=FrameLayout(this)
            val view=PlayerView(this).apply{useController=false}
            tile.addView(view,FrameLayout.LayoutParams(-1,-1))
            val label=TextView(this).apply{text=stream.optString("name");textSize=16f;setTextColor(Color.WHITE);setBackgroundColor(0xaa000000.toInt());setPadding(12,8,12,8)}
            tile.addView(label,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
            grid.addView(tile,GridLayout.LayoutParams(GridLayout.spec(index/2,1f),GridLayout.spec(index%2,1f)).apply{width=0;height=0;setMargins(4,4,4,4)})
            tiles+=tile
            val selector=DefaultTrackSelector(this).apply{setParameters(buildUponParameters().setMaxVideoSize(640,360).setMaxVideoBitrate(800_000))}
            val player=ExoPlayer.Builder(this).setTrackSelector(selector).setMediaSourceFactory(DefaultMediaSourceFactory(PlaybackSources.factory(this,stream))).build()
            players+=player;view.player=player;player.volume=if(index==audible)1f else 0f
            player.addListener(object:Player.Listener{override fun onPlayerError(error:PlaybackException){label.text=stream.optString("name")+" · Preview unavailable on this device"}})
            player.setMediaItem(MediaItem.fromUri(stream.optString("url")));player.playWhenReady=true;player.prepare()
        }
        grid.requestFocus();select(0)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun select(index:Int){selected=index.coerceIn(0,3);tiles.forEachIndexed{i,tile->tile.setPadding(if(i==selected)5 else 0,if(i==selected)5 else 0,if(i==selected)5 else 0,if(i==selected)5 else 0);tile.setBackgroundColor(if(i==selected)0xff26ddd2.toInt()else Color.BLACK)}}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean=when(keyCode){
        KeyEvent.KEYCODE_DPAD_LEFT->{select(selected/2*2);true}
        KeyEvent.KEYCODE_DPAD_RIGHT->{select(selected/2*2+1);true}
        KeyEvent.KEYCODE_DPAD_UP->{select(selected%2);true}
        KeyEvent.KEYCODE_DPAD_DOWN->{select(selected%2+2);true}
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{audible=selected;players.forEachIndexed{i,player->player.volume=if(i==audible)1f else 0f};true}
        else->super.onKeyDown(keyCode,event)
    }
    override fun onStop(){players.forEach{it.release()};players.clear();window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop()}
}
